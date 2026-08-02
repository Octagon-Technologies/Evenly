package app.splitevenly.data.claim

import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.entity.ShareEntity
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.data.remote.supabase.ClaimOutcome
import app.splitevenly.data.remote.supabase.PlaceholderClaimGateway
import app.splitevenly.data.repository.GroupRepositoryImpl
import app.splitevenly.data.repository.clockAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The 5-second undo window and the first-claim-wins guard.
 *
 * The property under test throughout is that **nothing is written until the window closes**: Undo is
 * cheap because there is no partial state to repair, and a claim that loses the guard (or can't reach
 * it) never touched the database at all.
 *
 * These run on real dispatchers with a short window rather than `runTest`'s virtual clock: the merge
 * itself is Room work on a real dispatcher, so virtual time advances *while it runs* and the window
 * fires in the middle of an assertion. Real time with a 60ms window is deterministic here and a
 * 10-second window is comfortably longer than the test body it is asserted inside.
 */
class PlaceholderClaimCoordinatorTest {

    private lateinit var db: EvenlyDatabase
    private lateinit var groups: GroupRepositoryImpl
    private val scopes = mutableListOf<CoroutineScope>()

    private val group = GroupId("g1")
    private val me = UserId("me")
    private val ghost = UserId("ph")

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        groups = GroupRepositoryImpl(
            db.groupDao(), db.memberDao(), db.userDao(), db.expenseDao(), db.shareDao(), db.conflictDao(),
            db.placeholderMergeDao(), db.placeholderClaimAnswerDao(), clockAt("2026-08-01"),
        )
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { (it.coroutineContext[kotlinx.coroutines.Job])?.cancel() }
        db.close()
    }

    /** Records what it was asked and answers with a scripted outcome (null throws, standing in for offline). */
    private class FakeGateway(
        private val outcome: ClaimOutcome? = ClaimOutcome(won = true),
    ) : PlaceholderClaimGateway {
        var calls = 0
        override suspend fun claim(groupId: String, placeholderUserId: String, claimerUserId: String, now: Long): ClaimOutcome {
            calls++
            return outcome ?: throw IllegalStateException("offline")
        }
    }

    private suspend fun seed() {
        for (u in listOf(me.value, ghost.value, "maya")) {
            db.userDao().upsert(
                UserEntity(
                    id = u, isPlaceholder = u == ghost.value, displayName = u,
                    placeholderGroupId = if (u == ghost.value) group.value else null,
                    createdAt = 1, updatedAt = 1,
                ),
            )
            db.memberDao().upsert(
                MemberEntity(id = "m_$u", groupId = group.value, userId = u, joinedAt = 1, createdAt = 1, updatedAt = 1),
            )
        }
        db.expenseDao().upsert(
            ExpenseEntity(
                id = "e1", groupId = group.value, title = "Airport taxi", amountSubunits = 1450,
                currency = "USD", expenseDate = "2026-07-01", payerUserId = "maya", splitMode = "EVEN",
                createdBy = "maya", createdAt = 1, updatedAt = 1,
            ),
        )
        db.shareDao().upsert(
            ShareEntity(id = "s_ph", expenseId = "e1", userId = ghost.value, shareOwedSubunits = 1450, createdAt = 1, updatedAt = 1),
        )
    }

    private fun coordinator(gateway: PlaceholderClaimGateway?, windowMillis: Long): PlaceholderClaimCoordinator {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return PlaceholderClaimCoordinator(
            groups = groups,
            gateway = gateway,
            appForeground = null,
            clock = clockAt("2026-08-01"),
            undoWindowMillis = windowMillis,
            scope = scope,
        )
    }

    /** Wait for the claim to reach an outcome. Fails via `runTest`'s own timeout if it never does. */
    private suspend fun PlaceholderClaimCoordinator.settled(): ClaimStatus = status.first {
        it is ClaimStatus.Claimed || it is ClaimStatus.Lost || it is ClaimStatus.Failed
    }

    private suspend fun shareOwner() = db.shareDao().getByExpense("e1").single().userId

    private suspend fun claimStamp() = db.memberDao().getMember(group.value, ghost.value)!!.placeholderClaimCompletedAt

    private companion object {
        /** Longer than any test body that asserts "still pending". */
        const val LONG_WINDOW = 10_000L

        /** Short enough to elapse inside a test, long enough not to race the confirm that starts it. */
        const val SHORT_WINDOW = 60L
    }

    @Test
    fun nothingIsWrittenDuringTheUndoWindow() = runTest {
        seed()
        val gateway = FakeGateway()
        val c = coordinator(gateway, LONG_WINDOW)

        c.confirm(group, ghost, "ph", me)

        assertIs<ClaimStatus.Undoable>(c.status.value)
        assertEquals(ghost.value, shareOwner(), "the merge must not have run yet")
        assertNull(claimStamp())
        assertEquals(0, gateway.calls, "the guard runs at flush, so an undone claim never reaches it")
    }

    @Test
    fun undoLeavesTheDatabaseUntouched() = runTest {
        seed()
        val gateway = FakeGateway()
        val c = coordinator(gateway, LONG_WINDOW)

        c.confirm(group, ghost, "ph", me)
        c.undo()
        delay(100)

        assertEquals(ClaimStatus.Idle, c.status.value)
        assertEquals(ghost.value, shareOwner())
        assertNull(claimStamp())
        assertEquals(0, gateway.calls)
    }

    @Test
    fun theWindowElapsing_commitsTheMerge() = runTest {
        seed()
        val gateway = FakeGateway()
        val c = coordinator(gateway, SHORT_WINDOW)

        c.confirm(group, ghost, "ph", me)

        assertIs<ClaimStatus.Claimed>(c.settled())
        assertEquals(me.value, shareOwner())
        assertNotNull(claimStamp())
        assertEquals(1, gateway.calls)
    }

    @Test
    fun flushCommitsEarly_andUndoCanNoLongerReverseIt() = runTest {
        // Leaving the group screen or backgrounding flushes. Undo must be gone the moment it does, so the
        // button can never be pressed against a merge that already happened.
        seed()
        val c = coordinator(FakeGateway(), LONG_WINDOW)

        c.confirm(group, ghost, "ph", me)
        c.flush()

        assertIs<ClaimStatus.Claimed>(c.settled())
        assertEquals(me.value, shareOwner())

        c.undo()
        delay(50)
        assertEquals(me.value, shareOwner(), "undo after the flush must not reverse anything")
    }

    @Test
    fun losingTheGuard_writesNothingAndNamesTheWinner() = runTest {
        seed()
        val c = coordinator(FakeGateway(ClaimOutcome(won = false, winnerUserId = "maya", winnerName = "Maya")), SHORT_WINDOW)

        c.confirm(group, ghost, "ph", me)

        val lost = c.settled()
        assertIs<ClaimStatus.Lost>(lost)
        assertEquals("Maya", lost.winnerName)
        assertEquals(ghost.value, shareOwner(), "the loser never merged, so there is nothing to reverse")
        assertNull(claimStamp())
    }

    @Test
    fun unreachableGuard_isNeverTreatedAsAwin() = runTest {
        // "Couldn't ask" read as "won" is exactly how two offline devices split one name's history.
        seed()
        val c = coordinator(FakeGateway(outcome = null), SHORT_WINDOW)

        c.confirm(group, ghost, "ph", me)

        assertIs<ClaimStatus.Failed>(c.settled())
        assertEquals(ghost.value, shareOwner())
        assertNull(claimStamp())
    }

    @Test
    fun withNoServerConfigured_theClaimSimplyApplies() = runTest {
        seed()
        val c = coordinator(gateway = null, windowMillis = SHORT_WINDOW)

        c.confirm(group, ghost, "ph", me)

        assertIs<ClaimStatus.Claimed>(c.settled())
        assertEquals(me.value, shareOwner())
    }

    @Test
    fun confirmingASecondClaimFlushesTheFirst() = runTest {
        // Only one pending claim at a time, and the first must land rather than be silently dropped.
        seed()
        db.userDao().upsert(
            UserEntity(id = "ph2", isPlaceholder = true, displayName = "ph2", placeholderGroupId = group.value, createdAt = 1, updatedAt = 1),
        )
        db.memberDao().upsert(
            MemberEntity(id = "m_ph2", groupId = group.value, userId = "ph2", joinedAt = 1, createdAt = 1, updatedAt = 1),
        )
        val gateway = FakeGateway()
        val c = coordinator(gateway, SHORT_WINDOW)

        c.confirm(group, ghost, "ph", me)
        c.confirm(group, UserId("ph2"), "ph2", me)

        assertIs<ClaimStatus.Undoable>(c.status.value, "the newer claim owns the screen, not the older one's result")
        c.settled()

        assertNotNull(claimStamp(), "the first claim was flushed, not dropped")
        assertNotNull(db.memberDao().getMember(group.value, "ph2")!!.placeholderClaimCompletedAt)
        assertEquals(2, gateway.calls)
    }

    @Test
    fun acknowledgeClearsAfinishedNotice() = runTest {
        seed()
        val c = coordinator(FakeGateway(), SHORT_WINDOW)

        c.confirm(group, ghost, "ph", me)
        c.settled()
        c.acknowledge()

        assertEquals(ClaimStatus.Idle, c.status.value)
    }
}
