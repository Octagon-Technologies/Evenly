// ShareCost receipt OCR ("Split the bill"). Takes a receipt photo and returns a structured, EDITABLE
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
// Three-tier cascade with a validation gate (Haiku -> Sonnet -> Opus, cheapest first): each tier's draft
// is checked against isValidDraft() before being trusted; a tier that fails escalates to the next, and
// Opus (the priciest tier) is additionally gated by an org-wide daily circuit breaker so one bad batch of
// photos — or a deliberate abuse attempt — can't run up an unbounded shared bill. A model can also report
// `is_receipt: false` at any tier, which short-circuits the WHOLE cascade immediately (no further paid
// calls) — this is what stops a selfie, a ride-share summary, or any other non-receipt photo from ever
// reaching Sonnet or Opus, since "is this a receipt at all" is a cheap, reliable classification even when
// full itemized extraction is hard.

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
}

interface ReceiptItem {
  label: string;
  quantity: number;
  line_total_subunits: number;
}

interface Receipt {
  currency: string;
  items: ReceiptItem[];
  tax_subunits: number;
  gratuity_subunits: number;
  tip_subunits: number;
  discount_subunits: number;
  detected_total_subunits: number;
  is_receipt: boolean;
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
      tax_subunits: { type: "integer", minimum: 0, description: "Sales tax/VAT total in minor units; 0 if none." },
      gratuity_subunits: { type: "integer", minimum: 0, description: "Auto service charge / gratuity in minor units; 0 if none. NOT a tip line the customer writes in." },
      tip_subunits: { type: "integer", minimum: 0, description: "Printed tip in minor units; 0 if blank (tips are usually added by hand later)." },
      discount_subunits: { type: "integer", minimum: 0, description: "Any discount/comp as a positive magnitude in minor units; 0 if none." },
      detected_total_subunits: { type: "integer", minimum: 0, description: "The grand total printed on the receipt, in minor units, for reconciliation." },
    },
    required: ["is_receipt", "currency", "items", "tax_subunits", "gratuity_subunits", "tip_subunits", "discount_subunits", "detected_total_subunits"],
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
  "items. If unsure of the currency, infer from symbols.";

// Three tiers, cheapest first. Each tier's draft is checked by isValidDraft() before being trusted —
// a failing tier escalates to the next. Opus is additionally gated by the org-wide circuit breaker
// below (see checkOpusBreaker) since it's the priciest tier in the cascade.
const TIERS = [
  { name: "haiku", model: "claude-haiku-4-5-20251001" },
  { name: "sonnet", model: "claude-sonnet-5" },
  { name: "opus", model: "claude-opus-4-8" },
] as const;

// Org-wide cap on Opus calls in a rolling 24h window, independent of the per-user receipt_scan_log
// limit below. Env-overridable; 200/day is a starting default, not a measured number — tune once real
// usage exists.
const OPUS_DAILY_CAP = Number(Deno.env.get("OPUS_DAILY_CAP") ?? "200");

// A label a model reaches for when it gives up and lumps the whole bill into one generic line instead
// of actually itemizing — the exact "receipt: $47.32" degenerate failure. Substring match (not exact),
// since a lumped label is often padded ("Receipt total", "Purchase items").
const GENERIC_LABELS = ["receipt", "total", "bill", "purchase", "items", "misc", "n/a"];

/**
 * Catches the "gave up and lumped everything into one line" failure mode, which reconciles PERFECTLY
 * (one item priced at exactly the total) and so is invisible to reconciles() alone — this needs its own
 * check. Independent of reconciliation: a receipt can reconcile and still be degenerate.
 */
function isDegenerate(receipt: Receipt, pageCount: number): boolean {
  const items = receipt.items ?? [];
  if (items.length === 0) return true;
  if (items.some((it) => GENERIC_LABELS.some((g) => String(it.label ?? "").toLowerCase().includes(g)))) return true;
  const noExtras = !receipt.tax_subunits && !receipt.gratuity_subunits && !receipt.tip_subunits && !receipt.discount_subunits;
  if (items.length === 1 && noExtras && items[0].line_total_subunits === receipt.detected_total_subunits) return true;
  // A multi-page bill that collapses to one line is almost certainly a lumping failure, not a real
  // one-item receipt that happened to span several photos.
  if (pageCount > 1 && items.length <= 1) return true;
  return false;
}

/**
 * A legitimate receipt's printed total is, by construction, the sum of its line items plus extras — the
 * store's own POS generated it that way. So this checks the model's own arithmetic against itself: if
 * items+extras don't sum to the model's own detected total, it misread something. `detected > 0` closes
 * the 0=0 loophole a fully blank/unreadable scan would otherwise slip through as "reconciled".
 */
