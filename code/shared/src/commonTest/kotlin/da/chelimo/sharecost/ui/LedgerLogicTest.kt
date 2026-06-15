package da.chelimo.sharecost.ui

import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.Member
import da.chelimo.sharecost.ui.screen.expense.evenSplit
import da.chelimo.sharecost.ui.screen.group.ExpensesState
import da.chelimo.sharecost.ui.screen.group.buildGroupExpenses
import da.chelimo.sharecost.ui.screen.group.dayLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerLogicTest {

    @Test
    fun evenSplit_sums_to_total_and_distributes_remainder() {
        val ids = listOf("a", "b", "c")
        val split = evenSplit(10000, ids)
        assertEquals(10000, split.values.sum(), "shares must sum to the total")
        // 10000 / 3 = 3333 r1 → first id gets the extra subunit
        assertEquals(3334, split["a"])
        assertEquals(3333, split["b"])
        assertEquals(3333, split["c"])
    }

    @Test
    fun evenSplit_empty_for_zero_or_no_participants() {
        assertTrue(evenSplit(0, listOf("a")).isEmpty())
        assertTrue(evenSplit(1000, emptyList()).isEmpty())
    }

    @Test
    fun dayLabel_is_Today_for_current_date_else_formatted() {
        assertEquals("Today", dayLabel("2026-06-15", today = "2026-06-15"))
        assertEquals("Fri, May 22", dayLabel("2026-05-22", today = "2026-06-15"))
    }

    @Test
    fun buildGroupExpenses_states_and_payer_attribution() {
        val me = UserId("u1")
        val group = Group(GroupId("g"), "Trip", "🏝️", "USD", adminUserId = me, inviteToken = "t", createdAt = 0)
        val members = listOf(
            Member(me, "Alex", isPlaceholder = false, isAdmin = true, joinedAt = 0),
            Member(UserId("u2"), "Bob", isPlaceholder = false, isAdmin = false, joinedAt = 0),
        )

        // null group → Loading; no expenses → Empty
        assertEquals(ExpensesState.Loading, buildGroupExpenses(null, emptyList(), members, me, "2026-06-15").state)
        assertEquals(ExpensesState.Empty, buildGroupExpenses(group, emptyList(), members, me, "2026-06-15").state)

        val mine = expense("e1", "Taxi", 5800, "2026-06-15", payer = me)
        val theirs = expense("e2", "Dinner", 9600, "2026-06-15", payer = UserId("u2"))
        val ui = buildGroupExpenses(group, listOf(mine, theirs), members, me, "2026-06-15")

        assertEquals(ExpensesState.Populated, ui.state)
        assertEquals("Trip", ui.groupName)
        assertEquals(1, ui.days.size)
        assertEquals("Today", ui.days[0].label)
        assertEquals("You paid", ui.days[0].items.first { it.title == "Taxi" }.sub)
        assertEquals("Bob paid", ui.days[0].items.first { it.title == "Dinner" }.sub)
    }

    private fun expense(id: String, title: String, amount: Long, date: String, payer: UserId) = Expense(
        id = ExpenseId(id),
        groupId = GroupId("g"),
        title = title,
        amountSubunits = amount,
        currency = "USD",
        expenseDate = date,
        payerUserId = payer,
        payerOutsideName = null,
        splitMode = "EVEN",
        status = "ACTIVE",
        notes = null,
        createdBy = payer,
        createdAt = 0,
        rowVersion = 1,
    )
}
