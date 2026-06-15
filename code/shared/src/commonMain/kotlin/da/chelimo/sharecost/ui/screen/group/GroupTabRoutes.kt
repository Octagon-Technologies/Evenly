package da.chelimo.sharecost.ui.screen.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.todayUtc
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.balance.Debt
import da.chelimo.sharecost.domain.group.Member
import da.chelimo.sharecost.domain.repository.ExpenseRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Maps bilateral [Debt]s to display rows, resolving names (current user → "You") and the settle peer. */
fun buildBalances(debts: List<Debt>, members: List<Member>, currentUserId: UserId?): List<DebtUi> {
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    fun name(id: UserId): String = if (id == currentUserId) "You" else (nameByUser[id.value] ?: "Someone")
    return debts.map { d ->
        val peer = if (d.debtorUserId == currentUserId) d.creditorUserId else d.debtorUserId
        DebtUi(
            from = name(d.debtorUserId),
            to = name(d.creditorUserId),
            amount = d.amountSubunits / 100.0,
            owedToYou = d.creditorUserId == currentUserId,
            peerUserId = peer.value,
        )
    }
}

/** Expenses tab content, wired. */
@OptIn(ExperimentalTime::class)
@Composable
fun GroupExpensesRoute(
    groupId: String,
    onOpenSettings: () -> Unit,
    onAdd: () -> Unit,
    onOpenExpense: (String) -> Unit,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val expenseList by remember(gid) { expenses.observeExpenses(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val today = remember { Clock.System.todayUtc() }
    val ui = buildGroupExpenses(group, expenseList, members, userId, today)
    GroupExpensesTab(
        groupEmoji = ui.groupEmoji, groupName = ui.groupName, state = ui.state, days = ui.days, drafts = 0,
        onOpenGroup = onOpenSettings, onAdd = onAdd, onOpenExpense = onOpenExpense, onSearch = onSearch, onFilter = onFilter,
    )
}

/** Balances tab content, wired (pairwise debts; category spend needs a category field — deferred). */
@Composable
fun GroupBalancesRoute(groupId: String, onSettleNav: (String) -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val debts by remember(gid) { expenses.observeBalances(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val expenseList by remember(gid) { expenses.observeExpenses(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val rows = buildBalances(debts, members, userId)
    val total = expenseList.sumOf { it.amountSubunits } / 100.0
    GroupBalancesTab(empty = rows.isEmpty(), debts = rows, spend = emptyList(), total = total, onSettle = { onSettleNav(it.peerUserId) })
}

/** Conflicts tab content. Retroactive-member conflicts aren't generated in the MVP, so this is empty. */
@Composable
fun GroupConflictsRoute(onIncludeNav: () -> Unit) {
    GroupConflictsTab(conflicts = emptyList(), onInclude = { onIncludeNav() }, onSkip = {})
}
