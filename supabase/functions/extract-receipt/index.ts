// Evenly receipt OCR ("Split the bill"). Takes a receipt photo and returns a structured, EDITABLE
// draft of the bill — line items + tax/gratuity/tip/discount — by asking Claude vision for forced
// structured output. The client always lands the user on the editable item list to confirm before any
// money is computed; this function only produces a first draft, never a source of truth.
//
// Inert until configured: set the `ANTHROPIC_API_KEY` secret. Without it the function returns 200 +
// { configured:false } so the client can fall back to manual entry without surfacing an error. A
// 401/403/credit-exhausted response from Anthropic (bad key, revoked key, no funds) routes through
// that same { configured:false } shape, since it's equally unfixable by the client retrying, and
// separately best-effort-alerts `SLACK_ALERT_WEBHOOK_URL` (optional; a no-op if unset) so the owner
// finds out without having to check logs.
//
//   POST { "files": [{ "data": "<base64>", "mediaType": "image/jpeg" | "application/pdf" }, ...] }  // multi-page
//     or { "imageBase64": "...", "mediaType"?: "image/jpeg" }     // single image inline (legacy)
//     or { "storagePath": "abc.jpg" }                             // object path in the `receipts` bucket
//
// `storagePath` names an OBJECT ONLY. The bucket is always `receipts`, hardcoded — a leading
// `receipts/` is tolerated for older clients and stripped. The caller cannot choose the bucket: this
// download runs with the service role, so a caller-named bucket would read any private bucket.
//
// `files` may mix several photos and/or PDFs — they're read together as ONE bill, so a multi-page
// receipt yields a single item list. PDFs go in as document blocks; images as image blocks.
//
// `groupId` is REQUIRED (400 without it). It is the Evenly Pro quota key, not just analytics: a group
// gets 5 successful scans free for its lifetime, after which any member can buy it a pass and the whole
// group scans without limit until that pass expires. Over the allowance and not Pro returns **402** with
// `reason: "quota_exhausted"` — deliberately not the 429 below, because "no scans left" opens a paywall
// and "too fast" opens a wait. Both refusals happen before any paid call. See PRO_PASS_SPEC.md §7.
//
// Auth: send the signed-in user's access token as the Bearer (the client does), with the anon key in the
// `apikey` header. The function REQUIRES a resolvable user (401 otherwise) so the per-user rate limit on
// this paid endpoint actually engages — an anon-key-only caller used to bypass it entirely (P1 #11).
//
// ONE primary pass with a single optional escalation (Sonnet 5 -> Opus 5). The primary runs with thinking
// OFF so the whole token budget goes to the tool JSON and the round trip stays short; the escalation runs
// with adaptive thinking for the genuinely hard photos. The escalation is additionally gated by an org-wide
// daily circuit breaker so one bad batch of photos — or a deliberate abuse attempt — can't run up an
// unbounded shared bill. A model can also report `is_receipt: false`, which short-circuits immediately (no
// further paid calls) — this is what stops a selfie, a ride-share summary, or any other non-receipt photo
// from ever reaching the escalation tier, since "is this a receipt at all" is a cheap, reliable
// classification even when full itemized extraction is hard.
//
// This replaced a three-tier Haiku -> Sonnet -> Opus cascade (2026-08-02). Two defects made that cascade
// both slow and wrong:
//   1. The validation gate was strict enough that real receipts almost always failed it, so nearly every
//      scan paid for all three sequential vision calls instead of one. See isValidDraft below.
//   2. Every tier ran with `max_tokens: 2048` and no `thinking` field. On claude-sonnet-5 an omitted
//      `thinking` means ADAPTIVE THINKING AT EFFORT high (a documented change from Sonnet 4.6), and
//      max_tokens caps thinking + tool output TOGETHER — so thinking could eat the budget and the
//      record_receipt JSON got truncated mid-object. A truncated tool_use `input` still parses: the keys
//      that made it through are present and the rest are absent, which the client's `= 0` defaults turn
//      into a bill with item names and 0.00 everywhere. Nothing checked `stop_reason`, so that truncated
//      draft was returned as if it were fine. Both are fixed below: thinking is now explicit, max_tokens
//      has real headroom, and a `max_tokens` stop is treated as a failed pass.
//
// THE MODEL DOES NO ARITHMETIC (2026-08-08). It transcribes the receipt; `billMath.ts` adds it up and
// checks the sum against the PRINTED grand total. Where the two disagree the model gets one verify turn:
// it is shown our working and asked to correct its READINGS, never to adjust a figure so the sum comes
// out. Two defects came from ignoring this — a dollars->cents rescale the model did by truncation, and a
// schema field defined as the sum of its own siblings, which turned the validation gate into a check of
// the model against itself. See contract.ts and billMath.ts.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import {
  EXTRACT_PROMPT,
  normalizeReceipt,
  RECEIPT_TOOL,
  type RawReceipt,
  type Receipt,
} from "./contract.ts";
import {
  type BillMath,
  computeBill,
  describeDiscrepancy,
  fmt,
} from "./billMath.ts";
import { captureServerEvent } from "../_shared/posthogServer.ts";

