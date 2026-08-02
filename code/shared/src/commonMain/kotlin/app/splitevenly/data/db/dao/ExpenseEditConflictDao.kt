package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.ExpenseEditConflictEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `expense_edit_conflicts` — parked optimistic-concurrency losers awaiting a pick-a-side resolution. */
@Dao
interface ExpenseEditConflictDao {

    @Upsert
    suspend fun upsert(conflict: ExpenseEditConflictEntity)

    @Upsert
    suspend fun upsertAll(conflicts: List<ExpenseEditConflictEntity>)

    @Query("SELECT * FROM expense_edit_conflicts WHERE id = :id")
    suspend fun getById(id: String): ExpenseEditConflictEntity?

    /** Every local row (incl. resolved) — the push side of sync. */
    @Query("SELECT * FROM expense_edit_conflicts")
    suspend fun allForSync(): List<ExpenseEditConflictEntity>

    /** Unresolved parked edits for a group, oldest first — drives the conflicts UI. */
    @Query(
        """
        SELECT * FROM expense_edit_conflicts
        WHERE group_id = :groupId AND resolved_at IS NULL
        ORDER BY created_at ASC, id ASC
        """
    )
    fun observeUnresolved(groupId: String): Flow<List<ExpenseEditConflictEntity>>

    /** Count of unresolved parked edits across a group — for a badge. */
    @Query("SELECT COUNT(*) FROM expense_edit_conflicts WHERE group_id = :groupId AND resolved_at IS NULL")
    fun observeUnresolvedCount(groupId: String): Flow<Int>

    @Query("UPDATE expense_edit_conflicts SET resolved_at = :ts, resolution = :resolution, resolved_by = :resolvedBy WHERE id = :id")
    suspend fun resolve(id: String, resolution: String, resolvedBy: String?, ts: Long)
}
