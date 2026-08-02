// Evenly web claim (WEB_CLAIM_SPEC.md). Lets a guest with no app and no account claim what they ate
// from a QR on the payer's phone. This is the ENTIRE security boundary for that flow: the browser
// bundle ships no Supabase client and no anon key (spec §4.1) — every guest read and write goes
// through this one function, which holds the service key and does its own authorisation per request,
// scoped to exactly the one bill its token names (spec §4.3). If a future change has the browser
// talking to PostgREST directly, this feature has become a data breach.
//
// Routing: one function, many actions, dispatched on the LAST path segment (works whether the caller
// hits `.../web-claim/resolve` or `.../web-claim/resolve/`) since Supabase edge functions don't do
// framework-level sub-routing. Every action is POST with a JSON body carrying `token` (the 128-bit
// base62 bill token from the QR/URL) — never the plaintext session id in a cookie, since identity here
// is a `localStorage` token the client sends explicitly (spec §2.2), not an HTTP session.
//
// Deferred out of this pass: payer-role assignment (`expenses.payer_user_id` is Zone 2 of the
// `merge_expense` causal-version model — needs its own careful pass, not a blind update) and the
// pending-item-edits APPROVAL side (that's the payer's in-app screen, build-order step 6). Proposing an
// edit (§3.6) IS implemented here; approving/rejecting it is not.

import { createClient, type SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "content-type, apikey, authorization",
};

// ── Small helpers ──────────────────────────────────────────────────────────────────────────────

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
  });
}

function err(code: string, status: number, detail?: string): Response {
  return json({ error: code, detail }, status);
}

function newId(): string {
  return crypto.randomUUID();
}

/** 128-bit random token, base62 — what goes in the QR/URL. Never stored; only its hash is. */
function newPlaintextToken(): string {
  const alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  const bytes = crypto.getRandomValues(new Uint8Array(22)); // 22 bytes > 128 bits of entropy
  let out = "";
  for (const b of bytes) out += alphabet[b % alphabet.length];
  return out;
}

async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Trim, collapse internal whitespace, cap length — spec §3.2. */
function normalizeName(raw: string): string {
  return raw.trim().replace(/\s+/g, " ").slice(0, 40);
}

/** Case-insensitive, whitespace-normalized equality — spec §2.4. Deliberately NOT fuzzy (that's for
 *  suggesting an existing person, never for blocking — a false-positive block is a dead end). */
function namesExactlyMatch(a: string, b: string): boolean {
  return normalizeName(a).toLowerCase() === normalizeName(b).toLowerCase();
}

/** Plain Levenshtein distance, small-input dynamic program (names are short, no need to optimize). */
function levenshtein(a: string, b: string): number {
  const m = a.length, n = b.length;
  const dp: number[] = new Array(n + 1);
  for (let j = 0; j <= n; j++) dp[j] = j;
  for (let i = 1; i <= m; i++) {
    let prevDiag = dp[0];
    dp[0] = i;
    for (let j = 1; j <= n; j++) {
      const tmp = dp[j];
      dp[j] = a[i - 1] === b[j - 1] ? prevDiag : 1 + Math.min(prevDiag, dp[j], dp[j - 1]);
      prevDiag = tmp;
    }
  }
  return dp[n];
}

/** Suggests (never blocks) — normalized Levenshtein <= 2, or one string containing the other (spec §3.2). */
function namesFuzzyMatch(a: string, b: string): boolean {
  const na = normalizeName(a).toLowerCase();
  const nb = normalizeName(b).toLowerCase();
  if (na === nb) return false; // exact match is handled (and blocked) separately
  if (na.includes(nb) || nb.includes(na)) return true;
  return levenshtein(na, nb) <= 2;
}

function serviceClient(): SupabaseClient {
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
}

// ── Link/session resolution — the one place every action proves its scope (spec §4.2, §4.3) ──────

interface LinkContext {
  link: { id: string; token_hash: string; expense_id: string; group_id: string; expires_at: number; revoked_at: number | null };
  expense: Record<string, unknown>;
  group: Record<string, unknown>;
}

/** Resolves a plaintext token to its bill link + expense + group, checking every gate in spec §4.2:
 *  exists, not revoked, not expired, bill not deleted, group not deleted. Returns a Response to send
 *  back verbatim on any failure, so callers can `if (ctx instanceof Response) return ctx;`. */
