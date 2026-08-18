package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.ExpenseItemEntity
import app.splitevenly.data.db.entity.PendingItemEditEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `pending_item_edits` — the log of every change a web guest made to a bill's menu
 * (WEB_CLAIM_SPEC.md §2.7).
 *
 * No delete of any kind **on this table**: it is the record of who changed what on a shared bill, and an
 * undo is another entry in it rather than an erasure. [markUndone] stamps in place.
 *
 * It also owns [undoAndRestore], which writes `expense_items`, `item_claims`, `item_shares` and
 * `expenses`. Those queries live here for the same reason [ItemShareDao.setServings] declares its one
 * `item_claims` query: a Room `@Transaction` default method can only call queries on its own DAO, and the
 * stamp and the restore have to be one transaction or neither.
 */
@Dao
@Suppress("TooManyFunctions") // The extra queries are the restore half of [undoAndRestore]'s transaction.
interface PendingItemEditDao {
    @Upsert
    suspend fun upsert(edit: PendingItemEditEntity)

    @Upsert
    suspend fun upsertAll(edits: List<PendingItemEditEntity>)

    /** Everything that happened to this bill's menu, oldest first, undone rows included: the screen has
     *  to keep showing what it just undid, or a mis-tap vanishes instead of being legible. */
    @Query("SELECT * FROM pending_item_edits WHERE expense_id = :expenseId ORDER BY proposed_at ASC")
    fun observeByExpense(expenseId: String): Flow<List<PendingItemEditEntity>>

    @Query("SELECT * FROM pending_item_edits WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PendingItemEditEntity?

    /**
     * Stamp a change as undone. **Conditional on it still being `APPLIED`** — that `WHERE` clause is
     * first-undo-wins, the same shape as `claim_placeholder`: anyone on the bill may undo (spec §2.7),
     * so two people tapping at once must produce one undo and one no-op, not two.
     *
     * Bumping `row_version` + `updated_at` is what carries it to the server.
     */
    @Query(
        """
        UPDATE pending_item_edits
           SET decision = 'UNDONE', decided_at = :ts, decided_by = :undoneBy,
               updated_at = :ts, row_version = row_version + 1
         WHERE id = :id AND decision = 'APPLIED'
        """,
    )
    suspend fun markUndone(
        id: String,
        undoneBy: String,
        ts: Long,
    ): Int

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM pending_item_edits")
    suspend fun allForSync(): List<PendingItemEditEntity>

    // ── The restore half of an undo, so it cannot come apart from the stamp above ──────────

    @Upsert
    suspend fun upsertItem(item: ExpenseItemEntity)

    @Upsert
    suspend fun upsertExpense(expense: ExpenseEntity)

    /** Soft-delete (Rule 1) the line an undone ADD put on the bill. */
    @Query(
        "UPDATE expense_items SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 " +
            "WHERE id IN (:ids)",
    )
    suspend fun softDeleteItemsByIds(
        ids: List<String>,
        ts: Long,
    )

    /** The claims on that line go with it: a claim on a line that is gone would keep billing for it (spec E22). */
    @Query(
        "UPDATE item_claims SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 " +
            "WHERE item_id IN (:itemIds) AND deleted_at IS NULL",
    )
    suspend fun softDeleteClaimsByItems(
        itemIds: List<String>,
        ts: Long,
    )

    @Query(
        "UPDATE item_shares SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 " +
            "WHERE item_id IN (:itemIds) AND deleted_at IS NULL",
    )
    suspend fun softDeleteItemSharesByItems(
        itemIds: List<String>,
        ts: Long,
    )

    /**
     * Take a guest's change back: claim the undo and put the line back, in ONE transaction.
     *
     * The two used to be separate writes, and [markUndone] is deliberately first-undo-wins. So an
     * interruption between them (a back-tap cancelling the navigation-scoped coroutine, or the process
     * dying) burned the only thing gating the restore: the line stayed at the price the payer had
     * rejected, every device rendered the change as *undone*, and no launch, pull or re-tap could ever
     * repair it, because the second tap correctly no-ops. Rolling the stamp back with the restore is what
     * makes the retry work (finding R3).
     *
     * The caller decides *what* to write and validates it first (finding R5); this only writes it. Shares
     * re-derive afterwards, outside — they are a self-healing materialization, not part of the atomic
     * unit, the same division [ItemShareDao.setServings] makes.
     *
     * Returns false, with **nothing written**, when this undo lost the race: the change is already
     * undone, which is what the caller wanted.
     */
    @Transaction
    @Suppress("LongParameterList") // The parameters ARE the undo; grouping them hides what is atomic.
    suspend fun undoAndRestore(
        editId: String,
        undoneBy: String,
        ts: Long,
        restoredItem: ExpenseItemEntity?,
        droppedItemIds: List<String>,
        expense: ExpenseEntity?,
    ): Boolean {
        // First, so losing the race costs nothing and needs no rollback.
        if (markUndone(editId, undoneBy, ts) == 0) return false
        if (droppedItemIds.isNotEmpty()) {
            softDeleteItemsByIds(droppedItemIds, ts)
            softDeleteClaimsByItems(droppedItemIds, ts)
            softDeleteItemSharesByItems(droppedItemIds, ts)
        }
        restoredItem?.let { upsertItem(it) }
        expense?.let { upsertExpense(it) }
        return true
    }
}
