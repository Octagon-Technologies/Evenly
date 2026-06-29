package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `expenses` (02 §3.7). Owns the expense+shares write transactions (02 §7.5). */
@Dao
interface ExpenseDao {

    @Upsert
    suspend fun upsert(expense: ExpenseEntity)

    @Upsert
    suspend fun upsertAll(expenses: List<ExpenseEntity>)

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: String): ExpenseEntity?

    /** All non-deleted expenses in a group, one-shot — the input to a retroactive-member sweep (03 §8.1). */
    @Query("SELECT * FROM expenses WHERE group_id = :groupId AND deleted_at IS NULL ORDER BY expense_date ASC, id ASC")
    suspend fun getActiveByGroup(groupId: String): List<ExpenseEntity>

    /** Every local row (incl. soft-deleted) — the push side of sync. */
    @Query("SELECT * FROM expenses")
    suspend fun allForSync(): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<ExpenseEntity?>

    /**
     * Group home feed (U-3): non-deleted expenses newest-first. `id DESC` is the stable tiebreaker
     * within a day — and because IDs are UUIDv7 (time-sortable), it orders same-day rows by creation
     * time without a separate column.
     */
    @Query(
        """
        SELECT * FROM expenses
        WHERE group_id = :groupId AND deleted_at IS NULL
        ORDER BY expense_date DESC, id DESC
        """
    )
    fun observeByGroup(groupId: String): Flow<List<ExpenseEntity>>

    /** Persist the recomputed denormalized status (02 §7.5). */
    @Query("UPDATE expenses SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)

    /** Reassign every expense a user paid in a group to another payer — reconcile placeholder → real (03 §8). */
    @Query("UPDATE expenses SET payer_user_id = :toUserId, updated_at = :now, row_version = row_version + 1 WHERE group_id = :groupId AND payer_user_id = :fromUserId")
    suspend fun reassignPayerInGroup(groupId: String, fromUserId: String, toUserId: String, now: Long)

    /**
     * Touch (mark dirty) the non-deleted expenses that have an active share for [userId] in [groupId].
     * Used after a placeholder→real *share* reassignment so those owed expenses re-push through the
     * commit_expense CAS — otherwise a share-only change wouldn't bump its parent expense's version.
     */
    @Query(
        """
        UPDATE expenses SET updated_at = :now, row_version = row_version + 1
        WHERE deleted_at IS NULL AND id IN (
            SELECT DISTINCT s.expense_id FROM shares s
            WHERE s.user_id = :userId AND s.deleted_at IS NULL
              AND s.expense_id IN (SELECT id FROM expenses WHERE group_id = :groupId)
        )
        """
    )
    suspend fun touchExpensesWithShareOfUser(groupId: String, userId: String, now: Long)

    // --- Shares (declared here so the expense + its shares write in one transaction) ------------

    @Upsert
    suspend fun upsertShares(shares: List<ShareEntity>)

    /** Tombstone the shares an edit removed (Rule 1): soft-delete so the removal syncs, never hard-delete. */
    @Query("UPDATE shares SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun softDeleteSharesByIds(ids: List<String>, ts: Long)

    /** Soft-delete an expense (04 §2.3 `delete_expense`); status becomes DELETED, version bumps. */
    @Query("UPDATE expenses SET deleted_at = :ts, status = 'DELETED', updated_at = :ts, row_version = row_version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    // --- Transactions ---------------------------------------------------------------------------

    /** Insert an expense and its shares atomically (AC-INV-001 enforced by the caller). */
    @Transaction
    suspend fun insertWithShares(expense: ExpenseEntity, shares: List<ShareEntity>) {
        upsert(expense)
        upsertShares(shares)
    }

    /**
     * Apply an expense edit atomically: update the expense, soft-delete the shares the edit removed,
     * and upsert the surviving/added shares. Removed shares are tombstoned (not hard-deleted) so the
     * removal syncs; surviving participants keep their share `id` (caller does the identity merge), so
     * settlement allocations stay linked and `remaining` re-derives instead of resetting (edit_expense).
     */
    @Transaction
    suspend fun replaceWithShares(
        expense: ExpenseEntity,
        shares: List<ShareEntity>,
        removedShareIds: List<String>,
        ts: Long,
    ) {
        upsert(expense)
        if (removedShareIds.isNotEmpty()) softDeleteSharesByIds(removedShareIds, ts)
        upsertShares(shares)
    }

    /**
     * Force local cache back to the server's canonical expense + shares after our edit was PARKED
     * (lost the optimistic-concurrency race). The rejected optimistic-only shares are tombstoned
     * (not hard-deleted) and won't re-push since the expense now matches the server. This is
     * local-cache reconciliation toward the server's truth, not a user-data deletion — the rejected
     * edit itself is preserved server-side in `expense_edit_conflicts`.
     */
    @Transaction
    suspend fun overwriteFromServer(expense: ExpenseEntity, serverShares: List<ShareEntity>, now: Long) {
        upsert(expense)
        softDeleteLocalSharesNotIn(expense.id, serverShares.map { it.id }, now)
        upsertShares(serverShares)
    }

    @Query("UPDATE shares SET deleted_at = :now, updated_at = :now WHERE expense_id = :expenseId AND deleted_at IS NULL AND id NOT IN (:keepIds)")
    suspend fun softDeleteLocalSharesNotIn(expenseId: String, keepIds: List<String>, now: Long)
}
