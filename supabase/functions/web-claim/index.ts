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

/** 128-bit random token, base62 — what goes in the QR/URL. Never stored; only its hash is.
 *  Rejection sampling, not `byte % 62`: 256 isn't a multiple of 62, so the modulo would make the first
 *  eight alphabet characters ~25% likelier than the rest. 22 chars clears 128 bits either way, so this
 *  is belt-and-braces rather than a live break — but a biased token generator is not a thing to leave
 *  in the one place that authorises a stranger to write to someone's bill. */
function newPlaintextToken(): string {
  const alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  const limit = 256 - (256 % alphabet.length); // 248 — bytes at or above this are redrawn
  let out = "";
  while (out.length < 22) {
    for (const b of crypto.getRandomValues(new Uint8Array(32))) {
      if (b >= limit) continue;
      out += alphabet[b % alphabet.length];
      if (out.length === 22) break;
    }
  }
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

interface Session {
  id: string;
  user_id: string;
  /** The payer merged this placeholder into a real account in-app while the guest held the session
   *  (spec E10). The session still identifies her, but it is now READ-ONLY: spec §4.4 is absolute that
   *  a web guest may never act as an account holder, and post-merge her writes would land on a retired
   *  placeholder id and silently detach from the account they were just folded into. */
  claimedElsewhere: boolean;
}

/** A guest's `localStorage` token → the placeholder they are, scoped to the LINK's group (identity is
 *  group-scoped and durable, spec §2.2 — a long-dead Ramen-night link still recognises her at Sunday
 *  roast). Returns null (not a Response) when there's simply no session yet, which is a normal state,
 *  not an error.
 *
 *  The `claimedElsewhere` check lives HERE rather than in the landing action, because every write path
 *  has to inherit it. It previously sat in actionResolve alone, which meant `claim`, `join`, `edit` and
 *  `done` all kept writing as a placeholder that no longer existed as an independent person. */
async function resolveSession(
  sb: SupabaseClient,
  groupId: string,
  sessionToken: string | undefined,
): Promise<Session | null> {
  if (!sessionToken) return null;
  const tokenHash = await sha256Hex(sessionToken);
  const { data } = await sb
    .from("web_sessions")
    .select("id, user_id, revoked_at")
    .eq("group_id", groupId)
    .eq("token_hash", tokenHash)
    .maybeSingle();
  if (!data || data.revoked_at) return null;

  const { data: member } = await sb
    .from("members")
    .select("placeholder_claim_completed_at")
    .eq("group_id", groupId).eq("user_id", data.user_id)
    .maybeSingle();

  await sb.from("web_sessions").update({ last_seen_at: Date.now() }).eq("id", data.id);
  return { id: data.id, user_id: data.user_id, claimedElsewhere: !!member?.placeholder_claim_completed_at };
}

/** The session gate every WRITE action runs: a session is required, and it must still be writable
 *  (spec E10, §4.4). Callers do `if (s instanceof Response) return s;`. */
function requireWritableSession(session: Session | null): Session | Response {
  if (!session) return err("SESSION_REQUIRED", 401);
  if (session.claimedElsewhere) return err("CLAIMED_ELSEWHERE", 409, "this name is now an account; the bill is read-only here");
  return session;
}

/** Ensures a `bill_participants` row exists for [userId] on [expenseId] — every identified guest is
 *  a participant of the bill they're claiming on, per spec §3.3's premise that the claim screen is
 *  participant-scoped, and `join_item_portion` refuses a joiner who isn't one. Idempotent (checked read
 *  then insert, not an upsert, since the row has no deterministic id convention shared with the app).
 *  Returns an error message, or null on success — a guest who silently isn't a participant can't join
 *  anything, and would be told nothing about why. */
async function ensureParticipant(
  sb: SupabaseClient, expenseId: string, groupId: string, userId: string, now: number,
): Promise<string | null> {
  const { data: existing } = await sb
    .from("bill_participants")
    .select("id")
    .eq("expense_id", expenseId)
    .eq("user_id", userId)
    .is("deleted_at", null)
    .maybeSingle();
  if (existing) return null;
  const { error } = await sb.from("bill_participants").insert({
    id: newId(), expense_id: expenseId, group_id: groupId, user_id: userId,
    created_at: now, updated_at: now,
  });
  // A concurrent request winning the same insert is success, not failure: the partial unique index on
  // (expense_id, user_id) where deleted_at is null is exactly what makes this idempotent under a race.
  if (error && error.code !== "23505") return error.message;
  return null;
}

// ── Rate limits (spec §4.5) ────────────────────────────────────────────────────────────────────

const MAX_WRITES_PER_MINUTE = 120;
const MAX_PLACEHOLDERS_PER_GROUP = 40; // spec says "per bill"; placeholders are group-scoped in the
                                        // schema (users.placeholder_group_id), so this enforces the
                                        // nearest real boundary — see supabase/AGENTS.md.
const MAX_ITEMS_PER_BILL = 200;
/** How long a `web_claim_write_log` row is worth keeping — a small multiple of the 60s window the rate
 *  check reads, enough that a clock skew between edge invocations can't prune a row still being counted. */
const RATE_LOG_RETENTION_MS = 5 * 60_000;

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

/** Records a write against the per-token budget, and drops that token's own rows older than the window
 *  it is measured over. Without the prune this table only ever grows: it is pure abuse-mitigation
 *  bookkeeping, nothing reads a row older than 60s, and a bill link lives 72 hours. The delete is
 *  scoped to one token's expired rows — never a bare or prefix-wide DELETE (`supabase/AGENTS.md`). */
async function logWrite(sb: SupabaseClient, tokenHash: string, now: number) {
  await sb.from("web_claim_write_log").insert({ id: newId(), token_hash: tokenHash, created_at: now });
  await sb.from("web_claim_write_log").delete()
    .eq("token_hash", tokenHash)
    .lt("created_at", now - RATE_LOG_RETENTION_MS);
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
    // The page has to prove it is real BEFORE it asks for a name (spec §3.1), and a bill with no
    // amount on it proves nothing. This is the expense's own stored total, not a computed split —
    // no per-person money is ever returned by this function.
    totalSubunits: ctx.expense.amount_subunits ?? 0,
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
    // E10: the payer may have claimed this placeholder in-app while the guest was mid-claim. The gate
    // itself is in resolveSession so that the write actions inherit it; here it only picks the screen.
    if (session.claimedElsewhere) return json({ state: "claimed_elsewhere", header });
    const { data: user } = await sb.from("users").select("id, display_name").eq("id", session.user_id).maybeSingle();
    const participantErr = await ensureParticipant(sb, ctx.link.expense_id, ctx.link.group_id, session.user_id, Date.now());
    if (participantErr) return err("BACKEND", 500, participantErr);
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
  // Newest-first on the claim/share rows, not an arbitrary page of them: the two expenses shown have to
  // be her two most RECENT, or the evidence strip can offer a stale pair while the dinner she actually
  // remembers sits just outside the page. The cap is generous enough that only a very long history is
  // truncated, and only at the far end.
  const EVIDENCE_SCAN = 200;
  const [{ data: claimIds }, { data: shareIds }] = await Promise.all([
    sb.from("item_claims").select("expense_id, created_at").eq("user_id", placeholder.id).is("deleted_at", null)
      .order("created_at", { ascending: false }).limit(EVIDENCE_SCAN),
    sb.from("item_shares").select("expense_id, created_at").eq("user_id", placeholder.id).is("deleted_at", null)
      .order("created_at", { ascending: false }).limit(EVIDENCE_SCAN),
  ]);
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

    // The exact-match check above drives the UI (the block copy, the suffix suggestions); the RPC
    // re-takes the same decision under an advisory lock and does the four inserts in one transaction.
    // Both are needed: only the lock stops two guests typing "Purity" at once from both landing, and
    // only one transaction stops a mid-sequence failure leaving a named placeholder with no session —
    // unreachable by the browser that just made it, and offered to everyone else as evidence.
    const now = Date.now();
    const userId = newId();
    const sessionToken = newPlaintextToken();
    const { data: created, error: createErr } = await sb.rpc("create_web_placeholder", {
      p_group_id: ctx.link.group_id,
      p_expense_id: ctx.link.expense_id,
      p_name: name,
      p_user_id: userId,
      p_member_id: newId(),
      p_session_id: newId(),
      p_session_token_hash: await sha256Hex(sessionToken),
      p_participant_id: newId(),
      p_now: now,
    });
    if (createErr) return err("BACKEND", 500, createErr.message);
    if (!created.created) {
      // Lost the race to an identical name. Same screen as the pre-check block, so the guest sees one
      // consistent outcome rather than a failure they can't act on.
      return json({ blocked: true, suggestions: [`${name} 2`, `${name} 3`, `${name} 4`] });
    }

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
    // The RPC writes the winning session row itself, under the advisory lock that decides the race —
    // inserting it out here afterwards let two racers each be told they won (see schema.sql).
    const sessionToken = newPlaintextToken();
    const { data, error } = await sb.rpc("claim_web_placeholder", {
      p_group_id: ctx.link.group_id,
      p_placeholder_user_id: body.placeholderUserId,
      p_session_id: newId(),
      p_session_token_hash: await sha256Hex(sessionToken),
      p_now: now,
    });
    if (error) return err("BACKEND", 500, error.message);
    if (!data.won) return json({ won: false, winnerSessionId: data.winner_session_id });

    const participantErr = await ensureParticipant(sb, ctx.link.expense_id, ctx.link.group_id, body.placeholderUserId, now);
    if (participantErr) return err("BACKEND", 500, participantErr);

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
  const [{ data: items }, { data: claims }, { data: shares }, { data: participants }, { data: pendingEdits }] = await Promise.all([
    sb.from("expense_items").select("id, label, quantity, line_total_subunits, sort_order").eq("expense_id", expenseId).is("deleted_at", null).order("sort_order"),
    sb.from("item_claims").select("id, item_id, user_id, quantity").eq("expense_id", expenseId).is("deleted_at", null),
    sb.from("item_shares").select("id, item_id, user_id, portion_id, quantity, added_by").eq("expense_id", expenseId).is("deleted_at", null),
    sb.from("bill_participants").select("user_id, done_at").eq("expense_id", expenseId).is("deleted_at", null),
    // The bill's change log (spec §2.7). Every row here has ALREADY moved the money in `items` above —
    // an edit applies on write — so this is never folded into the split. It exists so the guest can see
    // what changed and undo it, and so E19 holds for the honest reason: her total includes her edit
    // because the edit is real, not because the client is guessing at a provisional one.
    sb.from("pending_item_edits")
      .select("id, item_id, kind, proposed_label, proposed_quantity, proposed_unit_price_subunits, previous_label, previous_quantity, previous_unit_price_subunits, proposed_by, proposed_at, decided_at, decided_by, decision")
      .eq("expense_id", expenseId).order("proposed_at"),
  ]);

  const participantIds = (participants ?? []).map((p) => p.user_id as string);
  const claimantIds = (claims ?? []).map((c) => c.user_id as string);
  const shareUserIds = (shares ?? []).map((s) => s.user_id as string);
  const proposerIds = (pendingEdits ?? []).flatMap((e) => [e.proposed_by, e.decided_by]).filter(Boolean) as string[];
  const nameIds = Array.from(new Set([...participantIds, ...claimantIds, ...shareUserIds, ...proposerIds]));
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
      // Rides proportionally like tax. Omitting it here would make the guest's instant total short by a
      // delivery fee while the app's total (which reads the same column) was right — the one thing the TS
      // port is never allowed to do (web/AGENTS.md: stale by a poll, never disagreeing).
      otherChargesSubunits: ctx.expense.other_charges_subunits,
      // The causal version the guest read, which she hands back when naming the payer (spec §5.5,
      // E25). It is a *base*, not a claim: the server decides whether it is still current.
      splitVersion: ctx.expense.split_version ?? 1,
    },
    items: items ?? [],
    claims: claims ?? [],
    shares: shares ?? [],
    pendingEdits: pendingEdits ?? [],
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
    const session = requireWritableSession(await resolveSession(sb, ctx.link.group_id, body.sessionToken));
    if (session instanceof Response) return session;
    if (!body.itemId) return err("BAD_REQUEST", 400, "itemId required");
    const { data: item } = await sb.from("expense_items").select("id").eq("id", body.itemId).eq("expense_id", ctx.link.expense_id).is("deleted_at", null).maybeSingle();
    if (!item) return err("BAD_REQUEST", 400, "item not on this bill");

    const now = Date.now();
    const quantity = body.quantity ?? 0;
    const claimId = `${body.itemId}__${session.user_id}`;
    if (quantity <= 0) {
      const { error } = await sb.from("item_claims").update({ deleted_at: now, updated_at: now }).eq("item_id", body.itemId).eq("user_id", session.user_id).is("deleted_at", null);
      if (error) return err("BACKEND", 500, error.message);
    } else {
      // Filtered to LIVE rows: `item_claims_item_user_active_uidx` is partial, so a tombstone and a live
      // row can coexist for one (item, user) and an unfiltered maybeSingle() errors on the pair, drops
      // to the deterministic id, and collides with the live legacy-id row.
      const { data: existing } = await sb.from("item_claims").select("id, row_version")
        .eq("item_id", body.itemId).eq("user_id", session.user_id).is("deleted_at", null).maybeSingle();
      const { error } = await sb.from("item_claims").upsert({
        id: existing?.id ?? claimId, item_id: body.itemId, expense_id: ctx.link.expense_id, group_id: ctx.link.group_id,
        user_id: session.user_id, quantity, deleted_at: null, updated_at: now,
        created_at: existing ? undefined : now, row_version: (existing?.row_version ?? 0) + 1,
      });
      if (error) return err("BACKEND", 500, error.message);
    }
    return json({ ok: true });
  });
}

