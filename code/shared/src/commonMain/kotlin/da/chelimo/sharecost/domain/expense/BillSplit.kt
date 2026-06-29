package da.chelimo.sharecost.domain.expense

import da.chelimo.sharecost.allocate
import da.chelimo.sharecost.core.id.UserId

/**
 * "Split the bill" math engine (itemized expenses). Pure and DB-free so it unit-tests on both JVM and
 * Native. Given the bill's items, who claimed what, and the bill-level extras, it derives each
 * participant's owed total — penny-exact, with `Σ shares == Σ (claimed units × price) + extras` always.
 *
 * Item cost is exact: a claimed unit costs exactly its `unitPrice`, so claiming 2 of 4 plates owes
 * 2 × price and the 2 unclaimed plates stay unassigned (the Finish flow assigns leftovers before a bill
 * is treated as settled). Over-claiming (more units than ordered) simply costs more — it's surfaced in
 * the UI, never silently capped. The existing largest-remainder [allocate] is used for the *extras*:
 * tax/gratuity spread proportionally to what each person ordered, tip evenly (or proportionally).
 */

/** A line on the bill. Cost is per claimed unit, so the engine needs the unit price, not the line total. */
data class BillItem(
    val itemId: String,
    val unitPriceSubunits: Long,
)

/** One person's claim on an item. [weight] is the claimed unit count (2 of 4 plates → 2). */
data class BillClaim(
    val itemId: String,
    val userId: UserId,
    val weight: Long,
)

/**
 * Bill-level surcharges. Tax and gratuity ride proportionally to what each person ordered; tip splits
 * evenly by default (toggleable to proportional); a [discountSubunits] (a positive magnitude) is taken
 * off proportionally. All are integer subunits.
 */
data class BillExtras(
    val taxSubunits: Long = 0L,
    val gratuitySubunits: Long = 0L,
    val tipSubunits: Long = 0L,
    val tipSplitMode: TipSplitMode = TipSplitMode.EVEN,
    val discountSubunits: Long = 0L,
)

/**
 * Each claimant's item subtotal: `Σ over their claims (unit price × claimed units)`. Exact — no rounding
 * at the item level. Items nobody claimed contribute nothing; the Finish flow assigns leftovers before
 * shares are derived.
 */
fun itemSubtotals(items: List<BillItem>, claims: List<BillClaim>): Map<UserId, Long> {
    val priceByItem = items.associate { it.itemId to it.unitPriceSubunits }
    val acc = LinkedHashMap<UserId, Long>()
    for (claim in claims) {
        val price = priceByItem[claim.itemId] ?: continue
        if (claim.weight <= 0L) continue
        acc[claim.userId] = (acc[claim.userId] ?: 0L) + price * claim.weight
    }
    return acc
}

/**
 * Final owed total per participant: item subtotal + proportional tax & gratuity − proportional
 * discount + tip (even or proportional). The sum equals `Σ subtotals + tax + gratuity − discount + tip`
 * exactly, because each extra layer is an exact [allocate]. Returns empty when nothing is claimed.
 */
fun splitBill(items: List<BillItem>, claims: List<BillClaim>, extras: BillExtras): Map<UserId, Long> {
    val subtotals = itemSubtotals(items, claims).toList()
    if (subtotals.isEmpty()) return emptyMap()

    val proportionalPool = extras.taxSubunits + extras.gratuitySubunits
    val proportionalShares = byShareOrEven(proportionalPool, subtotals)
    val discountShares = byShareOrEven(extras.discountSubunits, subtotals)
    val tipShares = when (extras.tipSplitMode) {
        TipSplitMode.PROPORTIONAL -> byShareOrEven(extras.tipSubunits, subtotals)
        TipSplitMode.EVEN -> if (extras.tipSubunits == 0L) emptyMap()
            else allocate(extras.tipSubunits, subtotals.map { it.first to 1L })
    }

    return subtotals.associate { (id, subtotal) ->
        id to subtotal + (proportionalShares[id] ?: 0L) - (discountShares[id] ?: 0L) + (tipShares[id] ?: 0L)
    }
}

/**
 * Allocate [amount] across [subtotals] by their relative size, falling back to an even split when every
 * subtotal is 0 (so a proportional extra on an all-free bill can't divide-by-zero). Zero amount → no rows.
 */
private fun byShareOrEven(amount: Long, subtotals: List<Pair<UserId, Long>>): Map<UserId, Long> {
    if (amount == 0L) return emptyMap()
    return if (subtotals.sumOf { it.second } > 0L) allocate(amount, subtotals)
    else allocate(amount, subtotals.map { it.first to 1L })
}
