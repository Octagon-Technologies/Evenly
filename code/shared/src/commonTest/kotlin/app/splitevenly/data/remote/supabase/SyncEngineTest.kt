package app.splitevenly.data.remote.supabase

import app.splitevenly.core.error.AppError
import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.entity.UserEntity
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic tests for [SyncEngine.keepNewer], the last-write-wins guard `pull()` applies to every
 * synced table (Rule 5). No Supabase client or DB needed, so this runs on every target via commonTest.
 *
 * The guard exists to stop a pull that races ahead of our own push from clobbering an unsynced local
 * edit. The two roster/identity vectors below are the reason it was extended past the financial tables:
 *  - a claimed/soft-left placeholder member getting RESURRECTED by the stale still-ACTIVE server row,
 *  - a Settings display-name rename getting REVERTED by the stale server row.
 */
class SyncEngineTest {
    private fun member(
        id: String,
        userId: String = "u-$id",
        status: String = MemberEntity.STATUS_ACTIVE,
        placeholderClaimCompletedAt: Long? = null,
        updatedAt: Long,
    ) = MemberEntity(
        id = id,
        groupId = "g1",
        userId = userId,
        status = status,
        joinedAt = 0L,
        leftAt = if (status == MemberEntity.STATUS_LEFT) updatedAt else null,
        placeholderClaimCompletedAt = placeholderClaimCompletedAt,
        createdAt = 0L,
        updatedAt = updatedAt,
    )

    private fun user(
        id: String,
        displayName: String,
        updatedAt: Long,
    ) = UserEntity(
        id = id,
        displayName = displayName,
        createdAt = 0L,
        updatedAt = updatedAt,
    )

    private fun keepNewerMembers(
        incoming: List<MemberEntity>,
        local: List<MemberEntity>,
    ) = SyncEngine.keepNewer(incoming, local, { it.id }, { it.updatedAt })

    private fun keepNewerUsers(
        incoming: List<UserEntity>,
        local: List<UserEntity>,
    ) = SyncEngine.keepNewer(incoming, local, { it.id }, { it.updatedAt })

    @Test
    fun claimedPlaceholder_isNotResurrected_byStaleActiveServerRow() {
        // Local: the placeholder was claimed/merged on reconcile or join — soft-left + stamped, ts bumped.
        val localLeft = member("m1", status = MemberEntity.STATUS_LEFT, placeholderClaimCompletedAt = 200L, updatedAt = 200L)
        // Server still has the pre-claim ACTIVE row (our push hasn't landed); it is *older*.
        val staleActive = member("m1", status = MemberEntity.STATUS_ACTIVE, updatedAt = 100L)

        val fresh = keepNewerMembers(listOf(staleActive), listOf(localLeft))

        assertTrue(fresh.isEmpty(), "stale ACTIVE row must be dropped so the soft-left placeholder is not resurrected")
    }

    @Test
    fun member_legitimateServerUpdate_isApplied_whenNewerThanLocal() {
        val local = member("m1", status = MemberEntity.STATUS_ACTIVE, updatedAt = 100L)
        val newerServer = member("m1", status = MemberEntity.STATUS_LEFT, updatedAt = 300L)

        val fresh = keepNewerMembers(listOf(newerServer), listOf(local))

        assertEquals(listOf(newerServer), fresh, "a genuinely newer server row must win")
    }

    @Test
    fun member_newToDevice_passesThrough() {
        val newServerRow = member("m2", updatedAt = 50L)

        val fresh = keepNewerMembers(listOf(newServerRow), local = emptyList())

        assertEquals(listOf(newServerRow), fresh, "rows the device has never seen must be inserted")
    }

    @Test
    fun displayNameRename_isNotReverted_byStaleServerRow() {
        // Local: user renamed in Settings — ts bumped.
        val renamedLocal = user("u1", displayName = "Alex Chen", updatedAt = 500L)
        // Server still carries the old name; older ts.
        val staleServer = user("u1", displayName = "Alex", updatedAt = 400L)

        val fresh = keepNewerUsers(listOf(staleServer), listOf(renamedLocal))

        assertTrue(fresh.isEmpty(), "stale server row must be dropped so the Settings rename is not reverted")
    }

    @Test
    fun equalTimestamps_keepIncoming_soRepullIsIdempotent() {
        val local = user("u1", displayName = "Sam", updatedAt = 100L)
        val server = user("u1", displayName = "Sam", updatedAt = 100L)

        val fresh = keepNewerUsers(listOf(server), listOf(local))

        assertEquals(listOf(server), fresh, "equal updated_at keeps the incoming row (idempotent re-pull)")
    }