async function resolveLink(sb: SupabaseClient, token: string | undefined): Promise<LinkContext | Response> {
  if (!token) return err("BAD_REQUEST", 400, "token required");
  const tokenHash = await sha256Hex(token);
  const { data: link } = await sb.from("web_bill_links").select("*").eq("token_hash", tokenHash).maybeSingle();
  if (!link || link.revoked_at) return err("GONE", 404, "link revoked or unknown");

  const { data: expense } = await sb.from("expenses").select("*").eq("id", link.expense_id).maybeSingle();
  if (!expense || expense.deleted_at) return err("GONE", 404, "bill deleted");

  const { data: group } = await sb.from("groups").select("*").eq("id", link.group_id).maybeSingle();
  if (!group || group.deleted_at) return err("GONE", 404, "group deleted");

  if (link.expires_at <= Date.now()) return err("EXPIRED", 410);

  return { link, expense, group };
}

/** A guest's `localStorage` token → the placeholder they are, scoped to the LINK's group (identity is
 *  group-scoped and durable, spec §2.2 — a long-dead Ramen-night link still recognises her at Sunday
 *  roast). Returns null (not a Response) when there's simply no session yet, which is a normal state,
 *  not an error. */
async function resolveSession(
  sb: SupabaseClient,
  groupId: string,
  sessionToken: string | undefined,
): Promise<{ id: string; user_id: string } | null> {
  if (!sessionToken) return null;
  const tokenHash = await sha256Hex(sessionToken);
  const { data } = await sb
    .from("web_sessions")
    .select("id, user_id, revoked_at")
    .eq("group_id", groupId)
    .eq("token_hash", tokenHash)
    .maybeSingle();
  if (!data || data.revoked_at) return null;
  await sb.from("web_sessions").update({ last_seen_at: Date.now() }).eq("id", data.id);
  return { id: data.id, user_id: data.user_id };
}

/** Ensures a `bill_participants` row exists for [userId] on [expenseId] — every identified guest is
 *  a participant of the bill they're claiming on, per spec §3.3's premise that the claim screen is
 *  participant-scoped. Idempotent (checked read then insert, not an upsert, since the row has no
 *  deterministic id convention shared with the app). */
async function ensureParticipant(sb: SupabaseClient, expenseId: string, groupId: string, userId: string, now: number) {
  const { data: existing } = await sb
    .from("bill_participants")
    .select("id")
    .eq("expense_id", expenseId)
    .eq("user_id", userId)
    .is("deleted_at", null)
    .maybeSingle();
  if (existing) return;
  await sb.from("bill_participants").insert({
    id: newId(), expense_id: expenseId, group_id: groupId, user_id: userId,
    created_at: now, updated_at: now,
  });
}

// ── Rate limits (spec §4.5) ────────────────────────────────────────────────────────────────────

const MAX_WRITES_PER_MINUTE = 120;
const MAX_PLACEHOLDERS_PER_GROUP = 40; // spec says "per bill"; placeholders are group-scoped in the
                                        // schema (users.placeholder_group_id), so this enforces the
                                        // nearest real boundary — see supabase/AGENTS.md.
const MAX_ITEMS_PER_BILL = 200;

/** True if [tokenHash] has hit the per-token write budget. Best-effort, not fail-closed: this is abuse
 *  mitigation on a free-to-write endpoint, not a financial guardrail like receipt_scan_log's cost-bearing
 *  limit, so a rate-log hiccup should not itself lock a guest out of claiming their dinner. */
async function isWriteRateLimited(sb: SupabaseClient, tokenHash: string): Promise<boolean> {
  const oneMinuteAgo = Date.now() - 60_000;
  const { count, error } = await sb
    .from("web_claim_write_log")
    .select("id", { count: "exact", head: true })
    .eq("token_hash", tokenHash)
    .gt("created_at", oneMinuteAgo);
  if (error) return false;
  return (count ?? 0) >= MAX_WRITES_PER_MINUTE;
}

async function logWrite(sb: SupabaseClient, tokenHash: string, now: number) {
  await sb.from("web_claim_write_log").insert({ id: newId(), token_hash: tokenHash, created_at: now });
}

/** Wraps a write action with the rate check + write log, and the "exceeding a limit reads as expired,
 *  not an error" rule (spec §4.5) — this is what distinguishes an abuse block from a genuine failure to
 *  the guest, who has no way to tell them apart otherwise. [flagReason] is logged for the payer to see
 *  eventually (no dedicated flag table yet — console-logged, a real notification is a later increment). */
