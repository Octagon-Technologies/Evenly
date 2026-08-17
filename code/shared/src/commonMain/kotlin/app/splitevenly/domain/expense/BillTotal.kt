package app.splitevenly.domain.expense

/**
 * What a bill adds up to, and the two ways that sum stops being a legal expense.
 *
 * This arithmetic used to live twice in `data/repository/` — once in `BillRepositoryImpl` (behind its
 * `validate`) and once copied into `BillPendingEdits`, whose copy called no validator at all. So the
 * create and edit paths enforced "a bill's total is positive" and the *undo* path stored whatever fell
 * out (finding R5). One home in the layer that owns money is what makes that impossible rather than
 * merely fixed: a rule change here cannot land on one caller and miss the other.
 *
 * `otherChargesSubunits` is the standing evidence for that — `domain/AGENTS.md` records it as added on
 * 2026-08-08, and it had to be hand-added to both copies.
 */

/**
 * Σ of the entered line totals: the subtotal tax, gratuity and the discount ride *proportional to*.
 *
 * Deliberately not `quantity × per-unit`. `expense_items.line_total_subunits` is the entered truth and
 * per-unit is a rounded view of it, so rebuilding a $10.00 line over 3 units hands back $9.99.
 */
fun billItemSubtotalSubunits(lineTotalsSubunits: List<Long>): Long = lineTotalsSubunits.sum()

/** Bill total = Σ(line totals) + tax + gratuity + other charges + tip − discount. */
fun billTotalSubunits(
    lineTotalsSubunits: List<Long>,
    extras: BillExtrasInput,
): Long =
    billItemSubtotalSubunits(lineTotalsSubunits) +
        extras.taxSubunits + extras.gratuitySubunits + extras.otherChargesSubunits +
        extras.tipSubunits - extras.discountSubunits

/**
 * Why these lines plus these extras are not a bill anyone can be billed for. Both report on the
 * discount, the only extra that can subtract.
 */
enum class BillTotalProblem {
    /**
     * The discount is bigger than the items it rides proportional to. The total can still be positive on
     * a tip, so [NonPositiveTotal] does not catch it, and the claimant's `items − discount` goes below
     * zero while the parts still sum to the bill — no conservation check sees it, and `ShareDao`'s
     * `remaining > 0` filters then erase the credit (finding D1).
     */
    DiscountExceedsItems,

    /**
     * The whole expense is zero or below, violating the always-positive entity invariant and then hiding
     * behind those same `> 0` filters, so the bill vanishes from the balances instead of failing.
     */
    NonPositiveTotal,
}

/**
 * The bill-total rule, or null when the numbers are legal.
 *
 * `splitBill` clamps the same two rules for rows that never came through a validator (sync, the web
 * guest path); this is the form callers use to *tell a person* instead of silently normalising.
 */
fun billTotalProblem(
    lineTotalsSubunits: List<Long>,
    extras: BillExtrasInput,
): BillTotalProblem? =
    when {
        extras.discountSubunits > billItemSubtotalSubunits(lineTotalsSubunits) -> BillTotalProblem.DiscountExceedsItems
        billTotalSubunits(lineTotalsSubunits, extras) <= 0L -> BillTotalProblem.NonPositiveTotal
        else -> null
    }
