package da.chelimo.sharecost.ui.screen.expense

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The split editor's math (SplitLogic.kt) — every mode must produce shares that sum to the total. */
class SplitLogicTest {

    private val ids = listOf("a", "b", "c", "d")

    @Test
    fun even_distributes_remainder_and_sums_to_total() {
        val owed = splitOwed(SplitMode.Even, 10001, listOf("a", "b", "c"))
        assertEquals(10001, owed.values.sum())
        assertEquals(3334, owed["a"]) // 10001 / 3 = 3333 r2 → first two ids get the extra subunit
        assertEquals(3334, owed["b"])
        assertEquals(3333, owed["c"])
    }

    @Test
    fun share_weights_drive_proportional_split_summing_to_total() {
        val owed = splitOwed(SplitMode.Share, 10000, listOf("a", "b", "c"), units = mapOf("a" to 2, "b" to 1, "c" to 1))
        assertEquals(10000, owed.values.sum())
        assertEquals(5000, owed["a"])
        assertEquals(2500, owed["b"])
        assertEquals(2500, owed["c"])
    }

    @Test
    fun share_with_no_positive_weight_is_empty() {
        assertTrue(splitOwed(SplitMode.Share, 10000, listOf("a", "b"), units = mapOf("a" to 0, "b" to 0)).isEmpty())
    }

    @Test
    fun percent_allocates_full_total_even_when_percentages_fall_short_of_100() {
        // The design's 25/25/25/24.95 example sums to 99.95% — Save stays gated (percentTotalScaled
        // != 10000) — yet splitOwed still hands out the full total, normalizing by the weights.
        val percents = mapOf("a" to 25.0, "b" to 25.0, "c" to 25.0, "d" to 24.95)
        val owed = splitOwed(SplitMode.Percent, 9995, ids, percents = percents)
        assertEquals(9995, owed.values.sum())
        assertEquals(2495, owed["d"]) // 24.95% of 9995 = 2495 → the smallest cut; the rest get 2500
        assertEquals(9995L, percentTotalScaled(ids, percents)) // 99.95% — short of 100.00, so not saveable
    }

    @Test
    fun percent_summing_to_100_clears_the_save_gate() {
        val percents = mapOf("a" to 25.0, "b" to 25.0, "c" to 25.0, "d" to 25.0)
        assertEquals(10000L, percentTotalScaled(ids, percents))
        assertEquals(2500L, splitOwed(SplitMode.Percent, 10000, ids, percents = percents)["a"])
    }

    @Test
    fun exact_is_a_pass_through_of_typed_amounts() {
        val exact = mapOf("a" to 2500L, "b" to 2500L, "c" to 2000L, "d" to 2500L)
        val owed = splitOwed(SplitMode.Exact, 9500, ids, exact = exact)
        assertEquals(exact, owed)
        assertEquals(9500L, exactTotalSubunits(ids, exact))
    }

    @Test
    fun splitOwed_is_empty_for_no_amount_or_no_participants() {
        assertTrue(splitOwed(SplitMode.Even, 0, ids).isEmpty())
        assertTrue(splitOwed(SplitMode.Even, 1000, emptyList()).isEmpty())
    }

    @Test
    fun distributeRemainder_seeds_an_even_split_summing_to_100() {
        // 100.00 / 3 = 33.34 + 33.33 + 33.33; the first id carries the leftover hundredth.
        val seeded = distributeRemainder(listOf("a", "b", "c"), emptyMap())
        assertEquals(mapOf("a" to 33.34, "b" to 33.33, "c" to 33.33), seeded)
        assertEquals(10000L, percentTotalScaled(listOf("a", "b", "c"), seeded))
    }

    @Test
    fun distributeRemainder_fills_zeros_then_rebalances_overshoot() {
        // shortfall lands on the participants still at 0%
        val filled = distributeRemainder(listOf("a", "b", "c"), mapOf("a" to 50.0))
        assertEquals(mapOf("a" to 50.0, "b" to 25.0, "c" to 25.0), filled)
        // overshoot is spread across everyone
        val trimmed = distributeRemainder(listOf("a", "b"), mapOf("a" to 60.0, "b" to 60.0))
        assertEquals(mapOf("a" to 50.0, "b" to 50.0), trimmed)
    }

    @Test
    fun format2dp_always_renders_two_decimals() {
        assertEquals("0.00", format2dp(0.0))
        assertEquals("25.00", format2dp(25.0))
        assertEquals("33.34", format2dp(33.34))
        assertEquals("100.00", format2dp(100.0))
    }
}
