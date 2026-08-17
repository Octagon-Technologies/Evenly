package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.splitevenly.data.db.entity.BillParticipantEntity
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.ExpenseItemEntity

/**
 * The two places a "Split the bill" write is **one fact spread over several tables**, each as ONE Room
 * transaction.
 *
 * They live in their own DAO for the reason [PlaceholderMergeDao] does: a Room `@Transaction` default
 * method can only call queries declared on its own DAO, so a transaction spanning `expenses`,
 * `expense_items`, `bill_participants`, `item_claims` and `item_shares` has to declare all of them
 * somewhere, and hanging that off whichever single-table DAO happened to be nearest hides it.
 *
 * Both used to be loose sequences of calls issued from a **navigation-scoped** coroutine, so a back-tap
 * or a process death part-way through committed the first write and dropped the rest. Widening the
 * coroutine scope is not the fix and never was: it does not survive process death, which
 * [ItemShareDao.setServings]'s own comment already records from finding #15.
 */
@Dao
@Suppress("TooManyFunctions") // Each query is one table's half of the two transactions below.
interface BillWriteDao {
    @Upsert
    suspend fun upsertExpense(expense: ExpenseEntity)

    @Upsert
    suspend fun upsertItems(items: List<ExpenseItemEntity>)

    @Upsert
    suspend fun upsertParticipants(participants: List<BillParticipantEntity>)

    @Query(
        "UPDATE bill_participants SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 " +
            "WHERE id IN (:ids)",
    )
    suspend fun softDeleteParticipantsByIds(
        ids: List<String>,
        ts: Long,
    )

    @Query(
        "UPDATE item_claims SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 " +
            "WHERE expense_id = :expenseId AND user_id IN (:userIds) AND deleted_at IS NULL",
    )
    suspend fun softDeleteClaimsOfUsers(
        expenseId: String,
        userIds: List<String>,
        ts: Long,
    )

    @Query(
        "UPDATE item_shares SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 " +
            "WHERE expense_id = :expenseId AND user_id IN (:userIds) AND deleted_at IS NULL",
    )
    suspend fun softDeleteItemSharesOfUsers(
        expenseId: String,
        userIds: List<String>,
        ts: Long,
    )

    @Query(
        "UPDATE expense_items SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 " +
            "WHERE id IN (:ids)",
    )
    suspend fun softDeleteItemsByIds(
        ids: List<String>,
        ts: Long,
    )

    @Query(
        "UPDATE item_claims SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 " +
            "WHERE item_id IN (:itemIds) AND deleted_at IS NULL",
    )
    suspend fun softDeleteClaimsOfItems(
        itemIds: List<String>,
        ts: Long,
    )

    @Query(
        "UPDATE item_shares SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 " +
            "WHERE item_id IN (:itemIds) AND deleted_at IS NULL",
    )
    suspend fun softDeleteItemSharesOfItems(
        itemIds: List<String>,
        ts: Long,
    )

    /**
     * Create a bill: the expense row carrying the whole total, the lines that total is made of, and the
     * roster it is for (finding R8).
     *
     * Dying between the expense and the items left an ACTIVE itemized expense with a full amount and zero
     * lines. It pushes to the server and reaches every device; `observeUnresolvedBills` skips a bill with
     * no items, so it never surfaces as needing attention and the group's list just carries an $84 bill
     * nobody owes anything on. `ExpenseDao.insertWithShares` has always done the equivalent for a plain
     * expense; this is the bill-shaped version of it.
     *
     * Shares are **not** written here. They are a derived materialization the caller re-runs afterwards,
     * and it self-heals on every pull.
     */
    @Transaction
    suspend fun createBill(
        expense: ExpenseEntity,
        items: List<ExpenseItemEntity>,
        participants: List<BillParticipantEntity>,
    ) {
        upsertExpense(expense)
        if (items.isNotEmpty()) upsertItems(items)
        if (participants.isNotEmpty()) upsertParticipants(participants)
    }

    /**
     * Apply a bill edit: the expense row, the surviving and new lines, the lines this edit removed with
     * the claims on them, and the reconciled roster with the claims and slices of anyone taken off
     * (finding R4).
     *
     * The removal half is the one that costs money, and `data/AGENTS.md` states it in so many words:
     * "Taking someone off a bill must take their claims with them ... in the same write." The roster row
     * carries no money — a bill's owed amounts derive from `item_claims`/`item_shares` — so an
     * interruption after the roster tombstone alone left Mary off the bill and still paying for her
     * dishes, and the next pull made it *worse*: `SyncEngine` re-runs `rematerializeGroups`, which
     * faithfully re-derives her debt from her still-live claims. She is not on the roster, so no screen
     * offers a way to take those claims back.
     *
     * The rest of the edit rides along rather than being left loose beside it, because the alternative is
     * an expense whose stored total does not match the lines under it. The caller computes every row
     * first; this only writes them.
     *
     * Units a removed person held alone return to UNCLAIMED; a slice they shared survives for its
     * remaining members. Both follow from soft-deleting only their own rows.
     */
    @Transaction
    @Suppress("LongParameterList") // The parameters ARE the edit; grouping them hides what is atomic.
    suspend fun applyBillEdit(
        expense: ExpenseEntity,
        itemUpserts: List<ExpenseItemEntity>,
        removedItemIds: List<String>,
        addedParticipants: List<BillParticipantEntity>,
        removedParticipantIds: List<String>,
        removedUserIds: List<String>,
        ts: Long,
    ) {
        upsertExpense(expense)
        if (itemUpserts.isNotEmpty()) upsertItems(itemUpserts)
        if (removedItemIds.isNotEmpty()) {
            softDeleteItemsByIds(removedItemIds, ts)
            softDeleteClaimsOfItems(removedItemIds, ts)
            softDeleteItemSharesOfItems(removedItemIds, ts)
        }
        if (addedParticipants.isNotEmpty()) upsertParticipants(addedParticipants)
        if (removedParticipantIds.isNotEmpty()) {
            softDeleteParticipantsByIds(removedParticipantIds, ts)
            softDeleteClaimsOfUsers(expense.id, removedUserIds, ts)
            softDeleteItemSharesOfUsers(expense.id, removedUserIds, ts)
        }
    }
}
