package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.HistoryEventEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `expense_history` (06 §3). Append-only — no update/delete paths. */
@Dao
interface HistoryEventDao {

    @Upsert
    suspend fun upsert(event: HistoryEventEntity)

    @Upsert
    suspend fun upsertAll(events: List<HistoryEventEntity>)

    /** Live event feed for an expense, newest first. */
    @Query("SELECT * FROM expense_history WHERE expense_id = :expenseId ORDER BY created_at DESC")
    fun observeByExpense(expenseId: String): Flow<List<HistoryEventEntity>>

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM expense_history")
    suspend fun allForSync(): List<HistoryEventEntity>
}
