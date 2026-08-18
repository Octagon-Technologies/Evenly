package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.entity.ShareEntity
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.data.db.inMemoryTestDatabase
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
 * Delete-for-everyone, restore, and the 30-day local purge.
 *
 * The invariant most of these exist to protect is that **`members` rows stay ACTIVE through a delete**.
 * Soft-leaving them is the obvious-looking move and it silently breaks three things at once: the
 * server's membership RLS stops authorising the row the member needs to restore, `SyncEngine.pull`
 * drops the group out of its hydration set so no other device ever learns of the delete, and Recently
 * deleted goes empty on every phone but the deleter's.
 */
class GroupDeleteTest {
    private lateinit var db: EvenlyDatabase
    private lateinit var repo: GroupRepositoryImpl

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        repo = repoAt("2026-06-12")
    }

    @AfterTest
    fun tearDown() = db.close()

    /** The repository as it exists on a device whose clock reads [date]. */
    private fun repoAt(date: String): GroupRepositoryImpl =
        GroupRepositoryImpl(
            db.groupDao(),
            db.memberDao(),
            db.userDao(),
            db.expenseDao(),
            db.shareDao(),
            db.conflictDao(),
            db.placeholderMergeDao(),
            db.placeholderClaimAnswerDao(),
            clockAt(date),
            groupPurgeDao = db.groupPurgeDao(),
        )

    private suspend fun create(creator: String = "u1"): Group {
        val result = repo.createGroup(NewGroup(name = "Trip", baseCurrency = "USD", creatorUserId = UserId(creator)))
        assertTrue(result is AppResult.Ok)
        return result.value
    }

    private suspend fun addMember(
        groupId: GroupId,
        userId: String,
    ) {
        db.userDao().upsert(
            UserEntity(id = userId, displayName = userId.uppercase(), createdAt = 1_000L, updatedAt = 1_000L),
        )
        db.memberDao().upsert(
            MemberEntity(
                id = "m_$userId",
                groupId = groupId.value,
                userId = userId,
                joinedAt = 2_000L,
                createdAt = 2_000L,
                updatedAt = 2_000L,
            ),
        )
    }

    // --- Delete -----------------------------------------------------------------------------------

    @Test
    fun deleteGroup_leavesHomeAndAppearsInRecentlyDeleted() =
        runTest {
            val group = create()
            addMember(group.id, "u2")

            assertTrue(repo.deleteGroup(group.id, UserId("u2")) is AppResult.Ok)

            assertTrue(repo.observeGroupsForUser(UserId("u1")).first().isEmpty())
            assertNull(repo.observeGroup(group.id).first())

            // Both members see it, not just the one who deleted it.
            for (member in listOf("u1", "u2")) {
                val deleted = repo.observeDeletedGroups(UserId(member)).first()
                assertEquals(listOf(group.id), deleted.map { it.id }, "member $member should see the tombstone")
                assertEquals(UserId("u2"), deleted.single().deletedBy)
            }
        }

    @Test
    fun deleteGroup_keepsEveryMemberActive() =
        runTest {
            val group = create()
            addMember(group.id, "u2")

            assertTrue(repo.deleteGroup(group.id, UserId("u1")) is AppResult.Ok)

            // The load-bearing one. If these go LEFT, RLS revokes the restore and the delete never
            // reaches anyone else's device.
            val members = db.memberDao().allForSync().filter { it.groupId == group.id.value }
            assertEquals(2, members.size)
            assertTrue(members.all { it.status == MemberEntity.STATUS_ACTIVE })
        }

    @Test
    fun deleteGroup_resolvesTheDeletersName() =
        runTest {
            val group = create()
            addMember(group.id, "u2")
            assertTrue(repo.deleteGroup(group.id, UserId("u2")) is AppResult.Ok)

            assertEquals(
                "U2",
                repo
                    .observeDeletedGroups(UserId("u1"))
                    .first()
                    .single()
                    .deletedByName,
            )
        }

    @Test
    fun deleteGroup_byNonMember_isRefused() =
        runTest {
            val group = create()
            // Checked in the repository rather than trusted from the UI: this erases everyone's ledger.
            val result = repo.deleteGroup(group.id, UserId("stranger"))
            assertTrue(result is AppResult.Err)
            assertNotNull(repo.observeGroup(group.id).first())
        }

    @Test
    fun deleteGroup_twice_doesNotExtendTheDeadline() =
        runTest {
            val group = create()
            addMember(group.id, "u2")

            assertTrue(repo.deleteGroup(group.id, UserId("u1")) is AppResult.Ok)
            val firstStamp = db.groupDao().getById(group.id.value)?.deletedAt
            assertNotNull(firstStamp)

            // Someone else's delete lands on an already-deleted group. Re-stamping would silently push
            // the purge date out, so the second caller is a no-op that still reports success.
            val second = repoAt("2026-06-20").deleteGroup(group.id, UserId("u2"))
            assertTrue(second is AppResult.Ok)
            assertEquals(firstStamp, db.groupDao().getById(group.id.value)?.deletedAt)
            assertEquals("u1", db.groupDao().getById(group.id.value)?.deletedBy)
        }

    // --- Restore ----------------------------------------------------------------------------------

    @Test
    fun restoreGroup_byAnyMember_bringsItBackForEveryone() =
        runTest {
            val group = create()
            addMember(group.id, "u2")
            assertTrue(repo.deleteGroup(group.id, UserId("u1")) is AppResult.Ok)

            // u2 did not delete it and restores it anyway. That is the design, not a gap.
            assertTrue(repo.restoreGroup(group.id, UserId("u2")) is AppResult.Ok)

            assertEquals(listOf(group.id), repo.observeGroupsForUser(UserId("u1")).first().map { it.id })
            assertTrue(repo.observeDeletedGroups(UserId("u1")).first().isEmpty())
            assertNull(db.groupDao().getById(group.id.value)?.deletedBy)
        }

    @Test
    fun restoreGroup_pastTheWindow_isRefused() =
        runTest {
            val group = create()
            assertTrue(repo.deleteGroup(group.id, UserId("u1")) is AppResult.Ok)

            // 31 days on, the server may already have purged the rows. Clearing the tombstone here
            // would resurrect an empty shell on this device alone.
            val result = repoAt("2026-07-13").restoreGroup(group.id, UserId("u1"))
            assertTrue(result is AppResult.Err)
            assertEquals("GROUP_PURGED", (result.error as AppError.Backend).code)
            assertNotNull(db.groupDao().getById(group.id.value)?.deletedAt)
        }

    @Test
    fun deletedGroupPastTheWindow_isNotOfferedForRestore() =
        runTest {
            val group = create()
            assertTrue(repo.deleteGroup(group.id, UserId("u1")) is AppResult.Ok)

            // The list and the guard agree: nothing is shown that restoreGroup would then refuse.
            assertTrue(repoAt("2026-07-13").observeDeletedGroups(UserId("u1")).first().isEmpty())
        }

    // --- Purge ------------------------------------------------------------------------------------

    @Test
    fun purge_removesTheGroupAndEverythingUnderIt() =
        runTest {
            val group = create()
            addMember(group.id, "u2")
            seedExpense(group.id, expenseId = "e1", shareId = "s1")
            assertTrue(repo.deleteGroup(group.id, UserId("u1")) is AppResult.Ok)

            assertEquals(1, repoAt("2026-07-13").purgeExpiredDeletedGroups())

            assertNull(db.groupDao().getById(group.id.value))
            assertTrue(db.memberDao().allForSync().none { it.groupId == group.id.value })
            assertTrue(db.expenseDao().allForSync().none { it.groupId == group.id.value })
            // shares reach the group only through their expense, which is why they are deleted first.
            assertTrue(db.shareDao().allForSync().none { it.id == "s1" })
        }

    @Test
    fun purge_leavesGroupsStillInsideTheWindowAlone() =
        runTest {
            val group = create()
            assertTrue(repo.deleteGroup(group.id, UserId("u1")) is AppResult.Ok)

            // Day 29. Still restorable, so still here.
            assertEquals(0, repoAt("2026-07-11").purgeExpiredDeletedGroups())
            assertNotNull(db.groupDao().getById(group.id.value))
        }

    @Test
    fun purge_leavesLiveGroupsAlone() =
        runTest {
            val live = create()
            // Never deleted, and a long time has passed. The purge must key off the tombstone, not age.
            assertEquals(0, repoAt("2027-01-01").purgeExpiredDeletedGroups())
            assertNotNull(db.groupDao().getById(live.id.value))
        }

    @Test
    fun purge_isIdempotent() =
        runTest {
            val group = create()
            assertTrue(repo.deleteGroup(group.id, UserId("u1")) is AppResult.Ok)

            val later = repoAt("2026-07-13")
            assertEquals(1, later.purgeExpiredDeletedGroups())
            assertEquals(0, later.purgeExpiredDeletedGroups())
        }

    @Test
    fun purge_dropsThisGroupsPlaceholdersAndKeepsRealAccounts() =
        runTest {
            val group = create()
            addMember(group.id, "u2")
            val placeholder = repo.addPlaceholder(group.id, "Dave", createdBy = UserId("u1"))
            assertTrue(placeholder is AppResult.Ok)
            assertTrue(repo.deleteGroup(group.id, UserId("u1")) is AppResult.Ok)

            assertEquals(1, repoAt("2026-07-13").purgeExpiredDeletedGroups())

            // A placeholder is group-private by construction, so it dies with the group.
            assertNull(db.userDao().getById(placeholder.value.userId.value))
            // A real account is shared across groups and is never touched here.
            assertNotNull(db.userDao().getById("u2"))
        }

    private suspend fun seedExpense(
        groupId: GroupId,
        expenseId: String,
        shareId: String,
    ) {
        db.expenseDao().upsert(
            ExpenseEntity(
                id = expenseId,
                groupId = groupId.value,
                title = "Dinner",
                amountSubunits = 3_000,
                currency = "USD",
                payerUserId = "u1",
                expenseDate = "2026-06-10",
                splitMode = "EVEN",
                createdBy = "u1",
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
        db.shareDao().upsert(
            ShareEntity(
                id = shareId,
                expenseId = expenseId,
                userId = "u2",
                shareOwedSubunits = 1_500,
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
    }
}
