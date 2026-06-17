package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.ConflictEntity
import da.chelimo.sharecost.data.db.projection.ConflictWithExpenseRow
import kotlinx.coroutines.flow.Flow

/** DAO for `conflicts` (03 §8). `@Upsert` keeps retro-add idempotent on the (expense, user) index. */
@Dao
interface ConflictDao {

    @Upsert
    suspend fun upsert(conflict: ConflictEntity)

    @Upsert
    suspend fun upsertAll(conflicts: List<ConflictEntity>)

    @Query("SELECT * FROM conflicts WHERE id = :id")
    suspend fun getById(id: String): ConflictEntity?

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM conflicts")
    suspend fun allForSync(): List<ConflictEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM conflicts WHERE expense_id = :expenseId AND added_user_id = :userId)")
    suspend fun exists(expenseId: String, userId: String): Boolean

    /** Unresolved conflicts for a group, joined to their (live) expense, oldest first — drives the tab. */
    @Query(
        """
        SELECT c.id, c.group_id, c.expense_id, c.added_user_id, c.triggered_by_user_id, c.created_at,
               e.title AS expense_title, e.amount_subunits, e.currency
        FROM conflicts c
        INNER JOIN expenses e ON e.id = c.expense_id
        WHERE c.group_id = :groupId AND c.resolved_at IS NULL AND e.deleted_at IS NULL
        ORDER BY c.created_at ASC, c.id ASC
        """
    )
    fun observeUnresolved(groupId: String): Flow<List<ConflictWithExpenseRow>>

    @Query("UPDATE conflicts SET resolved_at = :now, resolution = :resolution WHERE id = :id")
    suspend fun resolve(id: String, resolution: String, now: Long)
}
