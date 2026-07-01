package da.chelimo.sharecost.domain.expense

import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.UserId

/**
 * A parked expense edit awaiting a pick-a-side resolution. Two people edited the same expense from the
 * same base version; the server kept the first (canonical) and parked the loser's payload. We surface
 * BOTH full sides so the UI can show exactly what differs — the total, the split mode, the payer, and
 * each participant's owed amount — instead of just a bare total. We never auto-merge two edits of a
 * money split, and we never lose the disagreement.
 *
 * [rejectedBy] is the loser (whose edit was parked); [winnerBy] wrote the [current] canonical version
 * ([winnerBy] is null for conflicts parked before the server tracked it). The UI diffs [current] vs
 * [rejected] and leads with the viewing user's own share.
 */
data class ExpenseEditConflict(
    val id: String,
    val expenseId: ExpenseId,
    val rejectedBy: UserId,
    val winnerBy: UserId?,
    val currency: String,
    val current: ConflictSide,
    val rejected: ConflictSide,
    val createdAt: Long,
)

/** One side of an edit conflict: the fields a human needs to compare the two versions. */
data class ConflictSide(
    val title: String,
    val amountSubunits: Long,
    val splitMode: String,
    val payerUserId: UserId?,
    val payerOutsideName: String?,
    /** Active participant → what they owe (subunits). Keyed by user so the two sides diff per person. */
    val shares: Map<UserId, Long>,
)
