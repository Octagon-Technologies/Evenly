package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.ExpenseItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `expense_items` — the line items of a "Split the bill" expense. Soft-delete only (Rule 1):
 * an item removed from the bill is tombstoned, never hard-deleted, so the removal syncs.
 */
@Dao
interface ExpenseItemDao {

    @Upsert
    suspend fun upsert(item: ExpenseItemEntity)

    @Upsert
    suspend fun upsertAll(items: List<ExpenseItemEntity>)

    /** The bill's **active** items in receipt order (excludes tombstones). */
    @Query("SELECT * FROM expense_items WHERE expense_id = :expenseId AND deleted_at IS NULL ORDER BY sort_order ASC")
    suspend fun getByExpense(expenseId: String): List<ExpenseItemEntity>

    /** Reactive active items for the edit/claim screens. */
    @Query("SELECT * FROM expense_items WHERE expense_id = :expenseId AND deleted_at IS NULL ORDER BY sort_order ASC")
    fun observeByExpense(expenseId: String): Flow<List<ExpenseItemEntity>>

    /** Every local row incl. tombstones — the push side of sync. */
    @Query("SELECT * FROM expense_items")
    suspend fun allForSync(): List<ExpenseItemEntity>

    /** Soft-delete items removed by an edit (Rule 1): tombstone so the removal syncs. */
    @Query("UPDATE expense_items SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun softDeleteByIds(ids: List<String>, ts: Long)
}
