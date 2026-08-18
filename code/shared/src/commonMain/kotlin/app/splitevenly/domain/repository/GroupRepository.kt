package app.splitevenly.domain.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.group.ClaimPreview
import app.splitevenly.domain.group.Conflict
import app.splitevenly.domain.group.DeletedGroup
import app.splitevenly.domain.group.Group
import app.splitevenly.domain.group.GroupDeleteImpact
import app.splitevenly.domain.group.Member
import app.splitevenly.domain.group.NewGroup
import app.splitevenly.domain.group.UnclaimedName
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
    suspend fun joinByToken(
        token: String,
        userId: UserId,
        claimPlaceholderId: UserId? = null,
    ): AppResult<Group>

    /** Leave a group; admin passes to the longest-tenured active member, else the group is abandoned. */
    suspend fun leaveGroup(
        groupId: GroupId,
        userId: UserId,
    ): AppResult<Unit>

    // --- Delete for everyone, undoable for 30 days ------------------------------------------------
    //
    // Distinct from leaving in the only way that matters: leaving removes YOU, deleting removes the
    // GROUP, for all of its members at once. That is the point — the alternative was messaging five
    // people individually to abandon a duplicate group. Because one member can now erase shared
    // financial history, every part of this is built to be visible and reversible: a tombstone rather
    // than a delete, 30 days in Recently deleted, a restore any member can perform, and a push telling
    // everyone who did it.

    /** The current user's groups deleted within the last 30 days, most recent first. */
    fun observeDeletedGroups(userId: UserId): Flow<List<DeletedGroup>>

    /**
     * [observeGroup] with the tombstone included, for the deleted-group gate only. Every other read
     * treats a deleted group as gone; this one has to render "Andrew deleted this group" for a member
     * who was sitting on that screen when the delete arrived.
     */
    fun observeGroupIncludingDeleted(groupId: GroupId): Flow<Group?>

    /** What a delete would cost, counted from the real group — the confirm sheet's facts. */
    suspend fun deleteImpact(groupId: GroupId): GroupDeleteImpact

    /**
     * Delete for everyone. Soft: stamps `deleted_at`/`deleted_by`, which syncs to every member's device
     * like any other tombstone. Members stay ACTIVE deliberately — see [observeDeletedGroups].
     */
    suspend fun deleteGroup(
        groupId: GroupId,
        userId: UserId,
    ): AppResult<Unit>

    /** Undo a delete, for everyone. Any active member may call it; whoever deleted it does not matter. */
    suspend fun restoreGroup(
        groupId: GroupId,
        userId: UserId,
    ): AppResult<Unit>

    /**
     * Hard-delete this device's copy of any group whose 30 days have elapsed, and return how many went.
     *
     * The one place in the app that destroys user data, sanctioned because by this point the group is
     * deleted for every member, unreachable from any screen, and past the window they had to change
     * their minds. Runs on its own timer rather than following the server, which by then has purged the
     * rows a pull would have needed to carry the news.
     */
    suspend fun purgeExpiredDeletedGroups(): Int

    /**
     * Rename a group, and optionally change its emoji (04 §2.3 `update_group`). A null [emoji] leaves
     * the current one in place, so a caller that only edits the name says nothing about the icon.
     */
    suspend fun renameGroup(
        groupId: GroupId,
        name: String,
        emoji: String? = null,
    ): AppResult<Group>

    /** Archive/unarchive a group for the calling user (per-member view, 04 §2.3 `set_archive`). */
    suspend fun setArchived(
        groupId: GroupId,
        userId: UserId,
        archived: Boolean,
    ): AppResult<Unit>

    /**
     * Add a placeholder participant (free-text name). They split in expenses but never sign in (01 §3.2).
     *
     * [createdBy] records who typed the name in, so the "Is this you?" question is never asked of the
     * person who created the name. Null only in tests and legacy call paths.
     */
    suspend fun addPlaceholder(
        groupId: GroupId,
        name: String,
        createdBy: UserId? = null,
    ): AppResult<Member>

    /** Remove a member (admin action). Soft-marks the membership LEFT; their past shares are untouched. */
    suspend fun removeMember(
        groupId: GroupId,
        userId: UserId,
    ): AppResult<Unit>

    /** Issue a fresh invite token, invalidating the old link (Group settings → "Rotate"). */
    suspend fun rotateInviteToken(groupId: GroupId): AppResult<Group>

    /**
     * Claim a placeholder as the real [realUserId] (03 §8 reconcile): reassigns the placeholder's
     * shares + paid expenses onto the real user and removes the placeholder membership. MVP assumes the
     * real user isn't already a participant in the same expenses (no per-expense share merge).
     */
    suspend fun reconcilePlaceholder(
        groupId: GroupId,
        placeholderUserId: UserId,
        realUserId: UserId,
    ): AppResult<Unit>

    /**
     * Names in [groupId] with expenses but no account that [userId] still has to answer: unclaimed, minus
     * the ones they have ruled out, minus the ones they created themselves. The single source for both the
     * "Is this you?" card and the full-screen list, so the two can never disagree.
     */
    fun observeUnclaimedNames(
        groupId: GroupId,
        userId: UserId,
    ): Flow<List<UnclaimedName>>

    /**
     * Record "these names are not me" for [userId] — permanently, and synced, so the answer survives a
     * reinstall and reaches their other devices. Idempotent: re-answering the same name is a no-op.
     */
    suspend fun answerNotMe(
        groupId: GroupId,
        placeholderUserIds: List<UserId>,
        userId: UserId,
    ): AppResult<Unit>

    /** The money a claim would move, for the confirm sheet. A claim never happens without showing this. */
    suspend fun claimPreview(
        groupId: GroupId,
        placeholderUserId: UserId,
        name: String,
    ): ClaimPreview

    /** Unresolved retroactive-member conflicts, oldest first — drives the Conflicts tab + its badge (03 §8). */
    fun observeConflicts(groupId: GroupId): Flow<List<Conflict>>

    /**
     * Add [memberUserId] to every past expense they aren't already in (03 §8.1, "all past"). EVEN
     * expenses are silently re-split to include them; non-EVEN expenses become [Conflict]s to resolve.
     * MVP assumes the affected expenses carry no prior settlements (same caveat as edit).
     */
    suspend fun addMemberToPastExpenses(
        groupId: GroupId,
        memberUserId: UserId,
        triggeredBy: UserId,
    ): AppResult<Unit>

    /**
     * Resolve one conflict (03 §8.4). [include] true → add the member with [newShareSubunits], reducing
     * the others proportionally so the total is unchanged; false → DISMISS, leaving them out.
     */
    suspend fun resolveConflict(
        conflictId: String,
        include: Boolean,
        newShareSubunits: Long? = null,
    ): AppResult<Unit>
}
