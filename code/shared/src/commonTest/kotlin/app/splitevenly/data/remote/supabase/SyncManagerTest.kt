package app.splitevenly.data.remote.supabase

import app.splitevenly.core.id.UserId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic tests for [SyncManager.gatedUser], the gate that decides when the sync driver (Realtime
 * socket + push-on-write + the 60s tick) is allowed to run: only while the app is signed in AND on
 * screen. No Supabase client / Room needed, so this runs on every target via commonTest — the same
 * `internal`-pure-function pattern as [SyncEngineTest].
 *
 * The asymmetry under test: leaving the app (foreground → false) waits out a grace before stopping
 * everything (so activity recreation / a glance at the app switcher doesn't churn the socket), but a
 * sign-out (userId → null) stops it IMMEDIATELY, never delayed by that grace.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncManagerTest {

    private val alice = UserId("alice")
    private val bob = UserId("bob")
    private val grace = 5_000L

    /** Collect gatedUser into a list on the background scope (runTest auto-cancels it at the end). */
    private fun TestScope.collectGate(user: MutableStateFlow<UserId?>, fg: MutableStateFlow<Boolean>): List<UserId?> {
        val seen = mutableListOf<UserId?>()
        backgroundScope.launch { SyncManager.gatedUser(user, fg, grace).toList(seen) }
        runCurrent()
        return seen
    }

    @Test
    fun foregroundAndSignedIn_emitsTheUser() = runTest {
        val seen = collectGate(MutableStateFlow(alice), MutableStateFlow(true))
        assertEquals(listOf(alice), seen)
    }

    @Test
    fun signedInButBackgrounded_emitsNullOnlyAfterGrace() = runTest {
        val fg = MutableStateFlow(true)
        val seen = collectGate(MutableStateFlow(alice), fg)
        fg.value = false
        advanceTimeBy(grace - 1); runCurrent()
        assertEquals(listOf(alice), seen, "still within grace → no teardown yet")
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(alice, null), seen)
    }

    @Test
    fun backgroundThenForegroundWithinGrace_neverEmits() = runTest {
        val fg = MutableStateFlow(true)
        val seen = collectGate(MutableStateFlow(alice), fg)
        fg.value = false                    // start the grace countdown
        advanceTimeBy(grace - 1_000); runCurrent()
        fg.value = true                     // come back before it elapses
        advanceTimeBy(grace * 2); runCurrent()
        // The transient background is absorbed — the socket never churned.
        assertEquals(listOf(alice), seen)
    }

    @Test
    fun signOut_emitsNullImmediately_ignoringGrace() = runTest {
        val user = MutableStateFlow<UserId?>(alice)
        val seen = collectGate(user, MutableStateFlow(true))
        user.value = null                   // sign out while still on screen
        runCurrent()                        // no grace applied to the user side
        assertEquals(listOf(alice, null), seen)
    }

    @Test
    fun userSwitch_reEmitsTheNewUser() = runTest {
        val user = MutableStateFlow<UserId?>(alice)
        val seen = collectGate(user, MutableStateFlow(true))
        user.value = bob
        runCurrent()
        assertEquals(listOf(alice, bob), seen)
    }
}