interface ReceiptPart {
  data: string;      // base64-encoded bytes
  mediaType: string; // image/* or application/pdf
}

interface ExtractRequest {
  files?: ReceiptPart[];
  imageBase64?: string;
  mediaType?: string;
  storagePath?: string;
  groupId?: string | null;
}

/** One model pass. `truncated` means the response stopped on max_tokens, so `receipt` may be a partial object. */
interface Pass {
  receipt: Receipt | null;
  truncated: boolean;
  usage: { inputTokens: number; outputTokens: number };
  /** The raw tool_use, replayed verbatim as the assistant turn when we ask the model to re-read. */
  toolUseId: string | null;
  rawInput: unknown;
}

/** The follow-up turn: the model's own draft handed back with our arithmetic over it. See billMath.ts. */
interface VerifyTurn {
  toolUseId: string | null;
  toolInput: unknown;
  feedback: string;
}

/**
 * Thrown by extractWithModel on a non-OK Anthropic response. `retryable` distinguishes a transient
 * failure (worth trying the next tier / letting the client retry) from an account-level failure that
 * will fail identically on every future call until a human fixes it (bad/revoked key, exhausted
 * credit) -- see classifyAnthropicFailure below.
 */
class AnthropicCallError extends Error {
  constructor(message: string, readonly status: number, readonly retryable: boolean) {
    super(message);
  }
}

/**
 * 401/403 mean the key itself is bad or revoked; a 400 whose body mentions credit/billing means the
 * account is out of funds. None of these get better on retry, on the next tier (same key), or ever
 * without a human changing the Anthropic account -- unlike 429 (rate limited) or 5xx (overloaded),
 * which are worth retrying.
 */
function classifyAnthropicFailure(status: number, bodyText: string): boolean {
  if (status === 401 || status === 403) return false;
  if (status === 400 && /credit|billing|balance/i.test(bodyText)) return false;
  return true; // 429, 5xx, and any other 4xx we haven't seen a reason to treat as terminal
}

// A Claude content block for one receipt page — a PDF renders as a document, everything else as an image.
function pageBlock(part: ReceiptPart) {
  const source = { type: "base64", media_type: part.mediaType, data: part.data };
  return part.mediaType === "application/pdf"
    ? { type: "document", source }
    : { type: "image", source };
}

// Two tiers. The PRIMARY handles the overwhelming majority of scans in one round trip: thinking is
// explicitly OFF, so the entire token budget goes to the record_receipt JSON and nothing is spent
// deliberating about a task that is pure transcription. The ESCALATION only runs when the primary's draft
// fails isValidDraft(), and it is the one place we pay for reasoning (adaptive thinking at medium effort)
// and for the pricier model. It is additionally gated by the org-wide circuit breaker (see
// checkEscalationBreaker) since it's the expensive path.
//
// `max_tokens` bounds thinking + tool output together, so each tier's budget is sized for its thinking
// mode, not just for the JSON. A receipt's record_receipt payload is small (a 40-line bill is well under
// 2k tokens), so these ceilings exist purely to make truncation impossible.
const TIERS = [
  {
    name: "primary",
    model: "claude-sonnet-5",
    maxTokens: 8192,
    thinking: { type: "disabled" },
    effort: "low",
  },
  {
    name: "escalation",
    model: "claude-opus-5",
    maxTokens: 16000,
    thinking: { type: "adaptive" },
    effort: "medium",
  },
] as const;

// Per-model pricing for the cost ledger, in MICROS OF A DOLLAR per token (1,000,000 micros = $1) so
// `cost_micros` stays an exact integer instead of an accumulating float. Rates checked 2026-08-02
// against the Anthropic pricing page -- they change; if a cost figure looks wrong, check this table
// against the console before suspecting the token counts.
const MODEL_RATES_MICROS_PER_TOKEN: Record<string, { input: number; output: number }> = {
  "claude-sonnet-5": { input: 3, output: 15 },
  "claude-opus-5": { input: 5, output: 25 },
};

function tokenCostMicros(model: string, inputTokens: number, outputTokens: number): number {
  const rate = MODEL_RATES_MICROS_PER_TOKEN[model];
  if (!rate) return 0; // unpriced model -- log $0 rather than throw and lose the scan over a pricing gap
  return Math.round(inputTokens * rate.input + outputTokens * rate.output);
}

// Org-wide cap on escalation-tier calls in a rolling 24h window, independent of the per-user
// receipt_scan_log limit below. Env-overridable (the env var keeps its original name so deployed
// environments don't need reconfiguring); 200/day is a starting default, not a measured number.
const ESCALATION_DAILY_CAP = Number(Deno.env.get("OPUS_DAILY_CAP") ?? "200");

// Evenly Pro's free allowance: successful scans a group gets before someone has to buy it a pass
// (PRO_PASS_SPEC.md §4). Per GROUP and for the life of the group, never reset. Env-overridable so the
// number can be A/B'd from the dashboard without a redeploy, which is the whole reason the cost ledger
// exists — 5 is a judgement call and the ledger is what will correct it.
const FREE_SCANS_PER_GROUP = Number(Deno.env.get("FREE_SCANS_PER_GROUP") ?? "5");

