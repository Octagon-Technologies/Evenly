package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.GroupEntity
import da.chelimo.sharecost.data.db.entity.MemberEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `groups` (02 §3.4). Also owns a few cross-table writes that must be atomic with a group. */
@Dao
interface GroupDao {

    @Upsert
    suspend fun upsert(group: GroupEntity)

    @Upsert
    suspend fun upsertAll(groups: List<GroupEntity>)

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun getById(id: String): GroupEntity?

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM groups")
    suspend fun allForSync(): List<GroupEntity>

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
          AND m.archived_at IS NULL
          AND g.deleted_at IS NULL
        ORDER BY g.created_at DESC
        """
    )
    fun observeGroupsForUser(userId: String): Flow<List<GroupEntity>>

    /** Home (Archived screen): groups where [userId] is active but has archived their view, newest first. */
    @Query(
        """
        SELECT g.* FROM groups g
        INNER JOIN members m ON m.group_id = g.id
        WHERE m.user_id = :userId
          AND m.status = 'ACTIVE'
          AND m.archived_at IS NOT NULL
          AND g.deleted_at IS NULL
        ORDER BY g.created_at DESC
        """
    )
    fun observeArchivedGroupsForUser(userId: String): Flow<List<GroupEntity>>

    /** Rotate a group's invite token (Group settings → "Rotate"). */
    @Query("UPDATE groups SET invite_token = :token, updated_at = :ts, row_version = row_version + 1 WHERE id = :id")
    suspend fun updateInviteToken(id: String, token: String, ts: Long)

    // --- Writes ---------------------------------------------------------------------------------

    /** Member upsert declared here so [createGroupWithAdmin] can write group + member atomically. */
    @Upsert
    suspend fun upsertMember(member: MemberEntity)

    @Query("UPDATE groups SET name = :name, updated_at = :ts, row_version = row_version + 1 WHERE id = :id")
    suspend fun updateName(id: String, name: String, ts: Long)

    /** Reassign (or clear, when abandoned) a group's admin. */
    @Query("UPDATE groups SET admin_user_id = :adminUserId, updated_at = :ts, row_version = row_version + 1 WHERE id = :groupId")
    suspend fun setGroupAdmin(groupId: String, adminUserId: String?, ts: Long)

    /** Soft-leave: mark a membership LEFT and drop any admin flag it held. */
    @Query("UPDATE members SET status = 'LEFT', left_at = :ts, is_admin = 0, updated_at = :ts, row_version = row_version + 1 WHERE id = :memberId")
    suspend fun markMemberLeft(memberId: String, ts: Long)

    /** Promote a member to admin (the inheritor when an admin leaves, 03 §7.5). */
    @Query("UPDATE members SET is_admin = 1, updated_at = :ts, row_version = row_version + 1 WHERE id = :memberId")
    suspend fun promoteMember(memberId: String, ts: Long)

    // --- Transactions ---------------------------------------------------------------------------

    /** Create a group and its creator membership (active admin) in one transaction. */
    @Transaction
    suspend fun createGroupWithAdmin(group: GroupEntity, admin: MemberEntity) {
        upsert(group)
        upsertMember(admin)
    }

    /**
     * Apply a member leaving (03 §7.5): mark them LEFT, and — only when the leaver was the admin
     * ([reassignAdmin]) — hand admin to the next member ([newAdminUserId]/[newAdminMemberId]) or
     * abandon the group (both null → admin_user_id = NULL, AC-INV-005). A non-admin leaving never
     * rewrites the group row. The caller picks the inheritor via `determineNextAdmin`.
     */
    @Transaction
    suspend fun applyLeave(
        leaverMemberId: String,
        groupId: String,
        reassignAdmin: Boolean,
        newAdminUserId: String?,
        newAdminMemberId: String?,
        ts: Long,
    ) {
        markMemberLeft(leaverMemberId, ts)
        if (reassignAdmin) {
            setGroupAdmin(groupId, newAdminUserId, ts)
            if (newAdminMemberId != null) promoteMember(newAdminMemberId, ts)
        }
    }
}
