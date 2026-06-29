package da.chelimo.sharecost.domain.expense

import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.UserId

/**
 * A parked expense edit awaiting a pick-a-side resolution. Two devices edited the same expense from
 * the same base version; the server kept the first (canonical) and parked the loser's payload. The UI
 * shows both sides so a human decides — keep the current version, or re-apply the rejected edit on top
 * of it. We never auto-merge two edits of a money split, and we never lose the disagreement.
 */
data class ExpenseEditConflict(
    val id: String,
    val expenseId: ExpenseId,
    val rejectedBy: UserId,
    val currency: String,
    val currentTitle: String,
    val currentAmountSubunits: Long,
    val rejectedTitle: String,
    val rejectedAmountSubunits: Long,
    val createdAt: Long,
)
