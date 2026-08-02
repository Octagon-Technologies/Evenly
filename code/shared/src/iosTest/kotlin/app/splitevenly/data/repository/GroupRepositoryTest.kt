package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.GroupEntity
import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.entity.ShareEntity
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.data.remote.supabase.RemoteGroupGateway
import app.splitevenly.domain.group.Group
import app.splitevenly.domain.group.NewGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [GroupRepositoryImpl]: create (group + admin member atomically), the home list, rename,
 * archive, join-by-token, and the admin-transfer-on-leave rule (03 §7.5, including group abandonment).
 */
class GroupRepositoryTest {

    private lateinit var db: EvenlyDatabase
    private lateinit var repo: GroupRepositoryImpl

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        repo = GroupRepositoryImpl(db.groupDao(), db.memberDao(), db.userDao(), db.expenseDao(), db.shareDao(), db.conflictDao(), db.placeholderMergeDao(), clockAt("2026-06-12"))
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun create(name: String = "Trip", creator: String = "u1"): Group {
        val result = repo.createGroup(NewGroup(name = name, baseCurrency = "USD", creatorUserId = UserId(creator)))
        assertTrue(result is AppResult.Ok, "createGroup should succeed")
        return result.value
    }

    private suspend fun addMember(groupId: GroupId, userId: String, joinedAt: Long, admin: Boolean = false) {
        db.memberDao().upsert(
            MemberEntity(
                id = "m_$userId",
                groupId = groupId.value,
                userId = userId,
                isAdmin = admin,
                joinedAt = joinedAt,
                createdAt = joinedAt,
                updatedAt = joinedAt,
            )
        )
    }

    @Test
    fun createGroup_persistsGroupAndAdminMember() = runTest {
        val group = create()
        assertEquals("Trip", group.name)
        assertEquals(UserId("u1"), group.adminUserId)

        assertNotNull(repo.observeGroup(group.id).first())
        val members = repo.observeMembers(group.id).first()
        assertEquals(1, members.size)
        assertEquals(UserId("u1"), members.single().userId)
        assertTrue(members.single().isAdmin)

        assertEquals(listOf(group.id), repo.observeGroupsForUser(UserId("u1")).first().map { it.id })
    }

    @Test
    fun createGroup_blankName_returnsValidation() = runTest {
        val result = repo.createGroup(NewGroup(name = "   ", baseCurrency = "USD", creatorUserId = UserId("u1")))
        assertTrue(result is AppResult.Err)
        assertTrue((result.error as AppError.Validation).fieldErrors.containsKey("name"))
    }

    @Test
    fun renameGroup_updatesName() = runTest {
        val group = create()
        val renamed = repo.renameGroup(group.id, "Ski Trip")
        assertTrue(renamed is AppResult.Ok)
        assertEquals("Ski Trip", renamed.value.name)
        assertEquals("Ski Trip", repo.observeGroup(group.id).first()?.name)
    }

    @Test
    fun joinByToken_addsMemberFromLocalCache() = runTest {
        val group = create()
        val joined = repo.joinByToken(group.inviteToken, UserId("u2"))
        assertTrue(joined is AppResult.Ok)
        assertEquals(group.id, joined.value.id)
        assertEquals(setOf("u1", "u2"), repo.observeMembers(group.id).first().map { it.userId.value }.toSet())
    }

    @Test
    fun joinByToken_unknownToken_returnsBackendError() = runTest {
        // With no remote gateway wired (local-only), a token that matches nothing is GROUP_NOT_FOUND (F7).
        val result = repo.joinByToken("nope", UserId("u2"))
        assertTrue(result is AppResult.Err)
        assertEquals("GROUP_NOT_FOUND", (result.error as AppError.Backend).code)
    }

    @Test
    fun joinByToken_neverSyncedGroup_resolvesViaServer() = runTest {
        // A token absent from the local cache resolves through the remote gateway (F7 cross-device join).
        val gateway = FakeRemoteGroupGateway(db)
        val withRemote = GroupRepositoryImpl(
            db.groupDao(), db.memberDao(), db.userDao(), db.expenseDao(), db.shareDao(), db.conflictDao(),
            db.placeholderMergeDao(), clockAt("2026-06-12"), remoteGroups = gateway,
        )
        val joined = withRemote.joinByToken("remote-token", UserId("u2"))
        assertTrue(joined is AppResult.Ok)
        assertEquals(FakeRemoteGroupGateway.GROUP_ID, joined.value.id.value)
        assertTrue(withRemote.observeMembers(joined.value.id).first().any { it.userId.value == "u2" })
    }