// A label a model reaches for when it gives up and lumps the whole bill into one generic line instead
// of actually itemizing — the exact "receipt: $47.32" degenerate failure.
//
// Matched against the WHOLE normalized label, not as a substring. Substring matching was a false-positive
// machine: "total" is inside "Totally Loaded Fries", "bill" is inside "Billy Burger", "items" is inside
// "Items Bar Combo". Every one of those is a perfectly good line on a perfectly good receipt, and each one
// used to condemn the draft and force the full escalation. The lumping failure this guards against
// produces a bare label, so whole-label matching still catches it.
const GENERIC_LABELS = new Set([
  "receipt", "total", "subtotal", "bill", "purchase", "purchases", "items", "item",
  "misc", "miscellaneous", "n/a", "na", "order", "amount", "charge",
]);

/** Lowercased, punctuation-stripped, whitespace-collapsed — so "Receipt Total:" and "receipt total" match. */
function normalizeLabel(label: string): string {
  return String(label ?? "").toLowerCase().replace(/[^a-z0-9 ]+/g, " ").replace(/\s+/g, " ").trim();
}

/**
 * Catches the "gave up and lumped everything into one line" failure mode, which reconciles PERFECTLY
 * (one item priced at exactly the total) and so is invisible to the reconciliation alone — this needs its
 * own check. Independent of it: a receipt can reconcile and still be degenerate.
 */
function isDegenerate(receipt: Receipt, math: BillMath, pageCount: number): boolean {
  const items = receipt.items ?? [];
  if (items.length === 0) return true;
  if (items.some((it) => GENERIC_LABELS.has(normalizeLabel(it.label)))) return true;
  const e = math.extras;
  const noExtras = !e.tax_subunits && !e.gratuity_subunits && !e.tip_subunits && !e.discount_subunits && !e.other_subunits;
  if (items.length === 1 && noExtras && items[0].line_total_subunits === math.printedTotal) return true;
  // A multi-page bill that collapses to one line is almost certainly a lumping failure, not a real
  // one-item receipt that happened to span several photos.
  if (pageCount > 1 && items.length <= 1) return true;
  return false;
}

/**
 * A draft that read the item NAMES but none of the prices, so the user lands on a bill of real dishes at
 * 0.00 each. It looks like a successful scan, and the original gate had no check for it at all — a
 * truncated tool_use payload sailed straight through to the client, whose `= 0` field defaults finished
 * the job silently.
 *
 * One priced line is the bar, not all of them: a genuinely comped or free line at 0 is legitimate.
 */
function hasAmounts(receipt: Receipt): boolean {
  return (receipt.items ?? []).some((it) => (it.line_total_subunits ?? 0) > 0);
}

/**
 * The three gates. `math.reconciles` is the load-bearing one, and it now compares OUR sum of the model's
 * readings against the grand total PRINTED on the receipt — two quantities the model cannot bring into
 * agreement by adjusting one of them, because it does not compute either.
 *
 * There is exactly one reconciliation branch, on purpose. The previous version had a second: the lines
 * against the model's own `subtotal` field, whose schema description told the model that field "must equal
 * the sum of every line_total_subunits above". A model that misread every line still summed its own
 * misreadings and matched itself, so the branch could not fail — and a bill $9.50 short of its printed
 * total shipped as `verified: true` on one tier with no warning (receipt_scan_log outcome "ok"). Printed
 * subtotals are now diagnostics only (`math.printedSubtotals`) and validate nothing.
 *
 * **Never add a gate whose two sides both come from the model.** It cannot fail, and it will read like
 * safety in the diff.
 */
