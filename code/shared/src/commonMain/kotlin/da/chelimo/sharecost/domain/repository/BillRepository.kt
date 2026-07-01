package da.chelimo.sharecost.domain.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.expense.BillView
import da.chelimo.sharecost.domain.expense.EditBill
import da.chelimo.sharecost.domain.expense.NewBill
import kotlinx.coroutines.flow.Flow

/**
 * "Split the bill" (itemized expenses). A bill is an expense whose participant shares are **derived**
 * from its items + claims + extras, never typed in. Reads are local-first `Flow`s; writes are
 * optimistic [AppResult]. Items are the creator-owned "menu" ([createBill]/[editBill]); claims are the
 * live, user-partitioned layer ([setClaim]) — each person only ever writes their own claim, so
 * concurrent claiming needs no conflict resolution.
 */
interface BillRepository {

    /** The bill with its items, live claims, extras, and the derived per-participant tab — the claim screen. */
    fun observeBill(expenseId: ExpenseId): Flow<BillView?>

    /** Create a bill (an ITEMIZED expense) from its menu + extras. Shares derive from claims as they arrive. */
    suspend fun createBill(input: NewBill): AppResult<ExpenseId>

    /**
     * Replace a bill's menu + extras (the "Save bill" action). Surviving lines keep their id (so claims
     * on them survive); removed lines and their claims are tombstoned. Shares re-derive — editing a
     * price mid-claim never disturbs a recorded claim or its settlement allocations.
     */
    suspend fun editBill(expenseId: ExpenseId, input: EditBill): AppResult<Unit>

    /**
     * Set [userId]'s claimed quantity on an item (0 = un-claim, soft-deleting their claim). Re-derives
     * the bill's shares. This is the only write a non-creator makes, and it touches only their own row.
     */
    suspend fun setClaim(expenseId: ExpenseId, itemId: String, userId: UserId, quantity: Int): AppResult<Unit>

    /**
     * Add or remove [memberUserId] from an item's **shared split** (the auto-union set). [addedBy] is who
     * performed it (you adding yourself, or naming a friend). Because the set unions, re-adding is a no-op
     * and overlapping "shared with" declarations merge automatically. Re-derives the bill's shares.
     */
    suspend fun setShareMember(
        expenseId: ExpenseId,
        itemId: String,
        memberUserId: UserId,
        addedBy: UserId,
        inShare: Boolean,
    ): AppResult<Unit>
}
