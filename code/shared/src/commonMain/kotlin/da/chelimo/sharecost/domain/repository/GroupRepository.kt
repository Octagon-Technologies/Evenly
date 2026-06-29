package da.chelimo.sharecost.domain.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.group.Conflict
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.Member
import da.chelimo.sharecost.domain.group.NewGroup
import kotlinx.coroutines.flow.Flow

/**
 * Groups + membership (06 §3.1). Read surface is `Flow` off Room (local-first, always works
 * offline); write surface is `suspend` returning [AppResult] (06 §9.2). Writes are optimistic —
 * they land in Room immediately (04 §6.2 steps 1–4); pushing to Supabase is the sync worker's job
 * (S-1), so these never block on the network.
 */
interface GroupRepository {

    /** The current user's active, non-archived, non-deleted groups, newest first (U-2). */
    fun observeGroupsForUser(userId: UserId): Flow<List<Group>>

    /** The current user's archived groups (per-member view), newest first — the Archived screen. */
    fun observeArchivedGroupsForUser(userId: UserId): Flow<List<Group>>

    /** Resolve an invite token to its group for a join preview (local cache; null if not synced). */
    suspend fun findGroupByToken(token: String): Group?

    /** A single group, or null once it is deleted. */
    fun observeGroup(groupId: GroupId): Flow<Group?>

    /** Active members of a group, joined to their user for display, oldest-join first. */
    fun observeMembers(groupId: GroupId): Flow<List<Member>>

    /** Total bytes of this group's live (non-deleted) receipts — drives the Storage section. */
    fun observeStorageUsedBytes(groupId: GroupId): Flow<Long>

    /** Create a group; the creator becomes the first active member and its admin (03 §7.5). */
    suspend fun createGroup(input: NewGroup): AppResult<Group>

    /**
     * Join a group by invite token (04 §2.3 `join_group_by_token`). Resolves against the local cache,
     * falling back to the server for a group this device never synced (F7).
     *
     * If [claimPlaceholderId] is a placeholder of this group, the joiner *claims* that identity as part
     * of joining: the placeholder's shares + paid expenses merge onto [userId] and the placeholder is
     * retired (same merge as [reconcilePlaceholder]) — so a friend who was tracked as "Dave" doesn't
     * have to reconcile manually in Group settings afterwards. Null → join as a brand-new member.
     */
    suspend fun joinByToken(token: String, userId: UserId, claimPlaceholderId: UserId? = null): AppResult<Group>

    /** Leave a group; admin passes to the longest-tenured active member, else the group is abandoned. */
    suspend fun leaveGroup(groupId: GroupId, userId: UserId): AppResult<Unit>

    /** Rename a group (04 §2.3 `update_group`). */
    suspend fun renameGroup(groupId: GroupId, name: String): AppResult<Group>

    /** Archive/unarchive a group for the calling user (per-member view, 04 §2.3 `set_archive`). */
    suspend fun setArchived(groupId: GroupId, userId: UserId, archived: Boolean): AppResult<Unit>

    /** Add a placeholder participant (free-text name). They split in expenses but never sign in (01 §3.2). */
    suspend fun addPlaceholder(groupId: GroupId, name: String): AppResult<Member>

    /** Remove a member (admin action). Soft-marks the membership LEFT; their past shares are untouched. */
    suspend fun removeMember(groupId: GroupId, userId: UserId): AppResult<Unit>

    /** Issue a fresh invite token, invalidating the old link (Group settings → "Rotate"). */
    suspend fun rotateInviteToken(groupId: GroupId): AppResult<Group>

    /**
     * Claim a placeholder as the real [realUserId] (03 §8 reconcile): reassigns the placeholder's
     * shares + paid expenses onto the real user and removes the placeholder membership. MVP assumes the
     * real user isn't already a participant in the same expenses (no per-expense share merge).
     */
    suspend fun reconcilePlaceholder(groupId: GroupId, placeholderUserId: UserId, realUserId: UserId): AppResult<Unit>

    /** Unresolved retroactive-member conflicts, oldest first — drives the Conflicts tab + its badge (03 §8). */
    fun observeConflicts(groupId: GroupId): Flow<List<Conflict>>

    /**
     * Add [memberUserId] to every past expense they aren't already in (03 §8.1, "all past"). EVEN
     * expenses are silently re-split to include them; non-EVEN expenses become [Conflict]s to resolve.
     * MVP assumes the affected expenses carry no prior settlements (same caveat as edit).
     */
    suspend fun addMemberToPastExpenses(groupId: GroupId, memberUserId: UserId, triggeredBy: UserId): AppResult<Unit>

    /**
     * Resolve one conflict (03 §8.4). [include] true → add the member with [newShareSubunits], reducing
     * the others proportionally so the total is unchanged; false → DISMISS, leaving them out.
     */
    suspend fun resolveConflict(conflictId: String, include: Boolean, newShareSubunits: Long? = null): AppResult<Unit>
}