async function withWriteGuard(
  sb: SupabaseClient,
  ctx: LinkContext,
  action: () => Promise<Response>,
): Promise<Response> {
  const tokenHash = ctx.link.token_hash;
  if (await isWriteRateLimited(sb, tokenHash)) {
    console.error(`web-claim: rate limit hit for token ${tokenHash.slice(0, 8)}… on expense ${ctx.link.expense_id}`);
    return err("EXPIRED", 410, "rate limited");
  }
  const result = await action();
  if (result.status < 400) await logWrite(sb, tokenHash, Date.now());
  return result;
}

// ── Header — what the guest sees before being asked for anything (spec §3.1) ──────────────────────

async function buildHeader(sb: SupabaseClient, ctx: LinkContext) {
  const { count: itemCount } = await sb
    .from("expense_items").select("id", { count: "exact", head: true })
    .eq("expense_id", ctx.link.expense_id).is("deleted_at", null);
  const { count: claimedCount } = await sb
    .from("bill_participants").select("id", { count: "exact", head: true })
    .eq("expense_id", ctx.link.expense_id).is("deleted_at", null).not("done_at", "is", null);
  const { count: participantCount } = await sb
    .from("bill_participants").select("id", { count: "exact", head: true })
    .eq("expense_id", ctx.link.expense_id).is("deleted_at", null);
  return {
    groupName: ctx.group.name,
    title: ctx.expense.title,
    currency: ctx.expense.currency,
    itemCount: itemCount ?? 0,
    claimedCount: claimedCount ?? 0,
    participantCount: participantCount ?? 0,
    expiresAt: ctx.link.expires_at,
  };
}

// ── Actions ─────────────────────────────────────────────────────────────────────────────────────

/** POST /web-claim/resolve — landing branch (spec §3.1). */
async function actionResolve(sb: SupabaseClient, body: { token?: string; sessionToken?: string }): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  const header = await buildHeader(sb, ctx);

  const session = await resolveSession(sb, ctx.link.group_id, body.sessionToken);
  if (session) {
    // E10: the payer may have claimed this placeholder in-app while the guest was mid-claim — a real
    // account merge stamps `placeholder_claim_completed_at`, at which point the web session is stale.
    const { data: user } = await sb.from("users").select("id, display_name, is_placeholder").eq("id", session.user_id).maybeSingle();
    const { data: member } = await sb.from("members")
      .select("placeholder_claim_completed_at")
      .eq("group_id", ctx.link.group_id).eq("user_id", session.user_id).maybeSingle();
    if (member?.placeholder_claim_completed_at) {
      return json({ state: "claimed_elsewhere", header });
    }
    await ensureParticipant(sb, ctx.link.expense_id, ctx.link.group_id, session.user_id, Date.now());
    return json({ state: "welcome_back", header, identity: { userId: user?.id, name: user?.display_name } });
  }

  const { data: placeholders } = await sb
    .from("users").select("id, display_name")
    .eq("placeholder_group_id", ctx.link.group_id).eq("is_placeholder", true);
  const liveIds = new Set<string>();
  if (placeholders?.length) {
    const { data: members } = await sb
      .from("members").select("user_id, placeholder_claim_completed_at")
      .eq("group_id", ctx.link.group_id).in("user_id", placeholders.map((p) => p.id));
    for (const m of members ?? []) if (!m.placeholder_claim_completed_at) liveIds.add(m.user_id as string);
  }
  const unclaimed = (placeholders ?? []).filter((p) => liveIds.has(p.id));
  if (unclaimed.length === 0) return json({ state: "name_entry", header });

  const candidates = await Promise.all(unclaimed.map((p) => withEvidence(sb, ctx.link.group_id, p)));
  return json({ state: "pick_name", header, candidates });
}

/** Up to 2 of a placeholder's own expenses as `{title, date, amount}` — the recognition evidence (spec
 *  §2.3, §4.6). Deliberately excludes balances; this is the minimum needed to make "is this you?"
 *  answerable, and it's the same information the group's own members already see. */
