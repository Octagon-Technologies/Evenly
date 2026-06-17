package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.entity.GroupEntity
import da.chelimo.sharecost.data.db.entity.MemberEntity
import da.chelimo.sharecost.data.db.inMemoryTestDatabase
import da.chelimo.sharecost.data.remote.supabase.RemoteGroupGateway
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.NewGroup
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

    private lateinit var db: ShareCostDatabase
    private lateinit var repo: GroupRepositoryImpl

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        repo = GroupRepositoryImpl(db.groupDao(), db.memberDao(), db.userDao(), db.expenseDao(), db.shareDao(), db.conflictDao(), clockAt("2026-06-12"))
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
            clockAt("2026-06-12"), remoteGroups = gateway,
        )
        val joined = withRemote.joinByToken("remote-token", UserId("u2"))
        assertTrue(joined is AppResult.Ok)
        assertEquals(FakeRemoteGroupGateway.GROUP_ID, joined.value.id.value)
        assertTrue(withRemote.observeMembers(joined.value.id).first().any { it.userId.value == "u2" })
    }

    /** Stand-in for the Supabase gateway: returns + caches a group for a single known token. */
    private class FakeRemoteGroupGateway(private val db: ShareCostDatabase) : RemoteGroupGateway {
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
}
