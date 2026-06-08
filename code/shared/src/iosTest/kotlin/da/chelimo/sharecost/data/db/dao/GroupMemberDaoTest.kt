package da.chelimo.sharecost.data.db.dao

import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.entity.GroupEntity
import da.chelimo.sharecost.data.db.entity.MemberEntity
import da.chelimo.sharecost.data.db.inMemoryTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Room tests for [GroupDao] + [MemberDao] (02 §3.4/§3.5). Covers column fidelity (incl. the
 * nullable `admin_user_id` for an abandoned group), the members→groups join that scopes a user's
 * home list, soft-delete exclusion, and the tenure ordering that feeds the admin-transfer rule.
 */
class GroupMemberDaoTest {

    private lateinit var db: ShareCostDatabase
    private lateinit var groupDao: GroupDao
    private lateinit var memberDao: MemberDao

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        groupDao = db.groupDao()
        memberDao = db.memberDao()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun group(
        id: String,
        token: String = "tok-$id",
        admin: String? = "admin",
        createdAt: Long = 1_000L,
        deletedAt: Long? = null,
    ) = GroupEntity(
        id = id,
        name = "Group $id",
        baseCurrency = "USD",
        adminUserId = admin,
        inviteToken = token,
        createdAt = createdAt,
        createdBy = "admin",
        updatedAt = createdAt,
        deletedAt = deletedAt,
    )

    private fun member(
        id: String,
        groupId: String,
        userId: String,
        status: String = MemberEntity.STATUS_ACTIVE,
        joinedAt: Long = 1_000L,
    ) = MemberEntity(
        id = id,
        groupId = groupId,
        userId = userId,
        status = status,
        joinedAt = joinedAt,
        leftAt = if (status == MemberEntity.STATUS_LEFT) joinedAt + 1 else null,
        createdAt = joinedAt,
        updatedAt = joinedAt,
    )

    @Test
    fun group_roundTrips_includingNullAdminForAbandoned() = runTest {
        val abandoned = group("g1", admin = null)
        groupDao.upsert(abandoned)
        val read = groupDao.getById("g1")
        assertEquals(abandoned, read)
        assertNull(read?.adminUserId)
    }

    @Test
    fun findByInviteToken_returnsLiveGroup() = runTest {
        groupDao.upsert(group("g1", token = "JOIN-ME"))
        assertEquals("g1", groupDao.findByInviteToken("JOIN-ME")?.id)
        assertNull(groupDao.findByInviteToken("nope"))
    }

    @Test
    fun observeGroupsForUser_onlyActiveMemberships_nonDeleted_newestFirst() = runTest {
        groupDao.upsertAll(
            listOf(
                group("g1", createdAt = 100),
                group("g2", createdAt = 300),
                group("g3", createdAt = 200),               // user LEFT this one
                group("g4", createdAt = 400, deletedAt = 9), // soft-deleted
            )
        )
        memberDao.upsertAll(
            listOf(
                member("m1", "g1", "u1"),
                member("m2", "g2", "u1"),
                member("m3", "g3", "u1", status = MemberEntity.STATUS_LEFT),
                member("m4", "g4", "u1"),
                member("m5", "g1", "other"), // different user — must not affect u1's list
            )
        )
        val ids = groupDao.observeGroupsForUser("u1").first().map { it.id }
        assertEquals(listOf("g2", "g1"), ids) // g2 newer than g1; g3 left, g4 deleted
    }

    @Test
    fun countActiveMembers_excludesLeft() = runTest {
        memberDao.upsertAll(
            listOf(
                member("m1", "g1", "u1"),
                member("m2", "g1", "u2"),
                member("m3", "g1", "u3", status = MemberEntity.STATUS_LEFT),
            )
        )
        assertEquals(2, memberDao.countActiveMembers("g1"))
    }

    @Test
    fun activeMembersByTenure_oldestJoinFirst_feedsAdminTransfer() = runTest {
        memberDao.upsertAll(
            listOf(
                member("m1", "g1", "newest", joinedAt = 300),
                member("m2", "g1", "oldest", joinedAt = 100),
                member("m3", "g1", "middle", joinedAt = 200),
                member("m4", "g1", "left", status = MemberEntity.STATUS_LEFT, joinedAt = 50),
            )
        )
        val order = memberDao.activeMembersByTenure("g1").map { it.userId }
        assertEquals(listOf("oldest", "middle", "newest"), order)
    }

    @Test
    fun memberUpsert_sameId_replaces() = runTest {
        memberDao.upsert(member("m1", "g1", "u1", status = MemberEntity.STATUS_ACTIVE))
        memberDao.upsert(member("m1", "g1", "u1", status = MemberEntity.STATUS_LEFT))
        assertEquals(MemberEntity.STATUS_LEFT, memberDao.getMember("g1", "u1")?.status)
    }
}
