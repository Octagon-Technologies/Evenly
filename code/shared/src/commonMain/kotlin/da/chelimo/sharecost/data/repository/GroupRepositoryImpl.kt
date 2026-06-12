package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.error.asErr
import da.chelimo.sharecost.core.error.asOk
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.data.db.dao.GroupDao
import da.chelimo.sharecost.data.db.dao.MemberDao
import da.chelimo.sharecost.data.db.entity.GroupEntity
import da.chelimo.sharecost.data.db.entity.MemberEntity
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.Member
import da.chelimo.sharecost.domain.group.MemberSnapshot
import da.chelimo.sharecost.domain.group.NewGroup
import da.chelimo.sharecost.domain.group.determineNextAdmin
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Local-first [GroupRepository] (04 §6.2/§6.3). Reads stream from Room; writes land in Room
 * immediately with a client-generated UUIDv7 id and stamped timestamps (steps 1–4 of the write
 * path). Pushing the mutation to Supabase (steps 5–7) is the sync worker's job — wired in S-1.
 */
@OptIn(ExperimentalTime::class)
class GroupRepositoryImpl(
    private val groupDao: GroupDao,
    private val memberDao: MemberDao,
    private val clock: Clock = Clock.System,
) : GroupRepository {

    override fun observeGroupsForUser(userId: UserId): Flow<List<Group>> =
        groupDao.observeGroupsForUser(userId.value).map { groups -> groups.map { it.toDomain() } }

    override fun observeGroup(groupId: GroupId): Flow<Group?> =
        groupDao.observeById(groupId.value).map { it?.toDomain() }

    override fun observeMembers(groupId: GroupId): Flow<List<Member>> =
        memberDao.observeActiveMembersWithUser(groupId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun createGroup(input: NewGroup): AppResult<Group> {
        val name = input.name.trim()
        if (name.isEmpty()) return validationErr("name", AppError.Validation.Reason.Required)

        val now = clock.nowEpochMillis()
        val groupId = newId()
        val creator = input.creatorUserId.value
        val group = GroupEntity(
            id = groupId,
            name = name,
            emoji = input.emoji,
            baseCurrency = input.baseCurrency,
            adminUserId = creator,
            inviteToken = newId(),
            createdAt = now,
            createdBy = creator,
            updatedAt = now,
        )
        val admin = MemberEntity(
            id = newId(),
            groupId = groupId,
            userId = creator,
            status = MemberEntity.STATUS_ACTIVE,
            isAdmin = true,
            joinedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        groupDao.createGroupWithAdmin(group, admin)
        return group.toDomain().asOk()
    }

    override suspend fun joinByToken(token: String, userId: UserId): AppResult<Group> {
        // MVP resolves against the local cache only; a never-synced group needs the server (S-1).
        val group = groupDao.findByInviteToken(token)
            ?: return AppError.Backend(
                status = null,
                code = "GROUP_NOT_CACHED",
                detail = "Group is not in the local cache; joining it requires sync (S-1).",
            ).asErr()

        val existing = memberDao.getMember(group.id, userId.value)
        if (existing == null || existing.status != MemberEntity.STATUS_ACTIVE) {
            val now = clock.nowEpochMillis()
            val member = (existing ?: MemberEntity(
                id = newId(),
                groupId = group.id,
                userId = userId.value,
                joinedAt = now,
                createdAt = now,
                updatedAt = now,
            )).copy(status = MemberEntity.STATUS_ACTIVE, leftAt = null, updatedAt = now)
            memberDao.upsert(member)
        }
        return group.toDomain().asOk()
    }

    override suspend fun leaveGroup(groupId: GroupId, userId: UserId): AppResult<Unit> {
        val group = groupDao.getById(groupId.value)
            ?: return validationErr("group", AppError.Validation.Reason.Required)
        val leaver = memberDao.getMember(groupId.value, userId.value)
            ?: return validationErr("member", AppError.Validation.Reason.Required)

        val now = clock.nowEpochMillis()
        val leaverWasAdmin = group.adminUserId == userId.value

        if (!leaverWasAdmin) {
            groupDao.applyLeave(
                leaverMemberId = leaver.id,
                groupId = groupId.value,
                reassignAdmin = false,
                newAdminUserId = null,
                newAdminMemberId = null,
                ts = now,
            )
            return AppResult.Ok(Unit)
        }

        // Admin leaving: longest-tenured remaining active member inherits, else group is abandoned.
        val activeMembers = memberDao.activeMembersByTenure(groupId.value)
        val snapshots = activeMembers.map {
            MemberSnapshot(userId = UserId(it.userId), joinedAt = it.joinedAt, isActive = true)
        }
        val nextAdminUserId = determineNextAdmin(userId, snapshots)
        val nextAdminMember = nextAdminUserId?.let { uid -> activeMembers.firstOrNull { it.userId == uid.value } }
        groupDao.applyLeave(
            leaverMemberId = leaver.id,
            groupId = groupId.value,
            reassignAdmin = true,
            newAdminUserId = nextAdminUserId?.value,
            newAdminMemberId = nextAdminMember?.id,
            ts = now,
        )
        return AppResult.Ok(Unit)
    }

    override suspend fun renameGroup(groupId: GroupId, name: String): AppResult<Group> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return validationErr("name", AppError.Validation.Reason.Required)
        groupDao.updateName(groupId.value, trimmed, clock.nowEpochMillis())
        val updated = groupDao.getById(groupId.value)
            ?: return validationErr("group", AppError.Validation.Reason.Required)
        return updated.toDomain().asOk()
    }

    override suspend fun setArchived(groupId: GroupId, userId: UserId, archived: Boolean): AppResult<Unit> {
        val now = clock.nowEpochMillis()
        memberDao.setArchived(groupId.value, userId.value, archivedAt = if (archived) now else null, ts = now)
        return AppResult.Ok(Unit)
    }

    private fun validationErr(field: String, reason: AppError.Validation.Reason): AppResult<Nothing> =
        AppError.Validation(mapOf(field to reason)).asErr()
}
