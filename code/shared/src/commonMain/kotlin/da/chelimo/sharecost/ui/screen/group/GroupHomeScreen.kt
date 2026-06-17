package da.chelimo.sharecost.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.ui.components.BottomNavItem
import da.chelimo.sharecost.ui.components.ScBottomNav
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.navigation.GroupTab
import da.chelimo.sharecost.ui.navigation.OverviewRoute
import da.chelimo.sharecost.ui.theme.ShareCostTheme
import org.koin.compose.koinInject

/**
 * Group home host (design §0). The four tabs (Expenses/Balances/Conflicts/Overview) are state inside
 * this one destination — the bottom nav switches them; the back stack is unaffected. Each tab is a
 * self-contained smart route that streams its own data, so this host is DI-driven (no `@Preview`; the
 * individual tabs have their own previews).
 */
@Composable
fun GroupHomeScreen(
    groupId: String = "1",
    initialTab: GroupTab = GroupTab.Expenses,
    onAdd: () -> Unit = {},
    onOpenExpense: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onFilter: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSettlePeer: (String) -> Unit = {},
    onIncludeNav: (conflictId: String, expenseId: String, memberUserId: String) -> Unit = { _, _, _ -> },
    onExport: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var tab by remember { mutableStateOf(initialTab) }
    val groups = koinInject<GroupRepository>()
    val conflicts by remember(groupId) { groups.observeConflicts(GroupId(groupId)) }.collectAsStateWithLifecycle(emptyList())
    val conflictCount = conflicts.size
    // If the conflicts clear while the tab is open, fall back to Expenses so we don't show a blank tab.
    if (conflictCount == 0 && tab == GroupTab.Conflicts) tab = GroupTab.Expenses

    Column(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                GroupTab.Expenses -> GroupExpensesRoute(groupId, onOpenSettings, onAdd, onOpenExpense, onSearch, onFilter)
                GroupTab.Balances -> GroupBalancesRoute(groupId, onSettleNav = onSettlePeer)
                GroupTab.Conflicts -> GroupConflictsRoute(groupId = groupId, onIncludeNav = onIncludeNav)
                GroupTab.Overview -> OverviewRoute(groupId, onExport = onExport)
            }
        }
        val items = buildList {
            add(BottomNavItem("expenses", "Expenses", ScIcons.Receipt))
            add(BottomNavItem("balances", "Balances", ScIcons.Swap))
            if (conflictCount > 0) add(BottomNavItem("conflicts", "Conflicts", ScIcons.Flag, badge = conflictCount))
            add(BottomNavItem("overview", "Overview", ScIcons.Chart))
        }
        ScBottomNav(
            items = items,
            selectedId = tab.name.lowercase(),
            onSelect = { id ->
                tab = when (id) {
                    "balances" -> GroupTab.Balances
                    "conflicts" -> GroupTab.Conflicts
                    "overview" -> GroupTab.Overview
                    else -> GroupTab.Expenses
                }
            },
        )
    }
}
