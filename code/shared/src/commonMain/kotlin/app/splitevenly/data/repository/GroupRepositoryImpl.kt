package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.error.asErr
import app.splitevenly.core.error.asOk
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.allocate
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.db.ExpenseStatus
import app.splitevenly.data.db.dao.ConflictDao
import app.splitevenly.data.db.dao.ExpenseDao
import app.splitevenly.data.db.dao.GroupDao
import app.splitevenly.data.db.dao.MemberDao
import app.splitevenly.data.db.dao.PlaceholderClaimAnswerDao
import app.splitevenly.data.db.dao.PlaceholderMergeDao
import app.splitevenly.data.db.dao.ReceiptDao
import app.splitevenly.data.db.dao.ShareDao
import app.splitevenly.data.db.dao.UserDao
import app.splitevenly.data.db.entity.ConflictEntity
import app.splitevenly.data.db.entity.GroupEntity
import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.entity.PlaceholderClaimAnswerEntity
import app.splitevenly.data.db.entity.ShareEntity
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.domain.group.ClaimLine
import app.splitevenly.domain.group.ClaimPreview
import app.splitevenly.domain.group.Conflict
import app.splitevenly.domain.group.Group
import app.splitevenly.domain.group.Member
import app.splitevenly.domain.group.MemberSnapshot
import app.splitevenly.domain.group.NewGroup
import app.splitevenly.domain.group.UnclaimedName
import app.splitevenly.domain.group.determineNextAdmin
import app.splitevenly.data.remote.supabase.RemoteGroupGateway
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.newId
import app.splitevenly.platform.EvAnalytics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
    // NOT an optional ctor dep: the placeholder merge is a money-correctness path, and a null-defaulted
    // "legacy behaviour" fallback here would be the blind reassignment this replaced.
    private val placeholderMergeDao: PlaceholderMergeDao,
    private val claimAnswerDao: PlaceholderClaimAnswerDao,
    private val clock: Clock = Clock.System,
    // Server-side invite-token resolution (F7); null in tests / offline stub → local-cache-only behaviour.
    private val remoteGroups: RemoteGroupGateway? = null,
    // Receipt sizes for the Storage section; null in tests → reports 0 bytes (optional-ctor-dep pattern).
    private val receiptDao: ReceiptDao? = null,
    // Analytics: null in unit tests (no PostHog context); production DI passes AndroidAnalytics.
    private val analytics: EvAnalytics? = null,
) : GroupRepository {

    override suspend fun addPlaceholder(groupId: GroupId, name: String, createdBy: UserId?): AppResult<Member> {
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
                // Stamped here rather than inferred later: inferring the creator from the payer of the
                // earliest expense the name appears in is wrong whenever someone adds a person to a bill
                // they didn't pay for.
                createdBy = createdBy?.value,
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
        analytics?.capture("placeholder_added")
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

    override fun observeStorageUsedBytes(groupId: GroupId): Flow<Long> =
        receiptDao?.observeTotalSizeBytesByGroup(groupId.value) ?: flowOf(0L)

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
        analytics?.capture("group_created")
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
        analytics?.capture("group_joined")
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
            analytics?.capture("group_left")
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
        analytics?.capture("group_left")
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
        // The whole identity moves in ONE transaction — shares (folding collisions), settlements and their
        // allocations, the itemized claims/shares/participants, the parent expenses' causal split_version,
        // and finally the members row. See [PlaceholderMergeDao]: a half-applied merge is silently wrong
        // money across several people's balances, which is worse than a merge that didn't run.
        placeholderMergeDao.mergePlaceholder(groupId.value, placeholderUserId.value, realUserId.value, now)
        return AppResult.Ok(Unit)
    }

    override fun observeUnclaimedNames(groupId: GroupId, userId: UserId): Flow<List<UnclaimedName>> =
        claimAnswerDao.observeUnansweredNames(groupId.value, userId.value).map { rows ->
            rows.map { r ->
                UnclaimedName(
                    userId = UserId(r.userId),
                    displayName = r.displayName,
                    expenseCount = r.expenseCount,
                    owedSubunits = r.owedSubunits,
                    // Summing across currencies is meaningless, so a multi-currency name shows its count
                    // alone and the card drops the amount rather than printing a wrong one.
                    currency = r.currency.takeIf { r.currencyCount == 1 },
                    multiCurrency = r.currencyCount > 1,
                )
            }
        }

    override suspend fun answerNotMe(
        groupId: GroupId,
        placeholderUserIds: List<UserId>,
        userId: UserId,
    ): AppResult<Unit> {
        if (placeholderUserIds.isEmpty()) return AppResult.Ok(Unit)
        val now = clock.nowEpochMillis()
        // Deterministic id from (group, name, answerer): the same answer made twice — offline on two
        // devices, or a re-tap — is one row, on the client and on the server's unique key alike.
        val answered = claimAnswerDao.answersOf(groupId.value, userId.value).associateBy { it.placeholderUserId }
        claimAnswerDao.upsertAll(
            placeholderUserIds.distinct().map { ph ->
                val existing = answered[ph.value]
                PlaceholderClaimAnswerEntity(
                    id = existing?.id ?: "${groupId.value}__${ph.value}__${userId.value}",
                    groupId = groupId.value,
                    placeholderUserId = ph.value,
                    answeredByUserId = userId.value,
                    answeredAt = existing?.answeredAt ?: now,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    rowVersion = (existing?.rowVersion ?: 0L) + 1,
                )
            },
        )
        analytics?.capture("placeholder_not_me")
        return AppResult.Ok(Unit)
    }

    override suspend fun claimPreview(groupId: GroupId, placeholderUserId: UserId, name: String): ClaimPreview {
        val owed = claimAnswerDao.owedLines(groupId.value, placeholderUserId.value)
            .map { ClaimLine(it.title, it.amountSubunits, it.currency) }
        val paid = claimAnswerDao.paidLines(groupId.value, placeholderUserId.value)
            .map { ClaimLine(it.title, it.amountSubunits, it.currency) }
        val currencies = (owed + paid).mapTo(HashSet()) { it.currency }
        return ClaimPreview(
            name = name,
            owed = owed,
            paid = paid,
            owedTotalSubunits = owed.sumOf { it.amountSubunits },
            // One total only makes sense in one currency; across several there is no number to print.
            currency = currencies.singleOrNull(),
        )
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
                // The merge preserves each existing participant's share id, so their settlement
                // allocations survive and remaining re-derives — only the new member's share is added.
                val ids = shares.map { it.userId } + memberUserId.value
                val owed = allocate(e.amountSubunits, ids.map { UserId(it) to 1L })
                val desired = ids.map { uid -> DesiredShare(uid, owed.getValue(UserId(uid))) }
                val (merged, removed) = mergeShares(shares, desired, e.id, now)
                // Re-splitting IS a Zone-2 change: advance the causal split_version so merge_expense
                // applies the new split instead of reverting it as metadata-only (P0 #1).
                expenseDao.replaceWithShares(
                    e.copy(
                        status = ExpenseStatus.ACTIVE, updatedAt = now, rowVersion = e.rowVersion + 1,
                        splitVersion = e.splitVersion + 1, splitUpdatedBy = triggeredBy.value,
                    ),
                    merged, removed, now,
                )
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
        // mergeShares keeps each existing participant's id (allocations survive) and adds the new member.
        val reallocated = allocate(expense.amountSubunits - share, existing.map { UserId(it.userId) to it.shareOwedSubunits })
        val desired = existing.map { s -> DesiredShare(s.userId, reallocated.getValue(UserId(s.userId))) } +
            DesiredShare(conflict.addedUserId, share)
        val (merged, removed) = mergeShares(existing, desired, conflict.expenseId, now)
        // Including the member reshapes every share — a Zone-2 change — so advance the causal split_version
        // (attributed to whoever added them); otherwise merge_expense reverts it as metadata-only (P0 #1).
        expenseDao.replaceWithShares(
            expense.copy(
                status = ExpenseStatus.ACTIVE, updatedAt = now, rowVersion = expense.rowVersion + 1,
                splitVersion = expense.splitVersion + 1, splitUpdatedBy = conflict.triggeredByUserId,
            ),
            merged, removed, now,
        )
        conflictDao.resolve(conflictId, "INCLUDE", now)
        return AppResult.Ok(Unit)
    }

    private fun validationErr(field: String, reason: AppError.Validation.Reason): AppResult<Nothing> =
        AppError.Validation(mapOf(field to reason)).asErr()
}
