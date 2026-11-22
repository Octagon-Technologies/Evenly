package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.domain.activity.HistoryEventType
import app.splitevenly.domain.expense.Expense
import app.splitevenly.domain.expense.NewExpense
import app.splitevenly.domain.expense.NewShare
import app.splitevenly.domain.settlement.NewSettlement
import app.splitevenly.domain.settlement.PaymentEditRefusal
import app.splitevenly.domain.settlement.SettlementRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two-party payment edits (`docs/features/one-time-splits.md` Part B, B1-B2), through the real Room
 * schema: Bob paid Groceries (Nov 12) and Internet (Nov 15); Alice settled up $15 across both.
 */
class PaymentEditRepositoryTest {
    private lateinit var db: EvenlyDatabase
    private lateinit var expenses: ExpenseRepositoryImpl
    private lateinit var settlements: SettlementRepositoryImpl

    private val alice = UserId("alice")
    private val bob = UserId("bob")
    private val steve = UserId("steve")

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        val clock = clockAt("2026-11-20")
        expenses = ExpenseRepositoryImpl(db.expenseDao(), db.shareDao(), clock, settlementDao = db.settlementDao())
        settlements = SettlementRepositoryImpl(db.settlementDao(), db.shareDao(), clock, historyEventDao = db.historyEventDao())
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun bill(
        title: String,
        date: String,
        aliceOwes: Long,
    ): Expense {
        val r =
            expenses.addExpense(
                NewExpense(
                    groupId = GroupId("g1"),
                    title = title,
                    amountSubunits = aliceOwes,
                    currency = "USD",
                    expenseDate = date,
                    payerUserId = bob,
                    splitMode = "EXACT",
                    createdBy = bob,
                    shares = listOf(NewShare(alice, aliceOwes)),
                ),
            )
        assertTrue(r is AppResult.Ok)
        return r.value
    }

    private suspend fun settleUp(amount: Long): SettlementRecord {
        val r =
            settlements.applySettlement(
                NewSettlement(GroupId("g1"), alice, bob, "USD", amount, createdBy = alice),
            )
        assertTrue(r is AppResult.Ok)
        return r.value
    }

    private suspend fun paidOn(e: Expense): Long =
        db
            .shareDao()
            .observeByExpense(e.id.value)
            .first()
            .sumOf { it.shareOwedSubunits - it.remainingSubunits }

    private suspend fun liveSettlements() = db.settlementDao().allForSync().filter { it.deletedAt == null }

    @Test
    fun totalEdit_down_cutsTheNewestBillFirst() =
        runTest {
            val groceries = bill("Groceries", "2026-11-12", 900)
            val internet = bill("Internet", "2026-11-15", 600)
            val paid = settleUp(1500)

            val r = settlements.editSettlement(paid.id, 500, actor = bob)

            assertTrue(r is AppResult.Ok, "Bob is a party: $r")
            assertEquals(500, paidOn(groceries))
            assertEquals(0, paidOn(internet))
            assertEquals(listOf(500L), liveSettlements().map { it.paymentAmountSubunits })
        }

    @Test
    fun totalEdit_up_fillsTheOldestUnpaidBill() =
        runTest {
            val rent = bill("Rent", "2026-11-01", 400)
            val groceries = bill("Groceries", "2026-11-12", 900)
            val internet = bill("Internet", "2026-11-15", 600)
            // A $15 payment scoped to the two newer bills, leaving Rent unpaid.
            val r0 =
                settlements.applySettlement(
                    NewSettlement(
                        GroupId("g1"),
                        alice,
                        bob,
                        "USD",
                        1500,
                        createdBy = alice,
                        expenseIds = listOf(groceries.id, internet.id),
                    ),
                )
            assertTrue(r0 is AppResult.Ok)

            val r = settlements.editSettlement(r0.value.id, 1700, actor = alice)

            assertTrue(r is AppResult.Ok)
            assertEquals(200, paidOn(rent), "the extra $2 lands on the oldest bill still owed")
            assertEquals(900, paidOn(groceries))
            assertEquals(600, paidOn(internet))
        }

    @Test
    fun totalEdit_aboveWhatIsOwed_isRefusedAndKeepsTheOriginal() =
        runTest {
            bill("Groceries", "2026-11-12", 900)
            bill("Internet", "2026-11-15", 600)
            val paid = settleUp(1500)

            val preview = settlements.previewSettlementEdit(paid.id, 1600)
            assertTrue(preview is AppResult.Ok)
            assertEquals(PaymentEditRefusal.AboveCeiling, preview.value.refusal)
            assertEquals(1500, preview.value.ceilingSubunits)

            val r = settlements.editSettlement(paid.id, 1600, actor = alice)
            assertTrue(r is AppResult.Err)
            assertEquals(listOf(paid.id.value), liveSettlements().map { it.id })
        }

