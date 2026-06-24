package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.error.asErr
import da.chelimo.sharecost.core.error.asOk
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.allocate
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.data.db.computeExpenseStatus
import da.chelimo.sharecost.data.db.dao.ConflictDao
import da.chelimo.sharecost.data.db.dao.ExpenseDao
import da.chelimo.sharecost.data.db.dao.GroupDao
import da.chelimo.sharecost.data.db.dao.MemberDao
import da.chelimo.sharecost.data.db.dao.ShareDao
import da.chelimo.sharecost.data.db.dao.UserDao
import da.chelimo.sharecost.data.db.entity.ConflictEntity
import da.chelimo.sharecost.data.db.entity.GroupEntity
import da.chelimo.sharecost.data.db.entity.MemberEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.data.db.entity.UserEntity
import da.chelimo.sharecost.domain.group.Conflict
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.Member
import da.chelimo.sharecost.domain.group.MemberSnapshot
import da.chelimo.sharecost.domain.group.NewGroup
import da.chelimo.sharecost.domain.group.determineNextAdmin
import da.chelimo.sharecost.data.remote.supabase.RemoteGroupGateway
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
    private val userDao: UserDao,
    private val expenseDao: ExpenseDao,
    private val shareDao: ShareDao,
    private val conflictDao: ConflictDao,
    private val clock: Clock = Clock.System,
    // Server-side invite-token resolution (F7); null in tests / offline stub → local-cache-only behaviour.
    private val remoteGroups: RemoteGroupGateway? = null,
) : GroupRepository {

    override suspend fun addPlaceholder(groupId: GroupId, name: String): AppResult<Member> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return validationErr("name", AppError.Validation.Reason.Required)
        val now = clock.nowEpochMillis()
        val userId = newId()
        userDao.upsert(
            UserEntity(
                id = userId,
                isPlaceholder = true,
                displayName = trimmed,
                email = null,
                placeholderGroupId = groupId.value,
                createdAt = now,
                updatedAt = now,
            ),
        )
        memberDao.upsert(
            MemberEntity(
                id = newId(),
                groupId = groupId.value,
                userId = userId,
                status = MemberEntity.STATUS_ACTIVE,
                isAdmin = false,
                joinedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return Member(UserId(userId), displayName = trimmed, isPlaceholder = true, isAdmin = false, joinedAt = now).asOk()
    }

    override fun observeGroupsForUser(userId: UserId): Flow<List<Group>> =
        groupDao.observeGroupsForUser(userId.value).map { groups -> groups.map { it.toDomain() } }

    override fun observeArchivedGroupsForUser(userId: UserId): Flow<List<Group>> =
        groupDao.observeArchivedGroupsForUser(userId.value).map { groups -> groups.map { it.toDomain() } }

    override suspend fun findGroupByToken(token: String): Group? =
        (groupDao.findByInviteToken(token.trim()) ?: remoteGroups?.resolveByToken(token.trim()))?.toDomain()

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

    override suspend fun joinByToken(token: String, userId: UserId, claimPlaceholderId: UserId?): AppResult<Group> {
        // Resolve locally first; fall back to the server (F7) so a group this device never synced can
        // still be joined. Only a token that matches nowhere is a genuine "not found".
        val group = groupDao.findByInviteToken(token.trim())
            ?: remoteGroups?.resolveByToken(token.trim())
            ?: return AppError.Backend(
                status = null,
                code = "GROUP_NOT_FOUND",
                detail = "No group matches this invite link.",
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

        // Claim a placeholder identity as part of joining (the joiner picked "I'm Dave"): merge the
        // placeholder's history onto the real member so they don't have to reconcile manually. Guarded
        // to an actual placeholder of *this* group so a bad/forged id can't reassign an arbitrary user.
        if (claimPlaceholderId != null && claimPlaceholderId != userId) {
            val placeholder = userDao.getById(claimPlaceholderId.value)
            if (placeholder?.isPlaceholder == true && placeholder.placeholderGroupId == group.id) {
                reconcilePlaceholder(GroupId(group.id), claimPlaceholderId, userId)
            }
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

    override suspend fun removeMember(groupId: GroupId, userId: UserId): AppResult<Unit> {
        memberDao.getMember(groupId.value, userId.value)
            ?: return validationErr("member", AppError.Validation.Reason.Required)
        memberDao.markLeftByUser(groupId.value, userId.value, clock.nowEpochMillis())
        return AppResult.Ok(Unit)
    }

    override suspend fun rotateInviteToken(groupId: GroupId): AppResult<Group> {
        groupDao.updateInviteToken(groupId.value, newId(), clock.nowEpochMillis())
        val updated = groupDao.getById(groupId.value)
            ?: return validationErr("group", AppError.Validation.Reason.Required)
        return updated.toDomain().asOk()
    }

    override suspend fun reconcilePlaceholder(groupId: GroupId, placeholderUserId: UserId, realUserId: UserId): AppResult<Unit> {
        if (placeholderUserId == realUserId) return validationErr("user", AppError.Validation.Reason.Malformed)
        val now = clock.nowEpochMillis()
        // Move the placeholder's debts + paid expenses onto the real user, then retire the placeholder.
        // markClaimedByUser (not markLeftByUser) stamps placeholder_claim_completed_at so the merged
        // placeholder disappears from the roster *and* every placeholder picker — not just one of them.
        shareDao.reassignUserInGroup(groupId.value, placeholderUserId.value, realUserId.value, now)
        expenseDao.reassignPayerInGroup(groupId.value, placeholderUserId.value, realUserId.value, now)
        memberDao.markClaimedByUser(groupId.value, placeholderUserId.value, now)
        return AppResult.Ok(Unit)
    }

    override fun observeConflicts(groupId: GroupId): Flow<List<Conflict>> =
        conflictDao.observeUnresolved(groupId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun addMemberToPastExpenses(
        groupId: GroupId,
        memberUserId: UserId,
        triggeredBy: UserId,
    ): AppResult<Unit> {
        val now = clock.nowEpochMillis()
        for (e in expenseDao.getActiveByGroup(groupId.value)) {
            val shares = shareDao.getByExpense(e.id)
            if (shares.isEmpty() || shares.any { it.userId == memberUserId.value }) continue
            if (e.splitMode == "EVEN") {
                // Silently re-split evenly across the existing participants + the new member (03 §8.1).
                val ids = shares.map { it.userId } + memberUserId.value
                val owed = allocate(e.amountSubunits, ids.map { UserId(it) to 1L })
                val reShared = ids.map { uid ->
                    val o = owed.getValue(UserId(uid))
                    ShareEntity(newId(), e.id, uid, shareOwedSubunits = o, remainingSubunits = o, createdAt = now, updatedAt = now)
                }
                val status = computeExpenseStatus(deletedAt = null, sumRemainingSubunits = reShared.sumOf { it.remainingSubunits })
                expenseDao.replaceWithShares(e.copy(status = status, updatedAt = now, rowVersion = e.rowVersion + 1), reShared)
            } else if (!conflictDao.exists(e.id, memberUserId.value)) {
                conflictDao.upsert(
                    ConflictEntity(
                        id = newId(),
                        groupId = groupId.value,
                        expenseId = e.id,
                        addedUserId = memberUserId.value,
                        triggeredByUserId = triggeredBy.value,
                        createdAt = now,
                    ),
                )
            }
        }
        return AppResult.Ok(Unit)
    }

    override suspend fun resolveConflict(conflictId: String, include: Boolean, newShareSubunits: Long?): AppResult<Unit> {
        val conflict = conflictDao.getById(conflictId)
            ?: return validationErr("conflict", AppError.Validation.Reason.Required)
        if (conflict.resolvedAt != null) return AppResult.Ok(Unit) // already resolved — idempotent
        val now = clock.nowEpochMillis()

        if (!include) {
            conflictDao.resolve(conflictId, "DISMISS", now)
            return AppResult.Ok(Unit)
        }

        val share = newShareSubunits ?: return validationErr("share", AppError.Validation.Reason.Required)
        val expense = expenseDao.getById(conflict.expenseId)
            ?: return validationErr("expense", AppError.Validation.Reason.Required)
        if (share <= 0L || share >= expense.amountSubunits) {
            return validationErr("share", AppError.Validation.Reason.OutOfRange)
        }
        val existing = shareDao.getByExpense(conflict.expenseId)
        if (existing.any { it.userId == conflict.addedUserId }) { // already a participant — just close it
            conflictDao.resolve(conflictId, "INCLUDE", now)
            return AppResult.Ok(Unit)
        }
        // Shrink the existing participants proportionally to free up the new member's share (03 §8.4).
        val reallocated = allocate(expense.amountSubunits - share, existing.map { UserId(it.userId) to it.shareOwedSubunits })
        val updated = existing.map { s ->
            val o = reallocated.getValue(UserId(s.userId))
            s.copy(shareOwedSubunits = o, remainingSubunits = o, updatedAt = now)
        } + ShareEntity(newId(), conflict.expenseId, conflict.addedUserId, shareOwedSubunits = share, remainingSubunits = share, createdAt = now, updatedAt = now)
        val status = computeExpenseStatus(deletedAt = null, sumRemainingSubunits = updated.sumOf { it.remainingSubunits })
        expenseDao.replaceWithShares(expense.copy(status = status, updatedAt = now, rowVersion = expense.rowVersion + 1), updated)
        conflictDao.resolve(conflictId, "INCLUDE", now)
        return AppResult.Ok(Unit)
    }

    private fun validationErr(field: String, reason: AppError.Validation.Reason): AppResult<Nothing> =
        AppError.Validation(mapOf(field to reason)).asErr()
}
