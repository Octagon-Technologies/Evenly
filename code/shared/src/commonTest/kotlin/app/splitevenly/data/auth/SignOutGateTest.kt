package app.splitevenly.data.auth

import app.splitevenly.domain.auth.SignOutOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2 — what stands between sign-out and permanently deleting someone's only copy of their expenses.
 *
 * Two independent holes, both here:
 *
 * 1. The count was only taken when `push()` returned `Err`. But `Ok` does not mean every row reached
 *    the server: #8's in-flight-edit guard deliberately leaves an expense dirty when a local edit lands
 *    during the round trip, and that path throws nothing, so the push reports success with a
 *    local-only expense still sitting there. `signOut` now asks on every attempt — the count is the
 *    only honest question, and it is exactly the comparison `push` would make.
 * 2. The count failed **open**: `runCatching { … }.getOrDefault(0)` turned a failing DB read into
 *    "nothing pending, safe to wipe" — the most permissive answer available, from the one read the
 *    whole wipe is authorised by.
 */
class SignOutGateTest {
    @Test
    fun aFailingCountIsNotReadAsNothingPending() =
        runTest {
            val pending = pendingWritesOrUnknown { throw IllegalStateException("Room is wedged") }

            assertEquals(PENDING_UNKNOWN, pending)
            assertTrue(pending != 0, "a failed read must never authorise the wipe")
        }

    @Test
    fun anUnknownCountStopsAndAsks_withoutInventingANumber() {
        val question = unsyncedChangesFor(PENDING_UNKNOWN)

        assertEquals(SignOutOutcome.UnsyncedChanges(null), question)
        assertNull(question?.pendingWrites, "we could not count, so the dialog must not show a count")
    }

    @Test
    fun aRealCountIsAskedAboutWithTheNumber() {
        assertEquals(SignOutOutcome.UnsyncedChanges(3), unsyncedChangesFor(3))
        assertEquals(SignOutOutcome.UnsyncedChanges(1), unsyncedChangesFor(1))
    }

    @Test
    fun onlyAnActualZeroAuthorisesTheWipe() =
        runTest {
            assertEquals(0, pendingWritesOrUnknown { 0 })
            assertNull(unsyncedChangesFor(0), "nothing pending is the one case that may wipe")
        }

    @Test
    fun asuccessfulCountPassesThrough() =
        runTest {
            assertEquals(7, pendingWritesOrUnknown { 7 })
        }
}
