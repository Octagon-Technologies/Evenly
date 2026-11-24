package app.splitevenly.domain.people

/** One group a cross-group payment can go to. */
data class SplitTarget(
    val groupId: String,
    val balanceSubunits: Long,
    /** ISO day of the oldest unpaid expense. */
    val oldestDate: String,
)

/** The default split: clear the group with the oldest unpaid expense first, then the next. */
fun oldestFirst(
    paidSubunits: Long,
    targets: List<SplitTarget>,
): Map<String, Long> {
    var left = paidSubunits.coerceAtLeast(0L)
    val out = linkedMapOf<String, Long>()
    for (t in targets.sortedWith(compareBy<SplitTarget> { it.oldestDate }.thenBy { it.groupId })) {
        val take = minOf(left, t.balanceSubunits.coerceAtLeast(0L))
        out[t.groupId] = take
        left -= take
    }
    return out
}

/** Where a hand-edited split stands against what was paid. */
data class SplitCheck(
    val assignedSubunits: Long,
    val paidSubunits: Long,
    /** Groups set above what they owe there. */
    val overBalance: Set<String>,
) {
    /** Positive: still to assign. Negative: assigned more than was paid. */
    val leftSubunits: Long get() = paidSubunits - assignedSubunits

    val addsUp: Boolean get() = leftSubunits == 0L && overBalance.isEmpty() && paidSubunits > 0
}

/**
 * Checks a split. Editing one group never moves another, so the caller passes the split exactly as typed;
 * a payment is only written when [SplitCheck.addsUp].
 */
fun checkSplit(
    paidSubunits: Long,
    split: Map<String, Long>,
    targets: List<SplitTarget>,
): SplitCheck {
    val balance = targets.associate { it.groupId to it.balanceSubunits }
    return SplitCheck(
        assignedSubunits = split.values.sumOf { it.coerceAtLeast(0L) },
        paidSubunits = paidSubunits,
        overBalance = split.filter { (g, v) -> v > (balance[g] ?: 0L) }.keys,
    )
}