/** POST /web-claim/share — declare who had a line together (spec §3.4, the share sheet).
 *
 *  `join` adds exactly one person: the caller. That is right for "＋ Add me", and useless for "who had
 *  the juice with you?", where a guest names people who are not holding a phone. So this walks the
 *  named members through `join_item_portion` **server-side**, caller first so the conversion of any
 *  existing solo claim happens once, then everyone else onto that same portion id.
 *
 *  The loop is sequential on purpose: each call reads the line's current assignment to decide whether
 *  it is converting a solo claim, creating a portion, or over-claiming, and running them concurrently
 *  would race that read against its own siblings.
 *
 *  Membership is checked against `bill_participants`, not taken on trust: a token authorises writes to
 *  THIS bill's claims (spec §4.3), never the invention of arbitrary user ids on someone else's plate. */
async function actionShare(
  sb: SupabaseClient,
  body: { token?: string; sessionToken?: string; itemId?: string; memberUserIds?: string[]; overClaimAck?: boolean },
): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  return withWriteGuard(sb, ctx, async () => {
    const session = requireWritableSession(await resolveSession(sb, ctx.link.group_id, body.sessionToken));
    if (session instanceof Response) return session;
    if (!body.itemId) return err("BAD_REQUEST", 400, "itemId required");
    const { data: item } = await sb.from("expense_items").select("id").eq("id", body.itemId).eq("expense_id", ctx.link.expense_id).is("deleted_at", null).maybeSingle();
    if (!item) return err("BAD_REQUEST", 400, "item not on this bill");

    const requested = Array.from(new Set(body.memberUserIds ?? []));
    const { data: participants } = await sb
      .from("bill_participants").select("user_id").eq("expense_id", ctx.link.expense_id).is("deleted_at", null);
    const allowed = new Set((participants ?? []).map((p) => p.user_id as string));
    const unknown = requested.filter((id) => !allowed.has(id));
    if (unknown.length > 0) return err("BAD_REQUEST", 400, "not a participant of this bill");

    // The caller goes first so the portion exists (and any solo claim on the line is retired) before
    // anyone is added to it. Everyone else then names that portion explicitly.
    const members = [session.user_id, ...requested.filter((id) => id !== session.user_id)];
    let portionId: string | null = null;
    for (const userId of members) {
      const { data, error } = await sb.rpc("join_item_portion", {
        p_item_id: body.itemId,
        p_joiner_user_id: userId,
        p_portion_id: portionId,
        p_now: Date.now(),
        p_over_claim_ack: body.overClaimAck ?? false,
      });
      if (error) {
        if (error.message?.includes("OVERCLAIMED")) return err("OVERCLAIMED", 409, error.message);
        return err("BACKEND", 500, error.message);
      }
      portionId = data.portion_id as string;
    }
    return json({ ok: true, portionId, members });
  });
}

