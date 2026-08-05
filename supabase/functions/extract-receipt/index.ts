// Evenly receipt OCR ("Split the bill"). Takes a receipt photo and returns a structured, EDITABLE
// draft of the bill — line items + tax/gratuity/tip/discount — by asking Claude vision for forced
// structured output. The client always lands the user on the editable item list to confirm before any
// money is computed; this function only produces a first draft, never a source of truth.
//
// Inert until configured: set the `ANTHROPIC_API_KEY` secret. Without it the function returns 200 +
// { configured:false } so the client can fall back to manual entry without surfacing an error.
//
//   POST { "files": [{ "data": "<base64>", "mediaType": "image/jpeg" | "application/pdf" }, ...] }  // multi-page
//     or { "imageBase64": "...", "mediaType"?: "image/jpeg" }     // single image inline (legacy)
//     or { "storagePath": "receipts/abc.jpg" }                    // already in the receipts bucket
//
// `files` may mix several photos and/or PDFs — they're read together as ONE bill, so a multi-page
// receipt yields a single item list. PDFs go in as document blocks; images as image blocks.
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

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

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

interface ReceiptItem {
  label: string;
  quantity: number;
  line_total_subunits: number;
}

interface Receipt {
  currency: string;
  items: ReceiptItem[];
  subtotal_subunits: number;
  tax_subunits: number;
  gratuity_subunits: number;
  tip_subunits: number;
  discount_subunits: number;
  detected_total_subunits: number;
  is_receipt: boolean;
}

/** One model pass. `truncated` means the response stopped on max_tokens, so `receipt` may be a partial object. */
interface Pass {
  receipt: Receipt | null;
  truncated: boolean;
  usage: { inputTokens: number; outputTokens: number };
}

// A Claude content block for one receipt page — a PDF renders as a document, everything else as an image.
function pageBlock(part: ReceiptPart) {
  const source = { type: "base64", media_type: part.mediaType, data: part.data };
  return part.mediaType === "application/pdf"
    ? { type: "document", source }
    : { type: "image", source };
}

// The shape we force Claude to emit. Amounts are integer MINOR units (cents) to match the app's
// subunit convention — no floating-point money crosses the wire. `is_receipt` is the model's escape
// hatch: tool_choice FORCES this tool to be called even on a photo that isn't a receipt at all, so
// without an explicit field to say "this isn't one" the model would have no way to say so — it would
// just have to invent a plausible-looking but fake structure to comply with the schema.
const RECEIPT_TOOL = {
  name: "record_receipt",
  description: "Record the structured contents of a restaurant or shop receipt.",
  input_schema: {
    type: "object",
    properties: {
      is_receipt: {
        type: "boolean",
        description:
          "true only if this image is genuinely an itemized purchase receipt or invoice with line items " +
          "and a total. false for anything else — a photo of a person, a screenshot of an unrelated app, " +
          "a ride-share trip summary, a random photo, or any document with no itemized purchase charges. " +
          "If false, leave items empty and every amount at 0 — do not invent a plausible-looking receipt.",
      },
      currency: { type: "string", description: "ISO 4217 code, e.g. USD, EUR, KES. Best guess from symbols/locale." },
      items: {
        type: "array",
        description: "Every ordered line. Split a '2 Pizza' line into quantity 2 at the per-unit price.",
        items: {
          type: "object",
          properties: {
            label: { type: "string" },
            quantity: { type: "integer", minimum: 1 },
            line_total_subunits: { type: "integer", minimum: 0, description: "The TOTAL price printed for this line (all units combined) in minor units (cents) — not a per-unit price." },
          },
          required: ["label", "quantity", "line_total_subunits"],
        },
      },
      subtotal_subunits: { type: "integer", minimum: 0, description: "The subtotal printed BEFORE tax/gratuity/tip/discount, in minor units. This must equal the sum of every line_total_subunits above. If no subtotal is printed, sum the lines yourself." },
      tax_subunits: { type: "integer", minimum: 0, description: "Sales tax/VAT total in minor units; 0 if none." },
      gratuity_subunits: { type: "integer", minimum: 0, description: "Auto service charge / gratuity in minor units; 0 if none. NOT a tip line the customer writes in." },
      tip_subunits: { type: "integer", minimum: 0, description: "Printed tip in minor units; 0 if blank (tips are usually added by hand later)." },
      discount_subunits: { type: "integer", minimum: 0, description: "Any discount/comp as a positive magnitude in minor units; 0 if none." },
      detected_total_subunits: { type: "integer", minimum: 0, description: "The grand total printed on the receipt, in minor units, for reconciliation." },
    },
    required: ["is_receipt", "currency", "items", "subtotal_subunits", "tax_subunits", "gratuity_subunits", "tip_subunits", "discount_subunits", "detected_total_subunits"],
  },
} as const;

