package app.splitevenly.domain.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a converted amount stops being a `Double`, and how often (finding R14).
 *
 * The rate has to be floating point — it is not money — so the rule worth pinning is not "avoid doubles",
 * it is "round once per figure a person reads". These pin the arithmetic and, more importantly, the size
 * and direction of the error the old per-share rounding produced, so nobody reintroduces it thinking it
 * was equivalent.
 */
class ConvertTest {
    @Test
    fun convertingIsRoundedToWholeSubunits() {
        assertEquals(400L, convertSubunits(333L, 1.2))
        assertEquals(0L, convertSubunits(0L, 1.2))
        assertEquals(1L, convertSubunits(1L, 0.5), "half rounds up, not toward zero")
    }

    @Test
    fun roundingEachShareThenSumming_doesNotAgreeWithSummingThenRoundingOnce() {
        // Fifteen EUR shares of 333 subunits at a rate that lands each one on a half-subunit.
        val shares = List(15) { 333L }
        val rate = 1.5

        val perShare = shares.sumOf { convertSubunits(it, rate) }
        val netted = convertSubunits(shares.sum(), rate)

        assertEquals(15L * 500L, perShare)
        assertEquals(7493L, netted)
        assertTrue(perShare != netted, "seven subunits of drift, from nothing but where the rounding sat")
    }

    /** The direction of the drift follows the rate, which is why it cannot be corrected after the fact. */
    @Test
    fun theDriftChangesSignWithTheRate() {
        val shares = List(15) { 333L }

        val up = shares.sumOf { convertSubunits(it, 1.5) } - convertSubunits(shares.sum(), 1.5)
        val down = shares.sumOf { convertSubunits(it, 1.4) } - convertSubunits(shares.sum(), 1.4)

        assertTrue(up > 0)
        assertTrue(down < 0)
    }
}
