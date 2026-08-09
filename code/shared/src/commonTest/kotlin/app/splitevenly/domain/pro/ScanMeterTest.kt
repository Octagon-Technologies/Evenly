package app.splitevenly.domain.pro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins when the free-scan meter appears and what it counts (`PRO_PASS_SPEC.md` §8.1). */
class ScanMeterTest {

    private val free = ProStatus.Free
    private val pro = ProStatus(isPro = true, expiresAt = 9_999, purchasedBy = "u_sam", tier = "week_1")

    @Test
    fun hiddenWhileProNoMatterTheCount() {
        // Even a group that burned all 5 before buying sees no counter while the pass is live.
        assertNull(scanMeterFor(pro, used = 5, limit = 5))
        assertNull(scanMeterFor(pro, used = 0, limit = 5))
    }

    @Test
    fun hiddenUntilTheCountIsKnown() {
        // The alternative is showing "5 of 5 left" from a default and then correcting it on screen.
        assertNull(scanMeterFor(free, used = null, limit = 5))
    }

    @Test
    fun hiddenWhilePlentyLeft() {
        // A brand new group counting down from 5 reads as a trial with a clock on it.
        assertNull(scanMeterFor(free, used = 0, limit = 5))
        assertNull(scanMeterFor(free, used = 1, limit = 5))
    }

    @Test
    fun appearsOnceThreeOrFewerRemain() {
        val meter = scanMeterFor(free, used = 2, limit = 5)
        assertNotNull(meter)
        assertEquals(3, meter.remaining)
        assertFalse(meter.isLow)
    }

    @Test
    fun lowAtTheLastOne() {
        val meter = scanMeterFor(free, used = 4, limit = 5)
        assertNotNull(meter)
        assertEquals(1, meter.remaining)
        assertTrue(meter.isLow)
    }

    @Test
    fun exhaustedNeverGoesNegative() {
        // The server can let a group overshoot: two people scanning at once both pass the check, which we
        // accept rather than lock a hot path. The meter must read 0, not "-1 of 5 left".
        val meter = scanMeterFor(free, used = 7, limit = 5)
        assertNotNull(meter)
        assertEquals(0, meter.remaining)
        assertTrue(meter.isLow)
    }
}