    @Test
    fun scopedEdit_fromOneBillsScreen_changesOnlyThatBill() =
        runTest {
            val groceries = bill("Groceries", "2026-11-12", 900)
            val internet = bill("Internet", "2026-11-15", 600)
            val paid = settleUp(1500)

            val r = settlements.editSettlement(paid.id, 400, actor = bob, scopeExpenseId = groceries.id)

            assertTrue(r is AppResult.Ok)
            assertEquals(1000, r.value.paymentAmountSubunits, "new total = 400 here + 600 untouched")
            assertEquals(400, paidOn(groceries))
            assertEquals(600, paidOn(internet))
        }

    @Test
    fun aThirdMember_cannotEditOrRemove() =
        runTest {
            val groceries = bill("Groceries", "2026-11-12", 900)
            val paid = settleUp(900)

            val edit = settlements.editSettlement(paid.id, 100, actor = steve)
            val void = settlements.voidSettlement(paid.id, actor = steve)

            assertEquals(AppError.NotAuthorized, (edit as AppResult.Err).error)
            assertEquals(AppError.NotAuthorized, (void as AppResult.Err).error)
            assertEquals(900, paidOn(groceries))
            assertEquals(listOf(paid.id.value), liveSettlements().map { it.id })
        }

    @Test
    fun edit_writesOneHistoryRowPerAffectedBill_andNamesItsChain() =
        runTest {
            val groceries = bill("Groceries", "2026-11-12", 900)
            val internet = bill("Internet", "2026-11-15", 600)
            val paid = settleUp(1500)

            val first = settlements.editSettlement(paid.id, 500, actor = bob)
            assertTrue(first is AppResult.Ok)

            val groceriesRows =
                db
                    .historyEventDao()
                    .observeByExpense(groceries.id.value)
                    .first()
                    .filter { it.type == HistoryEventType.SETTLEMENT_EDITED.name }
            val internetRows =
                db
                    .historyEventDao()
                    .observeByExpense(internet.id.value)
                    .first()
                    .filter { it.type == HistoryEventType.SETTLEMENT_EDITED.name }
            assertEquals(listOf("edit:1500:500:alice:500"), groceriesRows.map { it.detail })
            assertEquals(listOf("edit:1500:500:alice:0"), internetRows.map { it.detail })
            assertEquals("bob", groceriesRows.single().actorUserId)

            // A second edit of the replacement still names the FIRST payment, so the server's
            // last-edit-wins rule can find every payment of the chain.
            val second = settlements.editSettlement(first.value.id, 600, actor = alice)
            assertTrue(second is AppResult.Ok)
            val live = liveSettlements().single()
            assertEquals(paid.id.value, live.originalSettlementId)
        }

    @Test
    fun remove_byAParty_restoresTheBalanceAndIsRecorded() =
        runTest {
            val groceries = bill("Groceries", "2026-11-12", 900)
            val paid = settleUp(900)

            assertTrue(settlements.voidSettlement(paid.id, actor = alice) is AppResult.Ok)

            assertEquals(0, paidOn(groceries))
            val row =
                db
                    .historyEventDao()
                    .observeByExpense(groceries.id.value)
                    .first()
                    .single { it.type == HistoryEventType.SETTLEMENT_REMOVED.name }
            assertEquals("removed:900:alice", row.detail)
        }

    @Test
    fun aBillsPayments_includeSettleUps_withWhatLandedOnThatBill() =
        runTest {
            val groceries = bill("Groceries", "2026-11-12", 900)
            bill("Internet", "2026-11-15", 600)
            settleUp(1500)

            val onGroceries = settlements.observeExpensePayments(groceries.id).first().single()

            assertEquals(900, onGroceries.appliedHereSubunits)
            assertEquals(2, onGroceries.billCount)
            assertEquals(1500, onGroceries.record.paymentAmountSubunits)
        }

    @Test
    fun preview_listsEachBillBeforeAndAfter() =
        runTest {
            bill("Groceries", "2026-11-12", 900)
            bill("Internet", "2026-11-15", 600)
            val paid = settleUp(1500)

            val p = settlements.previewSettlementEdit(paid.id, 500)

            assertTrue(p is AppResult.Ok)
            assertNull(p.value.refusal)
            assertEquals(
                listOf(Triple("Groceries", 900L, 500L), Triple("Internet", 600L, 0L)),
                p.value.lines.map { Triple(it.title, it.beforeSubunits, it.afterSubunits) },
            )
            // A preview writes nothing.
            assertEquals(listOf(paid.id.value), liveSettlements().map { it.id })
        }
}
