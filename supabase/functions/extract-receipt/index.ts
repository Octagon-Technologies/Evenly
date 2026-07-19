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

// A Claude content block for one receipt page — a PDF renders as a document, everything else as an image.
function pageBlock(part: ReceiptPart) {
  const source = { type: "base64", media_type: part.mediaType, data: part.data };
  return part.mediaType === "application/pdf"
    ? { type: "document", source }
    : { type: "image", source };
}

// The shape we force Claude to emit. Amounts are integer MINOR units (cents) to match the app's
// subunit convention — no floating-point money crosses the wire.
const RECEIPT_TOOL = {
  name: "record_receipt",
  description: "Record the structured contents of a restaurant or shop receipt.",
  input_schema: {
    type: "object",
    properties: {
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
    required: ["currency", "items", "tax_subunits", "gratuity_subunits", "tip_subunits", "discount_subunits", "detected_total_subunits"],
  },
} as const;

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
  // whole scan even when it takes two model passes (see the fallback below) — a hard photo isn't two slots.
  const { error: logError } = await callerClient.from("receipt_scan_log").insert({ user_id: callerId });
  if (logError) return json({ error: "rate log unavailable — try again shortly." }, 503);

  // Two-tier OCR: the cheap model reads most receipts, but on a genuinely hard photo (dim, glare, curled
  // thermal paper, shot at an angle) Haiku returns ZERO items and the scan "fails" even though the bill is
  // perfectly legible to a stronger model. So we escalate: Haiku first, and only if it extracts nothing do
  // we spend the pricier Sonnet on a second pass of the same pages. Normal receipts stay cheap; only the
  // hard ones pay Sonnet rates — the failure mode that produced "Couldn't read it" on a clear receipt.
  let receipt: unknown;
  try {
    receipt = await extractWithModel(apiKey, pages, "claude-haiku-4-5-20251001");
    if (!hasItems(receipt)) {
      receipt = await extractWithModel(apiKey, pages, "claude-sonnet-5");
    }
  } catch (e) {
    return json({ error: (e as Error).message ?? "extraction failed" }, 502);
  }
  if (receipt === null) return json({ error: "model did not return structured output" }, 502);

  // (The scan was already logged against the rate-limit window before the calls above — see P1 #11.)
  // Pass the structured draft straight back; the client maps it to the editable bill form and computes
  // its own reconciliation (Σ items + extras vs detected_total) so a misread is flagged, never trusted.
  return json({ configured: true, receipt }, 200);
});

/** True when the model returned at least one line item — the signal that Haiku actually read the receipt. */
function hasItems(receipt: unknown): boolean {
  const items = (receipt as { items?: unknown } | null)?.items;
  return Array.isArray(items) && items.length > 0;
}

/**
 * One Claude-vision pass over the receipt pages with the given model. Returns the `record_receipt` tool
 * input (the structured draft), or `null` if the model produced no tool_use block. Throws on a non-OK
 * Anthropic response so the caller can relay it as a 502.
 */
async function extractWithModel(apiKey: string, pages: ReceiptPart[], model: string): Promise<unknown> {
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
          content: [
            ...pages.map(pageBlock),
            {
              type: "text",
              text:
                "Read this receipt (which may span several pages/images) and record it as ONE bill with " +
                "the record_receipt tool. Itemise every ordered line with its quantity and its LINE TOTAL " +
                "price in minor units (cents) — the total charged for that line as printed, not a computed " +
                "per-unit price. Separate sales tax, " +
                "an auto gratuity/service charge, any printed tip, and any discount. If a value isn't on " +
                "the receipt, use 0. Don't invent items. If unsure of the currency, infer from symbols.",
            },
          ],
        },
      ],
    }),
  });

  if (!res.ok) throw new Error(`anthropic ${res.status}: ${await res.text()}`);
  const body = await res.json();
  const toolUse = (body.content ?? []).find((b: { type: string }) => b.type === "tool_use");
  return toolUse ? toolUse.input : null;
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function b64encode(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}
