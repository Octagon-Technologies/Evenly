package app.splitevenly.domain.balance

import app.splitevenly.core.id.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [buildBilateralBalances] (spec 03-business-rules.md §2.2, D-02).
 *
 * Bilateral balances are computed strictly per (debtor, creditor, currency) pair.
 * The engine NEVER simplifies across triangles and NEVER nets across currencies.
 */
class BilateralBalanceTest {

    private val A = UserId("a")
    private val B = UserId("b")
    private val C = UserId("c")

    private val USD = "USD"
    private val EUR = "EUR"

    /** A share where [payerUserId] paid and [participantUserId] owes their portion. */
    private fun share(
        payer: UserId,
        participant: UserId,
        remaining: Long,
        currency: String = USD,
        expenseId: String = "e1",
    ) = Share(
        expenseId = expenseId,
        currency = currency,
        payerUserId = payer,
        participantUserId = participant,
        remainingSubunits = remaining,
    )

    /** Find a single debt for the given direction + currency, or null. */
    private fun List<Debt>.find(debtor: UserId, creditor: UserId, currency: String = USD): Debt? =
        singleOrNull {
            it.debtorUserId == debtor && it.creditorUserId == creditor && it.currency == currency
        }

    // AC-M2-040: A paid $30 EVEN among A,B,C; nobody settled.
    // B owes A $10, C owes A $10. A's own portion is settled (no row / remaining 0).
    @Test
    fun acM2040_evenSplit_payerOwedByOthers() {
        val shares = listOf(
            share(payer = A, participant = B, remaining = 1000L),
            share(payer = A, participant = C, remaining = 1000L),
            // A's own portion: settled by being the payer (remaining 0 -> filtered out)
            share(payer = A, participant = A, remaining = 0L),
        )

        val debts = buildBilateralBalances(shares)

        assertEquals(2, debts.size)
        assertEquals(1000L, debts.find(debtor = B, creditor = A)?.amountSubunits)
        assertEquals(1000L, debts.find(debtor = C, creditor = A)?.amountSubunits)
    }

    // AC-M2-041: triangle — A owes B $10, B owes C $10, C owes A $10.
    // ALL THREE debts must be present. Triangles are NEVER simplified to zero.
    @Test
    fun acM2041_triangle_allThreeDebtsPresent_neverSimplified() {
        val shares = listOf(
            // B paid; A owes B $10
            share(payer = B, participant = A, remaining = 1000L),
            // C paid; B owes C $10
            share(payer = C, participant = B, remaining = 1000L),
            // A paid; C owes A $10
            share(payer = A, participant = C, remaining = 1000L),
        )

        val debts = buildBilateralBalances(shares)

        assertEquals(3, debts.size)
        assertEquals(1000L, debts.find(debtor = A, creditor = B)?.amountSubunits)
        assertEquals(1000L, debts.find(debtor = B, creditor = C)?.amountSubunits)
        assertEquals(1000L, debts.find(debtor = C, creditor = A)?.amountSubunits)
    }

    // AC-M2-042: A owes B $20 USD in one expense, A owes B €15 EUR in another.
    // TWO separate Debt rows, one per currency. Currencies are NEVER merged.
    @Test
    fun acM2042_sameDirectionDifferentCurrencies_notMerged() {
        val shares = listOf(
            share(payer = B, participant = A, remaining = 2000L, currency = USD, expenseId = "e1"),
            share(payer = B, participant = A, remaining = 1500L, currency = EUR, expenseId = "e2"),
        )

        val debts = buildBilateralBalances(shares)

        assertEquals(2, debts.size)
        assertEquals(2000L, debts.find(debtor = A, creditor = B, currency = USD)?.amountSubunits)
        assertEquals(1500L, debts.find(debtor = A, creditor = B, currency = EUR)?.amountSubunits)
    }

    // Settled shares (remaining == 0) contribute nothing — no Debt row.
    @Test
    fun settledShare_remainingZero_producesNoDebt() {
        val shares = listOf(
            share(payer = A, participant = B, remaining = 0L),
        )

        val debts = buildBilateralBalances(shares)

        assertTrue(debts.isEmpty())
    }

    // Net cancellation within a pair+currency: A owes B $10 on E1; B owes A $6 on E2.
    // Net A->B = $4. Single Debt row.
    @Test
    fun netCancellation_samePairSameCurrency_collapsesToNet() {
        val shares = listOf(
            // B paid e1; A owes B $10
            share(payer = B, participant = A, remaining = 1000L, expenseId = "e1"),
            // A paid e2; B owes A $6
            share(payer = A, participant = B, remaining = 600L, expenseId = "e2"),
        )

        val debts = buildBilateralBalances(shares)

        assertEquals(1, debts.size)
        assertEquals(400L, debts.find(debtor = A, creditor = B)?.amountSubunits)
    }

    // Exact cancellation: equal and opposite shares net to zero -> no Debt row.
    @Test
    fun exactCancellation_netZero_producesNoDebt() {
        val shares = listOf(
            share(payer = B, participant = A, remaining = 1000L, expenseId = "e1"),
            share(payer = A, participant = B, remaining = 1000L, expenseId = "e2"),
        )

        val debts = buildBilateralBalances(shares)

        assertTrue(debts.isEmpty())
    }

    // AC-INV-004: for any (group, currency), debts in one direction sum to debts in the
    // other direction — the ledger nets to zero. Verified with the triangle case.
    @Test
    fun acInv004_ledgerNetsToZeroPerCurrency() {
        val shares = listOf(
            share(payer = B, participant = A, remaining = 1000L),
            share(payer = C, participant = B, remaining = 1000L),
            share(payer = A, participant = C, remaining = 1000L),
        )

        val debts = buildBilateralBalances(shares)

        // Per user net position (credited minus debited) must sum to zero across the group.
        val net = mutableMapOf<UserId, Long>()
        for (d in debts) {
            net[d.creditorUserId] = (net[d.creditorUserId] ?: 0L) + d.amountSubunits
            net[d.debtorUserId] = (net[d.debtorUserId] ?: 0L) - d.amountSubunits
        }
        assertEquals(0L, net.values.sum())
        // Triangle: each participant is owed exactly as much as they owe.
        assertEquals(0L, net[A])
        assertEquals(0L, net[B])
        assertEquals(0L, net[C])
    }
}
