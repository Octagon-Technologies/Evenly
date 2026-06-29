package da.chelimo.sharecost.ui.screen.group

import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.expense.ExpenseCategory
import da.chelimo.sharecost.domain.expense.ExpenseShare
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.Member
import da.chelimo.sharecost.ui.components.currencySymbol
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.theme.categoryColor
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
    sharesByExpenseId: Map<String, List<ExpenseShare>> = emptyMap(),
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
        ExpenseDayUi(
            label = dayLabel(date, today),
            items = list.map { it.toItemUi(currentUserId, nameByUser, sharesByExpenseId[it.id.value].orEmpty()) },
        )
    }
    return GroupExpensesUi(name, emoji, ExpensesState.Populated, days)
}

/** The current user's position on one expense: positive = owed to them, negative = they owe. */
private data class PersonalStake(val owedToYou: Boolean, val subunits: Long) {
    val label: String get() = if (owedToYou) "you're owed" else "you owe"
}

/**
 * What this expense means for [me], from the unsettled shares: if I paid, I'm owed whatever the *other*
 * participants still owe; otherwise I owe my own remaining share. Returns null when I'm square on it
 * (settled, payer-only with nothing outstanding, or not a participant).
 */
private fun Expense.personalStake(me: UserId?, shares: List<ExpenseShare>): PersonalStake? {
    if (me == null || shares.isEmpty()) return null
    val iAmPayer = payerUserId != null && payerUserId == me
    return if (iAmPayer) {
        val owed = shares.filter { it.userId != me }.sumOf { it.remainingSubunits }
        if (owed > 0L) PersonalStake(owedToYou = true, subunits = owed) else null
    } else {
        val mine = shares.filter { it.userId == me }.sumOf { it.remainingSubunits }
        if (mine > 0L) PersonalStake(owedToYou = false, subunits = mine) else null
    }
}

private fun Expense.toItemUi(
    currentUserId: UserId?,
    nameByUser: Map<String, String>,
    shares: List<ExpenseShare>,
): ExpenseItemUi {
    val payer = when {
        payerUserId == null -> payerOutsideName ?: "Someone"
        payerUserId == currentUserId -> "You"
        else -> nameByUser[payerUserId.value] ?: "Someone"
    }
    val category = ExpenseCategory.fromId(categoryId) ?: ExpenseCategory.OTHER
    val amount = amountSubunits / 100.0
    val stake = personalStake(currentUserId, shares)
    return ExpenseItemUi(
        id = id.value,
        title = title,
        sub = "$payer paid",
        icon = categoryIcon(category),
        remaining = amount,
        original = amount,
        // Derived (not stored): fully paid iff every participant share's derived remaining is 0.
        settled = shares.isNotEmpty() && shares.all { it.remainingSubunits == 0L },
        currencySymbol = currencySymbol(currency),
        payerName = payer,
        payerIsMe = payerUserId != null && payerUserId == currentUserId,
        amountLabel = moneySubunits(amountSubunits, currency),
        categoryColor = categoryColor(category),
        stakeLabel = stake?.label,
        stakeAmount = stake?.let { moneySubunits(it.subunits, currency) },
        stakeOwedToYou = stake?.owedToYou ?: false,
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
