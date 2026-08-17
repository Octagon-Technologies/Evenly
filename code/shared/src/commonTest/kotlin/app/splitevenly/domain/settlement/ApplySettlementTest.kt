package app.splitevenly.domain.settlement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [allocateSameCurrency] (spec 03-business-rules.md §4.1, §4.3.1 —
 * the same-currency settlement allocator; cross-currency is v1.1).
 *
 * Walks the (caller-ordered, oldest-first) shares applying min(remaining, paymentLeft)
 * to each until the payment is exhausted. Invariant:
 * sum(appliedSubunits) == min(payment, sum(remaining)).
 */
class ApplySettlementTest {
    private val USD = "USD"
    private val GBP = "GBP"

    private fun share(
        id: String,
        remaining: Long,
        currency: String = USD,
    ) = ShareBalance(shareId = id, currency = currency, remainingSubunits = remaining)

    // AC-M3-003: I owe $10; I pay $4 → one allocation of 400 (remaining would be 600).
    @Test
    fun acM3003_partialSettlement_appliesPaymentAmount() {
        val shares = listOf(share("s1", remaining = 1000L))

        val allocations = allocateSameCurrency(paymentAmountSubunits = 400L, shares = shares)

        assertEquals(listOf(Allocation(shareId = "s1", appliedSubunits = 400L)), allocations)
    }

    // AC-M3-004: I owe $6; I pay $6 → allocation of 600; share fully cleared.
    @Test
    fun acM3004_fullSettlement_clearsShare() {
        val shares = listOf(share("s1", remaining = 600L))

        val allocations = allocateSameCurrency(paymentAmountSubunits = 600L, shares = shares)

        assertEquals(listOf(Allocation(shareId = "s1", appliedSubunits = 600L)), allocations)
        assertEquals(600L, allocations.sumOf { it.appliedSubunits })
    }

    // AC-M3-011: E1 ($5 remaining, older) and E2 ($10); pay $7 →
    // E1 gets 500 (full), E2 gets 200 (partial). Oldest first.
    @Test
    fun acM3011_partialMultiExpense_paysOldestFirst() {
        val shares =
            listOf(
                share("e1", remaining = 500L),
                share("e2", remaining = 1000L),
            )

        val allocations = allocateSameCurrency(paymentAmountSubunits = 700L, shares = shares)

        assertEquals(
            listOf(
                Allocation(shareId = "e1", appliedSubunits = 500L),
                Allocation(shareId = "e2", appliedSubunits = 200L),
            ),
            allocations,
        )
    }

    // AC-M3-005: the allocator stays within a single currency. Every allocated shareId
    // maps back to a share of the one currency present (USD) — allocations never span
    // currencies in the same-currency path.
    @Test
    fun acM3005_allAllocationsWithinTheSingleShareCurrency() {
        val shares =
            listOf(
                share("s1", remaining = 300L, currency = USD),
                share("s2", remaining = 400L, currency = USD),
            )

        val allocations = allocateSameCurrency(paymentAmountSubunits = 700L, shares = shares)

        val currencyOf = shares.associate { it.shareId to it.currency }
        assertTrue(allocations.all { currencyOf[it.shareId] == USD })
    }

    // Mixed currencies are rejected — the same-currency allocator requires uniformity.
    @Test
    fun mixedCurrencies_throws() {
        val shares =
            listOf(
                share("s1", remaining = 300L, currency = USD),
                share("s2", remaining = 400L, currency = GBP),
            )

        assertFailsWith<IllegalArgumentException> {
            allocateSameCurrency(paymentAmountSubunits = 700L, shares = shares)
        }
    }

    // Zero payment → no allocations.
    @Test
    fun zeroPayment_returnsEmptyList() {
        val shares = listOf(share("s1", remaining = 1000L))

        val allocations = allocateSameCurrency(paymentAmountSubunits = 0L, shares = shares)

        assertTrue(allocations.isEmpty())
    }

    // Shares with remaining == 0 produce no allocation and are skipped over.
    @Test
    fun zeroRemainingShares_produceNoAllocation() {
        val shares =
            listOf(
                share("s0", remaining = 0L),
                share("s1", remaining = 500L),
            )

        val allocations = allocateSameCurrency(paymentAmountSubunits = 500L, shares = shares)

        assertEquals(listOf(Allocation(shareId = "s1", appliedSubunits = 500L)), allocations)
    }

    // A NEGATIVE remaining is a credit (the state Overpayment describes), not a debt to pay down: the
    // share is skipped, never netted against a sibling. The KDoc used to state its invariant over the
    // raw remainders, which made it false for exactly this input — it promised min(1000, 500) = 500 and
    // the function applies 1000 (finding D6). Every production caller filters `remaining > 0` first, so
    // this pins the contract rather than changing behaviour; without it the next caller to pass a raw
    // share set inherits a silent overpayment and nothing says which side is wrong.
    @Test
    fun negativeRemaining_isNotPaidDown_andDoesNotInflateThePayment() {
        val shares = listOf(share("credit", remaining = -500L), share("debt", remaining = 1000L))

        val allocations = allocateSameCurrency(paymentAmountSubunits = 1000L, shares = shares)

        assertEquals(listOf(Allocation(shareId = "debt", appliedSubunits = 1000L)), allocations)
        val payable = shares.sumOf { maxOf(it.remainingSubunits, 0L) }
        assertEquals(minOf(1000L, payable), allocations.sumOf { it.appliedSubunits }, "the documented invariant")
    }

    // …and the cap is still the cap when a credit is in the set: $50 against $3 owed applies $3.
    @Test
    fun negativeRemaining_paymentStillCappedAtWhatIsOwed() {
        val shares = listOf(share("credit", remaining = -500L), share("debt", remaining = 300L))

        val allocations = allocateSameCurrency(paymentAmountSubunits = 5000L, shares = shares)

        assertEquals(300L, allocations.sumOf { it.appliedSubunits })
        assertTrue(allocations.none { it.shareId == "credit" }, "a credit is never allocated against")
    }

    // Overpayment is capped at the total remaining (invariant: sum == min(payment, sumRemaining)).
    @Test
    fun paymentExceedingTotalRemaining_cappedAtSumRemaining() {
        val shares =
            listOf(
                share("e1", remaining = 500L),
                share("e2", remaining = 300L),
            )

        val allocations = allocateSameCurrency(paymentAmountSubunits = 5000L, shares = shares)

        assertEquals(800L, allocations.sumOf { it.appliedSubunits })
        assertEquals(
            listOf(
                Allocation(shareId = "e1", appliedSubunits = 500L),
                Allocation(shareId = "e2", appliedSubunits = 300L),
            ),
            allocations,
        )
    }
}