function isValidDraft(receipt: Receipt, math: BillMath, pageCount: number): boolean {
  return hasAmounts(receipt) && !isDegenerate(receipt, math, pageCount) && math.reconciles;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const apiKey = Deno.env.get("ANTHROPIC_API_KEY");
  if (!apiKey) return json({ configured: false, reason: "ANTHROPIC_API_KEY not set" }, 200);

  let payload: ExtractRequest;
  try {
    payload = await req.json();
  } catch {
    return json({ error: "invalid JSON body" }, 400);
  }

  // Resolve the receipt pages: a `files` array, a single inline image, or a download from the receipts
  // bucket with the service role. All resolved pages OCR together as one bill.
  let pages: ReceiptPart[] = payload.files ?? [];
  if (pages.length > 8) return json({ error: "Too many pages — scan up to 8 pages per receipt." }, 400);
  if (pages.length === 0 && payload.imageBase64) {
    pages = [{ data: payload.imageBase64, mediaType: payload.mediaType ?? "image/jpeg" }];
  }
  if (pages.length === 0 && payload.storagePath) {
    const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
    // The bucket is OURS, never the caller's: this download runs with the service role, so letting the
    // path name its own bucket would be a read primitive for every private bucket in the project.
    const path = payload.storagePath.replace(/^\/+/, "").replace(/^receipts\//, "");
    if (path.length === 0 || path.split("/").includes("..")) {
      return json({ error: "invalid storagePath" }, 400);
    }
    const { data, error } = await supabase.storage.from("receipts").download(path);
    if (error || !data) return json({ error: `download failed: ${error?.message ?? "no data"}` }, 400);
    pages = [{ data: b64encode(new Uint8Array(await data.arrayBuffer())), mediaType: data.type || "image/jpeg" }];
  }
  if (pages.length === 0) return json({ error: "files, imageBase64, or storagePath required" }, 400);

  // Per-user rate limit: at most 20 scans/hour on this paid, Claude-vision-backed endpoint. The caller
  // authenticates with their OWN user JWT (P1 #11 — the client now sends its access token, not the anon
  // key), so `auth.uid()` and this RLS-scoped query resolve to them.
  const authHeader = req.headers.get("Authorization") ?? "";
  const callerClient = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader } } },
  );
  const { data: userData } = await callerClient.auth.getUser();
  const callerId = userData?.user?.id;
  // Require a real user. An anon-key-only caller (the old client) resolves no user; without this the whole
  // rate limit was skipped, so anyone with the shipped anon key got unlimited paid calls (P1 #11).
  if (!callerId) return json({ error: "unauthorized" }, 401);

  // groupId is REQUIRED as of Evenly Pro (PRO_PASS_SPEC.md §7). It began as an optional analytics
  // attribution field, but it is now the quota key: a nullable quota key is simply a bypass, since
  // omitting one field would buy unlimited scans. Both in-app call sites already send a real group id
  // (LedgerRoutes.kt, BillRoutes.kt), so requiring it breaks no shipped client.
  const groupId = payload.groupId ?? null;
  if (!groupId) return json({ error: "groupId required" }, 400);

  const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString();
  const { count, error: countError } = await callerClient
    .from("receipt_scan_log")
    .select("id", { count: "exact", head: true })
    .eq("user_id", callerId)
    .gt("created_at", oneHourAgo)
    // Exclude rows logged for a REJECTED (rate_limited) attempt from the count itself — otherwise a
    // blocked request would consume a slot of the very limit that blocked it. `.or` (not `.neq`) because
    // a bare `outcome <> 'rate_limited'` also drops still-in-flight rows, whose outcome is null.
    .or("outcome.is.null,outcome.neq.rate_limited");
  // Fail CLOSED: if we can't read the window we don't know if the caller is over the limit, so refuse
  // rather than let an error open the floodgates on a paid endpoint.
  if (countError) return json({ error: "rate check unavailable — try again shortly." }, 503);
  if ((count ?? 0) >= 20) {
    // Blocked scans are otherwise invisible: we're turning users away and had no record of how often.
    // Best-effort only (a logging hiccup must not change the 429 the caller already earned).
    await logImmediateOutcome(callerClient, callerId, groupId, pages.length, "rate_limited");
    return json({ error: "Too many scans — try again in a bit." }, 429);
  }

  // ── Evenly Pro quota (PRO_PASS_SPEC.md §7) ────────────────────────────────────────────────────
  // Runs AFTER the rate limit and BEFORE the pre-call log insert, so a refused scan neither consumes a
  // rate-limit slot nor a free scan.
  //
  // SERVICE ROLE, not callerClient, and that is not a shortcut: `receipt_scan_log`'s RLS policy is
  // `user_id = auth.uid()`, so the caller's own client can only ever see the scans THEY did. The
  // allowance is per GROUP across all its members, so counting through callerClient would give every
  // member their own private 5 and a six-person group 30 free scans. `group_pro_status` and
  // `group_free_scans_used` are both revoked from anon/authenticated for the same reason.
  const serviceClient = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );

  // The caller must actually be in the group they are spending its allowance on. Without this, anyone
  // could pass a Pro group's id and scan on its pass, and the cost ledger would blame that group.
  const { data: membership, error: memberError } = await serviceClient
    .from("members")
    .select("id")
    .eq("group_id", groupId)
    .eq("user_id", callerId)
    .eq("status", "ACTIVE")
    .maybeSingle();
  // Fail closed, same reasoning as the rate-limit read: an unreadable membership means we cannot say
  // this scan is allowed, and this endpoint costs real money.
  if (memberError) return json({ error: "membership check unavailable — try again shortly." }, 503);
  if (!membership) return json({ error: "not a member of this group" }, 403);

  // No row means not Pro — group_pro_status has no `is_pro = false` row to return.
  const { data: proRows, error: proError } = await serviceClient
    .rpc("group_pro_status", { p_group_id: groupId, p_now: Date.now() });
  if (proError) return json({ error: "pass check unavailable — try again shortly." }, 503);
  const isPro = Array.isArray(proRows) ? proRows.length > 0 : !!proRows;

  if (!isPro) {
    const { data: usedRaw, error: usedError } = await serviceClient
      .rpc("group_free_scans_used", { p_group_id: groupId });
    if (usedError) return json({ error: "scan count unavailable — try again shortly." }, 503);
    const used = Number(usedRaw ?? 0);
    if (used >= FREE_SCANS_PER_GROUP) {
      // Logged before returning so refused scans are visible in the ledger — we are turning paying-
      // intent users away and should know how often, and by how much they overshoot.
      await logImmediateOutcome(callerClient, callerId, groupId, pages.length, "quota_exhausted");
      // 402, deliberately NOT the 429 above: "you have no scans left" and "you are going too fast" lead
      // to different screens (a paywall vs a wait), so the client must be able to tell them apart.
      return json(
        {
          error: "This group has used its free scans.",
          reason: "quota_exhausted",
          scansUsed: used,
          freeLimit: FREE_SCANS_PER_GROUP,
        },
        402,
      );
    }
  }

  // Log the scan BEFORE the paid call so the slot is consumed immediately — two concurrent requests can't
  // both slip under the limit (the classic check-then-act race), and a crash mid-call still counts. A
  // failing insert also fails closed (we won't spend a call we can't rate-limit). One log entry covers the
  // whole scan even when the escalation below runs too, so a hard photo isn't two slots.
  // `.select("id").single()` captures the row so it can be completed with cost/outcome below and returned
  // to the caller as `scanId`.
  const { data: insertedRow, error: logError } = await callerClient
    .from("receipt_scan_log")
    .insert({ user_id: callerId, group_id: groupId, page_count: pages.length })
    .select("id")
    .single();
  if (logError || !insertedRow) return json({ error: "rate log unavailable — try again shortly." }, 503);
  const scanId = insertedRow.id as string;
  const scanStartedAt = Date.now();

  const tiersUsed: string[] = [];
  let totalInputTokens = 0;
  let totalOutputTokens = 0;
  let totalCostMicros = 0;

  // Completes the ledger row and returns the caller's response. Best-effort: a ledger write failure is
  // logged and swallowed, never surfaced to the user — the opposite of the pre-call insert above, which
  // correctly fails closed. The asymmetry is deliberate: that insert protects the rate limit itself, this
  // update is bookkeeping after the paid call already happened.
  //
  // `raw_draft` / `residual_subunits` / `verified` exist because the 2026-08-08 postmortem could only
  // INFER what the model had done: the ledger recorded that a scan passed, and nothing recorded what it
  // returned. `raw_draft` is the tool input verbatim, before normalization, so the next diagnosis is a
  // query. `residual_subunits` is our computed total minus the printed one, which makes "are misreads
  // getting worse" answerable across scans rather than one screenshot at a time.
  async function finish(
    outcome: string,
    body: unknown,
    status: number,
    ledger?: { rawDraft?: unknown; residual?: number; verified?: boolean },
  ): Promise<Response> {
    const { error } = await callerClient
      .from("receipt_scan_log")
      .update({
        group_id: groupId,
        outcome,
        tiers_used: tiersUsed,
        input_tokens: totalInputTokens,
        output_tokens: totalOutputTokens,
        cost_micros: totalCostMicros,
        duration_ms: Date.now() - scanStartedAt,
        completed_at: new Date().toISOString(),
        raw_draft: ledger?.rawDraft ?? null,
        residual_subunits: ledger?.residual ?? null,
        verified: ledger?.verified ?? null,
      })
      .eq("id", scanId);
    if (error) console.error(`extract-receipt: failed to complete scan log ${scanId} (${outcome}): ${error.message}`);
    // Mirrors this same row into PostHog (ANALYTICS_PLAN_C_JOURNEY_AND_CLAIMING.md §4) so cost, latency,
    // and fallback rate are visible in PostHog's LLM observability dashboards without a Supabase query.
    // Best-effort and inert with no POSTHOG_API_KEY set, same as the ledger write above it — written right
    // beside it so the two can't drift apart from a partial edit.
    await captureServerEvent(callerId, "scan_model_completed", {
      scan_id: scanId,
      group_id: groupId,
      outcome,
      tiers_used: tiersUsed,
      is_fallback: tiersUsed.length > 1,
      input_tokens: totalInputTokens,
      output_tokens: totalOutputTokens,
      cost_micros: totalCostMicros,
      duration_ms: Date.now() - scanStartedAt,
    });
    return json({ ...(body as Record<string, unknown>), scanId }, status);
  }

  // Primary first, escalation only if it's needed. A tier that reports is_receipt:false ends everything
  // immediately (no further paid calls), since a non-receipt photo doesn't get more "receipt-y" on a
  // stronger model. A tier whose draft doesn't pass isValidDraft() escalates. The escalation tier is
  // additionally gated by the org-wide circuit breaker.
  let lastReceipt: Receipt | null = null;
  let lastMath: BillMath | null = null;
  // The tool input, verbatim, behind whichever reading we ended up keeping — including a verify turn's
  // correction. This is what gets stored for diagnosis, so it must track the KEPT draft, not the last one
  // the model happened to produce.
  let lastRawDraft: unknown = null;
  let lastError: string | null = null;
  // Set when a tier's call fails in a way retrying (the next tier, or the client hitting "try again")
  // can never fix — a bad/revoked key or exhausted credit. Both tiers share the one ANTHROPIC_API_KEY,
  // so once this fires there is no point spending the escalation tier's call too; see the `break` below.
  let terminalFailure: AnthropicCallError | null = null;
  // We're declining the priciest tier, which is itself worth counting — "how often do we say no". Doesn't
  // short-circuit the response: the primary's best-effort draft (if any) still ships below, same as before
  // this ledger existed — a declined escalation is never a silent dead end for the user.
  let breakerTripped = false;
  for (const tier of TIERS) {
    if (tier.name === "escalation") {
      breakerTripped = await checkEscalationBreaker();
      if (breakerTripped) break;
    }

    // A failure on ONE tier is not a failure of the scan. This used to return 502 immediately, so a
    // transient 429/529 from the primary model, or a request parameter the primary rejects, took the whole
    // scan down even though the escalation tier would have answered fine. Remember the error and only
    // surface it if every tier fails.
    let pass: Pass;
    try {
      pass = await extractWithModel(apiKey, pages, tier);
    } catch (e) {
      lastError = (e as Error).message ?? "extraction failed";
      console.error(`extract-receipt: ${tier.name} (${tier.model}) failed: ${lastError}`);
      if (e instanceof AnthropicCallError && !e.retryable) {
        terminalFailure = e;
        break; // same key on every tier — escalating would just fail the same way
      }
      continue;
    }

    // Every tier that actually ran cost money, including one that returned is_receipt:false or truncated —
    // account for it before any early return below.
    tiersUsed.push(tier.name);
    totalInputTokens += pass.usage.inputTokens;
    totalOutputTokens += pass.usage.outputTokens;
    totalCostMicros += tokenCostMicros(tier.model, pass.usage.inputTokens, pass.usage.outputTokens);

    if (pass.receipt === null) continue; // no tool_use block at all, so try the next tier

    if (pass.receipt.is_receipt === false) {
      return finish("not_receipt", { configured: true, noReceipt: true }, 200);
    }

    if (tier.name === "escalation") {
      // Log the escalation AFTER a successful call, since the breaker check above already gated
      // whether we're allowed to spend here. This just records that we did, for the next check.
      await logEscalation(callerId);
    }

    // A `max_tokens` stop means the tool JSON was cut off mid-object, so the fields that didn't make it
    // are simply absent. That parses cleanly and looks like a real draft, which is exactly how a bill of
    // correctly-read item names with 0.00 in every amount used to reach the user. Never trust a truncated
    // pass and never keep it as the fallback: escalate instead, and if the escalation also truncates we
    // would rather return nothing usable than a confidently wrong bill.
    if (pass.truncated) {
      console.warn(`extract-receipt: ${tier.name} (${tier.model}) stopped on max_tokens; draft discarded`);
      continue;
    }

    // ── The verify turn ────────────────────────────────────────────────────────────────────────
    // We add the transcription up (billMath.ts) and compare it against the grand total PRINTED on the
    // receipt. Those are two independent quantities: one is our arithmetic over the model's readings, the
    // other is a number the model only copied. The model cannot bring them into agreement by computing
    // differently, because it does not compute either of them.
    //
    // When they disagree, something was misread — so before paying for a stronger model, show this one our
    // working and let it look again. It is being asked to fix a READING, which is its job, not to fix a
    // sum, which is ours. One turn only: a model that has looked twice and still disagrees is not going to
    // converge by looking a third time, and the escalation tier below is the better spend.
    let receipt = pass.receipt;
    let math = computeBill(receipt);
    let rawDraft: unknown = pass.rawInput;
    if (!math.reconciles && hasAmounts(receipt)) {
      console.warn(
        `extract-receipt: ${tier.name} does not reconcile ` +
          `(computed=${fmt(math.computedTotal)}, printed=${fmt(math.printedTotal)}, ` +
          `residual=${fmt(math.residual)}); asking it to re-read`,
      );
      try {
        const retry = await extractWithModel(apiKey, pages, tier, {
          toolUseId: pass.toolUseId,
          toolInput: pass.rawInput,
          feedback: describeDiscrepancy(receipt, math),
        });
        tiersUsed.push(`${tier.name}:verify`);
        totalInputTokens += retry.usage.inputTokens;
        totalOutputTokens += retry.usage.outputTokens;
        totalCostMicros += tokenCostMicros(tier.model, retry.usage.inputTokens, retry.usage.outputTokens);

        if (retry.receipt && !retry.truncated) {
          const retryMath = computeBill(retry.receipt);
          // Keep the second reading ONLY if it is genuinely closer to the printed total. A model that
          // "corrects" itself further away has started inventing, and the first reading is the honest one.
          // An equal residual is not an improvement either — it means nothing was found, and the first
          // reading is the one that was not made under pressure to change something.
          if (Math.abs(retryMath.residual) < Math.abs(math.residual)) {
            console.info(
              `extract-receipt: ${tier.name} verify turn improved the read ` +
                `(${fmt(math.residual)} -> ${fmt(retryMath.residual)})`,
            );
            receipt = retry.receipt;
            math = retryMath;
            rawDraft = retry.rawInput;
          } else {
            console.info(`extract-receipt: ${tier.name} verify turn did not improve; keeping the first read`);
          }
        }
      } catch (e) {
        // The verify turn is an improvement, never a dependency: a failure here leaves the first reading
        // exactly as it was and the scan continues to the gates below.
        console.error(`extract-receipt: ${tier.name} verify turn failed: ${(e as Error).message}`);
      }
    }

    lastReceipt = receipt;
    lastMath = math;
    lastRawDraft = rawDraft;
    if (isValidDraft(receipt, math, pages.length)) {
      return finish(
        "ok",
        { configured: true, receipt: clientReceipt(receipt, math), verified: true },
        200,
        { rawDraft, residual: math.residual, verified: true },
      );
    }
    console.warn(
      `extract-receipt: ${tier.name} draft not verified ` +
        `(items=${receipt.items?.length ?? 0}, priced=${hasAmounts(receipt)}, ` +
        `computed=${fmt(math.computedTotal)}, printed=${fmt(math.printedTotal)}, ` +
        `residual=${fmt(math.residual)})`,
    );
  }

  if (lastReceipt === null) {
    // Not "we had bad luck reading a photo" — the account itself can't reach Anthropic at all, and
    // will fail identically for every user until a human fixes the key/credit. Route it through the
    // same `configured: false` shape as the "key not set" case above so the client's existing
    // ScanOutcome.Unavailable handling picks it up (no retry button, straight to manual entry), and
    // page the owner since this is invisible otherwise.
    if (terminalFailure) {
      await alertOpsOnce(
        "extract_receipt_unavailable",
        `Evenly: receipt scanning is down (extract-receipt, ${terminalFailure.status}: ${terminalFailure.message}). Check ANTHROPIC_API_KEY / credit balance.`,
      );
      return finish("unavailable", { configured: false, reason: terminalFailure.message }, 200);
    }
    return finish(breakerTripped ? "breaker_open" : "failed", { error: lastError ?? "model did not return structured output" }, 502);
  }

  // A draft with item names but no prices anywhere is not a partial success, it is a failed read wearing
  // a success costume. Handing it back fills the editor with real dishes at 0.00 and a 0.00 total, which
  // reads as "the scan worked, the bill was free" rather than "try again". Route it to the same
  // couldn't-read state as an unreadable photo so the user gets the retry and manual-entry actions.
  if (!hasAmounts(lastReceipt)) {
    console.warn("extract-receipt: best draft had no priced lines; reporting as unreadable");
    return finish(
      breakerTripped ? "breaker_open" : "invalid_draft",
      { configured: true, noReceipt: true },
      200,
      { rawDraft: lastRawDraft, residual: lastMath?.residual, verified: false },
    );
  }

  // Both tiers ran (or the breaker tripped) and neither produced a draft that reconciled. Return the best
  // available guess, from the most capable tier actually reached, flagged as unverified rather than
  // silently trusted. The client always lands on an editable draft (never a dead end); this just tells it
  // to show a "please check the amounts" notice instead of a quiet success.
  return finish(
    breakerTripped ? "breaker_open" : "invalid_draft",
    { configured: true, receipt: clientReceipt(lastReceipt, lastMath!), verified: false },
    200,
    { rawDraft: lastRawDraft, residual: lastMath!.residual, verified: false },
  );
});

