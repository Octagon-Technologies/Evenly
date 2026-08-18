package app.splitevenly.core.time

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S4, the write half — a device's own clock cannot stamp a row arbitrarily far into the future.
 *
 * Client sync outside `merge_expense` is last-write-wins on `updated_at`, and `updated_at` is whatever
 * the writing phone's clock said. One device set a year forward pins every field it touches on every
 * device that pulls the row, because no correctly-clocked phone can ever out-stamp it. The bound here
 * mirrors the server's `_clamp_client_ts` (`least(ts, now() + 60000)`) so the two halves agree; if they
 * disagreed, one of them would be dropping writes the other accepted.
 */
class ServerClockTest {
    @BeforeTest
    fun reset() = ServerClock.resetForTest()

    @AfterTest
    fun tearDown() = ServerClock.resetForTest()

    private val hour = 3_600_000L
    private val year = 365L * 24 * hour

    @Test
    fun withNoServerResponseSeenYet_clampIsTheIdentity() {
        // Deliberate: this is what keeps the clamp inert offline, inert in tests, and inert for every
        // device whose clock was never the problem.
        assertNull(ServerClock.offsetOrNull())
        assertEquals(1_700_000_000_000L, ServerClock.clamp(1_700_000_000_000L))
    }

    @Test
    fun aDeviceRunningAYearFast_stampsTheServersTimePlusTheTolerance() {
        val serverNow = 1_700_000_000_000L
        val deviceNow = serverNow + year

        ServerClock.observe(serverEpochMillis = serverNow, deviceEpochMillis = deviceNow)

        assertEquals(serverNow + ServerClock.SKEW_TOLERANCE_MS, ServerClock.clamp(deviceNow))
    }

    @Test
    fun aDeviceWithinTheTolerance_isLeftAlone() {
        val serverNow = 1_700_000_000_000L
        val deviceNow = serverNow + 5_000 // five seconds fast: ordinary, and not worth rewriting

        ServerClock.observe(serverEpochMillis = serverNow, deviceEpochMillis = deviceNow)

        assertEquals(deviceNow, ServerClock.clamp(deviceNow))
    }

    @Test
    fun aSlowDeviceIsNeverPushedForward() {
        // Only the upper bound is ours to enforce. Moving a slow clock forward would hand it writes it
        // did not make at that moment, and a slow clock loses last-write-wins rather than poisoning it.
        val serverNow = 1_700_000_000_000L
        val deviceNow = serverNow - hour

        ServerClock.observe(serverEpochMillis = serverNow, deviceEpochMillis = deviceNow)

        assertEquals(deviceNow, ServerClock.clamp(deviceNow))
    }

    @Test
    fun theTrustHorizonSitsJustAboveTheServersClock() {
        val serverNow = 1_700_000_000_000L
        val deviceNow = serverNow + year

        ServerClock.observe(serverEpochMillis = serverNow, deviceEpochMillis = deviceNow)

        val horizon = ServerClock.trustHorizonMillis(deviceNow)
        assertEquals(serverNow + ServerClock.SKEW_TOLERANCE_MS, horizon)
        // The poisoned stamp this device would have written sits above it — which is what lets
        // `SyncEngine.keepNewer` recognise it as a wrong clock rather than as a later edit.
        assertTrue(deviceNow > horizon)
    }

    @Test
    fun aClampedStampNeverExceedsItsOwnHorizon() {
        val serverNow = 1_700_000_000_000L
        val deviceNow = serverNow + year
        ServerClock.observe(serverEpochMillis = serverNow, deviceEpochMillis = deviceNow)

        // The invariant the two halves rest on: what we write is always something we would believe.
        assertTrue(ServerClock.clamp(deviceNow) <= ServerClock.trustHorizonMillis(deviceNow))
    }

    @Test
    fun theTolerationMatchesTheServersClamp() {
        // `_clamp_client_ts` in supabase/schema.sql is `least(ts, now()*1000 + 60000)`. These two move
        // together or one side starts dropping writes the other side accepted.
        assertEquals(60_000L, ServerClock.SKEW_TOLERANCE_MS)
    }
}