async function withEvidence(sb: SupabaseClient, groupId: string, placeholder: { id: string; display_name: string }) {
  const { data: claimIds } = await sb.from("item_claims").select("expense_id").eq("user_id", placeholder.id).is("deleted_at", null).limit(20);
  const { data: shareIds } = await sb.from("item_shares").select("expense_id").eq("user_id", placeholder.id).is("deleted_at", null).limit(20);
  const expenseIds = Array.from(new Set([...(claimIds ?? []), ...(shareIds ?? [])].map((r) => r.expense_id as string)));
  let evidence: Array<{ title: string; date: string; amountSubunits: number }> = [];
  if (expenseIds.length > 0) {
    const { data: expenses } = await sb
      .from("expenses").select("title, expense_date, amount_subunits, created_at")
      .eq("group_id", groupId).in("id", expenseIds).is("deleted_at", null)
      .order("created_at", { ascending: false }).limit(2);
    evidence = (expenses ?? []).map((e) => ({ title: e.title as string, date: e.expense_date as string, amountSubunits: e.amount_subunits as number }));
  }
  return { userId: placeholder.id, name: placeholder.display_name, evidence };
}

/** POST /web-claim/name — submit a chosen name (spec §3.2). Exact match blocks; fuzzy match suggests
 *  without blocking (only acted on when the client resubmits with `force: true`); otherwise creates a
 *  fresh placeholder + session + participant row in one go. */
async function actionName(
  sb: SupabaseClient,
  body: { token?: string; name?: string; force?: boolean },
): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  return withWriteGuard(sb, ctx, async () => {
    const name = normalizeName(body.name ?? "");
    if (!name) return err("BAD_REQUEST", 400, "name required");

    const { data: activeMembers } = await sb.from("members").select("user_id").eq("group_id", ctx.link.group_id).eq("status", "ACTIVE");
    const memberIds = (activeMembers ?? []).map((m) => m.user_id as string);
    if (memberIds.length === 0) return err("BAD_REQUEST", 400, "no active members");
    const { data: memberUsers } = await sb.from("users").select("id, display_name, is_placeholder").in("id", memberIds);

    const exact = (memberUsers ?? []).find((u) => namesExactlyMatch(u.display_name as string, name));
    if (exact) {
      const suffixes: string[] = [];
      for (let n = 2; suffixes.length < 3 && n < 10; n++) {
        const candidate = `${name} ${n}`;
        if (!(memberUsers ?? []).some((u) => namesExactlyMatch(u.display_name as string, candidate))) suffixes.push(candidate);
      }
      return json({ blocked: true, suggestions: suffixes });
    }

    if (!body.force) {
      const fuzzy = (memberUsers ?? []).find((u) => u.is_placeholder && namesFuzzyMatch(u.display_name as string, name));
      if (fuzzy) {
        const evidence = await withEvidence(sb, ctx.link.group_id, { id: fuzzy.id as string, display_name: fuzzy.display_name as string });
        return json({ suggestion: evidence });
      }
    }

    const { count: placeholderCount } = await sb
      .from("users").select("id", { count: "exact", head: true })
      .eq("placeholder_group_id", ctx.link.group_id).eq("is_placeholder", true);
    if ((placeholderCount ?? 0) >= MAX_PLACEHOLDERS_PER_GROUP) return err("EXPIRED", 410, "too many placeholders");

    const now = Date.now();
    const userId = newId();
    // created_by = the guest's own new id (spec §5.7): a self-created placeholder is never asked
    // "is this you?" later, since IDENTITY_CLAIM_SPEC.md skips anyone who created their own name.
    const { error: userErr } = await sb.from("users").insert({
      id: userId, is_placeholder: true, display_name: name, placeholder_group_id: ctx.link.group_id,
      created_by: userId, created_at: now, updated_at: now,
    });
    if (userErr) return err("BACKEND", 500, userErr.message);
    await sb.from("members").insert({
      id: newId(), group_id: ctx.link.group_id, user_id: userId, status: "ACTIVE",
      joined_at: now, created_at: now, updated_at: now,
    });
    const sessionToken = newPlaintextToken();
    await sb.from("web_sessions").insert({
      id: newId(), group_id: ctx.link.group_id, user_id: userId,
      token_hash: await sha256Hex(sessionToken), created_at: now, last_seen_at: now,
    });
    await ensureParticipant(sb, ctx.link.expense_id, ctx.link.group_id, userId, now);

    return json({ created: true, userId, sessionToken, header: await buildHeader(sb, ctx) });
  });
}