    @Test
    fun mixedBatch_keepsOnlyNonStaleRows() {
        val localStaleLoser = user("u1", displayName = "renamed-local", updatedAt = 500L) // local newer → drop incoming
        val localOlder = user("u2", displayName = "old", updatedAt = 100L) // server newer → keep incoming
        val incoming =
            listOf(
                user("u1", displayName = "server-old", updatedAt = 400L),
                user("u2", displayName = "server-new", updatedAt = 300L),
                user("u3", displayName = "brand-new", updatedAt = 10L), // new to device → keep
            )

        val fresh = keepNewerUsers(incoming, listOf(localStaleLoser, localOlder))

        assertEquals(setOf("u2", "u3"), fresh.map { it.id }.toSet())
    }

    @Test
    fun emptyIncoming_returnsEmpty() {
        assertTrue(keepNewerUsers(emptyList(), listOf(user("u1", "Sam", 100L))).isEmpty())
    }

    // --- selectIn chunking (#22) ---------------------------------------------------------------
    // One unchunked `id=in.(…)` carried every expense id in the URL, so past the gateway's URL limit
    // EVERY pull failed permanently — and #25 reported it as "you're offline".

    @Test
    fun chunkedSelect_splitsPastTheChunkSize_andConcatenatesInOrder() =
        runTest {
            val ids = (1..250).map { "id-$it" }
            val batches = mutableListOf<List<String>>()

            val out =
                SyncEngine.chunkedSelect(ids) { chunk ->
                    batches += chunk
                    chunk
                }

            assertEquals(listOf(100, 100, 50), batches.map { it.size })
            assertEquals(ids, out)
        }

    @Test
    fun chunkedSelect_underTheLimit_sendsOneRequest() =
        runTest {
            var calls = 0
            val ids = (1..100).map { "id-$it" }

            val out =
                SyncEngine.chunkedSelect(ids) {
                    calls++
                    it
                }

            assertEquals(1, calls)
            assertEquals(ids, out)
        }

    @Test
    fun chunkedSelect_empty_sendsNoRequestAtAll() =
        runTest {
            var calls = 0
            assertTrue(
                SyncEngine
                    .chunkedSelect<String>(emptyList()) {
                        calls++
                        it
                    }.isEmpty(),
            )
            assertEquals(0, calls)
        }

    // --- who user_subscriptions is pulled for ----------------------------------------------------
    // A subscription is keyed by PERSON, so it is pulled for the roster. The roster is empty for
    // someone in no groups, and the filter used to be the roster alone — so a subscriber who had not
    // joined a group yet never pulled their OWN row, and Settings went on offering them Pro.

    @Test
    fun subscriberIds_includeSelf_whenInNoGroups() {
        assertEquals(listOf("me"), SyncEngine.subscriberIdsFor(emptyList(), "me"))
    }

    @Test
    fun subscriberIds_includeSelf_alongsideTheRoster() {
        val ids = SyncEngine.subscriberIdsFor(listOf("alice", "bob"), "me")

        assertTrue("me" in ids, "self must always be pulled; got $ids")
        assertEquals(listOf("alice", "bob", "me"), ids)
    }

    @Test
    fun subscriberIds_doNotRepeatSelf_whenAlreadyInTheRoster() {
        assertEquals(listOf("alice", "me"), SyncEngine.subscriberIdsFor(listOf("alice", "me"), "me"))
    }

    // --- error classification (#25) --------------------------------------------------------------
    // Every sync failure used to map to Network.Unreachable, so an RLS denial, a decode drift and a
    // dead radio were indistinguishable — which made every other sync defect invisible in production.

    @Test
    fun classify_decodeFailure_isBackend_notOffline() {
        val e = SyncEngine.classifySyncError(SerializationException("unknown key 'foo'"))

        assertTrue(e is AppError.Backend, "expected Backend, got $e")
        assertEquals("decode", e.code)
    }

    @Test
    fun classify_transportFailure_staysNetworkUnreachable() {
        val e = SyncEngine.classifySyncError(IOException("connection reset"))

        assertTrue(e is AppError.Network, "expected Network, got $e")
        assertEquals(AppError.Network.Kind.Unreachable, e.kind)
    }

    @Test
    fun classify_unknownThrowable_isUnexpected_neverOffline() {
        val e = SyncEngine.classifySyncError(IllegalStateException("bug"))

        assertTrue(e is AppError.Unexpected, "expected Unexpected, got $e")
    }

    // --- the trust horizon (S4) -------------------------------------------------------------------
    // Last-write-wins with no bound on the writing device's clock means one phone set a year forward
    // pins a field on every device that pulls its row: the poisoned stamp beats every honest later edit
    // and no correctly-clocked device can out-stamp it. `horizon` is what makes a stamp past the
    // server's clock read as a wrong clock rather than as a later edit.

    private val now = 1_700_000_000_000L
    private val horizon = now + 60_000L
    private val poisoned = now + 365L * 24 * 3_600_000L

