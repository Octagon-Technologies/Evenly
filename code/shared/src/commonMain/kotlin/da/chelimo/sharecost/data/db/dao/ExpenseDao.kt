package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `expenses` (02 §3.7). */
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
}