    /** Stand-in for the Supabase gateway: returns + caches a group for a single known token. */
    private class FakeRemoteGroupGateway(private val db: EvenlyDatabase) : RemoteGroupGateway {
        override suspend fun resolveByToken(token: String): GroupEntity? {
            if (token != "remote-token") return null
            val group = GroupEntity(
                id = GROUP_ID, name = "Remote Trip", emoji = "🏔️", baseCurrency = "USD",
                adminUserId = "owner", inviteToken = token,
                createdAt = 1_000L, createdBy = "owner", updatedAt = 1_000L,
            )
            db.groupDao().upsert(group)
            return group
        }

        companion object { const val GROUP_ID = "remote_g" }
    }

    @Test
    fun leaveGroup_nonAdmin_keepsAdmin() = runTest {
        val group = create() // u1 admin
        addMember(group.id, "u2", joinedAt = 2_000L)

        assertTrue(repo.leaveGroup(group.id, UserId("u2")) is AppResult.Ok)

        assertEquals(listOf("u1"), repo.observeMembers(group.id).first().map { it.userId.value })
        assertEquals("u1", db.groupDao().getById(group.id.value)?.adminUserId)
    }

    @Test
    fun leaveGroup_admin_transfersToLongestTenuredActiveMember() = runTest {
        val group = create() // u1 admin, joined at the (large) clock time
        addMember(group.id, "u2", joinedAt = 2_000L) // longer-tenured than u1

        assertTrue(repo.leaveGroup(group.id, UserId("u1")) is AppResult.Ok)

        assertEquals("u2", db.groupDao().getById(group.id.value)?.adminUserId)
        val members = repo.observeMembers(group.id).first()
        assertEquals(listOf("u2"), members.map { it.userId.value })
        assertTrue(members.single().isAdmin)
    }

    @Test
    fun leaveGroup_lastMember_abandonsGroup() = runTest {
        val group = create()
        assertTrue(repo.leaveGroup(group.id, UserId("u1")) is AppResult.Ok)

        assertNull(db.groupDao().getById(group.id.value)?.adminUserId) // AC-INV-005
        assertTrue(repo.observeMembers(group.id).first().isEmpty())
    }

    @Test
    fun setArchived_togglesMemberArchivedAt() = runTest {
        val group = create()
        assertTrue(repo.setArchived(group.id, UserId("u1"), archived = true) is AppResult.Ok)
        assertNotNull(db.memberDao().getMember(group.id.value, "u1")?.archivedAt)

        assertTrue(repo.setArchived(group.id, UserId("u1"), archived = false) is AppResult.Ok)
        assertNull(db.memberDao().getMember(group.id.value, "u1")?.archivedAt)
    }

    @Test
    fun reconcilePlaceholder_movesHistory_andRetiresPlaceholderEverywhere() = runTest {
        // u1 created the group; "Dave" is a placeholder they've been tracking, with one share owed.
        val group = create() // u1 is the admin member
        val dave = (repo.addPlaceholder(group.id, "Dave") as AppResult.Ok).value.userId
        seedExpenseWithShare(group.id, expenseId = "e1", payer = "u1", owedBy = dave.value, owed = 500)

        // Before reconcile: Dave is a visible member AND a still-claimable placeholder (the bug surface).
        assertTrue(repo.observeMembers(group.id).first().any { it.userId == dave })
        assertEquals(listOf(dave.value), placeholders(group.id))

        assertTrue(repo.reconcilePlaceholder(group.id, dave, UserId("u1")) is AppResult.Ok)

        // Dave's debt moved onto u1...
        assertEquals(listOf("u1"), db.shareDao().getByExpense("e1").map { it.userId })
        // ...and the merged placeholder is gone from the roster *and* the picker — no lingering "Dave".
        assertEquals(listOf("u1"), repo.observeMembers(group.id).first().map { it.userId.value })
        assertTrue(placeholders(group.id).isEmpty())
        assertNotNull(db.memberDao().getMember(group.id.value, dave.value)?.placeholderClaimCompletedAt)
    }