/**
 * The transcription + our arithmetic -> the wire shape the app speaks. `summary` is an extraction detail
 * the app never sees; it is flattened here into the bill's five extras. Nothing in this function computes
 * anything the model was asked to compute — `math` arrived from billMath.ts.
 *
 * `other_charges_subunits` carries a printed charge that is none of the other four: a delivery fee, a
 * bottle deposit, a bag fee, a card surcharge. It briefly had no home and was folded into gratuity to keep
 * the bill's total honest, which meant the amount was right and the label on the user's screen was a lie.
 * It is a real column now, end to end, splitting proportionally like tax (domain/AGENTS.md).
 */
function clientReceipt(receipt: Receipt, math: BillMath) {
  return {
    currency: receipt.currency,
    items: receipt.items,
    tax_subunits: math.extras.tax_subunits,
    gratuity_subunits: math.extras.gratuity_subunits,
    tip_subunits: math.extras.tip_subunits,
    discount_subunits: math.extras.discount_subunits,
    other_charges_subunits: math.extras.other_subunits,
    detected_total_subunits: math.printedTotal,
  };
}

/** True if the org-wide daily escalation cap has been reached. Fails CLOSED (skips the pricey tier) on error. */
async function checkEscalationBreaker(): Promise<boolean> {
  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const dayAgo = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
  const { count, error } = await supabase
    .from("receipt_opus_escalations")
    .select("id", { count: "exact", head: true })
    .gt("created_at", dayAgo);
  if (error) return true; // can't verify we're under the cap, so don't spend on the priciest tier
  return (count ?? 0) >= ESCALATION_DAILY_CAP;
}

