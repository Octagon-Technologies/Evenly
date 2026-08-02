// Model registry for the receipt OCR lab. Each entry is fully self-contained —
// adding a new model (a different Claude tier, or eventually a non-Anthropic
// provider) means adding one object here, nothing in server/index.js or the
// frontend needs to change.
//
// The prompt + tool schema mirror supabase/functions/extract-receipt/index.ts
// exactly, so results here are a valid stand-in for what the deployed edge
// function would produce on the same photo.

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
};

const EXTRACT_PROMPT =
  "Read this receipt (which may span several pages/images) and record it as ONE bill with " +
  "the record_receipt tool. Itemise every ordered line with its quantity and its LINE TOTAL " +
  "price in minor units (cents) — the total charged for that line as printed, not a computed " +
  "per-unit price. Separate sales tax, " +
  "an auto gratuity/service charge, any printed tip, and any discount. If a value isn't on " +
  "the receipt, use 0. Don't invent items. If unsure of the currency, infer from symbols.";

function pageBlock({ mimeType, base64 }) {
  const source = { type: "base64", media_type: mimeType, data: base64 };
  return mimeType === "application/pdf" ? { type: "document", source } : { type: "image", source };
}

/**
 * One Claude-vision call. Returns { receipt, usage } — usage carries raw token
 * counts so the caller computes cost using this provider's own pricing.
 */
async function callAnthropic({ apiKey, model, pages }) {
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": apiKey,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
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

  if (!res.ok) {
    const text = await res.text();
    throw new Error(`anthropic ${res.status}: ${text}`);
  }
  const body = await res.json();
  const toolUse = (body.content ?? []).find((b) => b.type === "tool_use");
  return { receipt: toolUse ? toolUse.input : null, usage: body.usage ?? null };
}

// Pricing per million tokens (input/output), USD. Kept next to the provider
// definition so cost math travels with the model it prices — update here when
// Anthropic's price sheet changes. Sonnet 5 intro pricing applies through
// 2026-08-31; swap to $3/$15 after that date.
const PROVIDERS = [
  {
    id: "haiku-4.5",
    label: "Claude Haiku 4.5",
    model: "claude-haiku-4-5",
    priceInPerMTok: 1.0,
    priceOutPerMTok: 5.0,
    call: (args) => callAnthropic({ ...args, model: "claude-haiku-4-5" }),
  },
  {
    id: "sonnet-5",
    label: "Claude Sonnet 5",
    model: "claude-sonnet-5",
    priceInPerMTok: 2.0,
    priceOutPerMTok: 10.0,
    call: (args) => callAnthropic({ ...args, model: "claude-sonnet-5" }),
  },
];

export function getProvider(id) {
  const p = PROVIDERS.find((x) => x.id === id);
  if (!p) throw new Error(`unknown provider "${id}"`);
  return p;
}

export function costUsd(provider, usage) {
  if (!usage) return null;
  const inTok = usage.input_tokens ?? 0;
  const outTok = usage.output_tokens ?? 0;
  return (inTok / 1e6) * provider.priceInPerMTok + (outTok / 1e6) * provider.priceOutPerMTok;
}

export { PROVIDERS };
