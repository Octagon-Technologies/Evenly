/**
 * Largest-remainder allocator — a hand-port of `code/shared/src/commonMain/kotlin/app/splitevenly/
 * core/Allocator.kt`. Read that file before touching this one; it is the authority and this is the
 * copy (WEB_CLAIM_SPEC.md §2.10, §7).
 *
 * Sorts internally — callers pass weights in any order and get the same result. Tiebreak on equal
 * fractional remainders: user id ascending (UUIDv7 is time-ordered, so smallest value = longest-
 * tenured member).
 */

/** One weight in a split: the person, and how much of the total they pull. */
export type Weight = readonly [userId: string, weight: number];

/**
 * Split [totalSubunits] across [weights], penny-exact: the parts always sum back to the total.
 *
 * The multiply-then-divide runs in BigInt because Kotlin does it in `Long`: `total × weight` on a
 * big bill with percentage-scale weights overflows a double's 2^53 integer range long before it
 * overflows a Long, and a silently-rounded intermediate here is a wrong dollar amount downstream.
 */
export function allocate(totalSubunits: number, weights: readonly Weight[]): Record<string, number> {
  if (weights.length === 0) throw new Error('allocate: weights must not be empty');
  const totalWeight = weights.reduce((sum, [, w]) => sum + w, 0);
  if (totalWeight <= 0) throw new Error('allocate: total weight must be > 0');

  const total = BigInt(totalSubunits);
  const divisor = BigInt(totalWeight);
  // BigInt `/` and `%` truncate toward zero, exactly as Kotlin's Long `/` and `.rem` do.
  const entries = weights.map(([id, weight]) => {
    const product = total * BigInt(weight);
    return { id, base: Number(product / divisor), frac: product % divisor };
  });
  const remainder = totalSubunits - entries.reduce((sum, e) => sum + e.base, 0);

  const sorted = [...entries].sort((a, b) => {
    if (a.frac !== b.frac) return a.frac > b.frac ? -1 : 1; // frac descending
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0; // then id ascending — the D-28 tiebreak
  });

  const out: Record<string, number> = {};
  sorted.forEach((e, i) => {
    out[e.id] = e.base + (i < remainder ? 1 : 0);
  });
  return out;
}
