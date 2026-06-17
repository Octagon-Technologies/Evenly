package da.chelimo.sharecost.ui.screen.group

import da.chelimo.sharecost.domain.expense.Expense
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** Date windows offered by the filter sheet (F6), evaluated against the group's "today" (ISO date). */
enum class DateRange(val label: String) {
    All("Any time"),
    Last7("7 days"),
    Last30("30 days"),
    ThisMonth("This month"),
    ;

    fun contains(dateIso: String, todayIso: String): Boolean {
        if (this == All) return true
        return runCatching {
            val date = LocalDate.parse(dateIso)
            val today = LocalDate.parse(todayIso)
            when (this) {
                All -> true
                Last7 -> date in today.minus(DatePeriod(days = 7))..today
                Last30 -> date in today.minus(DatePeriod(days = 30))..today
                ThisMonth -> date.year == today.year && date.month == today.month
            }
        }.getOrDefault(true)
    }
}

/** The active expense-feed filter (F6). All-null / [DateRange.All] means no filtering. */
data class GroupFilter(
    val payerUserId: String? = null,
    val categoryId: String? = null,
    val dateRange: DateRange = DateRange.All,
) {
    val isActive: Boolean get() = payerUserId != null || categoryId != null || dateRange != DateRange.All
    val activeCount: Int get() = listOf(payerUserId != null, categoryId != null, dateRange != DateRange.All).count { it }
}

/** Apply [filter] to a feed list (payer + category + date window). Pure → unit-testable. */
fun applyFilter(expenses: List<Expense>, filter: GroupFilter, today: String): List<Expense> {
    if (!filter.isActive) return expenses
    return expenses.filter { e ->
        (filter.payerUserId == null || e.payerUserId?.value == filter.payerUserId) &&
            (filter.categoryId == null || e.categoryId == filter.categoryId) &&
            filter.dateRange.contains(e.expenseDate, today)
    }
}

/**
 * Holds the feed filter per group so it survives the Filter destination round-trip — the group tab is a
 * full nav destination, so a `remember` there would reset every time the sheet opens. Ephemeral UI
 * state (a Koin singleton), intentionally not persisted.
 */
class GroupFilterStore {
    private val flows = mutableMapOf<String, MutableStateFlow<GroupFilter>>()
    private fun flow(groupId: String): MutableStateFlow<GroupFilter> = flows.getOrPut(groupId) { MutableStateFlow(GroupFilter()) }
    fun filterFor(groupId: String): StateFlow<GroupFilter> = flow(groupId).asStateFlow()
    fun set(groupId: String, filter: GroupFilter) { flow(groupId).value = filter }
    fun clear(groupId: String) { flow(groupId).value = GroupFilter() }
}