/** POST /web-claim/claim-placeholder — "That's me" on the evidence list (spec §2.3 tier 2, §5.4). */
async function actionClaimPlaceholder(
  sb: SupabaseClient,
  body: { token?: string; placeholderUserId?: string },
): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  return withWriteGuard(sb, ctx, async () => {
    if (!body.placeholderUserId) return err("BAD_REQUEST", 400, "placeholderUserId required");
    const now = Date.now();
    const sessionId = newId();
    const { data, error } = await sb.rpc("claim_web_placeholder", {
      p_group_id: ctx.link.group_id,
      p_placeholder_user_id: body.placeholderUserId,
      p_session_id: sessionId,
      p_now: now,
    });
    if (error) return err("BACKEND", 500, error.message);
    if (!data.won) return json({ won: false, winnerSessionId: data.winner_session_id });

    const sessionToken = newPlaintextToken();
    const { error: sessErr } = await sb.from("web_sessions").insert({
      id: sessionId, group_id: ctx.link.group_id, user_id: body.placeholderUserId,
      token_hash: await sha256Hex(sessionToken), created_at: now, last_seen_at: now,
    });
    if (sessErr) return err("BACKEND", 500, sessErr.message);
    await ensureParticipant(sb, ctx.link.expense_id, ctx.link.group_id, body.placeholderUserId, now);

    const { data: user } = await sb.from("users").select("display_name").eq("id", body.placeholderUserId).maybeSingle();
    return json({ won: true, sessionToken, userId: body.placeholderUserId, name: user?.display_name, header: await buildHeader(sb, ctx) });
  });
}

/** POST /web-claim/bill — the claim list's raw data (spec §3.3, §4.3 read scope). Money is computed
 *  CLIENT-side by the ported TS allocator (spec §2.10, build-order step 4) — this only ever returns
 *  items/claims/shares/extras, never a computed total, so there is exactly one place split math lives. */
async function actionBill(sb: SupabaseClient, body: { token?: string }): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;

  const expenseId = ctx.link.expense_id;
  const [{ data: items }, { data: claims }, { data: shares }, { data: participants }] = await Promise.all([
    sb.from("expense_items").select("id, label, quantity, line_total_subunits, sort_order").eq("expense_id", expenseId).is("deleted_at", null).order("sort_order"),
    sb.from("item_claims").select("id, item_id, user_id, quantity").eq("expense_id", expenseId).is("deleted_at", null),
    sb.from("item_shares").select("id, item_id, user_id, portion_id, quantity, added_by").eq("expense_id", expenseId).is("deleted_at", null),
    sb.from("bill_participants").select("user_id, done_at").eq("expense_id", expenseId).is("deleted_at", null),
  ]);

  const participantIds = (participants ?? []).map((p) => p.user_id as string);
  const claimantIds = (claims ?? []).map((c) => c.user_id as string);
  const shareUserIds = (shares ?? []).map((s) => s.user_id as string);
  const nameIds = Array.from(new Set([...participantIds, ...claimantIds, ...shareUserIds]));
  const { data: users } = nameIds.length
    ? await sb.from("users").select("id, display_name").in("id", nameIds)
    : { data: [] as Array<{ id: string; display_name: string }> };
  const namesByUser = Object.fromEntries((users ?? []).map((u) => [u.id, u.display_name]));

  let payer: { userId: string | null; name: string | null; app: string | null; handle: string | null } = {
    userId: (ctx.expense.payer_user_id as string) ?? null, name: null, app: null, handle: null,
  };
  if (ctx.expense.payer_user_id) {
    const { data: payerUser } = await sb
      .from("users").select("display_name, preferred_payment_app, venmo_handle, cashapp_handle, paypal_handle, zelle_handle")
      .eq("id", ctx.expense.payer_user_id).maybeSingle();
    if (payerUser) {
      const app = payerUser.preferred_payment_app as string | null;
      const handleByApp: Record<string, string | null> = {
        venmo: payerUser.venmo_handle, cashapp: payerUser.cashapp_handle,
        paypal: payerUser.paypal_handle, zelle: payerUser.zelle_handle,
      };
      payer = { userId: ctx.expense.payer_user_id as string, name: payerUser.display_name as string, app, handle: app ? handleByApp[app] ?? null : null };
    }
  }

  return json({
    expense: {
      id: ctx.expense.id, title: ctx.expense.title, currency: ctx.expense.currency,
      taxSubunits: ctx.expense.tax_subunits, gratuitySubunits: ctx.expense.gratuity_subunits,
      tipSubunits: ctx.expense.tip_subunits, tipSplitMode: ctx.expense.tip_split_mode,
      discountSubunits: ctx.expense.discount_subunits,
    },
    items: items ?? [],
    claims: claims ?? [],
    shares: shares ?? [],
    participants: (participants ?? []).map((p) => ({ userId: p.user_id, name: namesByUser[p.user_id as string] ?? "Someone", doneAt: p.done_at })),
    namesByUser,
    payer,
  });
}

