package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.GroupEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `groups` (02 §3.4). */
@Dao
interface GroupDao {

    @Upsert
    suspend fun upsert(group: GroupEntity)

    @Upsert
    suspend fun upsertAll(groups: List<GroupEntity>)

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun getById(id: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<GroupEntity?>

    /** Join flow: resolve an invite token to a live group (02 §3.4, invite_token UNIQUE). */
    @Query("SELECT * FROM groups WHERE invite_token = :token AND deleted_at IS NULL")
    suspend fun findByInviteToken(token: String): GroupEntity?

    /**
     * Home screen (U-2): the non-deleted groups where [userId] is an ACTIVE member, newest first.
     * The `members` join is what scopes the list to *this* user — there is no per-user column on
     * `groups`.
     */
    @Query(
        """
        SELECT g.* FROM groups g
        INNER JOIN members m ON m.group_id = g.id
        WHERE m.user_id = :userId
          AND m.status = 'ACTIVE'
          AND g.deleted_at IS NULL
        ORDER BY g.created_at DESC
        """
    )
    fun observeGroupsForUser(userId: String): Flow<List<GroupEntity>>
}
