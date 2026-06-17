package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.entity.UserEntity
import da.chelimo.sharecost.data.db.inMemoryTestDatabase
import da.chelimo.sharecost.domain.expense.NewExpense
import da.chelimo.sharecost.domain.expense.NewShare
import da.chelimo.sharecost.domain.group.NewGroup
import da.chelimo.sharecost.domain.settlement.NewSettlement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end of the wired money loop over the real repos + in-memory DB:
 * add a placeholder, split an expense paid by them, see the bilateral debt, then settle it and watch
 * the balance clear. Backs the UI wiring (addPlaceholder / observeBalances / applySettlement).
 */
class MoneyLoopTest {

    private lateinit var db: ShareCostDatabase
    private lateinit var groups: GroupRepositoryImpl
    private lateinit var expenses: ExpenseRepositoryImpl
    private lateinit var settlements: SettlementRepositoryImpl

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        val clock = clockAt("2026-06-15")
        groups = GroupRepositoryImpl(db.groupDao(), db.memberDao(), db.userDao(), db.expenseDao(), db.shareDao(), db.conflictDao(), clock)
        expenses = ExpenseRepositoryImpl(db.expenseDao(), db.shareDao(), clock)
        settlements = SettlementRepositoryImpl(db.settlementDao(), db.shareDao(), clock)
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun placeholder_expense_creates_balance_then_settlement_clears_it() = runTest {
        val me = UserId("11111111-1111-1111-1111-111111111111")
        db.userDao().upsert(UserEntity(id = me.value, displayName = "Me", createdAt = 0, updatedAt = 0))

        val group = (groups.createGroup(NewGroup("Trip", "USD", me, "🏝️")) as AppResult.Ok).value
        val bob = (groups.addPlaceholder(group.id, "Bob") as AppResult.Ok).value.userId

        // Bob fronts $60; split evenly between me + Bob → I owe Bob $30.
        expenses.addExpense(
            NewExpense(
                groupId = group.id,
                title = "Cabana",
                amountSubunits = 6000,
                currency = "USD",
                expenseDate = "2026-06-15",
                payerUserId = bob,
                splitMode = "EVEN",
                createdBy = me,
                shares = listOf(NewShare(me, 3000), NewShare(bob, 3000)),
            ),
        )

        val debts = expenses.observeBalances(group.id).first()
        assertEquals(1, debts.size, "exactly one bilateral debt (Bob's self-share nets to zero)")
        assertEquals(me, debts[0].debtorUserId)
        assertEquals(bob, debts[0].creditorUserId)
        assertEquals(3000, debts[0].amountSubunits)

        settlements.applySettlement(
            NewSettlement(group.id, fromUserId = me, toUserId = bob, paymentCurrency = "USD", paymentAmountSubunits = 3000, createdBy = me),
        )

        assertTrue(expenses.observeBalances(group.id).first().isEmpty(), "settling the full amount clears the balance")
    }
}