/** POST /web-claim/claim — solo claim / unclaim a line (spec §2.6 "+ I had this"). Writes ONLY the
 *  caller's own `item_claims` row — the partition invariant holds by construction here, same as the
 *  app's `setClaim`. Joining someone ELSE's claim is a different action: see actionJoin. */
async function actionClaim(
  sb: SupabaseClient,
  body: { token?: string; sessionToken?: string; itemId?: string; quantity?: number },
): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  return withWriteGuard(sb, ctx, async () => {
    const session = await resolveSession(sb, ctx.link.group_id, body.sessionToken);
    if (!session) return err("SESSION_REQUIRED", 401);
    if (!body.itemId) return err("BAD_REQUEST", 400, "itemId required");
    const { data: item } = await sb.from("expense_items").select("id").eq("id", body.itemId).eq("expense_id", ctx.link.expense_id).is("deleted_at", null).maybeSingle();
    if (!item) return err("BAD_REQUEST", 400, "item not on this bill");

    const now = Date.now();
    const quantity = body.quantity ?? 0;
    const claimId = `${body.itemId}__${session.user_id}`;
    if (quantity <= 0) {
      await sb.from("item_claims").update({ deleted_at: now, updated_at: now }).eq("item_id", body.itemId).eq("user_id", session.user_id).is("deleted_at", null);
    } else {
      const { data: existing } = await sb.from("item_claims").select("id, row_version").eq("item_id", body.itemId).eq("user_id", session.user_id).maybeSingle();
      await sb.from("item_claims").upsert({
        id: existing?.id ?? claimId, item_id: body.itemId, expense_id: ctx.link.expense_id, group_id: ctx.link.group_id,
        user_id: session.user_id, quantity, deleted_at: null, updated_at: now,
        created_at: existing ? undefined : now, row_version: (existing?.row_version ?? 0) + 1,
      });
    }
    return json({ ok: true });
  });
}

/** POST /web-claim/join — join a line someone else already claimed (spec §2.6 "+ Add me", §3.5, §5.3).
 *  The one write a guest's session cannot make itself; delegates entirely to `join_item_portion`. */
async function actionJoin(
  sb: SupabaseClient,
  body: { token?: string; sessionToken?: string; itemId?: string; portionId?: string; overClaimAck?: boolean },
): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  return withWriteGuard(sb, ctx, async () => {
    const session = await resolveSession(sb, ctx.link.group_id, body.sessionToken);
    if (!session) return err("SESSION_REQUIRED", 401);
    if (!body.itemId) return err("BAD_REQUEST", 400, "itemId required");
    const { data: item } = await sb.from("expense_items").select("id").eq("id", body.itemId).eq("expense_id", ctx.link.expense_id).is("deleted_at", null).maybeSingle();
    if (!item) return err("BAD_REQUEST", 400, "item not on this bill");

    const { data, error } = await sb.rpc("join_item_portion", {
      p_item_id: body.itemId,
      p_joiner_user_id: session.user_id,
      p_portion_id: body.portionId ?? null,
      p_now: Date.now(),
      p_over_claim_ack: body.overClaimAck ?? false,
    });
    if (error) {
      if (error.message?.includes("OVERCLAIMED")) return err("OVERCLAIMED", 409, error.message);
      return err("BACKEND", 500, error.message);
    }
    return json({ ok: true, portionId: data.portion_id, quantity: data.quantity, members: data.members });
  });
}

/** POST /web-claim/edit — propose add/relabel/reprice/requantify/remove (spec §2.7, §3.6). Every write
 *  here lands as a PENDING edit; nothing here ever touches `expense_items` directly. Approval is the
 *  payer's in-app screen (build-order step 6), not implemented in this function. */