function reconciles(receipt: Receipt): boolean {
  const items = receipt.items ?? [];
  const itemSum = items.reduce((s, it) => s + (it.line_total_subunits ?? 0), 0);
  const computed = itemSum + (receipt.tax_subunits ?? 0) + (receipt.gratuity_subunits ?? 0) + (receipt.tip_subunits ?? 0) - (receipt.discount_subunits ?? 0);
  const detected = receipt.detected_total_subunits ?? 0;
  if (detected <= 0) return false;
  return Math.abs(computed - detected) <= 1;
}

function isValidDraft(receipt: Receipt, pageCount: number): boolean {
  return !isDegenerate(receipt, pageCount) && reconciles(receipt);
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

  const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString();
  const { count, error: countError } = await callerClient
    .from("receipt_scan_log")
    .select("id", { count: "exact", head: true })
    .eq("user_id", callerId)
    .gt("created_at", oneHourAgo);
  // Fail CLOSED: if we can't read the window we don't know if the caller is over the limit, so refuse
  // rather than let an error open the floodgates on a paid endpoint.
  if (countError) return json({ error: "rate check unavailable — try again shortly." }, 503);
  if ((count ?? 0) >= 20) return json({ error: "Too many scans — try again in a bit." }, 429);

  // Log the scan BEFORE the paid call so the slot is consumed immediately — two concurrent requests can't
  // both slip under the limit (the classic check-then-act race), and a crash mid-call still counts. A
  // failing insert also fails closed (we won't spend a call we can't rate-limit). One log entry covers the
  // whole scan even when it takes up to three model passes (the cascade below) — a hard photo isn't three
  // slots.
  const { error: logError } = await callerClient.from("receipt_scan_log").insert({ user_id: callerId });
  if (logError) return json({ error: "rate log unavailable — try again shortly." }, 503);

  // The cascade: try each tier in order, cheapest first. A tier that reports is_receipt:false ends the
  // WHOLE cascade immediately — no further paid calls, since a non-receipt photo doesn't get more
  // "receipt-y" on a stronger model. A tier whose draft doesn't pass isValidDraft() escalates to the
  // next. The priciest tier (Opus) is additionally gated by the org-wide circuit breaker.
  let lastReceipt: Receipt | null = null;
  for (const tier of TIERS) {
    if (tier.name === "opus") {
      const tripped = await checkOpusBreaker();
      if (tripped) break; // stop the cascade; fall through to the best-effort return below
    }

    let receipt: Receipt | null;
    try {
      receipt = await extractWithModel(apiKey, pages, tier.model);
    } catch (e) {
      return json({ error: (e as Error).message ?? "extraction failed" }, 502);
    }
    if (receipt === null) continue; // no tool_use block at all — try the next tier

    if (receipt.is_receipt === false) {
      return json({ configured: true, noReceipt: true }, 200);
    }

    if (tier.name === "opus") {
      // Log the escalation AFTER a successful call, since the breaker check above already gated
      // whether we're allowed to spend here — this just records that we did, for the next check.
      await logOpusEscalation(callerId);
    }

    lastReceipt = receipt;
    if (isValidDraft(receipt, pages.length)) {
      return json({ configured: true, receipt, verified: true }, 200);
    }
  }

  if (lastReceipt === null) return json({ error: "model did not return structured output" }, 502);

  // Every tier ran (or the Opus breaker tripped) and none produced a draft that validated. Return the
  // best available guess — the most capable tier actually reached — flagged as unverified rather than
  // silently trusted. The client always lands on an editable draft (never a dead end); this just tells
  // it to show a "please check the amounts" notice instead of a quiet success.
  return json({ configured: true, receipt: lastReceipt, verified: false }, 200);
});

/** True if the org-wide Opus daily cap has been reached — fails CLOSED (skip Opus) if the check itself errors. */
async function checkOpusBreaker(): Promise<boolean> {
  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const dayAgo = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
  const { count, error } = await supabase
    .from("receipt_opus_escalations")
    .select("id", { count: "exact", head: true })
    .gt("created_at", dayAgo);
  if (error) return true; // can't verify we're under the cap — don't spend on the priciest tier
  return (count ?? 0) >= OPUS_DAILY_CAP;
}

async function logOpusEscalation(userId: string): Promise<void> {
  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  await supabase.from("receipt_opus_escalations").insert({ user_id: userId });
}

/**
 * One Claude-vision pass over the receipt pages with the given model. Returns the `record_receipt` tool
 * input (the structured draft), or `null` if the model produced no tool_use block. Throws on a non-OK
 * Anthropic response so the caller can relay it as a 502.
 */
async function extractWithModel(apiKey: string, pages: ReceiptPart[], model: string): Promise<Receipt | null> {
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": apiKey,
      "anthropic-version": "2023-06-01",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model,
      max_tokens: 2048,
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
  return toolUse ? (toolUse.input as Receipt) : null;
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function b64encode(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}
