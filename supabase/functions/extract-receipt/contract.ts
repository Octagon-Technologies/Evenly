// The record_receipt contract: the tool schema, the prompt, and the printed-digits -> subunits
// normalization. Split out of index.ts (2026-08-08) so `tools/receipt-ocr-lab` imports the SAME schema and
// prompt the deployed function uses instead of keeping a copy. Its README always claimed the two mirrored
// each other; they had in fact drifted three changes apart, which makes the lab's results a stand-in for
// nothing. Keep this module free of `Deno.*` and of network calls so a plain Node process can import it.
//
// ────────────────────────────────────────────────────────────────────────────────────────────────
// THE MODEL DOES NO ARITHMETIC. NONE. This is the governing constraint of this file.
// ────────────────────────────────────────────────────────────────────────────────────────────────
//
// It transcribes what is printed. Every sum, difference, conversion, and cross-check happens in
// `billMath.ts` in integer arithmetic. The schema below contains no field whose value must be *computed*,
// and the prompt contains no instruction to add, check, or balance anything.
//
// Two production defects, one receipt (2026-08-08), both caused by asking a model to compute:
//
//   1. `line_total_subunits: integer` made the model do the dollars->cents rescale. It did it by keeping
//      the printed number's integer part and appending two zeros ($22.74 -> 2200), so a $103.50 bill
//      reached the editor as $94.00 with every cent discarded.
//
//   2. `subtotal_subunits` was described as "must equal the sum of every line_total_subunits above. If no
//      subtotal is printed, sum the lines yourself." That is a field whose value is an arithmetic result,
//      and it had two consequences. It made the reconciliation gate self-referential (the model summed its
//      own misreadings and matched itself, so a $9.50-short bill passed as `verified: true`). And on a
//      receipt that prints BOTH a pre-discount subtotal and a post-discount one, that description names
//      two different numbers — an unresolvable instruction. The model anchored on the pre-discount region,
//      and `Tax 6.32`, the one charge printed BELOW the other subtotal line, was dropped to 0.
//
// So there is no `subtotal` field here any more, and no per-kind amount slots at all. The model lists the
// lines in the totals block **as printed, in order, with their printed labels**, and tags each with what
// it is. Transcribing "Tax" and "6.32" off a line is reading. Deciding that Tax belongs in the tax bucket
// is classification. Neither is arithmetic. Which of two printed subtotals is "the" subtotal is a question
// we no longer ask, because we no longer need an answer: `billMath.ts` reconciles against the grand total.

import { parseAmountSubunits } from "./amount.ts";

export interface ReceiptItem {
  label: string;
  quantity: number;
  line_total_subunits: number;
}

/** One line from the receipt's totals block, in subunits, keeping the label as printed for diagnostics. */
export interface SummaryLine {
  label: string;
  kind: SummaryKind;
  amount_subunits: number;
}

export type SummaryKind =
  | "subtotal"
  | "tax"
  | "gratuity"
  | "tip"
  | "discount"
  | "total"
  | "payment"
  | "other";

/** A transcribed receipt, converted to subunits. Nothing here is summed — see `billMath.ts`. */
export interface Receipt {
  currency: string;
  items: ReceiptItem[];
  summary: SummaryLine[];
  is_receipt: boolean;
}

/** What the model emits: every amount is the digits AS PRINTED ("22.74"), never a converted integer. */
export interface RawReceiptItem {
  label: string;
  quantity: number;
  line_total: string;
}

export interface RawSummaryLine {
  label: string;
  kind: string;
  amount: string;
}

export interface RawReceipt {
  currency: string;
  items: RawReceiptItem[];
  summary: RawSummaryLine[];
  is_receipt: boolean;
}

const AMOUNT_DESC =
  " Copy the digits EXACTLY as printed, including the cents, e.g. \"22.74\". Do not round, truncate, " +
  "rescale, or compute this number in any way. Digits and one decimal point only — no currency symbol, " +
  "no sign, no thousands separators.";

const AMOUNT_FIELD = { type: "string", pattern: "^[0-9]+([.][0-9]{1,3})?$" } as const;

