package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.GroupPassEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `group_passes` — Evenly Pro (`PRO_PASS_SPEC.md`).
 *
 * Read plus upsert-from-pull only. There is no local write path and no `allForSync`, and that omission
 * is the point: `allForSync` is what `SyncEngine.push` consumes, so not having one makes "this table is
 * pull-only" a fact of the type system rather than a comment someone has to notice.
 */
@Dao
interface GroupPassDao {

    /** Landing rows from a pull. The server is the only source of these. */
    @Upsert
    suspend fun upsertAll(passes: List<GroupPassEntity>)

    /**
     * Every pass a group has ever held, newest expiry first. Deliberately NOT filtered on "still live":
     * the caller filters by a clock it passes in, so a screen that has been open across an expiry
     * re-evaluates instead of holding a `true` that a database query decided minutes ago.
     */
    @Query("SELECT * FROM group_passes WHERE group_id = :groupId AND deleted_at IS NULL ORDER BY expires_at DESC")
    fun observeForGroup(groupId: String): Flow<List<GroupPassEntity>>

    @Query("SELECT * FROM group_passes WHERE group_id = :groupId AND deleted_at IS NULL ORDER BY expires_at DESC")
    suspend fun forGroup(groupId: String): List<GroupPassEntity>
}
