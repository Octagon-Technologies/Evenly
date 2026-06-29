package da.chelimo.sharecost.domain.expense

import da.chelimo.sharecost.core.id.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BillSplitTest {

    private val A = UserId("a")
    private val B = UserId("b")
    private val C = UserId("c")

    // Each claimed unit costs exactly its unit price — no rounding at the item level.
    @Test
    fun countableUnits_eachPaysExactlyTheirUnits() {
        val items = listOf(BillItem("pizza", unitPriceSubunits = 1800L)) // 4 ordered @ $18.00
        val claims = listOf(
            BillClaim("pizza", A, weight = 2),
            BillClaim("pizza", B, weight = 1),
            BillClaim("pizza", C, weight = 1),
        )
        val result = itemSubtotals(items, claims)
        assertEquals(3600L, result[A])
        assertEquals(1800L, result[B])
        assertEquals(1800L, result[C])
    }

    // Claiming fewer units than were ordered charges only the claimed units; the rest stays unassigned.
    @Test
    fun partialClaim_chargesOnlyClaimedUnits() {
        val items = listOf(BillItem("pizza", 1000L)) // 4 ordered, only 2 claimed
        val result = splitBill(items, listOf(BillClaim("pizza", A, 2)), BillExtras())
        assertEquals(mapOf(A to 2000L), result) // 2 × $10, NOT 4 × $10
    }

    // Tax + gratuity proportional, tip even (the default) — the headline dinner case.
    @Test
    fun taxGratuityProportional_tipEven_default() {
        val items = listOf(BillItem("a-meal", 6000L), BillItem("b-meal", 4000L))
        val claims = listOf(BillClaim("a-meal", A, 1), BillClaim("b-meal", B, 1))
        val extras = BillExtras(
            taxSubunits = 800L,        // tax + gratuity = 2000 proportional → A 1200 / B 800
            gratuitySubunits = 1200L,
            tipSubunits = 1000L,       // even → 500 / 500
            tipSplitMode = TipSplitMode.EVEN,
        )
        val result = splitBill(items, claims, extras)
        assertEquals(6000L + 1200L + 500L, result[A]) // 7700
        assertEquals(4000L + 800L + 500L, result[B])  // 5300
        assertEquals(13000L, result.values.sum())
    }

    // Tip proportional toggle — heavier orderer carries more tip.
    @Test
    fun tipProportional_followsSubtotalRatio() {
        val items = listOf(BillItem("a-meal", 6000L), BillItem("b-meal", 4000L))
        val claims = listOf(BillClaim("a-meal", A, 1), BillClaim("b-meal", B, 1))
        val result = splitBill(items, claims, BillExtras(tipSubunits = 2000L, tipSplitMode = TipSplitMode.PROPORTIONAL))
        assertEquals(7200L, result[A])
        assertEquals(4800L, result[B])
    }

    // Discount reduces each share proportionally; total reconciles to subtotal − discount.
    @Test
    fun discount_reducesProportionally() {
        val items = listOf(BillItem("a-meal", 6000L), BillItem("b-meal", 4000L))
        val claims = listOf(BillClaim("a-meal", A, 1), BillClaim("b-meal", B, 1))
        val result = splitBill(items, claims, BillExtras(discountSubunits = 1000L)) // A −600 / B −400
        assertEquals(5400L, result[A])
        assertEquals(3600L, result[B])
        assertEquals(9000L, result.values.sum())
    }

    // The proportional extras still divide indivisibly via largest-remainder — penny-exact across people.
    @Test
    fun proportionalExtra_indivisible_isPennyExact() {
        val items = listOf(BillItem("x", 100L), BillItem("y", 100L), BillItem("z", 100L))
        val claims = listOf(BillClaim("x", A, 1), BillClaim("y", B, 1), BillClaim("z", C, 1))
        // Subtotals 100/100/100; $10 tax over three equal shares → 334/333/333 by largest-remainder.
        val result = splitBill(items, claims, BillExtras(taxSubunits = 1000L))
        assertEquals(1300L, result.values.sum())                       // 300 subtotal + 1000 tax
        assertEquals(listOf(434L, 433L, 433L), result.values.sortedDescending())
    }

    // The grand invariant: Σ shares == Σ(claimed units × price) + tax + gratuity − discount + tip.
    @Test
    fun invariant_sumEqualsBillTotal() {
        val items = listOf(BillItem("x", 577L), BillItem("y", 999L), BillItem("z", 205L))
        val claims = listOf(
            BillClaim("x", A, 1), BillClaim("x", B, 1), BillClaim("x", C, 1), // 3 × 577
            BillClaim("y", A, 1),                                              // 999
            BillClaim("z", B, 2), BillClaim("z", C, 1),                        // 3 × 205
        )
        val subtotal = 3 * 577L + 999L + 3 * 205L
        val extras = BillExtras(
            taxSubunits = 271L, gratuitySubunits = 533L,
            tipSubunits = 700L, tipSplitMode = TipSplitMode.EVEN,
            discountSubunits = 150L,
        )
        val expectedTotal = subtotal + 271L + 533L + 700L - 150L
        assertEquals(expectedTotal, splitBill(items, claims, extras).values.sum())
    }

    // Over-claiming (more units than were ordered) just costs more — surfaced in the UI, never capped.
    @Test
    fun overClaim_chargesEveryClaimedUnit() {
        val items = listOf(BillItem("beer", 900L)) // 2 ordered, but 3 units get claimed
        val result = itemSubtotals(items, listOf(BillClaim("beer", A, 1), BillClaim("beer", B, 1), BillClaim("beer", C, 1)))
        assertEquals(900L, result[A])
        assertEquals(2700L, result.values.sum()) // 3 × $9, exceeding the 2-unit line — by design
    }

    // Unclaimed items contribute nothing (they're resolved before shares are derived).
    @Test
    fun unclaimedItems_areIgnored() {
        val items = listOf(BillItem("claimed", 1000L), BillItem("orphan", 5000L))
        val result = splitBill(items, listOf(BillClaim("claimed", A, 1)), BillExtras())
        assertEquals(mapOf(A to 1000L), result)
    }

    // Nothing claimed → empty result, never a divide-by-zero.
    @Test
    fun nothingClaimed_returnsEmpty() {
        val items = listOf(BillItem("a", 1000L))
        assertTrue(splitBill(items, emptyList(), BillExtras(taxSubunits = 100L)).isEmpty())
    }
}