export const RECEIPT_TOOL = {
  name: "record_receipt",
  description:
    "Transcribe the contents of a receipt. This records what is printed; it performs no calculation.",
  input_schema: {
    type: "object",
    properties: {
      is_receipt: {
        type: "boolean",
        description:
          "true only if this image is genuinely an itemized purchase receipt or invoice with line items " +
          "and a total. false for anything else — a photo of a person, a screenshot of an unrelated app, " +
          "a ride-share trip summary, a random photo, or any document with no itemized purchase charges. " +
          "If false, leave items and summary empty — do not invent a plausible-looking receipt.",
      },
      currency: { type: "string", description: "ISO 4217 code, e.g. USD, EUR, KES. Best guess from symbols/locale." },
      items: {
        type: "array",
        description:
          "Every ordered line, in the order printed. A line reading '3 Strawberry Mint Sparkler  $38.22' " +
          "is one entry: quantity 3, line_total \"38.22\". Do not divide 38.22 by 3 — record the printed " +
          "figure. If a line has no price printed beside it, it is a modifier of the line above (a topping, " +
          "'NO Cheddar Cheese', 'Flour Tortilla'), not an item of its own; leave it out. A line whose price " +
          "is NEGATIVE is never an item, however it is positioned: it is a discount or comp and belongs in " +
          "`summary`.",
        items: {
          type: "object",
          properties: {
            label: { type: "string", description: "The item name as printed." },
            quantity: { type: "integer", minimum: 1, description: "The count printed on the line; 1 if none is printed." },
            line_total: { ...AMOUNT_FIELD, description: "The price printed beside this line." + AMOUNT_DESC },
          },
          required: ["label", "quantity", "line_total"],
        },
      },
      summary: {
        type: "array",
        description:
          "EVERY line in the receipt's totals block, in the order printed, top to bottom — subtotals, " +
          "discounts, service fees, tax, tip, the grand total, payment lines, all of them. Record each one " +
          "even when several look redundant or contradictory: a receipt may print two different subtotals " +
          "(one before a discount and one after), and both belong here. Do not omit a line because you " +
          "think it is already accounted for elsewhere, and do not add a line that is not printed.\n\n" +
          "Also put here any discount or comp printed with a NEGATIVE amount up among the item lines (a " +
          "'Free App (100%) -14.24' sitting under the dish it comps). Record its amount as a positive " +
          "magnitude with kind 'discount'.\n\n" +
          "RECORD EACH AMOUNT ONCE. Receipts routinely print the same discount twice: once beside the line " +
          "it applies to, and again as a 'Discount Total' below. That is ONE discount that was taken once, " +
          "so it gets ONE entry here, not two. The same goes for a service charge or tax restated in a " +
          "summary block. This is the one place two printed lines collapse to one entry; everything else " +
          "printed gets its own.",
        items: {
          type: "object",
          properties: {
            label: { type: "string", description: "The line's label exactly as printed, e.g. \"Pre-discount Subtotal\", \"Srv Fee (4.00%)\"." },
            kind: {
              type: "string",
              enum: ["subtotal", "tax", "gratuity", "tip", "discount", "total", "payment", "other"],
              description:
                "What this line is. 'subtotal' any running subtotal. 'tax' sales tax/VAT. 'gratuity' an " +
                "automatic service charge or service fee added by the venue. 'tip' a tip the customer " +
                "wrote in. 'discount' any discount, comp, or promotion (record its amount as a positive " +
                "magnitude, dropping the minus sign). 'total' the final amount charged for the order. " +
                "'payment' a line recording how it was paid or what is left to pay (a card charge, " +
                "'Amount Due', change given). 'other' anything else carrying an amount.",
            },
            amount: { ...AMOUNT_FIELD, description: "The amount printed on this line." + AMOUNT_DESC },
          },
          required: ["label", "kind", "amount"],
        },
      },
    },
    required: ["is_receipt", "currency", "items", "summary"],
  },
} as const;

export const EXTRACT_PROMPT =
  "Transcribe this receipt (which may span several pages/images) as ONE bill using the record_receipt " +
  "tool.\n\n" +
  "YOUR ONLY JOB IS TO READ WHAT IS PRINTED. You are not being asked to add anything up, work anything " +
  "out, or check that the receipt makes sense. Do not sum the items. Do not verify that the parts reach " +
  "the total. Do not adjust, reconcile, or balance any figure, and never change a number you can see to " +
  "make it agree with another number you can see. If two printed figures look inconsistent, record both " +
  "exactly as printed and move on. The arithmetic is done afterwards, in code, from what you record — a " +
  "figure you quietly corrected is a figure that can never be checked.\n\n" +
  "Copy every amount digit for digit, cents included: a line printed $22.74 is \"22.74\", never \"22\", " +
  "\"22.00\", or \"2274\". Dropping the part after the decimal point is the worst thing you can do here; " +
  "these amounts become what real people owe each other.\n\n" +
  "Record every priced line in `items`, and every line of the totals block in `summary` — including ones " +
  "that look redundant. Never record a line at 0 when a price is printed beside it: if a price is hard to " +
  "read, give your best reading of the digits rather than falling back to 0.\n\n" +
  "First decide is_receipt: true only for an itemized purchase receipt or invoice, false for anything " +
  "else (a person, an unrelated app screenshot, a ride-share trip summary, a random photo). If unsure of " +
  "the currency, infer it from the symbols.";

const KINDS: ReadonlySet<string> = new Set([
  "subtotal", "tax", "gratuity", "tip", "discount", "total", "payment", "other",
]);

/** The model's printed-digit transcription -> subunits. Converts; never sums. Summing is `billMath.ts`. */
export function normalizeReceipt(raw: RawReceipt): Receipt {
  return {
    currency: typeof raw?.currency === "string" && raw.currency ? raw.currency : "USD",
    items: (raw?.items ?? []).map((it) => ({
      label: String(it?.label ?? ""),
      quantity: Number.isFinite(it?.quantity) && it.quantity > 0 ? Math.floor(it.quantity) : 1,
      line_total_subunits: parseAmountSubunits(it?.line_total),
    })),
    summary: (raw?.summary ?? []).map((s) => ({
      label: String(s?.label ?? ""),
      // An unrecognised kind becomes "other" rather than being dropped: an amount we can't classify still
      // has to be visible to the reconciler as an unexplained charge.
      kind: (KINDS.has(s?.kind) ? s.kind : "other") as SummaryKind,
      amount_subunits: parseAmountSubunits(s?.amount),
    })),
    is_receipt: raw?.is_receipt !== false,
  };
}
