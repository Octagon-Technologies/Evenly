package app.splitevenly.data.db.dao

import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Room round-trip tests for [UserDao] (02 §3.2). Verifies column fidelity, `@Upsert` idempotency,
 * the `citext`/NOCASE behaviours, and the case-insensitive placeholder-name guard (AC-M1-021).
 */
class UserDaoTest {

    private lateinit var db: EvenlyDatabase
    private lateinit var dao: UserDao

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        dao = db.userDao()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun realUser(
        id: String,
        name: String = "Real $id",
        email: String? = "$id@example.com",
    ) = UserEntity(
        id = id,
        isPlaceholder = false,
        displayName = name,
        email = email,
        avatarUrl = "https://cdn/$id.png",
        baseCurrency = "EUR",
        placeholderGroupId = null,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        rowVersion = 3L,
    )

    private fun placeholder(id: String, name: String, groupId: String) = UserEntity(
        id = id,
        isPlaceholder = true,
        displayName = name,
        email = null,
        placeholderGroupId = groupId,
        createdAt = 10L,
        updatedAt = 10L,
    )

    /** The member row that every placeholder carries (see `addPlaceholder`); claimed → LEFT + stamped. */
    private fun placeholderMember(userId: String, groupId: String, claimedAt: Long? = null) = MemberEntity(
        id = "m_$userId",
        groupId = groupId,
        userId = userId,
        status = if (claimedAt == null) MemberEntity.STATUS_ACTIVE else MemberEntity.STATUS_LEFT,
        joinedAt = 10L,
        leftAt = claimedAt,
        placeholderClaimCompletedAt = claimedAt,
        createdAt = 10L,
        updatedAt = 10L,
    )

    @Test
    fun upsert_thenGetById_roundTripsAllColumns() = runTest {
        val u = realUser("u1")
        dao.upsert(u)
        assertEquals(u, dao.getById("u1"))
    }

    @Test
    fun upsert_sameId_replacesInsteadOfDuplicating() = runTest {
        dao.upsert(realUser("u1", name = "Old"))
        dao.upsert(realUser("u1", name = "New"))
        assertEquals("New", dao.getById("u1")?.displayName)
    }

    @Test
    fun getById_unknownId_returnsNull() = runTest {
        assertNull(dao.getById("missing"))
    }

    @Test
    fun findByEmail_isCaseInsensitive() = runTest {
        dao.upsert(realUser("u1", email = "Andrew@Evenly.com"))
        assertEquals("u1", dao.findByEmail("andrew@evenly.COM")?.id)
    }

    @Test
    fun observeById_emitsLatestRow() = runTest {
        dao.upsert(realUser("u1", name = "First"))
        assertEquals("First", dao.observeById("u1").first()?.displayName)
        dao.upsert(realUser("u1", name = "Second"))
        assertEquals("Second", dao.observeById("u1").first()?.displayName)
    }

    @Test
    fun countPlaceholderName_isCaseInsensitive_andGroupScoped() = runTest {
        dao.upsert(placeholder("p1", "Tyler", groupId = "G1"))
        assertEquals(1, dao.countPlaceholderName("G1", "tyler"))
        assertEquals(1, dao.countPlaceholderName("G1", "TYLER"))
        assertEquals(0, dao.countPlaceholderName("G2", "Tyler"))
    }

    @Test
    fun observePlaceholdersInGroup_onlyActiveUnclaimed_excludesRealUsersAndOtherGroups_nameOrdered() = runTest {
        dao.upsertAll(
            listOf(
                placeholder("p2", "Zoe", groupId = "G1"),
                placeholder("p1", "Ana", groupId = "G1"),
                placeholder("p3", "Other", groupId = "G2"),
                placeholder("p4", "Claimed", groupId = "G1"),
                realUser("r1"),
            )
        )
        // Every placeholder carries a member row; p4's was already claimed/merged (LEFT + stamped).
        db.memberDao().upsertAll(
            listOf(
                placeholderMember("p2", "G1"),
                placeholderMember("p1", "G1"),
                placeholderMember("p3", "G2"),
                placeholderMember("p4", "G1", claimedAt = 50L),
            )
        )
        // Only still-claimable G1 placeholders, name-ordered: real user, other group, and the
        // already-claimed "Claimed" are all excluded.
        val names = dao.observePlaceholdersInGroup("G1").first().map { it.displayName }
        assertEquals(listOf("Ana", "Zoe"), names)
    }
}
