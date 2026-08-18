package app.splitevenly.domain.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.expense.BillView
import app.splitevenly.domain.expense.EditBill
import app.splitevenly.domain.expense.NewBill
import app.splitevenly.domain.expense.PendingBillEdit
import app.splitevenly.domain.expense.UnresolvedBill
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
    suspend fun editBill(
        expenseId: ExpenseId,
        input: EditBill,
    ): AppResult<Unit>

    /**
     * Set [userId]'s claimed quantity on an item (0 = un-claim, soft-deleting their claim). Re-derives
     * the bill's shares. This is the only write a non-creator makes, and it touches only their own row.
     */
    suspend fun setClaim(
        expenseId: ExpenseId,
        itemId: String,
        userId: UserId,
        quantity: Int,
    ): AppResult<Unit>

    /**
     * Set a **shared portion** of a line: [memberIds] split [quantity] units evenly, grouped under
     * [portionId]. Multiple distinct portions can coexist on one line (that's what "2 solo, 3 solo, 1
     * shared, 2 left" needs), and a member may hold more than one — uniqueness is
     * `(item_id, user_id, portion_id)`, per-serving assignment depends on it. Empty members or
     * quantity ≤ 0 removes the portion. [addedBy] is who assigned it. Re-derives the bill's shares.
     */
    suspend fun setPortion(
        expenseId: ExpenseId,
        itemId: String,
        portionId: String,
        memberIds: List<UserId>,
        quantity: Int,
        addedBy: UserId,
    ): AppResult<Unit>

    /**
     * Re-slice an item into [servings] in one shot (#15): each entry is one unit assigned to those members
     * (empty entries are skipped). Replaces the item's whole assignment — its claims and portions are torn
     * down and rebuilt **atomically** (one DB transaction), so navigating away or a crash mid-flight can't
     * leave the item wiped half-way. [addedBy] is who assigned. Re-derives the bill's shares afterwards.
     */
    suspend fun setServings(
        expenseId: ExpenseId,
        itemId: String,
        servings: List<List<UserId>>,
        addedBy: UserId,
    ): AppResult<Unit>

    /**
     * Join [joinerUserId] onto a line someone else already claimed — the one write a device cannot make
     * itself, since it can mean converting ANOTHER person's solo claim into a shared portion, which the
     * "claims are partitioned by user" invariant forbids doing locally (`join_item_portion`, spec §5.3).
     * [portionId] names an existing (or about-to-exist) shared slice; omit it to join/convert the item's
     * one solo claim, or start a fresh single-member portion if it has none. Requires a live server call —
     * there is no offline path — and rejects with [app.splitevenly.core.error.AppError.Backend] (code
     * `"OVERCLAIMED"`) if it would push assigned units past the line's quantity, unless [overClaimAck].
     */
    suspend fun joinItem(
        expenseId: ExpenseId,
        itemId: String,
        joinerUserId: UserId,
        portionId: String? = null,
        overClaimAck: Boolean = false,
    ): AppResult<Unit>

    /** Add or remove a participant from a bill (who it's *for*). */
    suspend fun setParticipant(
        expenseId: ExpenseId,
        userId: UserId,
        included: Boolean,
    ): AppResult<Unit>

    /**
     * Menu changes web guests made to this bill, oldest first, undone ones included
     * (WEB_CLAIM_SPEC.md §2.7, §3.9.1). **All of them have already moved the money** — a guest's edit
     * applies on write — so this is the bill's history, not an inbox.
     */
    fun observePendingEdits(expenseId: ExpenseId): Flow<List<PendingBillEdit>>

    /**
     * Take **one** change back. Restores the line to what it replaced, advances the causal
     * `split_version` (it is a Zone-2 money edit) and re-derives the bill's shares. The log row is kept
     * and stamped `UNDONE`, because an undo is another entry in the record rather than an erasure.
     *
     * Anyone on the bill may undo (§2.7); [undoneBy] is recorded, not checked. Undoing something already
     * undone is a no-op, not an error. There is deliberately no bulk variant.
     */
    suspend fun undoPendingEdit(
        editId: String,
        undoneBy: UserId,
    ): AppResult<Unit>

    /**
     * Hand every still-unassigned unit on this bill to [memberIds], splitting each line's leftover evenly
     * between them (WEB_CLAIM_SPEC.md §3.9.2, E17 — "three people never claim"). One shared portion per
     * line, so the remainder stays visible as a group slice rather than being silently folded into
     * anyone's solo claim. Existing claims are never touched, and a bill with nothing left is a no-op.
     */
    suspend fun assignRemainder(
        expenseId: ExpenseId,
        memberIds: List<UserId>,
        addedBy: UserId,
    ): AppResult<Unit>

    /** Stamp / clear a participant's "I'm done claiming" marker (a personal nudge-silencer). */
    suspend fun markDone(
        expenseId: ExpenseId,
        userId: UserId,
        done: Boolean,
    ): AppResult<Unit>

    /**
     * A group's unresolved bills — itemized expenses with a line still needing someone, or a participant
     * who hasn't marked done. [viewer] flags the ones where *you* still owe a claim (the home card).
     */
    fun observeUnresolvedBills(
        groupId: GroupId,
        viewer: UserId?,
    ): Flow<List<UnresolvedBill>>
}
