package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.RowSyncStateEntity

/** Device-local per-row push/pull fingerprint tracker (see the entity). */
@Dao
interface RowSyncStateDao {

    @Query("SELECT * FROM row_sync_state WHERE table_name = :table")
    suspend fun forTable(table: String): List<RowSyncStateEntity>

    @Upsert
    suspend fun upsertAll(states: List<RowSyncStateEntity>)
}
