package da.chelimo.sharecost.domain.group

import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId

/**
 * An unresolved retroactive-member conflict (03 §8): [addedUserId] was added to the group after this
 * non-EVEN [expenseId] existed, so someone must decide their share (Include) or leave them out (Skip).
 * Carries the expense's title/amount for display; member names are resolved from the roster.
 */
data class Conflict(
    val id: String,
    val groupId: GroupId,
    val expenseId: ExpenseId,
    val expenseTitle: String,
    val amountSubunits: Long,
    val currency: String,
    val addedUserId: UserId,
    val triggeredByUserId: UserId,
    val createdAt: Long,
)
