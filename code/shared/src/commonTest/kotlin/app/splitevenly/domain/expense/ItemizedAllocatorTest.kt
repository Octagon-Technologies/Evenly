package app.splitevenly.domain.expense

import app.splitevenly.core.id.UserId
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemizedAllocatorTest {

    private val A = UserId("a")
    private val B = UserId("b")

    // AC-M2-016: proportional tip, no tax — heavier payer gets proportionally more tip
    @Test
    fun proportionalTip_noTax_sharesMatchSubtotalRatio() {
        val result = itemizedShares(
            subtotals = listOf(A to 6000L, B to 4000L),
            taxSubunits = 0L,
            tipSubunits = 2000L,
            tipSplitMode = TipSplitMode.PROPORTIONAL
        )
        assertEquals(7200L, result[A])
        assertEquals(4800L, result[B])
        assertEquals(12000L, result.values.sum())
    }

    // AC-M2-017: even tip, no tax — tip split equally regardless of subtotal
    @Test
    fun evenTip_noTax_tipSplitEqually() {
        val result = itemizedShares(
            subtotals = listOf(A to 6000L, B to 4000L),
            taxSubunits = 0L,
            tipSubunits = 2000L,
            tipSplitMode = TipSplitMode.EVEN
        )
        assertEquals(7000L, result[A])
        assertEquals(5000L, result[B])
        assertEquals(12000L, result.values.sum())
    }

    // AC-M2-018: proportional tax + even tip
    @Test
    fun proportionalTax_evenTip_finalSharesCorrect() {
        val result = itemizedShares(
            subtotals = listOf(A to 6000L, B to 4000L),
            taxSubunits = 1000L,
            tipSubunits = 1500L,
            tipSplitMode = TipSplitMode.EVEN
        )
        assertEquals(7350L, result[A])
        assertEquals(5150L, result[B])
        assertEquals(12500L, result.values.sum())
    }

    // AC-INV-012: sum(output) == sum(subtotals) + tax + tip for all non-zero cases
    @Test
    fun invariant_outputSumEqualsSubtotalsPlusTaxPlusTip_proportionalTip() {
        val subtotals = listOf(A to 6000L, B to 4000L)
        val result = itemizedShares(subtotals, taxSubunits = 0L, tipSubunits = 2000L, TipSplitMode.PROPORTIONAL)
        assertEquals(subtotals.sumOf { it.second } + 0L + 2000L, result.values.sum())
    }

    // AC-INV-012: invariant holds for even tip
    @Test
    fun invariant_outputSumEqualsSubtotalsPlusTaxPlusTip_evenTip() {
        val subtotals = listOf(A to 6000L, B to 4000L)
        val result = itemizedShares(subtotals, taxSubunits = 0L, tipSubunits = 2000L, TipSplitMode.EVEN)
        assertEquals(subtotals.sumOf { it.second } + 0L + 2000L, result.values.sum())
    }

    // AC-INV-012: invariant holds when both tax and tip are non-zero
    @Test
    fun invariant_outputSumEqualsSubtotalsPlusTaxPlusTip_taxAndEvenTip() {
        val subtotals = listOf(A to 6000L, B to 4000L)
        val result = itemizedShares(subtotals, taxSubunits = 1000L, tipSubunits = 1500L, TipSplitMode.EVEN)
        assertEquals(subtotals.sumOf { it.second } + 1000L + 1500L, result.values.sum())
    }

    // Zero tax and zero tip: output must equal subtotals exactly
    @Test
    fun zeroTaxAndZeroTip_outputMatchesSubtotalsExactly() {
        val subtotals = listOf(A to 6000L, B to 4000L)
        val result = itemizedShares(subtotals, taxSubunits = 0L, tipSubunits = 0L, TipSplitMode.PROPORTIONAL)
        assertEquals(6000L, result[A])
        assertEquals(4000L, result[B])
        assertEquals(subtotals.toMap().mapKeys { it.key }, result)
    }
}
