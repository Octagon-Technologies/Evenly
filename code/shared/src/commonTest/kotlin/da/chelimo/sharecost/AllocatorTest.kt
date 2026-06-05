package da.chelimo.sharecost

import da.chelimo.sharecost.core.id.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllocatorTest {

    // AC-M2-001 — Create EVEN split: $30 among 3 → three $10 shares
    @Test
    fun evenSplitThreeWayExact() {
        val a = UserId("user-a"); val b = UserId("user-b"); val c = UserId("user-c")
        val result = allocate(3000L, listOf(a to 1L, b to 1L, c to 1L))
        assertEquals(mapOf(a to 1000L, b to 1000L, c to 1000L), result)
    }

    // AC-M2-002 — Largest-remainder rounding: $10 among 3 → 334, 333, 333
    // Longest-tenured (smallest UserId.value) absorbs the leftover subunit (D-28).
    @Test
    fun evenSplitRemainder_longestTenuredGetsExtraCent() {
        val a = UserId("user-a"); val b = UserId("user-b"); val c = UserId("user-c")
        val result = allocate(1000L, listOf(a to 1L, b to 1L, c to 1L))
        assertEquals(334L, result[a])
        assertEquals(333L, result[b])
        assertEquals(333L, result[c])
        assertEquals(1000L, result.values.sum())
    }

    // AC-M2-003 — BY_SHARE: $100, weights {A:1, B:2, C:2} → [20, 40, 40]
    @Test
    fun byShareSplitNoRemainder() {
        val a = UserId("user-a"); val b = UserId("user-b"); val c = UserId("user-c")
        val result = allocate(10000L, listOf(a to 1L, b to 2L, c to 2L))
        assertEquals(mapOf(a to 2000L, b to 4000L, c to 4000L), result)
    }

    // AC-M2-004 — BY_PERCENTAGE 4-decimal precision: 33.3333%, 33.3334%, 33.3333% of $100
    // Weights: 333333, 333334, 333333 (× 10000). Sum must equal 10000 subunits.
    @Test
    fun byPercentageFourDecimalPrecision() {
        val a = UserId("user-a"); val b = UserId("user-b"); val c = UserId("user-c")
        val result = allocate(
            10000L,
            listOf(a to 333_333L, b to 333_334L, c to 333_333L)
        )
        assertEquals(10000L, result.values.sum())
        result.values.forEach { share ->
            // each share is within 1 subunit of the naive 3333.3... expectation
            assertTrue(share in 3333L..3334L, "share $share out of [3333, 3334]")
        }
    }

    // Order-independence: passing the same weights in a different order yields the same map.
    @Test
    fun orderIndependence_shuffledInputProducesSameMap() {
        val a = UserId("user-a"); val b = UserId("user-b"); val c = UserId("user-c")
        val abc = allocate(1000L, listOf(a to 1L, b to 1L, c to 1L))
        val cab = allocate(1000L, listOf(c to 1L, a to 1L, b to 1L))
        assertEquals(abc, cab)
    }

    // EVEN tiebreak (D-28): equal fracs → UserId.value ascending → "a" < "b" < "c"
    // 1000 among 3 EVEN: remainder = 1; UserId("a") gets 334, the others get 333.
    @Test
    fun evenTiebreak_longestTenuredByUserIdValueAscending() {
        val a = UserId("a"); val b = UserId("b"); val c = UserId("c")
        val result = allocate(1000L, listOf(a to 1L, b to 1L, c to 1L))
        assertEquals(334L, result[a], "a (smallest value) should absorb the leftover subunit")
        assertEquals(333L, result[b])
        assertEquals(333L, result[c])
        assertEquals(1000L, result.values.sum())
    }
}
