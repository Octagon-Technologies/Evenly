package da.chelimo.sharecost.domain.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
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

    /** The current user's active, non-deleted groups, newest first (U-2). */
    fun observeGroupsForUser(userId: UserId): Flow<List<Group>>

    /** A single group, or null once it is deleted. */
    fun observeGroup(groupId: GroupId): Flow<Group?>

    /** Active members of a group, joined to their user for display, oldest-join first. */
    fun observeMembers(groupId: GroupId): Flow<List<Member>>

    /** Create a group; the creator becomes the first active member and its admin (03 §7.5). */
    suspend fun createGroup(input: NewGroup): AppResult<Group>

    /**
     * Join a group by invite token (04 §2.3 `join_group_by_token`). MVP resolves against the local
     * cache only; joining a group the device has never synced requires the server (S-1).
     */
    suspend fun joinByToken(token: String, userId: UserId): AppResult<Group>

    /** Leave a group; admin passes to the longest-tenured active member, else the group is abandoned. */
    suspend fun leaveGroup(groupId: GroupId, userId: UserId): AppResult<Unit>

    /** Rename a group (04 §2.3 `update_group`). */
    suspend fun renameGroup(groupId: GroupId, name: String): AppResult<Group>

    /** Archive/unarchive a group for the calling user (per-member view, 04 §2.3 `set_archive`). */
    suspend fun setArchived(groupId: GroupId, userId: UserId, archived: Boolean): AppResult<Unit>
}