/** POST /web-claim/leave — take myself off a line (spec §2.7 "the person affected can remove you",
 *  E12). The mirror of `join`, and deliberately NOT an RPC: leaving touches only the caller's OWN
 *  `item_claims` / `item_shares` rows, so the partition invariant `join_item_portion` exists to
 *  protect is never in play. A portion left with one member is a solo assignment expressed as a
 *  one-member slice, which the split engine already bills correctly.
 *
 *  Without this, "＋ Add me" is a one-way door: a guest who joins the wrong plate can undo a solo
 *  claim but never a share, and §2.7's promise that joining is "reversible by the affected person"
 *  is only half true. */
async function actionLeave(
  sb: SupabaseClient,
  body: { token?: string; sessionToken?: string; itemId?: string },
): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  return withWriteGuard(sb, ctx, async () => {
    const session = requireWritableSession(await resolveSession(sb, ctx.link.group_id, body.sessionToken));
    if (session instanceof Response) return session;
    if (!body.itemId) return err("BAD_REQUEST", 400, "itemId required");
    const { data: item } = await sb.from("expense_items").select("id").eq("id", body.itemId).eq("expense_id", ctx.link.expense_id).is("deleted_at", null).maybeSingle();
    if (!item) return err("BAD_REQUEST", 400, "item not on this bill");

    // Soft-delete only, and scoped to this one (item, user) pair — never a bare delete, per
    // `supabase/AGENTS.md`. Both tables in one action because "take me off this line" is one gesture
    // to the guest whether she got there by claiming or by joining.
    const now = Date.now();
    const [claimRes, shareRes] = await Promise.all([
      sb.from("item_claims").update({ deleted_at: now, updated_at: now })
        .eq("item_id", body.itemId).eq("user_id", session.user_id).is("deleted_at", null),
      sb.from("item_shares").update({ deleted_at: now, updated_at: now })
        .eq("item_id", body.itemId).eq("user_id", session.user_id).is("deleted_at", null),
    ]);
    if (claimRes.error) return err("BACKEND", 500, claimRes.error.message);
    if (shareRes.error) return err("BACKEND", 500, shareRes.error.message);
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
    const session = requireWritableSession(await resolveSession(sb, ctx.link.group_id, body.sessionToken));
    if (session instanceof Response) return session;
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

/** POST /web-claim/edit — add/relabel/reprice/requantify/remove a line (spec §2.7, §3.6).
 *
 *  **It applies.** There is no approval gate and no waiting state: the edit lands on `expense_items` and
 *  the log row lands stamped `APPLIED`, both inside `apply_web_bill_edit`'s single transaction. The
 *  payer is *told*, and anyone on the bill can undo (see `actionUndoEdit`).
 *
 *  Nothing here writes `expenses` or `expense_items` directly, and that is not stylistic. This function
 *  has a service key and no `auth.uid()`, so it cannot go through `merge_expense`; a blind update that
 *  skipped the causal `split_version` bump would be silently reverted by the payer's next push. The RPC
 *  is the only place that write is expressed. See `WEB_CLAIM_SPEC.md` §5.8. */
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
    const session = requireWritableSession(await resolveSession(sb, ctx.link.group_id, body.sessionToken));
    if (session instanceof Response) return session;
    const kind = body.kind;
    if (!kind || !["ADD", "RELABEL", "REPRICE", "REQUANTITY", "REMOVE"].includes(kind)) return err("BAD_REQUEST", 400, "invalid kind");
    if (kind !== "ADD" && !body.itemId) return err("BAD_REQUEST", 400, "itemId required");

    // An unapproved ADD used to be a proposal with no line, so the cap had to count proposals too.
    // They are real lines now, and counting them twice would cap a bill at half its limit.
    if (kind === "ADD") {
      const { count: itemCount } = await sb.from("expense_items")
        .select("id", { count: "exact", head: true })
        .eq("expense_id", ctx.link.expense_id).is("deleted_at", null);
      if ((itemCount ?? 0) >= MAX_ITEMS_PER_BILL) return err("EXPIRED", 410, "too many items");
    }

    // `previous_*` are captured inside the RPC, from the row as it stands at that instant, rather than
    // sent from here: they are what Undo restores, so a value that took a round trip through a browser
    // would let a stale client restore the wrong price.
    const { data, error } = await sb.rpc("apply_web_bill_edit", {
      p_expense_id: ctx.link.expense_id,
      p_group_id: ctx.link.group_id,
      p_kind: kind,
      p_item_id: body.itemId ?? null,
      p_label: body.label ?? null,
      p_quantity: body.quantity ?? null,
      p_unit_price_subunits: body.unitPriceSubunits ?? null,
      p_actor: session.user_id,
      p_now: Date.now(),
    });
    if (error) {
      if (error.message?.includes("item not on this bill")) return err("BAD_REQUEST", 400, error.message);
      return err("BACKEND", 500, error.message);
    }
    return json({ ok: true, editId: data.edit_id, itemId: data.item_id });
  });
}

