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
// Auth: send the project's anon or service-role key as the Bearer (the client uses its anon key, the
// same as every other authenticated call).

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
            unit_price_subunits: { type: "integer", minimum: 0, description: "Price of ONE unit in minor units (cents)." },
          },
          required: ["label", "quantity", "unit_price_subunits"],
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

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": apiKey,
      "anthropic-version": "2023-06-01",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: "claude-haiku-4-5-20251001",
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
                "the record_receipt tool. Itemise every ordered " +
                "line with its quantity and PER-UNIT price in minor units (cents). Separate sales tax, " +
                "an auto gratuity/service charge, any printed tip, and any discount. If a value isn't on " +
                "the receipt, use 0. Don't invent items. If unsure of the currency, infer from symbols.",
            },
          ],
        },
      ],
    }),
  });

  if (!res.ok) return json({ error: `anthropic ${res.status}: ${await res.text()}` }, 502);
  const body = await res.json();
  const toolUse = (body.content ?? []).find((b: { type: string }) => b.type === "tool_use");
  if (!toolUse) return json({ error: "model did not return structured output" }, 502);

  // Pass the structured draft straight back; the client maps it to the editable bill form and computes
  // its own reconciliation (Σ items + extras vs detected_total) so a misread is flagged, never trusted.
  return json({ configured: true, receipt: toolUse.input }, 200);
});

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function b64encode(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}