const EXTRACT_PROMPT =
  "Read this receipt (which may span several pages/images) and record it as ONE bill with " +
  "the record_receipt tool. First decide is_receipt: only true for an itemized purchase receipt " +
  "or invoice with line items and a total — false for anything else (a person, an unrelated app " +
  "screenshot, a ride-share trip summary, a random photo). If true, itemise every ordered line " +
  "with its quantity and its LINE TOTAL price in minor units (cents) — the total charged for that " +
  "line as printed, not a computed per-unit price. Separate sales tax, an auto gratuity/service " +
  "charge, any printed tip, and any discount. If a value isn't on the receipt, use 0. Don't invent " +
  "items. If unsure of the currency, infer from symbols.\n\n" +
  "Every line you record must carry its printed price. Never record a line at 0 when a price is " +
  "printed next to it. If a price is hard to read, give your best reading of the digits rather than " +
  "falling back to 0. 0 is only correct for a genuinely free or comped line.\n\n" +
  "Before you record, check your arithmetic against the receipt: the line totals must sum to " +
  "subtotal_subunits, and subtotal + tax + gratuity + tip - discount must equal " +
  "detected_total_subunits (the grand total printed on the receipt). If they don't match, re-read " +
  "the receipt and correct the figures you misread. Report what is actually printed; do not fudge a " +
  "number to force the arithmetic to balance.";

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

// Reconciliation slack, in minor units. A real POS total can sit a cent off its own lines through
// per-line tax rounding, so an exact match is too strict a bar for "the model read this correctly".
const RECONCILE_TOLERANCE_SUBUNITS = 2;

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
 * (one item priced at exactly the total) and so is invisible to reconciles() alone — this needs its own
 * check. Independent of reconciliation: a receipt can reconcile and still be degenerate.
 */
function isDegenerate(receipt: Receipt, pageCount: number): boolean {
  const items = receipt.items ?? [];
  if (items.length === 0) return true;
  if (items.some((it) => GENERIC_LABELS.has(normalizeLabel(it.label)))) return true;
  const noExtras = !receipt.tax_subunits && !receipt.gratuity_subunits && !receipt.tip_subunits && !receipt.discount_subunits;
  if (items.length === 1 && noExtras && items[0].line_total_subunits === receipt.detected_total_subunits) return true;
  // A multi-page bill that collapses to one line is almost certainly a lumping failure, not a real
  // one-item receipt that happened to span several photos.
  if (pageCount > 1 && items.length <= 1) return true;
  return false;
}

/**
 * The failure this whole rewrite exists for: a draft that read the item NAMES but none of the prices, so
 * the user lands on a bill of real dishes at 0.00 each. It reconciles at neither end and looks like a
 * successful scan, and the old gate had no check for it at all — a truncated tool_use payload sailed
 * straight through to the client, whose `= 0` field defaults finished the job silently.
 *
 * One priced line is the bar, not all of them: a genuinely comped or free line at 0 is legitimate.
 */
function hasAmounts(receipt: Receipt): boolean {
  return (receipt.items ?? []).some((it) => (it.line_total_subunits ?? 0) > 0);
}

/** Sum of every line total. This is what the bill editor shows as "Subtotal", so it's the figure that matters. */
function itemSum(receipt: Receipt): number {
  return (receipt.items ?? []).reduce((s, it) => s + (it.line_total_subunits ?? 0), 0);
}

/** items + tax + gratuity + tip - discount. The editor computes exactly this as the bill's total. */
function computedTotal(receipt: Receipt): number {
  return itemSum(receipt)
    + (receipt.tax_subunits ?? 0)
    + (receipt.gratuity_subunits ?? 0)
    + (receipt.tip_subunits ?? 0)
    - (receipt.discount_subunits ?? 0);
}

/**
 * A legitimate receipt's printed total is, by construction, the sum of its line items plus extras (the
 * store's own POS generated it that way). So this checks the model's own arithmetic against itself: if
 * items+extras don't sum to the model's own detected total, it misread something. `detected > 0` closes
 * the 0=0 loophole a fully blank/unreadable scan would otherwise slip through as "reconciled".
 *
 * Two deliberate relaxations over the original exact-match version, both because it was rejecting good
 * drafts and paying for a whole extra vision pass to replace them with no better answer:
 *
 *   1. A couple of cents of slack (RECONCILE_TOLERANCE_SUBUNITS). Per-line tax rounding genuinely leaves
 *      a POS total a cent or two off the sum of its own lines.
 *   2. Matching against the model's own `subtotal_subunits` counts too. The single most common near-miss
 *      is a receipt carrying a charge we have no field for (a bottle deposit, a delivery fee, a card
 *      surcharge): the lines and the subtotal agree perfectly, and only the grand total is off by that
 *      one unmodelled charge. Escalating there buys nothing, because the stronger model reads the same
 *      receipt and lands in the same place. The user still sees every item and every amount, and the
 *      grand total is the one thing they can check at a glance.
 */
