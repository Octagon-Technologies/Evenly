package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.MemberEntity
import da.chelimo.sharecost.data.db.projection.MemberWithUserRow
import kotlinx.coroutines.flow.Flow

/** DAO for `members` (02 §3.5). */
@Dao
interface MemberDao {

    @Upsert
    suspend fun upsert(member: MemberEntity)

    @Upsert
    suspend fun upsertAll(members: List<MemberEntity>)

    @Query("SELECT * FROM members WHERE group_id = :groupId AND user_id = :userId")
    suspend fun getMember(groupId: String, userId: String): MemberEntity?

    /** Every local row — the push side of sync. */
    @Query("SELECT * FROM members")
    suspend fun allForSync(): List<MemberEntity>

    @Query(
        """
        SELECT * FROM members
        WHERE group_id = :groupId AND status = 'ACTIVE'
        ORDER BY joined_at ASC
        """
    )
    fun observeActiveMembers(groupId: String): Flow<List<MemberEntity>>

    @Query("SELECT COUNT(*) FROM members WHERE group_id = :groupId AND status = 'ACTIVE'")
    suspend fun countActiveMembers(groupId: String): Int

    /**
     * Active members oldest-join first — feeds the admin-transfer rule (03 §7.5: admin passes to
     * the longest-tenured active member).
     */
    @Query(
        """
        SELECT * FROM members
        WHERE group_id = :groupId AND status = 'ACTIVE'
        ORDER BY joined_at ASC, id ASC
        """
    )
    suspend fun activeMembersByTenure(groupId: String): List<MemberEntity>

    /**
     * Active roster joined to each member's user for display (oldest-join first). LEFT JOIN so a
     * member whose `users` row has not synced yet still appears (null display name) — out-of-order
     * sync is expected (02 §7).
     */
    @Query(
        """
        SELECT m.user_id, u.display_name, COALESCE(u.is_placeholder, 0) AS is_placeholder,
               m.is_admin, m.joined_at,
               u.venmo_handle, u.cashapp_handle, u.paypal_handle, u.zelle_handle
        FROM members m
        LEFT JOIN users u ON u.id = m.user_id
        WHERE m.group_id = :groupId AND m.status = 'ACTIVE'
        ORDER BY m.joined_at ASC, m.id ASC
        """
    )
    fun observeActiveMembersWithUser(groupId: String): Flow<List<MemberWithUserRow>>

    /** Archive/unarchive a member's view of a group (04 §2.3 `set_archive`). */
    @Query("UPDATE members SET archived_at = :archivedAt, updated_at = :ts, row_version = row_version + 1 WHERE group_id = :groupId AND user_id = :userId")
    suspend fun setArchived(groupId: String, userId: String, archivedAt: Long?, ts: Long)

    /** Soft-remove a member by user (admin removes someone, or a placeholder is merged on reconcile). */
    @Query("UPDATE members SET status = 'LEFT', left_at = :ts, is_admin = 0, updated_at = :ts, row_version = row_version + 1 WHERE group_id = :groupId AND user_id = :userId")
    suspend fun markLeftByUser(groupId: String, userId: String, ts: Long)

    /**
     * Retire a placeholder membership because it was claimed/merged into a real user (reconcile, 03 §8).
     * Soft-leaves it *and* stamps `placeholder_claim_completed_at` so it is excluded from the active
     * roster (`status='ACTIVE'`) **and** from placeholder pickers ([UserDao.observePlaceholdersInGroup])
     * — without the stamp a claimed placeholder keeps reappearing as a still-pickable identity.
     */
    @Query(
        """
        UPDATE members
        SET status = 'LEFT', left_at = :ts, is_admin = 0, placeholder_claim_completed_at = :ts,
            updated_at = :ts, row_version = row_version + 1
        WHERE group_id = :groupId AND user_id = :userId
        """
    )
    suspend fun markClaimedByUser(groupId: String, userId: String, ts: Long)
}