async function actionEdit(
  sb: SupabaseClient,
  body: {
    token?: string; sessionToken?: string; kind?: string; itemId?: string;
    label?: string; quantity?: number; unitPriceSubunits?: number;
  },
): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  return withWriteGuard(sb, ctx, async () => {
    const session = await resolveSession(sb, ctx.link.group_id, body.sessionToken);
    if (!session) return err("SESSION_REQUIRED", 401);
    const kind = body.kind;
    if (!kind || !["ADD", "RELABEL", "REPRICE", "REQUANTITY", "REMOVE"].includes(kind)) return err("BAD_REQUEST", 400, "invalid kind");

    let previous: { label: string | null; quantity: number | null; unit_price_subunits: number | null } | null = null;
    if (kind !== "ADD") {
      if (!body.itemId) return err("BAD_REQUEST", 400, "itemId required");
      const { data: item } = await sb.from("expense_items").select("label, quantity, unit_price_subunits").eq("id", body.itemId).eq("expense_id", ctx.link.expense_id).is("deleted_at", null).maybeSingle();
      if (!item) return err("BAD_REQUEST", 400, "item not on this bill");
      previous = item;
    } else {
      const [{ count: itemCount }, { count: pendingAdds }] = await Promise.all([
        sb.from("expense_items").select("id", { count: "exact", head: true }).eq("expense_id", ctx.link.expense_id).is("deleted_at", null),
        sb.from("pending_item_edits").select("id", { count: "exact", head: true }).eq("expense_id", ctx.link.expense_id).eq("kind", "ADD").is("decided_at", null),
      ]);
      if ((itemCount ?? 0) + (pendingAdds ?? 0) >= MAX_ITEMS_PER_BILL) return err("EXPIRED", 410, "too many items");
    }

    const now = Date.now();
    const { data: inserted, error } = await sb.from("pending_item_edits").insert({
      id: newId(), expense_id: ctx.link.expense_id, group_id: ctx.link.group_id,
      item_id: body.itemId ?? null, kind,
      proposed_label: body.label ?? null, proposed_quantity: body.quantity ?? null, proposed_unit_price_subunits: body.unitPriceSubunits ?? null,
      previous_label: previous?.label ?? null, previous_quantity: previous?.quantity ?? null, previous_unit_price_subunits: previous?.unit_price_subunits ?? null,
      proposed_by: session.user_id, proposed_at: now, created_at: now, updated_at: now,
    }).select("id").single();
    if (error) return err("BACKEND", 500, error.message);
    return json({ ok: true, pendingEditId: inserted.id });
  });
}

/** POST /web-claim/done — "I'm done" / undo (spec §2.6, §3.3, E31). A nudge-silencer, not a lock. */
async function actionDone(sb: SupabaseClient, body: { token?: string; sessionToken?: string; done?: boolean }): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  return withWriteGuard(sb, ctx, async () => {
    const session = await resolveSession(sb, ctx.link.group_id, body.sessionToken);
    if (!session) return err("SESSION_REQUIRED", 401);
    const now = Date.now();
    await sb.from("bill_participants")
      .update({ done_at: body.done ? now : null, updated_at: now })
      .eq("expense_id", ctx.link.expense_id).eq("user_id", session.user_id).is("deleted_at", null);
    return json({ ok: true });
  });
}

// ── Router ──────────────────────────────────────────────────────────────────────────────────────

// deno-lint-ignore no-explicit-any
const ACTIONS: Record<string, (sb: SupabaseClient, body: any) => Promise<Response>> = {
  resolve: actionResolve,
  name: actionName,
  "claim-placeholder": actionClaimPlaceholder,
  bill: actionBill,
  claim: actionClaim,
  join: actionJoin,
  edit: actionEdit,
  done: actionDone,
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS_HEADERS });
  if (req.method !== "POST") return err("METHOD_NOT_ALLOWED", 405);

  const path = new URL(req.url).pathname.replace(/\/+$/, "");
  const action = path.split("/").pop() ?? "";
  const handler = ACTIONS[action];
  if (!handler) return err("NOT_FOUND", 404, `unknown action "${action}"`);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return err("BAD_REQUEST", 400, "invalid JSON body");
  }

  const sb = serviceClient();
  try {
    return await handler(sb, body);
  } catch (e) {
    console.error(`web-claim: unhandled error in "${action}": ${(e as Error).message}`);
    return err("BACKEND", 500);
  }
});
