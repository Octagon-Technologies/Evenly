package da.chelimo.sharecost.domain.expense

import da.chelimo.sharecost.allocate
import da.chelimo.sharecost.core.id.UserId

/**
 * "Split the bill" math engine (itemized expenses). Pure and DB-free so it unit-tests on both JVM and
 * Native. Given a bill's items and how people claimed them, it derives each participant's owed total —
 * penny-exact — and, alongside, a per-item **reconciliation** that is the *only* thing the UI ever
 * surfaces: is each line fully claimed, still unclaimed, or over-claimed?
 *
 * A line's **line total** is the source of truth (it's what receipts print), and it's split penny-exact
 * across the line's units via the largest-remainder [allocate] — so a $10 line over 3 units never leaks a
 * cent even though $10 ÷ 3 isn't whole. Every unit is then owned by a set of people (the primitive):
 *  - **Individual** claims own whole units — a claimed unit costs its exact per-unit slice (claim 2 of 4
 *    plates → pay 2 of the 4 slices). Sum of individual units per line is checked against the quantity.
 *  - **Shared** membership is an auto-union set: whoever's in splits the line's *remaining* (un-individually-
 *    claimed) units evenly. Because it's a set, overlapping "shared with" declarations merge for free
 *    (Bob adds Mary, Steve adds Bob → {Bob, Mary, Steve}, ÷3) with nothing to confirm or resolve.
 *
 * Bill extras ride on top: tax + gratuity proportional to what each person ordered, tip even by default
 * (toggleable), discount negative-proportional — all penny-exact via the existing largest-remainder
 * [allocate].
 */

/** A line on the bill: [quantity] units costing [lineTotalSubunits] in total (the truth, as printed). */
data class BillItem(
    val itemId: String,
    val lineTotalSubunits: Long,
    val quantity: Int,
)

/** One person's whole-unit claim on a line ("I had 2 of these"). */
data class IndividualClaim(
    val itemId: String,
    val userId: UserId,
    val units: Int,
)

/** One person's membership in a line's shared split. The set of these per line is the sharer group. */
data class SharedMember(
    val itemId: String,
    val userId: UserId,
)

/** Bill-level surcharges. Tip defaults to an even split (toggleable); tax & gratuity ride proportionally. */
data class BillExtras(
    val taxSubunits: Long = 0L,
    val gratuitySubunits: Long = 0L,
    val tipSubunits: Long = 0L,
    val tipSplitMode: TipSplitMode = TipSplitMode.EVEN,
    val discountSubunits: Long = 0L,
)

/** Per-line claim status — the single signal the UI surfaces (amber for unclaimed / over-claimed). */
enum class ItemStatus { RESOLVED, UNCLAIMED, OVERCLAIMED }

/** How one line reconciled: how many units are individually claimed, whether anyone shares it, and the verdict. */
data class ItemReconcile(
    val itemId: String,
    val quantity: Int,
    val individualUnits: Int,
    val hasSharers: Boolean,
    val status: ItemStatus,
)

/** The engine's output: what each participant owes, plus the per-line reconciliation. */
data class BillResult(
    val owedByUser: Map<UserId, Long>,
    val items: List<ItemReconcile>,
) {
    /** A bill is resolved once every line is fully and correctly claimed (no unclaimed units, no over-claim). */
    val fullyResolved: Boolean get() = items.all { it.status == ItemStatus.RESOLVED }

    /** Lines that still need someone — drives the "N dishes still need someone" nudge. */
    val unclaimedCount: Int get() = items.count { it.status == ItemStatus.UNCLAIMED }

    /** Lines claimed more times than ordered — the only thing worth an amber "check this". */
    val overClaimedCount: Int get() = items.count { it.status == ItemStatus.OVERCLAIMED }
}

/**
 * Derive each participant's owed total + the per-line reconciliation.
 *
 * A line's remaining (un-individually-claimed) units are absorbed by its shared set, split evenly and
 * penny-exact. A line is UNCLAIMED if units are left over and nobody shares it, OVERCLAIMED if more units
 * were individually claimed than ordered, else RESOLVED. Extras spread over whatever's been claimed.
 */