    @Test
    fun joinByToken_claimingPlaceholder_mergesHistoryAndRetiresIt() = runTest {
        // u1 tracked friend "Dave" as a placeholder owing 800; Dave now joins via the link and claims it.
        val group = create() // u1 admin
        val dave = (repo.addPlaceholder(group.id, "Dave") as AppResult.Ok).value.userId
        seedExpenseWithShare(group.id, expenseId = "e1", payer = "u1", owedBy = dave.value, owed = 800)

        val joined = repo.joinByToken(group.inviteToken, UserId("u2"), claimPlaceholderId = dave)
        assertTrue(joined is AppResult.Ok)

        // u2 joined as a real member, Dave's debt merged onto them in one step — no manual reconcile...
        assertEquals(setOf("u1", "u2"), repo.observeMembers(group.id).first().map { it.userId.value }.toSet())
        assertEquals(listOf("u2"), db.shareDao().getByExpense("e1").map { it.userId })
        // ...and the claimed placeholder is gone from the roster and the picker.
        assertTrue(placeholders(group.id).isEmpty())
    }

    @Test
    fun joinByToken_claimingNonPlaceholder_isIgnored() = runTest {
        // A claim id that isn't a placeholder of this group must never reassign an arbitrary user's rows.
        val group = create()
        db.userDao().upsert(userRow(id = "u1", name = "Alex")) // a real user, not a placeholder
        seedExpenseWithShare(group.id, expenseId = "e1", payer = "u1", owedBy = "u1", owed = 400)

        val joined = repo.joinByToken(group.inviteToken, UserId("u2"), claimPlaceholderId = UserId("u1"))
        assertTrue(joined is AppResult.Ok)

        assertEquals(setOf("u1", "u2"), repo.observeMembers(group.id).first().map { it.userId.value }.toSet())
        assertEquals(listOf("u1"), db.shareDao().getByExpense("e1").map { it.userId }) // u1's share untouched
    }

    @Test
    fun memberRoster_resolvesToCurrentGlobalName_placeholderNameUnaffected() = runTest {
        // The authenticated user (u1) and a placeholder "Dave" both belong to the group. The roster
        // reads names from the global `users` row (no per-group copy), so a Settings rename — which
        // ProfileRepositoryImpl.updateDisplayName routes straight to userDao.updateDisplayName — must
        // propagate to the member row, while the placeholder keeps its creator-assigned name.
        val group = create()
        db.userDao().upsert(userRow(id = "u1", name = "Alex"))
        val dave = (repo.addPlaceholder(group.id, "Dave") as AppResult.Ok).value.userId

        assertEquals("Alex", repo.observeMembers(group.id).first().first { it.userId.value == "u1" }.displayName)

        db.userDao().updateDisplayName(id = "u1", name = "Alexandra", now = 5_000L)

        val members = repo.observeMembers(group.id).first()
        assertEquals("Alexandra", members.first { it.userId.value == "u1" }.displayName)
        assertEquals("Dave", members.first { it.userId == dave }.displayName) // placeholder untouched
    }

    /** Insert an active expense plus a single share owed by [owedBy] — minimal reconcile fixture. */
    private suspend fun seedExpenseWithShare(groupId: GroupId, expenseId: String, payer: String, owedBy: String, owed: Long) {
        db.expenseDao().upsert(
            ExpenseEntity(
                id = expenseId, groupId = groupId.value, title = "Tacos", amountSubunits = owed,
                currency = "USD", expenseDate = "2026-06-12", payerUserId = payer, splitMode = "EVEN",
                createdBy = payer, createdAt = 1_000L, updatedAt = 1_000L,
            ),
        )
        db.shareDao().upsert(
            ShareEntity(
                id = "s_$owedBy", expenseId = expenseId, userId = owedBy,
                shareOwedSubunits = owed, createdAt = 1_000L, updatedAt = 1_000L,
            ),
        )
    }

    private fun userRow(id: String, name: String) =
        UserEntity(id = id, displayName = name, createdAt = 1_000L, updatedAt = 1_000L)

    /** The group's currently-claimable placeholder user ids (the reconcile / join-sheet picker source). */
    private suspend fun placeholders(groupId: GroupId): List<String> =
        db.userDao().observePlaceholdersInGroup(groupId.value).first().map { it.id }
}
