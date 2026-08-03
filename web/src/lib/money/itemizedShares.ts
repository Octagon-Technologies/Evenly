/**
 * Hand-port of `code/shared/src/commonMain/kotlin/app/splitevenly/domain/expense/ItemizedAllocator.kt`.
 * Kotlin is the authority (WEB_CLAIM_SPEC.md §7).
 *
 * Older, simpler surface than [splitBill]: given per-person subtotals, ride tax and tip on top. It is
 * kept in step with Kotlin because the spec names it, not because the claim list calls it — the web
 * surface should reach for `splitBill`, which does its own extras.
 */

import { allocate, type Weight } from './allocate.ts';
import type { TipSplitMode } from './billSplit.ts';

export function itemizedShares(
  subtotals: readonly Weight[],
  taxSubunits: number,
  tipSubunits: number,
  tipSplitMode: TipSplitMode,
): Record<string, number> {
  const taxShares: Record<string, number> = taxSubunits === 0 ? {} : allocate(taxSubunits, subtotals);

  const tipShares: Record<string, number> =
    tipSubunits === 0
      ? {}
      : tipSplitMode === 'PROPORTIONAL'
        ? allocate(tipSubunits, subtotals)
        : allocate(tipSubunits, subtotals.map(([id]) => [id, 1] as const));

  const out: Record<string, number> = {};
  for (const [id, subtotal] of subtotals) {
    out[id] = subtotal + (taxShares[id] ?? 0) + (tipShares[id] ?? 0);
  }
  return out;
}
