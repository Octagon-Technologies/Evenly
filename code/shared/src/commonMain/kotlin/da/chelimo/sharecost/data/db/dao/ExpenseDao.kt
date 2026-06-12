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

    // --- Shares (declared here so the expense + its shares write in one transaction) ------------

    @Upsert
    suspend fun upsertShares(shares: List<ShareEntity>)

    @Query("DELETE FROM shares WHERE expense_id = :expenseId")
    suspend fun deleteSharesForExpense(expenseId: String)

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

    /** Replace an expense's fields and its full share set atomically (edit_expense). */
    @Transaction
    suspend fun replaceWithShares(expense: ExpenseEntity, shares: List<ShareEntity>) {
        deleteSharesForExpense(expense.id)
        upsert(expense)
        upsertShares(shares)
    }
}
