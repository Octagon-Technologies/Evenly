package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.time.todayUtc
import da.chelimo.sharecost.domain.repository.ExpenseRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.ui.screen.group.GroupOverviewTab
import da.chelimo.sharecost.ui.screen.group.buildOverview
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Overview tab, wired: streams the group + expenses + members + balances into the aggregates. */
@OptIn(ExperimentalTime::class)
@Composable
fun OverviewRoute(groupId: String, onExport: () -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val expenseList by remember(gid) { expenses.observeExpenses(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val balances by remember(gid) { expenses.observeBalances(gid) }.collectAsStateWithLifecycle(emptyList())
    val today = remember { Clock.System.todayUtc() }

    val ui = buildOverview(group, expenseList, members, balances, today)
    GroupOverviewTab(
        groupEmoji = ui.groupEmoji,
        groupName = ui.groupName,
        byDay = ui.byDay,
        byMember = ui.byMember,
        totalSubunits = ui.totalSubunits,
        expenseCount = ui.expenseCount,
        perPersonSubunits = ui.perPersonSubunits,
        currencyCode = ui.currencyCode,
        balances = ui.balances,
        onExport = onExport,
    )
}
