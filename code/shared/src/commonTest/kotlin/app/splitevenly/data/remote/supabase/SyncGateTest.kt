package app.splitevenly.data.remote.supabase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S1 — the fence around sign-out's cache wipe.
 *
 * The defect these pin is an *ordering* one, which is why it survived a wipe test that passed: the DAO
 * emptied every table correctly, and then a pull that had been sitting on the sync mutex behind
 * sign-out's own push woke up, ran with the departing account's session still valid, and put all of it
 * back. The next account on the phone then read the previous one's groups, expenses and payment
 * handles. Nothing in the suite failed, because nothing in the suite ran a pull concurrently with the
 * wipe.
 *
 * `runCurrent()` between the launches is what makes the interleaving deterministic rather than hopeful:
 * each coroutine is advanced to its suspension point in the order the failing sequence describes.
 */
class SyncGateTest {
    private companion object {
        const val A = "account-a"
        const val B = "account-b"
    }

    @Test
    fun pullQueuedBehindSignOutsPush_isRefused_andTheWipeRunsAfterTheInFlightWork() =
        runTest {
            val gate = SyncGate()
            val order = mutableListOf<String>()
            val inFlightMayFinish = CompletableDeferred<Unit>()

            // 1. A pull (or sign-out's own push) is already running and holding the lock.
            launch {
                gate.withSync(A) {
                    inFlightMayFinish.await()
                    order += "in-flight"
                }
            }
            runCurrent()

            // 2. PushController's ungated pull queues behind it — this is the one that used to re-land
            //    account A's rows into the cache the wipe had just emptied.
            launch {
                val ran = gate.withSync(A) { order += "queued-pull" }
                if (ran == null) order += "queued-pull-refused"
            }
            runCurrent()

            // 3. Sign out. Closing happens before the lock is requested, so the queued pull sees it.
            launch { gate.closeForSignOut(A) { order += "wipe" } }
            runCurrent()

            inFlightMayFinish.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("in-flight", "queued-pull-refused", "wipe"), order)
        }

    @Test
    fun theWipeWaitsForWorkAlreadyInFlight_ratherThanInterleavingWithIt() =
        runTest {
            val gate = SyncGate()
            val order = mutableListOf<String>()
            val inFlightMayFinish = CompletableDeferred<Unit>()

            launch {
                gate.withSync(A) {
                    order += "pull-start"
                    inFlightMayFinish.await()
                    order += "pull-end"
                }
            }
            runCurrent()

            launch { gate.closeForSignOut(A) { order += "wipe" } }
            runCurrent()

            // The wipe must not have started while the pull was still writing rows: wiping first and
            // letting the pull finish afterwards is the same leak with the steps swapped.
            assertEquals(listOf("pull-start"), order)

            inFlightMayFinish.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("pull-start", "pull-end", "wipe"), order)
        }

    @Test
    fun everySyncAfterSignOut_isRefusedForThatAccount() =
        runTest {
            val gate = SyncGate()
            gate.closeForSignOut(A) { }

            assertNull(gate.withSync(A) { "pulled" }, "a pull after the wipe must not run")
            assertTrue(gate.isClosedFor(A))
        }

    @Test
    fun anotherAccountSigningIn_syncsNormally_andDoesNotUnblockTheOneThatLeft() =
        runTest {
            val gate = SyncGate()
            gate.closeForSignOut(A) { }

            // B is a different account: never fenced, so its own sync works immediately.
            assertEquals("pulled", gate.withSync(B) { "pulled" })

            // And B's sign-in must not lift A's fence. The latch is keyed by user id for exactly this:
            // a stale pull queued for A would otherwise be handed permission to run by B's arrival.
            gate.reopen(B)
            assertTrue(gate.isClosedFor(A), "B signing in must not reopen sync for A")
            assertNull(gate.withSync(A) { "pulled" })
        }

    @Test
    fun theSameAccountSigningBackIn_reopensSync() =
        runTest {
            val gate = SyncGate()
            gate.closeForSignOut(A) { }

            gate.reopen(A)

            assertFalse(gate.isClosedFor(A))
            assertEquals("pulled", gate.withSync(A) { "pulled" }, "signing back in must restore sync")
        }

    @Test
    fun abortedSignOut_leavesSyncRunning() =
        runTest {
            val gate = SyncGate()
            // The unsynced-changes branch: the fence closes, nothing is wiped, the user stays signed in.
            val wiped = gate.closeForSignOut(A) { false }
            assertFalse(wiped)

            gate.reopen(A)

            // Without the reopen this device would never sync again while it stayed signed in — which is
            // a worse outcome than the sign-out the user cancelled.
            assertEquals(Unit, gate.withSync(A) { })
        }
}
