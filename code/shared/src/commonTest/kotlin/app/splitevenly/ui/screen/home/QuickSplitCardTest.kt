package app.splitevenly.ui.screen.home

import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.remote.supabase.ListReadiness
import app.splitevenly.domain.balance.Debt
import app.splitevenly.domain.expense.Expense
import app.splitevenly.domain.expense.ExpenseShare
import app.splitevenly.domain.expense.ExpenseWithShares
import app.splitevenly.domain.expense.quickSplitTokenFrom
import app.splitevenly.domain.expense.quickSplitUrl
import app.splitevenly.domain.group.Group
import app.splitevenly.domain.group.GroupKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickSplitCardTest {
    private val me = UserId("me")
    private val ann = UserId("ann")
    private val joe = UserId("joe")
    private val group = Group(GroupId("g"), "Quick split", "🧾", "USD", me, "inv", createdAt = 0, kind = GroupKind.Quick)

    private fun bill(
        amount: Long,
        vararg shares: Triple<UserId, Long, Long>,
    ) = ExpenseWithShares(
        Expense(
            id = ExpenseId("e"),
            groupId = group.id,
            title = "Dinner at Nobu",
            amountSubunits = amount,
            currency = "USD",
            expenseDate = "2026-11-22",
            payerUserId = me,
            payerOutsideName = null,
            splitMode = "EVEN",
            status = "ACTIVE",
            notes = null,
            createdBy = me,
            createdAt = 1_000L,
            rowVersion = 1,
        ),
        shares.map { (u, owed, remaining) -> ExpenseShare("s-${u.value}", u, owed, remaining) },
    )

    private fun card(
        bill: ExpenseWithShares?,
        debts: List<Debt> = emptyList(),
        lastPaymentAt: Long? = null,
    ) = quickSplitCard(group, me, listOf("You", "Ann", "Joe"), debts, bill, lastPaymentAt)

    @Test
    fun countsOnlyPeopleWhoPaidTheirWholeShare() {
        val c =
            card(bill(3000, Triple(me, 1000, 0), Triple(ann, 1000, 0), Triple(joe, 1000, 400)), debts = listOf(Debt(joe, me, "USD", 400)))
        assertEquals("1 of 2 paid", c.last)
        assertEquals("Dinner at Nobu", c.name)
        assertEquals(GroupBalanceStatus.Owed, c.status)
        assertEquals(4.0, c.amount)
        assertFalse(c.quick!!.allSettled)
    }

    @Test
    fun anItemizedBillNobodyClaimedIsNotSettled() {
        val c = card(bill(3000, Triple(me, 0, 0)))
        assertFalse(c.quick!!.allSettled)
        assertEquals("0 of 2 paid", c.last)
    }

    @Test
    fun settledOnceEveryShareIsPaidAndTheBillIsHandedOut() {
        val c = card(bill(3000, Triple(me, 1000, 0), Triple(ann, 1000, 0), Triple(joe, 1000, 0)), lastPaymentAt = 5_000L)
        assertTrue(c.quick!!.allSettled)
        assertEquals("All settled", c.last)
        assertEquals(5_000L, c.quick!!.settledAt)
    }

    @Test
    fun anUnsavedBillResumesTheEditor() {
        val c = card(bill = null)
        assertNull(c.quick!!.expenseId)
        assertEquals(GroupBalanceStatus.Empty, c.status)
    }

    @Test
    fun settledQuickSplitsMoveToHistoryADayLater() {
        val settled = card(bill(3000, Triple(me, 1000, 0), Triple(ann, 1000, 0), Triple(joe, 1000, 0)), lastPaymentAt = 0L)
        val open = card(bill(3000, Triple(me, 1000, 0), Triple(ann, 1000, 1000), Triple(joe, 1000, 0)))
        val groupCard = GroupCardUi("g2", "🏠", "Apartment", 3, GroupBalanceStatus.Owed, 42.1, "You're owed")

        val soon = homeState(listOf(settled, open, groupCard), emptyList(), 0, ListReadiness.Ready, now = QUICK_SPLIT_HISTORY_DELAY_MS - 1)
        assertIs<HomeUiState.Content>(soon)
        assertEquals(3, soon.active.size)
        assertTrue(soon.history.isEmpty())

        val later = homeState(listOf(settled, open, groupCard), emptyList(), 0, ListReadiness.Ready, now = QUICK_SPLIT_HISTORY_DELAY_MS)
        assertIs<HomeUiState.Content>(later)
        assertEquals(listOf(open.id, groupCard.id), later.active.map { it.id })
        assertEquals(listOf(settled.id), later.history.map { it.id })
    }

    @Test
    fun quickSplitLinksUseTheAppLinkPath() {
        assertEquals("split-evenly.app/s/AbCdEf0123456789", quickSplitUrl("split-evenly.app/b/AbCdEf0123456789"))
        assertEquals("AbCdEf0123456789", quickSplitTokenFrom("https://split-evenly.app/s/AbCdEf0123456789"))
        assertEquals("AbCdEf0123456789", quickSplitTokenFrom("Your share of Dinner: split-evenly.app/s/AbCdEf0123456789"))
        assertNull(quickSplitTokenFrom("https://split-evenly.app/b/AbCdEf0123456789"))
        assertNull(quickSplitTokenFrom("https://split-evenly.app/j/0190a1b2-c3d4"))
    }
}