/** POST /web-claim/undo — take back a change to the menu (spec §2.7).
 *
 *  **Anyone on the bill may call this**, not only the person who made the change and not only the payer.
 *  That is the decision, not an oversight: an undo is itself an attributed entry in the log, undoing an
 *  already-undone change is a no-op, and the log is the tiebreak. A rights hierarchy here would
 *  re-import the adjudication model that dropping the approval gate removed.
 *
 *  Session-gated all the same — "anyone on the bill" means an identified guest, not an anonymous holder
 *  of the URL. */
async function actionUndoEdit(
  sb: SupabaseClient,
  body: { token?: string; sessionToken?: string; editId?: string },
): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  return withWriteGuard(sb, ctx, async () => {
    const session = requireWritableSession(await resolveSession(sb, ctx.link.group_id, body.sessionToken));
    if (session instanceof Response) return session;
    if (!body.editId) return err("BAD_REQUEST", 400, "editId required");

    // Scoped to THIS bill before the RPC sees it: a token authorises writes to one expense (spec §4.3),
    // and the RPC takes only an edit id, so nothing else stops a guest naming a change on another bill.
    const { data: edit } = await sb.from("pending_item_edits")
      .select("id").eq("id", body.editId).eq("expense_id", ctx.link.expense_id).maybeSingle();
    if (!edit) return err("BAD_REQUEST", 400, "change not on this bill");

    const { data, error } = await sb.rpc("undo_web_bill_edit", {
      p_edit_id: body.editId,
      p_actor: session.user_id,
      p_now: Date.now(),
    });
    if (error) return err("BACKEND", 500, error.message);
    return json({ ok: true, changed: data.changed });
  });
}

