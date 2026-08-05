package app.splitevenly.domain.group

/** One expense a claim would move onto the claimer, at the amount that actually lands on a balance. */
data class ClaimLine(val title: String, val amountSubunits: Long, val currency: String)

/**
 * What claiming a name would do to the money, shown *before* it moves.
 *
 * [owed] is what the claimer takes on; [paid] is what they would be recorded as having put on the
 * table. Both can be empty: a name with no history at all is still a candidate identity, and the sheet
 * says so plainly rather than showing an empty box.
 *
 * [currency] is null when the lines span more than one, in which case there is no single total to
 * print. Never sum across currencies.
 */
data class ClaimPreview(
    val name: String,
    val owed: List<ClaimLine>,
    val paid: List<ClaimLine>,
    val owedTotalSubunits: Long,
    val currency: String?,
) {
    val isEmpty: Boolean get() = owed.isEmpty() && paid.isEmpty()
}
