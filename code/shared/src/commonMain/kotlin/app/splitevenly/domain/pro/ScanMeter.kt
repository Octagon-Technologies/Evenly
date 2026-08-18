package app.splitevenly.domain.pro

/** What the meter under the scan card should say, or nothing at all. */
data class ScanMeter(
    val used: Int,
    val limit: Int,
    val remaining: Int,
) {
    /** Down to the last one. The row warms to the "in credit" amber at this point, not red: nothing has
     *  gone wrong, and a red row over a working feature reads as a fault in the app. */
    val isLow: Boolean get() = remaining <= 1
}

/**
 * Decides whether a group sees the free-scan meter, and what it reads (`PRO_PASS_SPEC.md` §8.1).
 *
 * Returns null in three cases, each deliberate:
 *  - **Pro.** There is nothing to count while a pass is live, and a counter would be pure noise.
 *  - **[used] is null.** The count has never been fetched for this group. Showing "5 of 5 left" from a
 *    default would be inventing a number, and the first fetch could then contradict it on screen.
 *  - **More than [SHOW_FROM_REMAINING] left.** A brand new group counting down from 5 reads as a trial
 *    with a clock on it. A group that has already scanned a few times is just being told a fact.
 */
fun scanMeterFor(status: ProStatus, used: Int?, limit: Int): ScanMeter? {
    if (status.isPro) return null
    if (used == null) return null
    val remaining = (limit - used).coerceAtLeast(0)
    if (remaining > SHOW_FROM_REMAINING) return null
    return ScanMeter(used = used, limit = limit, remaining = remaining)
}

/** Show the meter once this many free scans (or fewer) are left. */
const val SHOW_FROM_REMAINING = 3
