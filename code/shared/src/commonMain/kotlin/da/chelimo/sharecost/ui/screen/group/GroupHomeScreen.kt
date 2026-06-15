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
import androidx.compose.ui.tooling.preview.Preview
import da.chelimo.sharecost.ui.components.BottomNavItem
import da.chelimo.sharecost.ui.components.ScBottomNav
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.navigation.GroupTab
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/**
 * Group home host (design §0). The four tabs (Expenses/Balances/Conflicts/Overview) are state inside
 * this one destination — the bottom nav switches them; the back stack is unaffected.
 */
@Composable
fun GroupHomeScreen(
    groupId: String = "1",
    initialTab: GroupTab = GroupTab.Expenses,
    conflictCount: Int = 2,
    onAdd: () -> Unit = {},
    onOpenExpense: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onFilter: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSettle: (DebtUi) -> Unit = {},
    onInclude: (ConflictUi) -> Unit = {},
    onExport: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var tab by remember { mutableStateOf(initialTab) }

    Column(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                GroupTab.Expenses -> GroupExpensesTab(
                    onOpenGroup = onOpenSettings, onAdd = onAdd, onOpenExpense = onOpenExpense,
                    onSearch = onSearch, onFilter = onFilter,
                )
                GroupTab.Balances -> GroupBalancesTab(onSettle = onSettle)
                GroupTab.Conflicts -> GroupConflictsTab(onInclude = onInclude)
                GroupTab.Overview -> GroupOverviewTab(onExport = onExport)
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

@Preview
@Composable
private fun GroupHomePreview() {
    ShareCostTheme { GroupHomeScreen() }
}
