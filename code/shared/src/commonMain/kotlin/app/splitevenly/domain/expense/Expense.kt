package app.splitevenly.domain.expense

import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId

/**
 * Domain view of an expense (02 §3.7). [amountSubunits] is always positive (a refund's meaning is
 * carried by `kind` + participants, not a sign). [expenseDate] is the local calendar date as an
 * ISO-8601 string ("YYYY-MM-DD"). [status] is the denormalized value owned by
 * [app.splitevenly.data.db.computeExpenseStatus].
 */
data class Expense(
    val id: ExpenseId,
    val groupId: GroupId,
    val title: String,
    val amountSubunits: Long,
    val currency: String,
    val expenseDate: String,
    val payerUserId: UserId?,
    val payerOutsideName: String?,
    val splitMode: String,
    val status: String,
    val notes: String?,
    val categoryId: String? = null,
    val createdBy: UserId,
    val createdAt: Long,
    val rowVersion: Long,
)

/**
 * One participant's portion of an expense (02 §3.8). [remainingSubunits] is what is still unpaid.
 *
 * The raw split inputs are carried through from persistence so the split editor can be re-rendered on
 * edit without recomputing: exactly one of [shareUnits]/[sharePercentage]/[shareExactSubunits] is
 * non-null for the SHARE/PERCENT/EXACT modes respectively; all three are null for an EVEN split.
 */
data class ExpenseShare(
    val id: String,
    val userId: UserId,
    val owedSubunits: Long,
    val remainingSubunits: Long,
    val shareUnits: Int? = null,
    val sharePercentage: Double? = null,
    val shareExactSubunits: Long? = null,
)

/** An expense with its participant shares — the detail view (05 §4). */
data class ExpenseWithShares(
    val expense: Expense,
    val shares: List<ExpenseShare>,
)

/**
 * One participant's owed amount for a new/edited expense. The caller (a use case / the split editor)
 * has already run the allocator (03 §1.2); the repository only persists the result and enforces the
 * sum invariant (AC-INV-001).
 *
 * The raw split inputs are preserved so the split editor can be re-rendered without recomputing:
 * exactly one of [shareUnits]/[sharePercentage]/[shareExactSubunits] is non-null for the SHARE/PERCENT/
 * EXACT modes respectively; all three are null for an EVEN split.
 */
data class NewShare(
    val userId: UserId,
    val owedSubunits: Long,
    val shareUnits: Int? = null,
    val sharePercentage: Double? = null,
    val shareExactSubunits: Long? = null,
)

/** Input to add an expense in one transaction (04 §2.3 `add_expense`). */
data class NewExpense(
    val groupId: GroupId,
    val title: String,
    val amountSubunits: Long,
    val currency: String,
    val expenseDate: String,
    val payerUserId: UserId?,
    val splitMode: String,
    val createdBy: UserId,
    val shares: List<NewShare>,
    val payerOutsideName: String? = null,
    val notes: String? = null,
    val categoryId: String? = null,
)

/**
 * Input to edit an expense (04 §2.3 `edit_expense`). Replaces the editable fields and the full share
 * set. MVP assumes the expense carries no prior settlement allocations when edited; reconciling an
 * edit against existing settlements is a server/sync concern (S-1).
 */
data class EditExpense(
    val title: String,
    val amountSubunits: Long,
    val currency: String,
    val expenseDate: String,
    val payerUserId: UserId?,
    val splitMode: String,
    val shares: List<NewShare>,
    val payerOutsideName: String? = null,
    val notes: String? = null,
    val categoryId: String? = null,
    /** Who is performing the edit — recorded on the expense's activity log. Null = unattributed. */
    val editedBy: UserId? = null,
)