/** POST /web-claim/payer — "I paid for this" (spec §3.8, §5.5, E24, E25).
 *
 *  A guest may be named the payer of an existing bill. They may never create one, upload a receipt, or
 *  trigger OCR (spec §9) — nothing here does any of that.
 *
 *  `baseSplitVersion` is the version the guest's page last read, and the server decides whether it is
 *  still current. Two guests both tapping "I paid" is E25: the second one is causally stale, loses, and
 *  is told who the payer now is. That is a `200` with `ok: false`, not an error — losing a race you
 *  entered honestly is a state to explain, not a failure to retry. */
async function actionSetPayer(
  sb: SupabaseClient,
  body: { token?: string; sessionToken?: string; userId?: string; baseSplitVersion?: number },
): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  return withWriteGuard(sb, ctx, async () => {
    const session = requireWritableSession(await resolveSession(sb, ctx.link.group_id, body.sessionToken));
    if (session instanceof Response) return session;
    // Naming *someone else* the payer is allowed and is the same write; what is not allowed is naming a
    // user id that is not on this bill, which the RPC also refuses.
    const userId = body.userId ?? session.user_id;

    const { data, error } = await sb.rpc("set_web_bill_payer", {
      p_expense_id: ctx.link.expense_id,
      p_user_id: userId,
      p_base_split_version: body.baseSplitVersion ?? (ctx.expense.split_version as number) ?? 1,
      p_actor: session.user_id,
      p_now: Date.now(),
    });
    if (error) {
      if (error.message?.includes("not a participant")) return err("BAD_REQUEST", 400, error.message);
      return err("BACKEND", 500, error.message);
    }
    return json({
      ok: data.ok, stale: data.stale,
      payerUserId: data.payer_user_id, payerName: data.payer_name,
      splitVersion: data.split_version,
    });
  });
}

