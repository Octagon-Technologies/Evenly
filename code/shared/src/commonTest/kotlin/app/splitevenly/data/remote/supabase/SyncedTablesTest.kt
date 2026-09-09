package app.splitevenly.data.remote.supabase

import app.splitevenly.data.db.dao.WIPED_TABLES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S9 — "the synced table list" was maintained by hand in four places and two already disagreed.
 *
 * The list has one home now (`SyncEngine.SYNCED_TABLES`), `push` and `countPendingLocalWrites` walk the
 * same table objects, and `SyncEngine`'s own `init` check crashes on the next launch if those objects
 * stop matching the list. That leaves two consumers a compiler cannot reach — Room's invalidation
 * tracker and `SignOutWipeDao`'s hand-written `DELETE`s — and this is what reaches them.
 */
class SyncedTablesTest {
    @Test
    fun everySyncedTableIsClearedBySignOutsWipe() {
        val missed = SyncEngine.SYNCED_TABLES.filterNot { it in WIPED_TABLES }

        // A synced table the wipe misses is #24's cross-account leak coming back: the next account on
        // this phone finds the previous one's rows in Room and pushes them up under its own session.
        assertTrue(missed.isEmpty(), "sign-out's wipe does not clear: $missed")
    }

    @Test
    fun theTablesTheInvalidationListUsedToMiss_areInIt() {
        // The two that had drifted out of `SyncManager.SYNC_TABLES`. A local write to either triggered
        // no prompt push at all: it waited for the 60s fallback tick, and if the app was backgrounded
        // inside the 5s grace, for the next foreground syncNow.
        assertTrue("expense_blocked_users" in SyncEngine.SYNCED_TABLES)
        assertTrue("pending_item_edits" in SyncEngine.SYNCED_TABLES)
    }

    @Test
    fun sharesIsNotWatched_becausePushNeverSendsIt() {
        // The drift in the other direction. A bill's shares are a local derived materialization and a
        // normal expense's ride with `merge_expense`, so every `materializeShares` used to fire a full
        // push cycle that carried no share data.
        assertTrue("shares" !in SyncEngine.SYNCED_TABLES)
    }

    @Test
    fun theListHasNoDuplicates() {
        assertEquals(SyncEngine.SYNCED_TABLES.size, SyncEngine.SYNCED_TABLES.toSet().size)
        assertEquals(WIPED_TABLES.size, WIPED_TABLES.toSet().size)
    }
}
