package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.data.db.projection.OutstandingShareForPair
import da.chelimo.sharecost.data.db.projection.OutstandingShareRow
import da.chelimo.sharecost.data.db.projection.ReconcileExpenseRow
import kotlinx.coroutines.flow.Flow

/** DAO for `shares` (02 §3.8). */
@Dao
interface ShareDao {

    @Upsert
    suspend fun upsert(share: ShareEntity)

    @Upsert
    suspend fun upsertAll(shares: List<ShareEntity>)

    @Query("SELECT * FROM shares WHERE expense_id = :expenseId")
    suspend fun getByExpense(expenseId: String): List<ShareEntity>

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM shares")
    suspend fun allForSync(): List<ShareEntity>

    @Query("SELECT * FROM shares WHERE expense_id = :expenseId")
    fun observeByExpense(expenseId: String): Flow<List<ShareEntity>>

    /** AC-INV-001: must equal `expenses.amount_subunits`. `COALESCE` so no-rows returns 0, not null. */
    @Query("SELECT COALESCE(SUM(share_owed_subunits), 0) FROM shares WHERE expense_id = :expenseId")
    suspend fun sumOwed(expenseId: String): Long

    /** Drives the recomputed status (AC-INV-003): 0 ⇒ SETTLED. */
    @Query("SELECT COALESCE(SUM(remaining_subunits), 0) FROM shares WHERE expense_id = :expenseId")
    suspend fun sumRemaining(expenseId: String): Long

    /**
     * Outstanding shares across a group, joined to their expense for currency + payer — the input to
     * the bilateral balance engine (03 §2.2). Excludes settled shares (remaining = 0) and
     * soft-deleted expenses.
     */
    @Query(
        """
        SELECT s.expense_id, e.currency, e.payer_user_id, s.user_id, s.remaining_subunits
        FROM shares s
        INNER JOIN expenses e ON e.id = s.expense_id
        WHERE e.group_id = :groupId
          AND e.deleted_at IS NULL
          AND s.remaining_subunits > 0
        """
    )
    fun observeOutstandingShares(groupId: String): Flow<List<OutstandingShareRow>>

    /**
     * Outstanding shares that [fromUserId] (the participant who owes) still owes [toUserId] (the
     * expense payer), oldest expense first — the exact input the settlement allocator walks
     * (03 §4.2/§4.3.1). Excludes settled shares and soft-deleted expenses.
     */
    @Query(
        """
        SELECT s.id, s.expense_id, e.currency, s.remaining_subunits, e.expense_date
        FROM shares s
        INNER JOIN expenses e ON e.id = s.expense_id
        WHERE e.group_id = :groupId
          AND e.deleted_at IS NULL
          AND e.payer_user_id = :toUserId
          AND s.user_id = :fromUserId
          AND s.remaining_subunits > 0
        ORDER BY e.expense_date ASC, s.expense_id ASC, s.id ASC
        """
    )
    suspend fun outstandingForPair(
        groupId: String,
        fromUserId: String,
        toUserId: String,
    ): List<OutstandingShareForPair>

    /** Distinct parent expenses of the given shares — the set to re-status when voiding a settlement. */
    @Query("SELECT DISTINCT expense_id FROM shares WHERE id IN (:shareIds)")
    suspend fun expenseIdsForShares(shareIds: List<String>): List<String>

    /** Reassign every share a user holds in a group to another user — reconcile placeholder → real (03 §8). */
    @Query(
        """
        UPDATE shares SET user_id = :toUserId, updated_at = :now, row_version = row_version + 1
        WHERE user_id = :fromUserId AND expense_id IN (SELECT id FROM expenses WHERE group_id = :groupId)
        """
    )
    suspend fun reassignUserInGroup(groupId: String, fromUserId: String, toUserId: String, now: Long)

    /** The expenses a (placeholder) user is booked into — the claim cards on the Reconcile screen. */
    @Query(
        """
        SELECT e.title AS title, e.amount_subunits AS amount_subunits
        FROM shares s INNER JOIN expenses e ON e.id = s.expense_id
        WHERE s.user_id = :userId AND e.group_id = :groupId AND e.deleted_at IS NULL
        ORDER BY e.expense_date ASC
        """
    )
    suspend fun expensesForUser(groupId: String, userId: String): List<ReconcileExpenseRow>
}