async function logEscalation(userId: string): Promise<void> {
  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  await supabase.from("receipt_opus_escalations").insert({ user_id: userId });
}

const OPS_ALERT_COOLDOWN_MS = 60 * 60 * 1000;

/**
 * Posts `message` to the ops Slack channel, at most once per `kind` per cooldown window — otherwise a
 * burst of identical failures (every scan, while the key stays broken) would spam the channel once per
 * scan. Inert until `SLACK_ALERT_WEBHOOK_URL` is set, same optional-config pattern as
 * `ANTHROPIC_API_KEY`. Best-effort throughout: a failed cooldown check or a failed post must never
 * change the response already decided for the caller.
 */
async function alertOpsOnce(kind: string, message: string): Promise<void> {
  const webhookUrl = Deno.env.get("SLACK_ALERT_WEBHOOK_URL");
  if (!webhookUrl) return;

  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const cooldownStart = new Date(Date.now() - OPS_ALERT_COOLDOWN_MS).toISOString();
  const { count, error } = await supabase
    .from("ops_alerts")
    .select("kind", { count: "exact", head: true })
    .eq("kind", kind)
    .gt("sent_at", cooldownStart);
  if (error) {
    console.error(`extract-receipt: ops_alerts cooldown check failed: ${error.message}`);
    return; // can't confirm we're outside the cooldown, so don't risk spamming
  }
  if ((count ?? 0) > 0) return;

  try {
    await fetch(webhookUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text: message }),
    });
  } catch (e) {
    console.error(`extract-receipt: Slack alert failed: ${(e as Error).message}`);
  }
  await supabase.from("ops_alerts").insert({ kind });
}

