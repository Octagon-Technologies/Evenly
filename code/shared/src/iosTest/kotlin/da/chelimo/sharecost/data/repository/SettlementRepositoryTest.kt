package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.data.db.ExpenseStatus
import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.inMemoryTestDatabase
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.expense.NewExpense
import da.chelimo.sharecost.domain.expense.NewShare
import da.chelimo.sharecost.domain.settlement.NewSettlement
import da.chelimo.sharecost.domain.settlement.SettlementRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [SettlementRepositoryImpl]: oldest-first allocation across the debtor→creditor shares
 * (03 §4.3.1), status recompute on full vs partial settle (AC-INV-003), the PAYMENT_OVERALLOCATED
 * guard (04 §2.2), and void (restore shares + reactivate, 04 §2.3).
 */
class SettlementRepositoryTest {

    private lateinit var db: ShareCostDatabase
    private lateinit var expenses: ExpenseRepositoryImpl
    private lateinit var settlements: SettlementRepositoryImpl

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        val clock = clockAt("2026-06-12")
        expenses = ExpenseRepositoryImpl(db.expenseDao(), db.shareDao(), clock)
        settlements = SettlementRepositoryImpl(db.settlementDao(), db.shareDao(), clock)
    }

    @AfterTest
    fun tearDown() = db.close()

    /** An expense paid by [payer], wholly owed by [debtor] (so it forms a debtor→payer balance). */
    private suspend fun owedExpense(date: String, amount: Long, payer: String, debtor: String): Expense {
        val result = expenses.addExpense(
            NewExpense(
                groupId = GroupId("g1"),
                title = "E$date",
                amountSubunits = amount,
                currency = "USD",
                expenseDate = date,
                payerUserId = UserId(payer),
                splitMode = "EVEN",
                createdBy = UserId(payer),
                shares = listOf(NewShare(UserId(debtor), amount)),
            )
        )
        assertTrue(result is AppResult.Ok, "addExpense should succeed")
        return result.value
    }

    private fun newSettlement(amount: Long) = NewSettlement(
        groupId = GroupId("g1"),
        fromUserId = UserId("u2"), // debtor pays
        toUserId = UserId("u1"),   // creditor receives
        paymentCurrency = "USD",
        paymentAmountSubunits = amount,
        createdBy = UserId("u2"),
    )

    // Remaining is derived (owed − Σ applied), not stored — sum the per-share derived remaining.
    private suspend fun remaining(id: ExpenseId) =
        db.shareDao().observeByExpense(id.value).first().sumOf { it.remainingSubunits }
    // "Settled" is likewise derived: an expense is settled iff every share's derived remaining is 0.
    private suspend fun status(id: ExpenseId): String =
        if (remaining(id) == 0L) ExpenseStatus.SETTLED else ExpenseStatus.ACTIVE

    @Test
    fun applySettlement_paysOldestFirst_settlesAndPartials() = runTest {
        val e1 = owedExpense("2026-06-01", 1000, payer = "u1", debtor = "u2")
        val e2 = owedExpense("2026-06-03", 1000, payer = "u1", debtor = "u2")

        val result = settlements.applySettlement(newSettlement(1500))
        assertTrue(result is AppResult.Ok)
        val record: SettlementRecord = result.value
        assertEquals(UserId("u2"), record.fromUserId)
        assertEquals(UserId("u1"), record.toUserId)
        assertEquals(1500, record.paymentAmountSubunits)

        // Oldest (e1) cleared fully; e2 partially paid (500 of 1000 applied).
        assertEquals(0, remaining(e1.id))
        assertEquals(ExpenseStatus.SETTLED, status(e1.id))
        assertEquals(500, remaining(e2.id))
        assertEquals(ExpenseStatus.ACTIVE, status(e2.id))

        assertEquals(1, settlements.observeSettlements(GroupId("g1")).first().size)
    }

    @Test
    fun applySettlement_scopedToExpense_paysThatExpenseNotOldest() = runTest {
        // e1 is older, so a relationship-wide partial would hit it first. Scoping to e2 must instead
        // pay e2 down and leave the older e1 untouched (the single-expense "Settle 'X'" sheet).
        val e1 = owedExpense("2026-06-01", 1000, payer = "u1", debtor = "u2")
        val e2 = owedExpense("2026-06-03", 1000, payer = "u1", debtor = "u2")

        val result = settlements.applySettlement(newSettlement(400).copy(expenseId = e2.id))
        assertTrue(result is AppResult.Ok)

        assertEquals(1000, remaining(e1.id))           // older expense untouched
        assertEquals(ExpenseStatus.ACTIVE, status(e1.id))
        assertEquals(600, remaining(e2.id))            // the targeted expense was paid down
        assertEquals(ExpenseStatus.ACTIVE, status(e2.id))
    }

    @Test
    fun applySettlement_overAllocated_returnsValidation() = runTest {
        owedExpense("2026-06-01", 1000, payer = "u1", debtor = "u2")

        val result = settlements.applySettlement(newSettlement(5000)) // owed total is only 1000
        assertTrue(result is AppResult.Err)
        assertTrue((result.error as AppError.Validation).fieldErrors.containsKey("amount"))
    }

    @Test
    fun applySettlement_nothingOwed_returnsValidation() = runTest {
        val result = settlements.applySettlement(newSettlement(100)) // no expenses ⇒ owed total 0
        assertTrue(result is AppResult.Err)
        assertTrue((result.error as AppError.Validation).fieldErrors.containsKey("amount"))
    }

    @Test
    fun voidSettlement_restoresSharesAndReactivates() = runTest {
        val e1 = owedExpense("2026-06-01", 1000, payer = "u1", debtor = "u2")
        val e2 = owedExpense("2026-06-03", 1000, payer = "u1", debtor = "u2")
        val applied = settlements.applySettlement(newSettlement(1500))
        assertTrue(applied is AppResult.Ok)

        assertTrue(settlements.voidSettlement(applied.value.id) is AppResult.Ok)

        // Both shares restored to full; both expenses active again; settlement gone from the list.
        assertEquals(1000, remaining(e1.id))
        assertEquals(ExpenseStatus.ACTIVE, status(e1.id))
        assertEquals(1000, remaining(e2.id))
        assertEquals(ExpenseStatus.ACTIVE, status(e2.id))
        assertTrue(settlements.observeSettlements(GroupId("g1")).first().isEmpty())
    }
}
