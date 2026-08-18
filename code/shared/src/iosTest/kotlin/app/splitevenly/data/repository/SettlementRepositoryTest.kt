package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.SettlementId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.ExpenseStatus
import app.splitevenly.data.db.entity.SettlementAllocationEntity
import app.splitevenly.data.db.entity.SettlementEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.domain.expense.Expense
import app.splitevenly.domain.expense.NewExpense
import app.splitevenly.domain.expense.NewShare
import app.splitevenly.domain.settlement.NewSettlement
import app.splitevenly.domain.settlement.SettlementRecord
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
    private lateinit var db: EvenlyDatabase
    private lateinit var expenses: ExpenseRepositoryImpl
    private lateinit var settlements: SettlementRepositoryImpl

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        val clock = clockAt("2026-06-12")
        expenses = ExpenseRepositoryImpl(db.expenseDao(), db.shareDao(), clock, settlementDao = db.settlementDao())
        settlements = SettlementRepositoryImpl(db.settlementDao(), db.shareDao(), clock)
    }

    @AfterTest
    fun tearDown() = db.close()

    /** An expense paid by [payer], wholly owed by [debtor] (so it forms a debtor→payer balance). */
    private suspend fun owedExpense(
        date: String,
        amount: Long,
        payer: String,
        debtor: String,
    ): Expense {
        val result =
            expenses.addExpense(
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
                ),
            )
        assertTrue(result is AppResult.Ok, "addExpense should succeed")
        return result.value
    }

    private fun newSettlement(amount: Long) =
        NewSettlement(
            groupId = GroupId("g1"),
            fromUserId = UserId("u2"), // debtor pays
            toUserId = UserId("u1"), // creditor receives
            paymentCurrency = "USD",
            paymentAmountSubunits = amount,
            createdBy = UserId("u2"),
        )

    // Remaining is derived (owed − Σ applied), not stored — sum the per-share derived remaining.
    private suspend fun remaining(id: ExpenseId) =
        db
            .shareDao()
            .observeByExpense(id.value)
            .first()
            .sumOf { it.remainingSubunits }

    // "Settled" is likewise derived: an expense is settled iff every share's derived remaining is 0.
    private suspend fun status(id: ExpenseId): String = if (remaining(id) == 0L) ExpenseStatus.SETTLED else ExpenseStatus.ACTIVE

    @Test
    fun applySettlement_paysOldestFirst_settlesAndPartials() =
        runTest {
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
    fun applySettlement_scopedToExpense_paysThatExpenseNotOldest() =
        runTest {
            // e1 is older, so a relationship-wide partial would hit it first. Scoping to e2 must instead
            // pay e2 down and leave the older e1 untouched (the single-expense "Settle 'X'" sheet).
            val e1 = owedExpense("2026-06-01", 1000, payer = "u1", debtor = "u2")
            val e2 = owedExpense("2026-06-03", 1000, payer = "u1", debtor = "u2")

            val result = settlements.applySettlement(newSettlement(400).copy(expenseId = e2.id))
            assertTrue(result is AppResult.Ok)

            assertEquals(1000, remaining(e1.id)) // older expense untouched
            assertEquals(ExpenseStatus.ACTIVE, status(e1.id))
            assertEquals(600, remaining(e2.id)) // the targeted expense was paid down
            assertEquals(ExpenseStatus.ACTIVE, status(e2.id))
        }

    @Test
    fun applySettlement_overAllocated_returnsValidation() =
        runTest {
            owedExpense("2026-06-01", 1000, payer = "u1", debtor = "u2")

            val result = settlements.applySettlement(newSettlement(5000)) // owed total is only 1000
            assertTrue(result is AppResult.Err)
            assertTrue((result.error as AppError.Validation).fieldErrors.containsKey("amount"))
        }

    @Test
    fun applySettlement_nothingOwed_returnsValidation() =
        runTest {
            val result = settlements.applySettlement(newSettlement(100)) // no expenses ⇒ owed total 0
            assertTrue(result is AppResult.Err)
            assertTrue((result.error as AppError.Validation).fieldErrors.containsKey("amount"))
        }

    @Test
    fun editSettlement_correctsAmount_reallocates() =
        runTest {
            val e1 = owedExpense("2026-06-01", 1000, payer = "u1", debtor = "u2")
            val applied = settlements.applySettlement(newSettlement(1000).copy(expenseId = e1.id))
            assertTrue(applied is AppResult.Ok)
            assertEquals(0, remaining(e1.id)) // fully paid

            // Correct the recorded payment down to 600 → 400 still owed; the old payment is replaced in place.
            val edited = settlements.editSettlement(applied.value.id, 600)
            assertTrue(edited is AppResult.Ok)
            assertEquals(600, edited.value.paymentAmountSubunits)
            assertEquals(400, remaining(e1.id))

            // Exactly one live payment on the expense (old voided, new recorded).
            val payments = settlements.observePaymentsForExpense(e1.id).first()
            assertEquals(1, payments.size)
            assertEquals(600, payments.first().paymentAmountSubunits)
        }

    @Test
    fun editSettlement_aboveOwed_rejectsAndKeepsOriginal() =
        runTest {
            val e1 = owedExpense("2026-06-01", 1000, payer = "u1", debtor = "u2")
            val applied = settlements.applySettlement(newSettlement(500).copy(expenseId = e1.id))
            assertTrue(applied is AppResult.Ok)

            // Editing above what's owed (1000) is rejected — and because the guard runs *before* the void,
            // the original 500 payment must survive intact (a rejected correction never loses money).
            val edited = settlements.editSettlement(applied.value.id, 2000)
            assertTrue(edited is AppResult.Err)
            assertTrue((edited.error as AppError.Validation).fieldErrors.containsKey("amount"))
            assertEquals(500, remaining(e1.id)) // unchanged: still 500 of 1000 paid
            val payments = settlements.observePaymentsForExpense(e1.id).first()
            assertEquals(1, payments.size)
            assertEquals(500, payments.first().paymentAmountSubunits)
        }

    /**
     * The rejection that arrives with **no crash and no race the caller can see** (finding R6).
     *
     * The ceiling check runs before the void and is what the comment there promises, but it is not the
     * only way the re-record can fail: a background pull landing the debtor's own offline payment for the
     * same expense between that check and the write pays the share down, and the re-record is then
     * refused from inside. When the void and the re-record were two separate writes, that refusal left
     * the void standing: a payment Ann had genuinely recorded was soft-deleted with no replacement, the
     * debt came back, and `LedgerRoutes` discards the result so nobody was even told.
     *
     * `replaceSettlement` does both halves in one transaction and rolls the void back with the refusal,
     * so the original survives. This drives that path from the repository, which is where the finding
     * says the window is; `SettlementDaoTest` pins the transaction itself.
     */
    @Test
    fun editSettlement_refusedByAPaymentThatLandedMidEdit_keepsTheOriginalPayment() =
        runTest {
            val e1 = owedExpense("2026-06-01", 1000, payer = "u1", debtor = "u2")
            val applied = settlements.applySettlement(newSettlement(1000).copy(expenseId = e1.id))
            assertTrue(applied is AppResult.Ok)

            // u2's own device recorded the same 1000 offline a minute earlier; the pull lands it now. Writing
            // it straight to the DAO is what a pull does: it is already-agreed ground truth, not a new payment
            // going through the over-allocation guard.
            val shareId =
                db
                    .shareDao()
                    .getByExpense(e1.id.value)
                    .first { it.userId == "u2" }
                    .id
            db.settlementDao().upsert(
                SettlementEntity(
                    id = "pulled",
                    groupId = "g1",
                    fromUserId = "u2",
                    toUserId = "u1",
                    paymentCurrency = "USD",
                    paymentAmountSubunits = 1000,
                    settledAt = 5,
                    createdBy = "u2",
                    createdAt = 5,
                    updatedAt = 5,
                ),
            )
            db.settlementDao().upsertAllocations(
                listOf(
                    SettlementAllocationEntity(
                        id = "pulled-a",
                        settlementId = "pulled",
                        groupId = "g1",
                        shareId = shareId,
                        appliedAmountSubunits = 1000,
                        appliedCurrency = "USD",
                        createdAt = 5,
                    ),
                ),
            )

            // Ann corrects her 1000 down to 900. The pre-flight ceiling still passes (0 outstanding + 1000
            // covered by this payment), and the in-transaction guard is what refuses it.
            val edited = settlements.editSettlement(applied.value.id, 900)

            assertTrue(edited is AppResult.Err)
            assertTrue((edited.error as AppError.Validation).fieldErrors.containsKey("amount"))
            val live =
                db
                    .settlementDao()
                    .allForSync()
                    .filter { it.deletedAt == null }
                    .map { it.id }
                    .toSet()
            assertTrue(applied.value.id.value in live, "a refused correction must not un-pay a recorded payment")
            assertEquals(setOf(applied.value.id.value, "pulled"), live)
        }

    @Test
    fun voidSettlement_restoresSharesAndReactivates() =
        runTest {
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

    /**
     * P1 #9: the SAME payment recorded twice (two devices both log it, then sync) drives the share's
     * derived remaining NEGATIVE. Every outstanding query filters `> 0`, so without a dedicated signal the
     * debt just reads "settled" and the extra money vanishes. `observeOverpayments` must surface it, and
     * voiding one payment must clear it (remaining derives back to 0). We insert the two settlements +
     * allocations via the low-level DAO to model the post-sync state — on a single device the guarded
     * `applySettlement` rejects the second; the banner is the cross-device safety net.
     */
    @Test
    fun overpayment_isSurfacedByObserveOverpayments_andClearsOnVoid() =
        runTest {
            val e = owedExpense("2026-06-01", 3000, payer = "u1", debtor = "u2") // u2 owes u1 $30
            val shareId =
                db
                    .shareDao()
                    .getByExpense(e.id.value)
                    .first { it.userId == "u2" }
                    .id

            fun settle(id: String) =
                SettlementEntity(
                    id = id,
                    groupId = "g1",
                    fromUserId = "u2",
                    toUserId = "u1",
                    paymentCurrency = "USD",
                    paymentAmountSubunits = 3000,
                    settledAt = 1,
                    createdBy = "u2",
                    createdAt = 1,
                    updatedAt = 1,
                )

            fun alloc(
                id: String,
                sid: String,
            ) = SettlementAllocationEntity(
                id = id,
                settlementId = sid,
                groupId = "g1",
                shareId = shareId,
                appliedAmountSubunits = 3000,
                appliedCurrency = "USD",
                createdAt = 1,
            )
            db.settlementDao().upsert(settle("s1"))
            db.settlementDao().upsertAllocations(listOf(alloc("a1", "s1")))
            db.settlementDao().upsert(settle("s2"))
            db.settlementDao().upsertAllocations(listOf(alloc("a2", "s2")))

            val over = expenses.observeOverpayments(GroupId("g1"), UserId("u1")).first()
            assertEquals(1, over.size, "the double payment must surface as one over-paid pair")
            assertEquals(UserId("u2"), over.single().debtorUserId)
            assertEquals(UserId("u1"), over.single().creditorUserId)
            assertEquals(3000, over.single().overpaidSubunits, "$30 paid past a $30 debt = $30 over")

            // Void one of the duplicates → the overpayment derives away.
            assertTrue(settlements.voidSettlement(SettlementId("s2")) is AppResult.Ok)
            assertTrue(
                expenses.observeOverpayments(GroupId("g1"), UserId("u1")).first().isEmpty(),
                "removing the duplicate clears the signal (remaining back to 0)",
            )
            assertEquals(0, remaining(e.id))
        }

    /**
     * P1 #9 false-positive guard: two DIFFERENT payments (a $20 one and a separate $25 one) can also push
     * the derived remaining negative, but that's just two real payments adding up past what was owed, not
     * the same payment logged twice. `observeOverpayments` must require the last two payments to be the
     * exact same amount before surfacing the banner, so this pair must NOT appear.
     */
    @Test
    fun overpayment_notSurfaced_whenLastTwoPaymentsDiffer() =
        runTest {
            val e = owedExpense("2026-06-01", 3000, payer = "u1", debtor = "u2") // u2 owes u1 $30
            val shareId =
                db
                    .shareDao()
                    .getByExpense(e.id.value)
                    .first { it.userId == "u2" }
                    .id

            fun settle(
                id: String,
                amount: Long,
                settledAt: Long,
            ) = SettlementEntity(
                id = id,
                groupId = "g1",
                fromUserId = "u2",
                toUserId = "u1",
                paymentCurrency = "USD",
                paymentAmountSubunits = amount,
                settledAt = settledAt,
                createdBy = "u2",
                createdAt = settledAt,
                updatedAt = settledAt,
            )

            fun alloc(
                id: String,
                sid: String,
                amount: Long,
            ) = SettlementAllocationEntity(
                id = id,
                settlementId = sid,
                groupId = "g1",
                shareId = shareId,
                appliedAmountSubunits = amount,
                appliedCurrency = "USD",
                createdAt = 1,
            )
            db.settlementDao().upsert(settle("s1", 2000, 1))
            db.settlementDao().upsertAllocations(listOf(alloc("a1", "s1", 2000)))
            db.settlementDao().upsert(settle("s2", 2500, 2))
            db.settlementDao().upsertAllocations(listOf(alloc("a2", "s2", 2500)))

            assertTrue(
                expenses.observeOverpayments(GroupId("g1"), UserId("u1")).first().isEmpty(),
                "two different payment amounts must never surface as a double payment, even summing past what was owed",
            )
        }

    /** P1 #9b: once a debt is fully paid, a second same-device payment is refused (not silently over-applied). */
    @Test
    fun applySettlement_afterFullPayment_refusesSecondPayment() =
        runTest {
            val e = owedExpense("2026-06-01", 3000, payer = "u1", debtor = "u2")
            assertTrue(settlements.applySettlement(newSettlement(3000).copy(expenseId = e.id)) is AppResult.Ok)
            val second = settlements.applySettlement(newSettlement(3000).copy(expenseId = e.id))
            assertTrue(second is AppResult.Err, "nothing outstanding ⇒ the second payment is rejected")
            assertEquals(0, remaining(e.id), "still exactly one payment's worth applied — no over-apply")
        }
}
