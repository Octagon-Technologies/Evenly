/**
 * Printed money text -> integer minor units. Its own module so `amount.test.ts` can exercise it without
 * importing `index.ts` (which calls `Deno.serve` and reaches for env secrets at import time).
 *
 * This exists because of the 2026-08-08 defect: the record_receipt tool schema asked the model for
 * `line_total_subunits: integer`, which made the MODEL responsible for turning "$22.74" into 2274. It did
 * that by keeping the integer part and appending two zeros — 2200, 3800, 1200, 300 — so a $103.50 bill
 * reached the editor as $94.00 with every cent on the receipt discarded. Reading digits off a photo is
 * transcription; rescaling a decimal is arithmetic. The model now only transcribes, and this converts.
 */

/**
 * "22.74" -> 2274, exactly, using integer arithmetic on the digit groups. Never `Number(x) * 100`: that
 * is float arithmetic on money, and `19.49 * 100` is 1948.9999999999998, which truncates to 1948 — the
 * same off-by-a-cent class of bug this whole change exists to remove.
 *
 * Deliberately tolerant of what a model emits around the edges of the schema's `pattern` (a stray "$", a
 * comma, a minus on a discount, a bare number instead of a string), because the alternative to parsing it
 * is dropping a real amount to 0. Anything genuinely unreadable is 0, which the caller's gates treat as a
 * failed read.
 *
 * Fixed 2 decimal places for every currency, matching the app's subunit convention app-wide (a JPY amount
 * is stored x100 there too). Do not make this currency-aware here alone.
 */
export function parseAmountSubunits(raw: unknown): number {
  if (typeof raw === "number") {
    // The model ignored the string type. It still means major units as printed, so scale it the exact way.
    return Number.isFinite(raw) ? parseAmountSubunits(raw.toFixed(3)) : 0;
  }
  if (typeof raw !== "string") return 0;

  // Keep digits and separators only: strips currency symbols, spaces, signs, and stray letters ("USD").
  const cleaned = raw.replace(/[^0-9.,]/g, "");
  if (!cleaned) return 0;

  // The LAST separator is the decimal point, with two exceptions for a group that can't be a fraction:
  // 4+ trailing digits, and the bare-comma thousands form "1,234" (a comma is never the decimal point in
  // a 3-decimal currency, so a lone comma before exactly three digits and no dot is grouping). A lone DOT
  // before three digits stays a decimal — "22.749" is a 3-decimal price, and the schema forbids thousands
  // separators anyway, so reading "1.234" as 1.23 is the direction to be wrong in: an amount off by a
  // factor of 1000 is caught by the reconcile gate, one off by a cent is not.
  const lastSep = Math.max(cleaned.lastIndexOf("."), cleaned.lastIndexOf(","));
  const frac = lastSep < 0 ? "" : cleaned.slice(lastSep + 1).replace(/[^0-9]/g, "");
  const groupedThousands = frac.length > 3
    || (frac.length === 3 && cleaned[lastSep] === "," && !cleaned.includes("."));
  const hasDecimal = frac.length > 0 && !groupedThousands;

  const intPart = (hasDecimal ? cleaned.slice(0, lastSep) : cleaned).replace(/[^0-9]/g, "");
  const major = intPart === "" ? 0 : Number(intPart);
  // A third decimal place rounds into the cent rather than being dropped — dropping is the exact failure
  // this function exists to prevent.
  const minor = hasDecimal ? Math.round(Number(frac.padEnd(3, "0").slice(0, 3)) / 10) : 0;
  if (!Number.isSafeInteger(major) || !Number.isSafeInteger(minor)) return 0;
  return major * 100 + minor;
}