    @Test
    fun aPoisonedLocalRow_acceptsTheNextHonestEdit_insteadOfBeingPinnedForever() {
        // Device C pulled the future-stamped row earlier; B now renames the group correctly.
        val poisonedLocal = user("u1", displayName = "from-the-bad-clock", updatedAt = poisoned)
        val honestServer = user("u1", displayName = "Alex renamed this", updatedAt = now)

        val fresh = SyncEngine.keepNewer(listOf(honestServer), listOf(poisonedLocal), { it.id }, { it.updatedAt }, horizon)

        assertEquals(listOf(honestServer), fresh, "a local stamp past the horizon is a wrong clock, not a later edit")
    }

    @Test
    fun aPoisonedIncomingRow_cannotClobberAGenuinelyNewerLocalEdit() {
        val honestLocal = user("u1", displayName = "renamed-here", updatedAt = now)
        val poisonedServer = user("u1", displayName = "from-the-bad-clock", updatedAt = poisoned)

        val fresh = SyncEngine.keepNewer(listOf(poisonedServer), listOf(honestLocal), { it.id }, { it.updatedAt }, horizon)

        // Compared as if it were the horizon: still newer than an older local row (so a real edit made on
        // a bad phone is not lost), but it cannot beat a local edit made after the horizon.
        assertEquals(listOf(poisonedServer), fresh)
    }

    @Test
    fun theHorizonDoesNotDisturbOrdinaryLastWriteWins() {
        val localNewer = user("u1", displayName = "local", updatedAt = now)
        val serverOlder = user("u1", displayName = "server", updatedAt = now - 3_600_000L)

        val fresh = SyncEngine.keepNewer(listOf(serverOlder), listOf(localNewer), { it.id }, { it.updatedAt }, horizon)

        assertTrue(fresh.isEmpty(), "an honest older server row must still lose to an honest newer local one")
    }

    @Test
    fun theHorizonDoesNotResurrectAClaimedPlaceholder() {
        // The regression this guard could plausibly cause, pinned: both stamps are well inside the
        // horizon, so the soft-left placeholder is still protected exactly as before.
        val localLeft = member("m1", status = MemberEntity.STATUS_LEFT, placeholderClaimCompletedAt = now, updatedAt = now)
        val staleActive = member("m1", status = MemberEntity.STATUS_ACTIVE, updatedAt = now - 1_000)

        val fresh = SyncEngine.keepNewer(listOf(staleActive), listOf(localLeft), { it.id }, { it.updatedAt }, horizon)

        assertTrue(fresh.isEmpty(), "the placeholder guard must survive the horizon rule")
    }

    // --- membership scoping (S5) ------------------------------------------------------------------
    // The memberships query filtered on user_id alone, so a member who LEFT a group kept hydrating its
    // expenses, settlements, receipts and everyone's payment handles onto their phone forever, and kept
    // offering those rows back up on every push. The row itself has to survive (historical shares
    // resolve names through it); what must not survive is its vote on what gets pulled.

    @Test
    fun groupsYouHaveLeft_areNotHydrated() {
        val ids =
            SyncEngine.activeGroupIds(
                listOf(
                    member("m1", updatedAt = 1).copy(groupId = "iceland", status = MemberEntity.STATUS_LEFT),
                    member("m2", updatedAt = 1).copy(groupId = "flat", status = MemberEntity.STATUS_ACTIVE),
                ),
            )

        assertEquals(listOf("flat"), ids)
    }

    @Test
    fun leavingEveryGroup_leavesNothingToPull() {
        val ids =
            SyncEngine.activeGroupIds(
                listOf(member("m1", updatedAt = 1).copy(groupId = "iceland", status = MemberEntity.STATUS_LEFT)),
            )

        assertTrue(ids.isEmpty(), "an ex-member of everything must pull no group's ledger at all")
    }

    @Test
    fun rejoiningAGroupYouLeft_hydratesItAgain() {
        // Both rows exist for the same group (the LEFT one is history, the ACTIVE one is now), and the
        // group has to come back — a filter that keyed off "has any LEFT row" would strand the rejoiner.
        val ids =
            SyncEngine.activeGroupIds(
                listOf(
                    member("m1", updatedAt = 1).copy(groupId = "iceland", status = MemberEntity.STATUS_LEFT),
                    member("m2", updatedAt = 2).copy(groupId = "iceland", status = MemberEntity.STATUS_ACTIVE),
                ),
            )

        assertEquals(listOf("iceland"), ids)
    }

    @Test
    fun duplicateActiveRows_collapseToOneGroupId() {
        val ids =
            SyncEngine.activeGroupIds(
                listOf(
                    member("m1", updatedAt = 1).copy(groupId = "flat"),
                    member("m2", updatedAt = 1).copy(groupId = "flat"),
                ),
            )

        assertEquals(listOf("flat"), ids)
    }
}
