// Model registry for the receipt OCR lab. Each entry is fully self-contained —
// adding a new model (a different Claude tier, or eventually a non-Anthropic
// provider) means adding one object here, nothing in server/index.js or the
// frontend needs to change.
//
// The prompt, tool schema, and printed-digits -> subunits normalization are IMPORTED from the edge
// function itself (`supabase/functions/extract-receipt/contract.ts`), not copied. They used to be copied,
// and by 2026-08-08 the copy had drifted three changes behind: no `is_receipt`, the pre-fix
// `*_subunits: integer` schema, a stale prompt, and `max_tokens: 2048` with no `thinking` field. A lab
// that mirrors a version of the function nobody runs is a stand-in for nothing. Import, never copy.

import { EXTRACT_PROMPT, RECEIPT_TOOL, normalizeReceipt }
  from "../../../supabase/functions/extract-receipt/contract.ts";
import { computeBill } from "../../../supabase/functions/extract-receipt/billMath.ts";

function pageBlock({ mimeType, base64 }) {
  const source = { type: "base64", media_type: mimeType, data: base64 };
  return mimeType === "application/pdf" ? { type: "document", source } : { type: "image", source };
}

/**
 * One Claude-vision call. Returns the transcription flattened to the subunit shape the app consumes, so
 * the frontend reads the same numbers the bill editor would be filled with, plus `math` (our arithmetic
 * over it) and `raw` (what the model literally emitted — the thing there was no record of when the
 * 2026-08-08 defect had to be diagnosed). `usage` carries raw token counts so the caller computes cost
 * using this provider's own pricing.
 *
 * The model does no arithmetic here either: `computeBill` is the same module the edge function uses.
 *
 * `thinking` and `max_tokens` are per-provider and passed through explicitly, mirroring the TIERS table in
 * index.ts: an omitted `thinking` is not a stable default across models, and max_tokens bounds thinking and
 * tool output together, which is how a 2048 budget once truncated the receipt JSON mid-object.
 */
async function callAnthropic({ apiKey, model, pages, maxTokens = 8192, thinking = { type: "disabled" }, effort = "low" }) {
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": apiKey,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model,
      max_tokens: maxTokens,
      thinking,
      output_config: { effort },
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
  if (!toolUse) {
    return { receipt: null, raw: null, math: null, truncated: body.stop_reason === "max_tokens", usage: body.usage ?? null };
  }
  const transcribed = normalizeReceipt(toolUse.input);
  const math = computeBill(transcribed);
  return {
    receipt: {
      currency: transcribed.currency,
      items: transcribed.items,
      summary: transcribed.summary,
      tax_subunits: math.extras.tax_subunits,
      gratuity_subunits: math.extras.gratuity_subunits + math.extras.other_subunits,
      tip_subunits: math.extras.tip_subunits,
      discount_subunits: math.extras.discount_subunits,
      detected_total_subunits: math.printedTotal,
    },
    raw: toolUse.input,
    math,
    truncated: body.stop_reason === "max_tokens",
    usage: body.usage ?? null,
  };
}

// Pricing per million tokens (input/output), USD. Kept next to the provider
// definition so cost math travels with the model it prices — update here when
// Anthropic's price sheet changes. Sonnet 5 intro pricing applies through
// 2026-08-31; swap to $3/$15 after that date.
//
// `primary` and `escalation` are the two tiers the deployed function actually runs (TIERS in index.ts);
// the bare model entries are for comparing tiers the function does not use.
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
    id: "primary",
    label: "Sonnet 5 — deployed primary tier",
    model: "claude-sonnet-5",
    priceInPerMTok: 3.0,
    priceOutPerMTok: 15.0,
    call: (args) => callAnthropic({
      ...args, model: "claude-sonnet-5", maxTokens: 8192, thinking: { type: "disabled" }, effort: "low",
    }),
  },
  {
    id: "sonnet-5-thinking",
    label: "Sonnet 5 — adaptive thinking, medium",
    model: "claude-sonnet-5",
    priceInPerMTok: 3.0,
    priceOutPerMTok: 15.0,
    call: (args) => callAnthropic({
      ...args, model: "claude-sonnet-5", maxTokens: 16000, thinking: { type: "adaptive" }, effort: "medium",
    }),
  },
  {
    id: "escalation",
    label: "Opus 5 — deployed escalation tier",
    model: "claude-opus-5",
    priceInPerMTok: 5.0,
    priceOutPerMTok: 25.0,
    call: (args) => callAnthropic({
      ...args, model: "claude-opus-5", maxTokens: 16000, thinking: { type: "adaptive" }, effort: "medium",
    }),
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
