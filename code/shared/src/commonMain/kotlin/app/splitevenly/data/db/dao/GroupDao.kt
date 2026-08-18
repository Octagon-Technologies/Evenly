package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.splitevenly.data.db.entity.GroupEntity
import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.projection.DeletedGroupRow
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

    /**
     * The same row, tombstone included. Only the deleted-group gate uses this: a member sitting on the
     * group screen when someone else's delete arrives needs to render "Andrew deleted this group"
     * rather than the blank screen a null would produce.
     */
    @Query("SELECT * FROM groups WHERE id = :id")
    fun observeByIdIncludingDeleted(id: String): Flow<GroupEntity?>

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
        """,
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
        """,
    )
    fun observeArchivedGroupsForUser(userId: String): Flow<List<GroupEntity>>

    /**
     * Home (Recently deleted): groups deleted in the last 30 days that [userId] is still an ACTIVE
     * member of, most-recently-deleted first.
     *
     * The membership filter is `ACTIVE` and stays that way: a group delete deliberately leaves every
     * `members` row alone, which is what keeps the group inside RLS reach *and* inside
     * `SyncEngine.pull`'s hydration set so all five members' devices learn about the delete. Someone
     * who had already LEFT the group before it was deleted is correctly not shown it.
     *
     * [cutoff] is `now - 30 days`; a row older than that is awaiting purge on both sides and must not
     * be offered for restore, because the server may already have purged it.
     */
    @Query(
        """
        SELECT g.id AS id, g.name AS name, g.emoji AS emoji,
               g.deleted_at AS deleted_at, g.deleted_by AS deleted_by,
               du.display_name AS deleted_by_name,
               (SELECT COUNT(*) FROM members m2 WHERE m2.group_id = g.id AND m2.status = 'ACTIVE') AS member_count
        FROM groups g
        INNER JOIN members m ON m.group_id = g.id
        LEFT JOIN users du ON du.id = g.deleted_by
        WHERE m.user_id = :userId
          AND m.status = 'ACTIVE'
          AND g.deleted_at IS NOT NULL
          AND g.deleted_at >= :cutoff
        ORDER BY g.deleted_at DESC
        """,
    )
    fun observeDeletedGroupsForUser(
        userId: String,
        cutoff: Long,
    ): Flow<List<DeletedGroupRow>>

    /** Groups whose 30-day window has elapsed on this device — the local purge's work list. */
    @Query("SELECT id FROM groups WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun idsPurgeableBefore(cutoff: Long): List<String>

    // --- Delete impact --------------------------------------------------------------------------
    // Counted from the real group so the confirm sheet states facts. Reads rather than writes, but
    // they live here beside the delete they exist for rather than being scattered across four DAOs.

    @Query("SELECT COUNT(*) FROM members WHERE group_id = :groupId AND status = 'ACTIVE'")
    suspend fun activeMemberCount(groupId: String): Int

    @Query("SELECT COUNT(*) FROM expenses WHERE group_id = :groupId AND deleted_at IS NULL")
    suspend fun liveExpenseCount(groupId: String): Int

    @Query("SELECT COUNT(*) FROM receipts WHERE group_id = :groupId AND deleted_at IS NULL")
    suspend fun liveReceiptCount(groupId: String): Int

    /**
     * How many debts in this group still have money on them. Same derivation as
     * `ShareDao.observeOutstandingShares` — remaining is `owed − applied`, never stored — narrowed to
     * a count of the debtor/creditor pairs, which is the unit a person recognises ("3 balances"),
     * not the share rows behind them.
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT s.user_id, e.payer_user_id FROM shares s
            INNER JOIN expenses e ON e.id = s.expense_id
            WHERE e.group_id = :groupId AND e.deleted_at IS NULL AND s.deleted_at IS NULL
              AND (e.payer_user_id IS NULL OR s.user_id <> e.payer_user_id)
              AND s.share_owed_subunits - COALESCE((
                    SELECT SUM(sa.applied_amount_subunits) FROM settlement_allocations sa
                    INNER JOIN settlements st ON st.id = sa.settlement_id
                    WHERE sa.share_id = s.id AND st.deleted_at IS NULL), 0) > 0
            GROUP BY s.user_id, e.payer_user_id
        )
        """,
    )
    suspend fun unsettledBalanceCount(groupId: String): Int

    /**
     * When this group's Evenly Pro pass runs out, or null if it has none live.
     *
     * Deliberately the *local* mirror rather than `group_pro_status`: the confirm sheet must render
     * offline, and it is telling the user something they cannot act on anyway (the pass survives the
     * purge and is not refunded), so a stale answer costs a sentence, not money.
     */
    @Query(
        """
        SELECT MAX(expires_at) FROM group_passes
        WHERE group_id = :groupId AND revoked_at IS NULL AND deleted_at IS NULL AND expires_at > :now
        """,
    )
    suspend fun liveProPassExpiry(
        groupId: String,
        now: Long,
    ): Long?

    /** Rotate a group's invite token (Group settings → "Rotate"). */
    @Query("UPDATE groups SET invite_token = :token, updated_at = :ts, row_version = row_version + 1 WHERE id = :id")
    suspend fun updateInviteToken(
        id: String,
        token: String,
        ts: Long,
    )

    // --- Writes ---------------------------------------------------------------------------------

    /** Member upsert declared here so [createGroupWithAdmin] can write group + member atomically. */
    @Upsert
    suspend fun upsertMember(member: MemberEntity)

    /**
     * Group identity edit. One statement rather than a name write plus an emoji write, so the pair
     * travels as a single `row_version` bump and cannot half-arrive at another device. A null [emoji]
     * leaves the existing one alone.
     */
    @Query(
        """
        UPDATE groups SET name = :name, emoji = COALESCE(:emoji, emoji), updated_at = :ts,
                          row_version = row_version + 1
        WHERE id = :id
        """,
    )
    suspend fun updateNameAndEmoji(
        id: String,
        name: String,
        emoji: String?,
        ts: Long,
    )

    /** Reassign (or clear, when abandoned) a group's admin. */
    @Query("UPDATE groups SET admin_user_id = :adminUserId, updated_at = :ts, row_version = row_version + 1 WHERE id = :groupId")
    suspend fun setGroupAdmin(
        groupId: String,
        adminUserId: String?,
        ts: Long,
    )

    /**
     * Delete for everyone: stamp the tombstone that syncs. Conditional on the group not already being
     * deleted, so two members tapping Delete at once produce one write and one no-op rather than a
     * second timestamp that would silently extend the 30-day window. Returns rows affected.
     */
    @Query(
        """
        UPDATE groups SET deleted_at = :ts, deleted_by = :deletedBy, updated_at = :ts,
                          row_version = row_version + 1
        WHERE id = :id AND deleted_at IS NULL
        """,
    )
    suspend fun softDelete(
        id: String,
        deletedBy: String,
        ts: Long,
    ): Int

    /** Undo the tombstone. Conditional for the same reason, so a double tap restores once. */
    @Query(
        """
        UPDATE groups SET deleted_at = NULL, deleted_by = NULL, updated_at = :ts,
                          row_version = row_version + 1
        WHERE id = :id AND deleted_at IS NOT NULL
        """,
    )
    suspend fun restore(
        id: String,
        ts: Long,
    ): Int

    /** Soft-leave: mark a membership LEFT and drop any admin flag it held. */
    @Query(
        "UPDATE members SET status = 'LEFT', left_at = :ts, is_admin = 0, updated_at = :ts, row_version = row_version + 1 WHERE id = :memberId",
    )
    suspend fun markMemberLeft(
        memberId: String,
        ts: Long,
    )

    /** Promote a member to admin (the inheritor when an admin leaves, 03 §7.5). */
    @Query("UPDATE members SET is_admin = 1, updated_at = :ts, row_version = row_version + 1 WHERE id = :memberId")
    suspend fun promoteMember(
        memberId: String,
        ts: Long,
    )

    // --- Transactions ---------------------------------------------------------------------------

    /** Create a group and its creator membership (active admin) in one transaction. */
    @Transaction
    suspend fun createGroupWithAdmin(
        group: GroupEntity,
        admin: MemberEntity,
    ) {
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
