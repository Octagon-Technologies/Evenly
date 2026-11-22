package app.splitevenly.domain.settlement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `docs/features/one-time-splits.md` Part B: how an edited payment re-spreads across its bills. */
class PaymentEditTest {
    // Alice's $15 settle-up: Groceries (Nov 12) $9, Internet (Nov 15) $6.
    private val groceries = CoveredPortion("sh-groc", "e-groc", "2026-11-12", 900)
    private val internet = CoveredPortion("sh-net", "e-net", "2026-11-15", 600)
    private val current = listOf(groceries, internet)

    private fun ok(r: PaymentRespread): PaymentRespread.Ok {
        assertTrue(r is PaymentRespread.Ok, "expected Ok, got $r")
        return r
    }

    @Test
    fun reduce_cutsNewestBillFirst() {
        val r = ok(respreadPayment(current, outstandingOldestFirst = emptyList(), newAmountSubunits = 500))
        assertEquals(500, r.newTotalSubunits)
        // Internet (newest) drops to $0 and is omitted; Groceries keeps $5.
        assertEquals(mapOf("sh-groc" to 500L), r.appliedByShare)
    }

    @Test
    fun reduce_withinNewestBill_leavesOlderUntouched() {
        val r = ok(respreadPayment(current, emptyList(), 1200))
        assertEquals(mapOf("sh-groc" to 900L, "sh-net" to 300L), r.appliedByShare)
    }

    @Test
    fun increase_fillsOldestUnpaidBillFirst() {
        // Still owed: an older Rent share ($4) and $1 more on Groceries. The extra $2 goes to Rent.
        val outstanding =
            listOf(
                ShareBalance("sh-rent", "USD", 400),
                ShareBalance("sh-groc", "USD", 100),
            )
        val r = ok(respreadPayment(current, outstanding, 1700))
        assertEquals(1700, r.newTotalSubunits)
        assertEquals(mapOf("sh-groc" to 900L, "sh-net" to 600L, "sh-rent" to 200L), r.appliedByShare)
    }

    @Test
    fun increase_aboveWhatIsOwed_isRefusedWithTheCeiling() {
        val outstanding = listOf(ShareBalance("sh-groc", "USD", 100))
        val r = respreadPayment(current, outstanding, 1700)
        assertEquals(PaymentRespread.Refused(PaymentEditRefusal.AboveCeiling, 1600), r)
    }

    @Test
    fun zeroOrNegative_isRefused() {
        val r = respreadPayment(current, emptyList(), 0)
        assertTrue(r is PaymentRespread.Refused && r.reason == PaymentEditRefusal.NotPositive)
    }

    @Test
    fun scopedEdit_changesOnlyThatBill() {
        // Edited from Groceries' screen: $9 there becomes $4; Internet keeps its $6.
        val r = ok(respreadPayment(current, emptyList(), 400, scopeExpenseId = "e-groc"))
        assertEquals(1000, r.newTotalSubunits)
        assertEquals(mapOf("sh-groc" to 400L, "sh-net" to 600L), r.appliedByShare)
    }

    @Test
    fun scopedIncrease_isCappedAtThatBill_evenWhenOthersAreOwed() {
        val outstanding =
            listOf(
                ShareBalance("sh-rent", "USD", 5000),
                ShareBalance("sh-groc", "USD", 100),
            )
        val refused = respreadPayment(current, outstanding, 1100, scopeExpenseId = "e-groc")
        assertEquals(PaymentRespread.Refused(PaymentEditRefusal.AboveCeiling, 1000), refused)

        val r = ok(respreadPayment(current, outstanding, 1000, scopeExpenseId = "e-groc"))
        assertEquals(mapOf("sh-groc" to 1000L, "sh-net" to 600L), r.appliedByShare)
        assertFalse("sh-rent" in r.appliedByShare, "a single-bill edit never touches another bill")
    }

    @Test
    fun scopedEdit_onABillThePaymentDoesNotCover_isRefused() {
        val r = respreadPayment(current, emptyList(), 100, scopeExpenseId = "e-other")
        assertTrue(r is PaymentRespread.Refused && r.reason == PaymentEditRefusal.NotCovered)
    }

    @Test
    fun parties_areTheTwoPeopleOnly() {
        assertTrue(isPaymentParty("alice", "bob", "alice"))
        assertTrue(isPaymentParty("alice", "bob", "bob"))
        assertFalse(isPaymentParty("alice", "bob", "steve"))
        assertFalse(isPaymentParty("alice", "bob", null))
    }
}
