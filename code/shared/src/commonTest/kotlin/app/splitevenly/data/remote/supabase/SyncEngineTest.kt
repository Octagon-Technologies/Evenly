package app.splitevenly.data.remote.supabase

import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.entity.UserEntity
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

    private fun user(id: String, displayName: String, updatedAt: Long) = UserEntity(
        id = id,
        displayName = displayName,
        createdAt = 0L,
        updatedAt = updatedAt,
    )

    private fun keepNewerMembers(incoming: List<MemberEntity>, local: List<MemberEntity>) =
        SyncEngine.keepNewer(incoming, local, { it.id }, { it.updatedAt })

    private fun keepNewerUsers(incoming: List<UserEntity>, local: List<UserEntity>) =
        SyncEngine.keepNewer(incoming, local, { it.id }, { it.updatedAt })

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
        val localOlder = user("u2", displayName = "old", updatedAt = 100L)                // server newer → keep incoming
        val incoming = listOf(
            user("u1", displayName = "server-old", updatedAt = 400L),
            user("u2", displayName = "server-new", updatedAt = 300L),
            user("u3", displayName = "brand-new", updatedAt = 10L),                       // new to device → keep
        )

        val fresh = keepNewerUsers(incoming, listOf(localStaleLoser, localOlder))

        assertEquals(setOf("u2", "u3"), fresh.map { it.id }.toSet())
    }

    @Test
    fun emptyIncoming_returnsEmpty() {
        assertTrue(keepNewerUsers(emptyList(), listOf(user("u1", "Sam", 100L))).isEmpty())
    }
}
