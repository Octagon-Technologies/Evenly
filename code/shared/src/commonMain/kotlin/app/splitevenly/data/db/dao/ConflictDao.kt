package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.ConflictEntity

/**
 * DAO for `conflicts` (03 §8). Sync-only: the retroactive "add to all past expenses" sweep that wrote
 * these rows was removed (2026-11-24), so nothing raises or shows one any more. The table stays because
 * the server copy cannot be dropped and existing rows still round-trip.
 */
@Dao
interface ConflictDao {

    @Upsert
    suspend fun upsert(conflict: ConflictEntity)

    @Upsert
    suspend fun upsertAll(conflicts: List<ConflictEntity>)

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM conflicts")
    suspend fun allForSync(): List<ConflictEntity>
}