/**
 * One Claude-vision pass over the receipt pages with the given tier. Returns the `record_receipt` tool
 * input (the structured draft) plus whether the response was cut off by the token ceiling. Throws on a
 * non-OK Anthropic response so the caller can relay it as a 502.
 *
 * `thinking` is set EXPLICITLY on every request rather than left to the model's default. That default is
 * not uniform and not stable across models: on claude-sonnet-5 an omitted `thinking` runs adaptive
 * thinking at effort high, while on the Opus 4.x line the same omission runs no thinking at all. Since
 * `max_tokens` bounds thinking and tool output together, inheriting the default is how a 2048-token
 * budget silently became a thinking budget and truncated the receipt JSON.
 */
async function extractWithModel(
  apiKey: string,
  pages: ReceiptPart[],
  tier: (typeof TIERS)[number],
  verify?: VerifyTurn,
): Promise<Pass> {
  // The first turn is always the photo + the transcription prompt. A verify turn appends the model's own
  // tool_use and a tool_result carrying our arithmetic, so it re-reads the receipt with its previous
  // answer in front of it instead of starting cold — a cold re-read reproduces the same misreading.
  const messages: unknown[] = [
    { role: "user", content: [...pages.map(pageBlock), { type: "text", text: EXTRACT_PROMPT }] },
  ];
  if (verify && verify.toolUseId) {
    messages.push({
      role: "assistant",
      content: [{ type: "tool_use", id: verify.toolUseId, name: RECEIPT_TOOL.name, input: verify.toolInput }],
    });
    messages.push({
      role: "user",
      content: [{ type: "tool_result", tool_use_id: verify.toolUseId, content: verify.feedback }],
    });
  }

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": apiKey,
      "anthropic-version": "2023-06-01",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: tier.model,
      max_tokens: tier.maxTokens,
      thinking: tier.thinking,
      output_config: { effort: tier.effort },
      tools: [RECEIPT_TOOL],
      tool_choice: { type: "tool", name: "record_receipt" },
      messages,
    }),
  });

  if (!res.ok) {
    const bodyText = await res.text();
    throw new AnthropicCallError(`anthropic ${res.status}: ${bodyText}`, res.status, classifyAnthropicFailure(res.status, bodyText));
  }
  const body = await res.json();
  const toolUse = (body.content ?? []).find((b: { type: string }) => b.type === "tool_use");
  return {
    receipt: toolUse ? normalizeReceipt(toolUse.input as RawReceipt) : null,
    truncated: body.stop_reason === "max_tokens",
    usage: {
      inputTokens: body.usage?.input_tokens ?? 0,
      outputTokens: body.usage?.output_tokens ?? 0,
    },
    toolUseId: toolUse?.id ?? null,
    rawInput: toolUse?.input ?? null,
  };
}

/**
 * Logs a scan attempt that never reached the pre-call insert — today only the rate-limit rejection.
 * Written directly with `outcome` and `completed_at` already set, since there's no in-flight paid call
 * to complete later. Best-effort: a failure here must never change the response already decided above.
 */
async function logImmediateOutcome(
  client: ReturnType<typeof createClient>,
  userId: string,
  groupId: string | null,
  pageCount: number,
  outcome: string,
): Promise<void> {
  const { error } = await client.from("receipt_scan_log").insert({
    user_id: userId,
    group_id: groupId,
    page_count: pageCount,
    outcome,
    completed_at: new Date().toISOString(),
  });
  if (error) console.error(`extract-receipt: failed to log ${outcome}: ${error.message}`);
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function b64encode(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}
