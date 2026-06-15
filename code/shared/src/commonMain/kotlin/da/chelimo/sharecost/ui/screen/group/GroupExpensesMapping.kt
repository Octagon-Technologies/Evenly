package da.chelimo.sharecost.ui.screen.group

import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.Member
import da.chelimo.sharecost.ui.components.currencySymbol
import da.chelimo.sharecost.ui.components.icon.ScIcons
import kotlinx.datetime.LocalDate

/** Mapped state for the Group home Expenses tab — pure, so it is unit-testable without Compose. */
data class GroupExpensesUi(
    val groupName: String,
    val groupEmoji: String,
    val state: ExpensesState,
    val days: List<ExpenseDayUi>,
)

/**
 * Maps the group + its expenses + members into the Expenses-tab UI. Expenses arrive newest-first
 * (repo contract), and `groupBy` preserves that order, so day sections come out newest-first too.
 * Per-participant "remaining of original" needs the share rows (a follow-up slice); the feed shows
 * the full expense amount for now.
 */
fun buildGroupExpenses(
    group: Group?,
    expenses: List<Expense>,
    members: List<Member>,
    currentUserId: UserId?,
    today: String,
): GroupExpensesUi {
    val name = group?.name ?: ""
    val emoji = group?.emoji ?: "💸"
    val state = when {
        group == null -> ExpensesState.Loading
        expenses.isEmpty() -> ExpensesState.Empty
        else -> ExpensesState.Populated
    }
    if (state != ExpensesState.Populated) return GroupExpensesUi(name, emoji, state, emptyList())

    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    val days = expenses.groupBy { it.expenseDate }.map { (date, list) ->
        ExpenseDayUi(label = dayLabel(date, today), items = list.map { it.toItemUi(currentUserId, nameByUser) })
    }
    return GroupExpensesUi(name, emoji, ExpensesState.Populated, days)
}

private fun Expense.toItemUi(currentUserId: UserId?, nameByUser: Map<String, String>): ExpenseItemUi {
    val payer = when {
        payerUserId == null -> payerOutsideName ?: "Someone"
        payerUserId == currentUserId -> "You"
        else -> nameByUser[payerUserId.value] ?: "Someone"
    }
    val amount = amountSubunits / 100.0
    return ExpenseItemUi(
        id = id.value,
        title = title,
        sub = "$payer paid",
        icon = ScIcons.Receipt,
        remaining = amount,
        original = amount,
        settled = status.equals("SETTLED", ignoreCase = true),
        currencySymbol = currencySymbol(currency),
    )
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Today" for the current date, else "Wed, May 22"; falls back to the raw ISO string. */
fun dayLabel(date: String, today: String): String {
    if (date == today) return "Today"
    return runCatching {
        val d = LocalDate.parse(date)
        val dow = d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        "$dow, ${MONTHS[d.monthNumber - 1]} ${d.dayOfMonth}"
    }.getOrDefault(date)
}