fun splitBill(
    items: List<BillItem>,
    individualClaims: List<IndividualClaim>,
    sharedMembers: List<SharedMember>,
    extras: BillExtras,
): BillResult {
    val indivByItem = individualClaims.groupBy { it.itemId }
    val sharersByItem = sharedMembers.groupBy { it.itemId }.mapValues { (_, ms) -> ms.map { it.userId }.distinct() }

    val subtotal = LinkedHashMap<UserId, Long>()
    val reconcile = ArrayList<ItemReconcile>(items.size)

    for (item in items) {
        val unitsByUser = indivByItem[item.itemId].orEmpty()
            .groupBy { it.userId }
            .mapValues { (_, cs) -> cs.sumOf { it.units } }
            .filter { it.value > 0 }
        val totalIndiv = unitsByUser.values.sum()
        val sharers = sharersByItem[item.itemId].orEmpty()
        val quantity = item.quantity
        val remainder = quantity - totalIndiv

        if (totalIndiv > quantity) {
            // Over-claim: more units claimed than ordered. Cost every claimed unit at the derived per-unit
            // price so the tab exceeds the line total — surfaced as OVERCLAIMED, never silently capped.
            val perUnit = perUnitSubunits(item.lineTotalSubunits, quantity)
            for ((user, units) in unitsByUser) {
                subtotal[user] = (subtotal[user] ?: 0L) + units.toLong() * perUnit
            }
        } else {
            // Split the line total penny-exact across its units, then hand each unit to its claimant.
            val unitCosts = splitEven(item.lineTotalSubunits, quantity)
            var cursor = 0
            for ((user, units) in unitsByUser.entries.sortedBy { it.key.value }) {
                var owed = 0L
                repeat(units) { owed += unitCosts[cursor++] }
                subtotal[user] = (subtotal[user] ?: 0L) + owed
            }
            // The shared set absorbs the leftover units, split evenly (penny-exact via allocate). With no
            // sharers the leftover units simply aren't billed (the line reconciles as UNCLAIMED).
            if (remainder > 0 && sharers.isNotEmpty()) {
                var pool = 0L
                while (cursor < quantity) pool += unitCosts[cursor++]
                for ((user, amount) in allocate(pool, sharers.map { it to 1L })) {
                    subtotal[user] = (subtotal[user] ?: 0L) + amount
                }
            }
        }

        val status = when {
            totalIndiv > quantity -> ItemStatus.OVERCLAIMED
            totalIndiv < quantity && sharers.isEmpty() -> ItemStatus.UNCLAIMED
            else -> ItemStatus.RESOLVED
        }
        reconcile += ItemReconcile(item.itemId, quantity, totalIndiv, sharers.isNotEmpty(), status)
    }

    val subtotals = subtotal.toList()
    if (subtotals.isEmpty()) return BillResult(emptyMap(), reconcile)

    val proportionalShares = byShareOrEven(extras.taxSubunits + extras.gratuitySubunits, subtotals)
    val discountShares = byShareOrEven(extras.discountSubunits, subtotals)
    val tipShares = when (extras.tipSplitMode) {
        TipSplitMode.PROPORTIONAL -> byShareOrEven(extras.tipSubunits, subtotals)
        TipSplitMode.EVEN -> if (extras.tipSubunits == 0L) emptyMap()
            else allocate(extras.tipSubunits, subtotals.map { it.first to 1L })
    }

    val owed = subtotals.associate { (id, sub) ->
        id to sub + (proportionalShares[id] ?: 0L) - (discountShares[id] ?: 0L) + (tipShares[id] ?: 0L)
    }
    return BillResult(owed, reconcile)
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

/**
 * The **derived** per-unit price shown next to the "each" field — the line total shared evenly and
 * rounded (half-up). Display only; the exact, penny-preserving split happens in [splitBill] via
 * [splitEven], so `perUnitSubunits × quantity` may differ from the true line total by a cent or two.
 */
fun perUnitSubunits(lineTotalSubunits: Long, quantity: Int): Long =
    if (quantity <= 0) lineTotalSubunits else (lineTotalSubunits + quantity / 2) / quantity

/**
 * Split [total] into [parts] penny-exact slices summing to [total]: each slice is `total / parts`, and
 * the first `total % parts` slices get one extra subunit (largest-remainder, over equal weights).
 */
private fun splitEven(total: Long, parts: Int): List<Long> {
    if (parts <= 0) return emptyList()
    val base = total / parts
    val extra = (total - base * parts).toInt()
    return List(parts) { base + if (it < extra) 1L else 0L }
}
