package app.splitevenly.domain.pro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the client mirror of the server's `group_pro_status` against the same cases the SQL side was
 * verified with. The two implementations exist so a Pro badge costs no round trip
 * (`PRO_PASS_SPEC.md` §5.2); these cases are what keeps them from drifting apart.
 */
class ProStatusTest {

    private fun pass(expiresAt: Long, by: String = "u_sam", tier: String = "week_1", revokedAt: Long? = null) =
        ProPass(expiresAt = expiresAt, purchasedBy = by, tier = tier, revokedAt = revokedAt)

    @Test
    fun noPassesIsFree() {
        assertEquals(ProStatus.Free, proStatusOf(emptyList(), now = 500))
    }

    @Test
    fun latestExpiringPassWins() {
        // Three live passes: the answer must be the one that expires LAST, not the newest or the
        // priciest. This is what makes stacking work without any stacking code.
        val status = proStatusOf(
            listOf(
                pass(1000, by = "u_sam", tier = "week_1"),
                pass(3000, by = "u_ada", tier = "week_2"),
                pass(9000, by = "u_bob", tier = "month_1"),
            ),
            now = 500,
        )
        assertTrue(status.isPro)
        assertEquals(9000, status.expiresAt)
        assertEquals("u_bob", status.purchasedBy)
        assertEquals("month_1", status.tier)
    }

    @Test
    fun expiryBoundaryIsStrict() {
        val one = listOf(pass(5000))
        assertTrue(proStatusOf(one, now = 4999).isPro)
        // Exactly at the expiry the pass has run out. Pinned because an off-by-one here is a group that
        // silently keeps scanning, or loses Pro a moment early on someone's last dinner.
        assertFalse(proStatusOf(one, now = 5000).isPro)
        assertFalse(proStatusOf(one, now = 5001).isPro)
    }

    @Test
    fun revokedPassIsIgnored() {
        // Refund or chargeback. Still in date, still not Pro.
        assertFalse(proStatusOf(listOf(pass(9000, revokedAt = 10)), now = 500).isPro)
    }

    @Test
    fun revokedPassDoesNotHideALiveOne() {
        // Two friends bought; one refunded. The group keeps what the other one paid for.
        val status = proStatusOf(
            listOf(pass(9000, by = "u_bob", revokedAt = 10), pass(3000, by = "u_ada")),
            now = 500,
        )
        assertTrue(status.isPro)
        assertEquals(3000, status.expiresAt)
        assertEquals("u_ada", status.purchasedBy)
    }

    @Test
    fun allPassesExpiredIsFree() {
        val status = proStatusOf(listOf(pass(100), pass(200)), now = 5000)
        assertEquals(ProStatus.Free, status)
    }
}
