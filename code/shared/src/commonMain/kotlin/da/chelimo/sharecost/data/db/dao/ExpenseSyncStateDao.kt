package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.ExpenseSyncStateEntity

/** Device-local base_version tracker for the expense optimistic-concurrency push (see the entity). */
@Dao
interface ExpenseSyncStateDao {

    @Upsert
    suspend fun upsert(state: ExpenseSyncStateEntity)

    @Upsert
    suspend fun upsertAll(states: List<ExpenseSyncStateEntity>)

    @Query("SELECT * FROM expense_sync_state")
    suspend fun all(): List<ExpenseSyncStateEntity>

    @Query("DELETE FROM expense_sync_state WHERE expense_id = :expenseId")
    suspend fun clear(expenseId: String)
}
