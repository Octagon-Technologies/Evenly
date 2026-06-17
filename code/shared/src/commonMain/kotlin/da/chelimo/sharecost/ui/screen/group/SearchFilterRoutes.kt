package da.chelimo.sharecost.ui.screen.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.todayUtc
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.repository.ExpenseRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.ui.components.currencySymbol
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Search overlay, wired (F6): live-filters the group's real expenses (by title) + members (by name). */
@Composable
fun SearchRoute(groupId: String, onBack: () -> Unit, onOpenExpense: (String) -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val expenseList by remember(gid) { expenses.observeExpenses(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val q = query.trim()
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    fun payerName(e: Expense): String = when {
        e.payerUserId == null -> e.payerOutsideName ?: "Someone"
        e.payerUserId == userId -> "You"
        else -> nameByUser[e.payerUserId.value] ?: "Someone"
    }

    val expenseResults = if (q.isEmpty()) emptyList() else expenseList
        .filter { it.title.contains(q, ignoreCase = true) }
        .map { e ->
            val amount = e.amountSubunits / 100.0
            SearchExpenseUi(
                id = e.id.value,
                title = e.title,
                sub = "${payerName(e)} paid",
                remaining = amount,
                original = amount,
                currencySymbol = currencySymbol(e.currency),
            )
        }
    val memberResults = if (q.isEmpty()) emptyList() else members
        .filter { (it.displayName ?: "").contains(q, ignoreCase = true) }
        .map { m ->
            val paid = expenseList.count { it.payerUserId == m.userId }
            SearchMemberUi(
                name = if (m.userId == userId) "You" else (m.displayName ?: "Someone"),
                sub = "$paid ${if (paid == 1) "expense" else "expenses"} paid",
                me = m.userId == userId,
            )
        }

    SearchOverlay(
        query = query,
        onQueryChange = { query = it },
        expenseResults = expenseResults,
        memberResults = memberResults,
        onBack = onBack,
        onOpenExpense = onOpenExpense,
    )
}

/** Filter sheet, wired (F6): edits the per-group [GroupFilterStore] entry; the Expenses tab observes it. */
@OptIn(ExperimentalTime::class)
@Composable
fun FilterRoute(groupId: String, onDismiss: () -> Unit) {
    val store = koinInject<GroupFilterStore>()
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val expenseList by remember(gid) { expenses.observeExpenses(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val current by remember(groupId) { store.filterFor(groupId) }.collectAsStateWithLifecycle()
    val today = remember { Clock.System.todayUtc() }

    val memberUis = members.map { m ->
        FilterMemberUi(m.userId.value, if (m.userId == userId) "You" else (m.displayName ?: "Someone"), me = m.userId == userId)
    }
    FilterSheet(
        members = memberUis,
        initial = current,
        countFor = { f -> applyFilter(expenseList, f, today).size },
        onDismiss = onDismiss,
        onReset = { store.clear(groupId); onDismiss() },
        onApply = { f -> store.set(groupId, f); onDismiss() },
    )
}
