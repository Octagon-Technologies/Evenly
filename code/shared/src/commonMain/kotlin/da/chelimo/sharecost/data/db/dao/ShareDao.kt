package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.data.db.projection.OutstandingShareRow
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
}
