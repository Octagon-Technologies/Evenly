package app.splitevenly.data.claim

import app.splitevenly.core.error.AppResult
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
import app.splitevenly.domain.repository.GroupRepository
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
import kotlin.test.assertTrue

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
        groups =
            GroupRepositoryImpl(
                db.groupDao(),
                db.memberDao(),
                db.userDao(),
                db.expenseDao(),
                db.shareDao(),
                db.conflictDao(),
                db.placeholderMergeDao(),
                db.placeholderClaimAnswerDao(),
                clockAt("2026-08-01"),
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

        override suspend fun claim(
            groupId: String,
            placeholderUserId: String,
            claimerUserId: String,
            now: Long,
        ): ClaimOutcome {
            calls++
            return outcome ?: throw IllegalStateException("offline")
        }
    }

    private suspend fun seed() {
        for (u in listOf(me.value, ghost.value, "maya")) {
            db.userDao().upsert(
                UserEntity(
                    id = u,
                    isPlaceholder = u == ghost.value,
                    displayName = u,
                    placeholderGroupId = if (u == ghost.value) group.value else null,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            db.memberDao().upsert(
                MemberEntity(id = "m_$u", groupId = group.value, userId = u, joinedAt = 1, createdAt = 1, updatedAt = 1),
            )
        }
        db.expenseDao().upsert(
            ExpenseEntity(
                id = "e1",
                groupId = group.value,
                title = "Airport taxi",
                amountSubunits = 1450,
                currency = "USD",
                expenseDate = "2026-07-01",
                payerUserId = "maya",
                splitMode = "EVEN",
                createdBy = "maya",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        db.shareDao().upsert(
            ShareEntity(id = "s_ph", expenseId = "e1", userId = ghost.value, shareOwedSubunits = 1450, createdAt = 1, updatedAt = 1),
        )
    }

    /** In-memory [PendingClaimStore]: a Native test process has no usable Keychain to hold the real one. */
    private class FakeClaimStore : PendingClaimStore {
        val claims = mutableListOf<ParkedClaim>()

        override suspend fun park(claim: ParkedClaim) {
            claims.removeAll { it.groupId == claim.groupId && it.placeholderUserId == claim.placeholderUserId }
            claims += claim
        }

        override suspend fun clear(
            groupId: GroupId,
            placeholderUserId: UserId,
        ) {
            claims.removeAll { it.groupId == groupId && it.placeholderUserId == placeholderUserId }
        }

        override suspend fun parked(): List<ParkedClaim> = claims.toList()
    }

    /** A [GroupRepository] whose merge fails the first N times, standing in for dying between the two halves. */
    private class MergeFailsAtFirst(
        private val real: GroupRepository,
        private var failures: Int,
    ) : GroupRepository by real {
        var attempts = 0
            private set

        override suspend fun reconcilePlaceholder(
            groupId: GroupId,
            placeholderUserId: UserId,
            realUserId: UserId,
        ): AppResult<Unit> {
            attempts++
            if (failures-- > 0) error("process died between the guard and the merge")
            return real.reconcilePlaceholder(groupId, placeholderUserId, realUserId)
        }
    }

    private fun coordinator(
        gateway: PlaceholderClaimGateway?,
        windowMillis: Long,
        store: PendingClaimStore = FakeClaimStore(),
        repository: GroupRepository = groups,
    ): PlaceholderClaimCoordinator {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return PlaceholderClaimCoordinator(
            groups = repository,
            store = store,
            gateway = gateway,
            appForeground = null,
            clock = clockAt("2026-08-01"),
            undoWindowMillis = windowMillis,
            scope = scope,
        )
    }

    /** Wait for the claim to reach an outcome. Fails via `runTest`'s own timeout if it never does. */
    private suspend fun PlaceholderClaimCoordinator.settled(): ClaimStatus =
        status.first {
            it is ClaimStatus.Claimed || it is ClaimStatus.Lost || it is ClaimStatus.Failed
        }

    private suspend fun shareOwner() =
        db
            .shareDao()
            .getByExpense("e1")
            .single()
            .userId

    private suspend fun claimStamp() = db.memberDao().getMember(group.value, ghost.value)!!.placeholderClaimCompletedAt

    private companion object {
        /** Longer than any test body that asserts "still pending". */
        const val LONG_WINDOW = 10_000L

        /** Short enough to elapse inside a test, long enough not to race the confirm that starts it. */
        const val SHORT_WINDOW = 60L
    }

    @Test
    fun nothingIsWrittenDuringTheUndoWindow() =
        runTest {
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
    fun undoLeavesTheDatabaseUntouched() =
        runTest {
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
    fun theWindowElapsing_commitsTheMerge() =
        runTest {
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
    fun flushCommitsEarly_andUndoCanNoLongerReverseIt() =
        runTest {
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
    fun losingTheGuard_writesNothingAndNamesTheWinner() =
        runTest {
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
    fun unreachableGuard_isNeverTreatedAsAwin() =
        runTest {
            // "Couldn't ask" read as "won" is exactly how two offline devices split one name's history.
            seed()
            val c = coordinator(FakeGateway(outcome = null), SHORT_WINDOW)

            c.confirm(group, ghost, "ph", me)

            assertIs<ClaimStatus.Failed>(c.settled())
            assertEquals(ghost.value, shareOwner())
            assertNull(claimStamp())
        }

    @Test
    fun withNoServerConfigured_theClaimSimplyApplies() =
        runTest {
            seed()
            val c = coordinator(gateway = null, windowMillis = SHORT_WINDOW)

            c.confirm(group, ghost, "ph", me)

            assertIs<ClaimStatus.Claimed>(c.settled())
            assertEquals(me.value, shareOwner())
        }

    @Test
    fun confirmingASecondClaimFlushesTheFirst() =
        runTest {
            // Only one pending claim at a time, and the first must land rather than be silently dropped.
            seed()
            db.userDao().upsert(
                UserEntity(
                    id = "ph2",
                    isPlaceholder = true,
                    displayName = "ph2",
                    placeholderGroupId = group.value,
                    createdAt = 1,
                    updatedAt = 1,
                ),
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

    // ── The window past the flush: the guard is a server-side write (R2) ─────────────────────────

    /**
     * `claim_placeholder` retires the placeholder server-side and stamps it claimed, so the next pull
     * drops it out of `observePlaceholdersInGroup` and it stops being pickable in the Reconcile picker
     * *and* the Join-sheet identity picker. Dying between that and the local merge therefore stranded the
     * name's whole history with no affordance anywhere to retry from. The park is the only thing that can
     * finish it, so it is written before the guard is asked and cleared only once the merge returns.
     */
    @Test
    fun aWonClaimWhoseMergeNeverRan_isFinishedByTheResume() =
        runTest {
            seed()
            val store = FakeClaimStore()
            val flaky = MergeFailsAtFirst(groups, failures = 1)
            val gateway = FakeGateway()
            val c = coordinator(gateway, SHORT_WINDOW, store, flaky)

            c.confirm(group, ghost, "ph", me)

            assertIs<ClaimStatus.Failed>(c.settled(), "a merge that did not run is not a claim that happened")
            assertEquals(ghost.value, shareOwner(), "the money is still under the retired name")
            assertEquals(1, store.parked().size, "and the claim is parked, because nothing else can finish it")

            // Next launch, next group open.
            c.resumePending()

            assertEquals(me.value, shareOwner(), "the history is Sam's, without anybody re-picking a name")
            assertNotNull(claimStamp())
            assertTrue(store.parked().isEmpty(), "and the park is spent")
            assertEquals(2, gateway.calls, "the guard is idempotent for the same claimer, so re-asking is free")
        }

    @Test
    fun aClaimThatLandedFirstTime_leavesNothingParkedToResume() =
        runTest {
            seed()
            val store = FakeClaimStore()
            val c = coordinator(FakeGateway(), SHORT_WINDOW, store)

            c.confirm(group, ghost, "ph", me)
            assertIs<ClaimStatus.Claimed>(c.settled())

            assertTrue(store.parked().isEmpty())
        }

    @Test
    fun losingTheGuard_clearsTheParkSoTheResumeNeverMergesANameSomebodyElseOwns() =
        runTest {
            seed()
            val store = FakeClaimStore()
            val c = coordinator(FakeGateway(ClaimOutcome(won = false, winnerName = "Maya")), SHORT_WINDOW, store)

            c.confirm(group, ghost, "ph", me)
            assertIs<ClaimStatus.Lost>(c.settled())

            assertTrue(store.parked().isEmpty())
            c.resumePending()
            assertEquals(ghost.value, shareOwner(), "the loser must never merge, now or later")
        }

    /**
     * "The RPC committed but the reply was lost" and "the RPC never ran" are indistinguishable from here,
     * and the first strands the name exactly as above. So an unreachable guard parks too, and the resume
     * asks again rather than assuming.
     */
    @Test
    fun anUnreachableGuard_leavesTheClaimParkedForTheResumeToAskAgain() =
        runTest {
            seed()
            val store = FakeClaimStore()
            val c = coordinator(FakeGateway(outcome = null), SHORT_WINDOW, store)

            c.confirm(group, ghost, "ph", me)
            assertIs<ClaimStatus.Failed>(c.settled())

            assertEquals(1, store.parked().size)
            assertEquals(ghost.value, shareOwner(), "and still nothing was merged")
        }

    /**
     * A throw out of the merge used to escape `scope.launch`, be swallowed by the `SupervisorJob`, and
     * leave the status on `Confirming` forever: the same stranded end state by a non-crash route, with
     * the spinner still turning.
     */
    @Test
    fun aMergeThatThrows_saysSoInsteadOfSittingOnConfirming() =
        runTest {
            seed()
            val c = coordinator(FakeGateway(), SHORT_WINDOW, FakeClaimStore(), MergeFailsAtFirst(groups, failures = 99))

            c.confirm(group, ghost, "ph", me)

            assertIs<ClaimStatus.Failed>(c.settled())
        }

    @Test
    fun acknowledgeClearsAfinishedNotice() =
        runTest {
            seed()
            val c = coordinator(FakeGateway(), SHORT_WINDOW)

            c.confirm(group, ghost, "ph", me)
            c.settled()
            c.acknowledge()

            assertEquals(ClaimStatus.Idle, c.status.value)
        }
}
