package app.splitevenly.domain.expense

import app.splitevenly.allocate
import app.splitevenly.core.id.UserId

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

/**
 * A shared *slice* of a line: [quantity] units split evenly among [members]. Unlike [SharedMember] (one
 * set per line, swallowing ALL leftover), MULTIPLE portions can coexist on a line — each its own
 * [portionId] — which is what "2 solo, 3 solo, 1 shared, 2 left" needs and a single set can't express. A
 * solo assignment is just an [IndividualClaim]; 2+ [members] here = an evenly-split shared slice.
 */
data class SharedPortion(
    val itemId: String,
    val portionId: String,
    val quantity: Int,
    val members: List<UserId>,
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

/**
 * One person's tab broken into its parts, so the claim screen can *explain* the number instead of a bare
 * total (a $5 juice quietly becoming $6.94 reads as a bug). [taxSubunits] folds gratuity in; the parts sum
 * to the tab: items + tax + tip − discount.
 */
data class TabBreakdown(
    val itemsSubunits: Long,
    val taxSubunits: Long,
    val tipSubunits: Long,
    val discountSubunits: Long,
) {
    val totalSubunits: Long get() = itemsSubunits + taxSubunits + tipSubunits - discountSubunits
}

/** The engine's output: what each participant owes (+ the breakdown behind it), plus the per-line reconciliation. */
data class BillResult(
    val owedByUser: Map<UserId, Long>,
    val items: List<ItemReconcile>,
    val breakdownByUser: Map<UserId, TabBreakdown> = emptyMap(),
    // Per-item food allocation: itemId -> (user -> subunits owed FOR THAT LINE). Recorded in lockstep with
    // the running subtotal, so the assign screen can show a penny-exact "who pays what" per line without
    // recomputing it (a separate recompute would drift from this engine by a cent or two). Excludes extras.
    val perItemByUser: Map<String, Map<UserId, Long>> = emptyMap(),
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
 * were individually claimed than ordered, else RESOLVED. Extras are billed proportional to each person's
 * share of the WHOLE bill (an even tip: per head across [participants]) — so the still-unclaimed portion
 * stays unbilled instead of piling onto the first claimant.
 */
fun splitBill(
    items: List<BillItem>,
    individualClaims: List<IndividualClaim>,
    sharedMembers: List<SharedMember>,
    extras: BillExtras,
    // The people the bill is *for* — used only to split an EVEN tip per head (stable regardless of who
    // has claimed yet). Empty falls back to the current claimants, preserving older callers/tests.
    participants: List<UserId> = emptyList(),
    // Explicit shared slices (each a quantity + member set). When an item has any, they define its sharing;
    // otherwise [sharedMembers] falls back to the legacy single "all-leftover" set. Additive — old callers
    // pass nothing and get identical results.
    sharedPortions: List<SharedPortion> = emptyList(),
): BillResult {
    val indivByItem = individualClaims.groupBy { it.itemId }
    val sharersByItem = sharedMembers.groupBy { it.itemId }.mapValues { (_, ms) -> ms.map { it.userId }.distinct() }
    val portionsByItem = sharedPortions.groupBy { it.itemId }

    val subtotal = LinkedHashMap<UserId, Long>()
    val reconcile = ArrayList<ItemReconcile>(items.size)
    // Per-item food allocation, recorded via [charge] alongside the running subtotal so per-line "who pays
    // what" is penny-exact and can never drift from the totals.
    val perItem = LinkedHashMap<String, Map<UserId, Long>>()

    for (item in items) {
        val quantity = item.quantity
        val explicitPortions = portionsByItem[item.itemId].orEmpty()
        val legacyMembers = sharersByItem[item.itemId].orEmpty()
        // A single unit can't be both solo-claimed AND split via the LEGACY set — the moment that set
        // exists the whole 1-unit line is shared and any individual claim on it is ignored (a $5 juice
        // split reads "$2.50 each", not "$0.00"). Explicit portions don't need this guard: the UI never
        // hands the same unit out twice, and over-assignment is surfaced as OVERCLAIMED below.
        val fullyShared = explicitPortions.isEmpty() && quantity == 1 && legacyMembers.isNotEmpty()
        val unitsByUser = if (fullyShared) emptyMap() else indivByItem[item.itemId].orEmpty()
            .groupBy { it.userId }
            .mapValues { (_, cs) -> cs.sumOf { it.units } }
            .filter { it.value > 0 }
        val totalIndiv = unitsByUser.values.sum()
        // Effective shared slices: explicit portions (each carries its own quantity) win; otherwise the
        // legacy set becomes ONE portion covering everything left after individual claims — which is
        // byte-for-byte the old "share absorbs the leftover" behaviour.
        val portions: List<LinePortion> = when {
            explicitPortions.isNotEmpty() -> explicitPortions.map { LinePortion(it.quantity, it.members.distinct()) }
            legacyMembers.isNotEmpty() -> listOf(LinePortion((quantity - totalIndiv).coerceAtLeast(0), legacyMembers))
            else -> emptyList()
        }
        val totalAssigned = totalIndiv + portions.sumOf { it.quantity }
        val itemAlloc = LinkedHashMap<UserId, Long>()
        fun charge(user: UserId, amount: Long) {
            subtotal[user] = (subtotal[user] ?: 0L) + amount
            itemAlloc[user] = (itemAlloc[user] ?: 0L) + amount
        }

        if (totalAssigned > quantity) {
            // Over-assignment: more units handed out than ordered. Cost every unit at the derived per-unit
            // price so the tab exceeds the line total — surfaced as OVERCLAIMED, never silently capped.
            val perUnit = perUnitSubunits(item.lineTotalSubunits, quantity)
            for ((user, units) in unitsByUser) charge(user, units.toLong() * perUnit)
            for (p in portions) if (p.members.isNotEmpty()) {
                for ((user, amt) in allocate(p.quantity.toLong() * perUnit, p.members.map { it to 1L })) charge(user, amt)
            }
        } else {
            // Split the line total penny-exact across its units, then hand slices out in order: individual
            // whole units first, then each shared portion its quantity of slices (split evenly). Any slices
            // left over stay unbilled → the line reconciles as UNCLAIMED (its units still "need someone").
            val unitCosts = splitEven(item.lineTotalSubunits, quantity)
            var cursor = 0
            for ((user, units) in unitsByUser.entries.sortedBy { it.key.value }) {
                var owed = 0L
                repeat(units) { owed += unitCosts[cursor++] }
                charge(user, owed)
            }
            for (p in portions) {
                if (p.quantity <= 0 || p.members.isEmpty()) continue
                var pool = 0L
                repeat(p.quantity) { pool += unitCosts[cursor++] }
                for ((user, amt) in allocate(pool, p.members.map { it to 1L })) charge(user, amt)
            }
        }
        if (itemAlloc.isNotEmpty()) perItem[item.itemId] = itemAlloc

        val status = when {
            totalAssigned > quantity -> ItemStatus.OVERCLAIMED
            totalAssigned < quantity -> ItemStatus.UNCLAIMED
            else -> ItemStatus.RESOLVED
        }
        reconcile += ItemReconcile(item.itemId, quantity, totalIndiv, portions.isNotEmpty(), status)
    }

    val subtotals = subtotal.toList()
    if (subtotals.isEmpty()) return BillResult(emptyMap(), reconcile, perItemByUser = perItem)

    // Extras (tax/gratuity/discount, and a PROPORTIONAL tip) ride proportional to each person's share of
    // the WHOLE bill's item subtotal — NOT just what's been claimed so far. Otherwise the first person to
    // claim absorbs 100% of tax + tip (a $5 juice showing a $102 tab). The still-unclaimed portion of the
    // bill rides a phantom bucket whose slice is computed then dropped — it gets billed as those items are
    // claimed, so every person's own share stays stable and correct throughout live claiming.
    val fullSubtotal = items.sumOf { it.lineTotalSubunits }
    val claimedSum = subtotals.sumOf { it.second }
    fun proportionalToFullBill(amount: Long): Map<UserId, Long> {
        if (amount == 0L) return emptyMap()
        // All-free bill (no subtotal to weight by) → even split among claimants, never divide-by-zero.
        if (fullSubtotal <= 0L) return allocate(amount, subtotals.map { it.first to 1L })
        val unclaimed = (fullSubtotal - claimedSum).coerceAtLeast(0L)
        return allocate(amount, subtotals + (UNCLAIMED_BUCKET to unclaimed)) - UNCLAIMED_BUCKET
    }

    val proportionalShares = proportionalToFullBill(extras.taxSubunits + extras.gratuitySubunits)
    val discountShares = proportionalToFullBill(extras.discountSubunits)
    val tipShares = when (extras.tipSplitMode) {
        TipSplitMode.PROPORTIONAL -> proportionalToFullBill(extras.tipSubunits)
        // Even per head across everyone the bill is for (stable regardless of claim order); non-claimers
        // just don't pick up their slice until they claim. Fall back to claimants when no set is supplied.
        TipSplitMode.EVEN -> if (extras.tipSubunits == 0L) emptyMap()
            else allocate(extras.tipSubunits, participants.ifEmpty { subtotals.map { it.first } }.map { it to 1L })
    }

    val breakdown = subtotals.associate { (id, sub) ->
        id to TabBreakdown(
            itemsSubunits = sub,
            taxSubunits = proportionalShares[id] ?: 0L, // tax + gratuity
            tipSubunits = tipShares[id] ?: 0L,
            discountSubunits = discountShares[id] ?: 0L,
        )
    }
    val owed = breakdown.mapValues { (_, b) -> b.totalSubunits }
    return BillResult(owed, reconcile, breakdown, perItem)
}

/** A line's effective shared slice inside [splitBill]: [quantity] units split evenly among [members]. */
private class LinePortion(val quantity: Int, val members: List<UserId>)

/** Sentinel weight-bucket for the un-yet-claimed portion of a bill; its extras slice is computed then dropped. */
private val UNCLAIMED_BUCKET = UserId("\u0000unclaimed")

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
