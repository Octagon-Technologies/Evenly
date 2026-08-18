// All arithmetic for a scanned receipt. Every sum, difference, and comparison the extraction pipeline
// performs happens here, in integer minor units, from figures the model only ever transcribed.
//
// The model does not do this work and is never asked to. Two production defects on 2026-08-08 came from
// asking it to (see contract.ts): it rescaled dollars to cents by truncation, and it was given a field
// whose value was defined as the sum of its own other fields, which made the validation gate a check of
// the model against itself. A gate that reads only the model's output validates nothing.
//
// The flow this module exists for:
//
//   1. The model transcribes items and the totals block.               (contract.ts)
//   2. `computeBill` adds it up and compares against the PRINTED grand total.   (here)
//   3. If they disagree, the model is shown this arithmetic and asked to re-read the receipt and correct
//      its READINGS — never to adjust a number so the sum comes out right.      (index.ts, verify turn)
//
// Step 2 is unconditional and deterministic. Exposing it as a tool the model may choose to call would put
// "does the math happen at all" in the model's hands; running it in code every time does not.

import type { Receipt, SummaryKind, SummaryLine } from "./contract.ts";

/** Reconciliation slack. A real POS total can sit a cent off its own lines through per-line tax rounding. */
export const RECONCILE_TOLERANCE_SUBUNITS = 2;

/** The extras the app's bill model can hold, summed from the transcribed totals block. */
export interface BillExtras {
  tax_subunits: number;
  gratuity_subunits: number;
  tip_subunits: number;
  discount_subunits: number;
  /** Printed charges we have no field for (a delivery fee, a bottle deposit, a card surcharge). */
  other_subunits: number;
}

export interface BillMath {
  extras: BillExtras;
  /** Σ every item line. */
  itemSum: number;
  /** itemSum + tax + gratuity + tip + other − discount. What the receipt should come to. */
  computedTotal: number;
  /** The grand total as PRINTED, transcribed by the model. 0 when the receipt printed none we could read. */
  printedTotal: number;
  /** computedTotal − printedTotal. 0 is a clean read; anything else is a misread we have not explained. */
  residual: number;
  /** Every printed subtotal, kept for the verify turn's diagnostics — never used to validate. */
  printedSubtotals: number[];
  reconciles: boolean;
}

function sumKind(summary: SummaryLine[], kind: SummaryKind): number {
  return summary.reduce((s, l) => (l.kind === kind ? s + l.amount_subunits : s), 0);
}

/**
 * The grand total, from the totals block. A receipt often prints several `total`-ish lines (an order
 * total, then a card charge, then "Amount Due $0.00"); `payment` lines are classified apart precisely so
 * a $0.00 amount-due line can never be mistaken for the bill being free. Among `total` lines we take the
 * LARGEST: a partial-payment or change-due line transcribed as a total would otherwise silently shrink
 * the bill, and a bill is never smaller than the amount charged for it.
 */
function printedTotalOf(summary: SummaryLine[]): number {
  return summary.reduce((max, l) => (l.kind === "total" && l.amount_subunits > max ? l.amount_subunits : max), 0);
}

/** Adds up a transcribed receipt and checks it against the printed grand total. Pure integer arithmetic. */
export function computeBill(receipt: Receipt): BillMath {
  const summary = receipt.summary ?? [];
  const extras: BillExtras = {
    tax_subunits: sumKind(summary, "tax"),
    gratuity_subunits: sumKind(summary, "gratuity"),
    tip_subunits: sumKind(summary, "tip"),
    discount_subunits: sumKind(summary, "discount"),
    other_subunits: sumKind(summary, "other"),
  };

  const itemSum = (receipt.items ?? []).reduce((s, it) => s + (it.line_total_subunits ?? 0), 0);
  const computedTotal = itemSum
    + extras.tax_subunits
    + extras.gratuity_subunits
    + extras.tip_subunits
    + extras.other_subunits
    - extras.discount_subunits;

  const printedTotal = printedTotalOf(summary);
  const residual = computedTotal - printedTotal;

  return {
    extras,
    itemSum,
    computedTotal,
    printedTotal,
    residual,
    printedSubtotals: summary.filter((l) => l.kind === "subtotal").map((l) => l.amount_subunits),
    // A printed total of 0 is not a reconciliation, it is a receipt whose total we failed to read. Without
    // this the 0 = 0 case would report every blank scan as perfectly reconciled.
    reconciles: printedTotal > 0 && Math.abs(residual) <= RECONCILE_TOLERANCE_SUBUNITS,
  };
}

/** "2274" -> "22.74", for the arithmetic we show the model and for log lines. Integer arithmetic only. */
export function fmt(subunits: number): string {
  const sign = subunits < 0 ? "-" : "";
  const abs = Math.abs(subunits);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, "0")}`;
}

/**
 * The verify turn's message: our arithmetic, written out line by line, and the gap it leaves.
 *
 * Deliberately shows the model the WORKING and not just the verdict — it has to be able to see which
 * reading to go back to, and a bare "these don't match" gives it nowhere to look. Equally deliberately, it
 * never suggests a number: the failure mode of a model told "you are 9.50 short" is to put 9.50 somewhere,
 * which is the fabrication that a self-consistent-but-wrong draft is made of.
 */
export function describeDiscrepancy(receipt: Receipt, math: BillMath): string {
  const items = (receipt.items ?? [])
    .map((it) => `  ${fmt(it.line_total_subunits)}  ${it.label}${it.quantity > 1 ? ` (qty ${it.quantity})` : ""}`)
    .join("\n");

  const extraLine = (label: string, v: number, sign = "+") => (v ? `  ${sign} ${fmt(v)}  ${label}\n` : "");

  const printedTotalNote = math.printedTotal > 0
    ? `You transcribed the printed grand total as ${fmt(math.printedTotal)}.`
    : "You did not record a line of kind \"total\", so there is nothing to check this against.";

  return (
    "I added up what you transcribed. I did the arithmetic, not you — these are your figures, summed:\n\n" +
    `${items}\n` +
    `  ${"-".repeat(28)}\n` +
    `  ${fmt(math.itemSum)}  sum of the ${(receipt.items ?? []).length} item lines\n` +
    extraLine("tax", math.extras.tax_subunits) +
    extraLine("service charge / gratuity", math.extras.gratuity_subunits) +
    extraLine("tip", math.extras.tip_subunits) +
    extraLine("other charges", math.extras.other_subunits) +
    extraLine("discount", math.extras.discount_subunits, "-") +
    `  ${"-".repeat(28)}\n` +
    `  ${fmt(math.computedTotal)}  what your figures come to\n\n` +
    `${printedTotalNote} That is off by ${fmt(Math.abs(math.residual))}.\n\n` +
    "Something was read wrong. Look at the receipt again and call record_receipt with a corrected " +
    "transcription.\n\n" +
    "Correct only what the image actually says. Check the digits you were least sure of, look for a " +
    "priced line you skipped or recorded twice, a total-block line you left out, and a quantity or a " +
    "decimal point in the wrong place. Do NOT invent a line, do NOT change an amount you can clearly " +
    "read, and do NOT put the difference somewhere to make the sum come out — a receipt that balances " +
    "because you adjusted it is worse than one that does not balance, because nothing downstream can " +
    "tell that you did. If you look again and every figure really is what is printed, transcribe it " +
    "again unchanged; the receipt itself may not add up, and that is a legitimate answer."
  );
}