function reconciles(receipt: Receipt): boolean {
  const detected = receipt.detected_total_subunits ?? 0;
  const subtotal = receipt.subtotal_subunits ?? 0;
  const lines = itemSum(receipt);

  if (detected > 0 && Math.abs(computedTotal(receipt) - detected) <= RECONCILE_TOLERANCE_SUBUNITS) return true;
  if (subtotal > 0 && Math.abs(lines - subtotal) <= RECONCILE_TOLERANCE_SUBUNITS) return true;
  return false;
}

function isValidDraft(receipt: Receipt, pageCount: number): boolean {
  return hasAmounts(receipt) && !isDegenerate(receipt, pageCount) && reconciles(receipt);
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
    const slash = payload.storagePath.indexOf("/");
    const bucket = slash > 0 ? payload.storagePath.slice(0, slash) : "receipts";
    const path = slash > 0 ? payload.storagePath.slice(slash + 1) : payload.storagePath;
    const { data, error } = await supabase.storage.from(bucket).download(path);
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

  // Cross-session contract with the client's group-scoped analytics work: an optional groupId the
  // caller may attach to the scan for cost-per-group breakdowns. Absent is fine — stored as null.
  const groupId = payload.groupId ?? null;

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
  async function finish(outcome: string, body: unknown, status: number): Promise<Response> {
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
      })
      .eq("id", scanId);
    if (error) console.error(`extract-receipt: failed to complete scan log ${scanId} (${outcome}): ${error.message}`);
    return json({ ...(body as Record<string, unknown>), scanId }, status);
  }

  // Primary first, escalation only if it's needed. A tier that reports is_receipt:false ends everything
  // immediately (no further paid calls), since a non-receipt photo doesn't get more "receipt-y" on a
  // stronger model. A tier whose draft doesn't pass isValidDraft() escalates. The escalation tier is
  // additionally gated by the org-wide circuit breaker.
  let lastReceipt: Receipt | null = null;
  let lastError: string | null = null;
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
      continue;
    }

    // Every tier that actually ran cost money, including one that returned is_receipt:false or truncated —
    // account for it before any early return below.
    tiersUsed.push(tier.name);
    totalInputTokens += pass.usage.inputTokens;
    totalOutputTokens += pass.usage.outputTokens;
    totalCostMicros += tokenCostMicros(tier.model, pass.usage.inputTokens, pass.usage.outputTokens);

    const receipt = pass.receipt;
    if (receipt === null) continue; // no tool_use block at all, so try the next tier

    if (receipt.is_receipt === false) {
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

    lastReceipt = receipt;
    if (isValidDraft(receipt, pages.length)) {
      return finish("ok", { configured: true, receipt, verified: true }, 200);
    }
    console.warn(
      `extract-receipt: ${tier.name} draft not verified ` +
        `(items=${receipt.items?.length ?? 0}, priced=${hasAmounts(receipt)}, ` +
        `computed=${computedTotal(receipt)}, detected=${receipt.detected_total_subunits ?? 0})`,
    );
  }

  if (lastReceipt === null) {
    return finish(breakerTripped ? "breaker_open" : "failed", { error: lastError ?? "model did not return structured output" }, 502);
  }

  // A draft with item names but no prices anywhere is not a partial success, it is a failed read wearing
  // a success costume. Handing it back fills the editor with real dishes at 0.00 and a 0.00 total, which
  // reads as "the scan worked, the bill was free" rather than "try again". Route it to the same
  // couldn't-read state as an unreadable photo so the user gets the retry and manual-entry actions.
  if (!hasAmounts(lastReceipt)) {
    console.warn("extract-receipt: best draft had no priced lines; reporting as unreadable");
    return finish(breakerTripped ? "breaker_open" : "invalid_draft", { configured: true, noReceipt: true }, 200);
  }

  // Both tiers ran (or the breaker tripped) and neither produced a draft that reconciled. Return the best
  // available guess, from the most capable tier actually reached, flagged as unverified rather than
  // silently trusted. The client always lands on an editable draft (never a dead end); this just tells it
  // to show a "please check the amounts" notice instead of a quiet success.
  return finish(breakerTripped ? "breaker_open" : "invalid_draft", { configured: true, receipt: lastReceipt, verified: false }, 200);
});

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
): Promise<Pass> {
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
      messages: [
        {
          role: "user",
          content: [...pages.map(pageBlock), { type: "text", text: EXTRACT_PROMPT }],
        },
      ],
    }),
  });

  if (!res.ok) throw new Error(`anthropic ${res.status}: ${await res.text()}`);
  const body = await res.json();
  const toolUse = (body.content ?? []).find((b: { type: string }) => b.type === "tool_use");
  return {
    receipt: toolUse ? (toolUse.input as Receipt) : null,
    truncated: body.stop_reason === "max_tokens",
    usage: {
      inputTokens: body.usage?.input_tokens ?? 0,
      outputTokens: body.usage?.output_tokens ?? 0,
    },
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