/** POST /web-claim/done — "I'm done" / undo (spec §2.6, §3.3, E31). A nudge-silencer, not a lock. */
async function actionDone(sb: SupabaseClient, body: { token?: string; sessionToken?: string; done?: boolean }): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  return withWriteGuard(sb, ctx, async () => {
    const session = requireWritableSession(await resolveSession(sb, ctx.link.group_id, body.sessionToken));
    if (session instanceof Response) return session;
    const now = Date.now();
    const { error } = await sb.from("bill_participants")
      .update({ done_at: body.done ? now : null, updated_at: now })
      .eq("expense_id", ctx.link.expense_id).eq("user_id", session.user_id).is("deleted_at", null);
    if (error) return err("BACKEND", 500, error.message);
    return json({ ok: true });
  });
}

/** POST /web-claim/release — "Not Purity? Use a different name" (spec E4, E5). Revokes this browser's
 *  session and hands back the landing branch, so the guest lands on the evidence list as an unknown
 *  visitor again. Spec E4 puts this on EVERY screen, and E5 (one phone passed around a table) calls it
 *  the most likely real failure — so it must be available whether or not the session is still writable,
 *  which is why it takes `resolveSession` directly rather than the writable gate.
 *
 *  It never touches the placeholder itself: identity is durable (§2.2), and her claims stay hers. All
 *  that ends is this browser's binding to her. */
async function actionRelease(sb: SupabaseClient, body: { token?: string; sessionToken?: string }): Promise<Response> {
  const ctx = await resolveLink(sb, body.token);
  if (ctx instanceof Response) return ctx;
  return withWriteGuard(sb, ctx, async () => {
    const session = await resolveSession(sb, ctx.link.group_id, body.sessionToken);
    if (!session) return json({ ok: true, released: false }); // already nobody — the desired end state
    const { error } = await sb.from("web_sessions").update({ revoked_at: Date.now() }).eq("id", session.id);
    if (error) return err("BACKEND", 500, error.message);
    return json({ ok: true, released: true });
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
  share: actionShare,
  leave: actionLeave,
  edit: actionEdit,
  undo: actionUndoEdit,
  payer: actionSetPayer,
  done: actionDone,
  release: actionRelease,
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
