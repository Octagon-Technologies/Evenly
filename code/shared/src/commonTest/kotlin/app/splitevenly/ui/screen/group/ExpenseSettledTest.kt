package app.splitevenly.ui.screen.group

import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.expense.Expense
import app.splitevenly.domain.expense.ExpenseShare
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpenseSettledTest {
    private val me = UserId("me")
    private val bob = UserId("bob")

    private fun expense(amount: Long) =
        Expense(
            id = ExpenseId("e"),
            groupId = GroupId("g"),
            title = "dinner",
            amountSubunits = amount,
            currency = "USD",
            expenseDate = "2026-10-03",
            payerUserId = me,
            payerOutsideName = null,
            splitMode = "ITEMIZED",
            status = "ACTIVE",
            notes = null,
            createdBy = me,
            createdAt = 0,
            rowVersion = 0,
        )

    private fun share(
        who: UserId,
        owed: Long,
        remaining: Long,
    ) = ExpenseShare("s-${who.value}", who, owed, remaining)

    @Test
    fun unclaimedItemsKeepTheBillActiveEvenWhenThePayerClaimedTheirOwn() {
        // $65.82 dinner, payer claimed $24, the rest is unclaimed.
        assertFalse(expense(6582).isSettled(listOf(share(me, 2400, 0))))
    }

    @Test
    fun fullyClaimedButUnpaidIsNotSettled() {
        assertFalse(expense(6000).isSettled(listOf(share(me, 3000, 0), share(bob, 3000, 3000))))
    }

    @Test
    fun settledOnceEveryoneElseHasPaidThePayer() {
        assertTrue(expense(6000).isSettled(listOf(share(me, 3000, 3000), share(bob, 3000, 0))))
    }

    @Test
    fun noSharesIsNotSettled() {
        assertFalse(expense(6000).isSettled(emptyList()))
    }
}
