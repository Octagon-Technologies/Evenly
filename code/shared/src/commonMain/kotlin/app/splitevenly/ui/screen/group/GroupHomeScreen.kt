package app.splitevenly.ui.screen.group

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.id.GroupId
import app.splitevenly.domain.repository.ExpenseRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.ui.components.BottomNavItem
import app.splitevenly.ui.components.EvBottomNav
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.navigation.GroupTab
import app.splitevenly.ui.navigation.OverviewRoute
import app.splitevenly.ui.theme.EvMotion
import app.splitevenly.ui.theme.EvenlyTheme
import org.koin.compose.koinInject

/** Persists the active group tab by name across a pushed-then-popped sub-screen (see the `tab` state). */
private val GroupTabSaver = Saver<GroupTab, String>(save = { it.name }, restore = { GroupTab.valueOf(it) })

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
    onBack: () -> Unit = {},
    onAdd: () -> Unit = {},
    onOpenExpense: (String) -> Unit = {},
    onOpenBill: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSettlePeer: (String) -> Unit = {},
    onIncludeNav: (conflictId: String, expenseId: String, memberUserId: String) -> Unit = { _, _, _ -> },
    onExport: () -> Unit = {},
    /** "See all N" on the identity card, and the settings row, both open the full-screen list. */
    onClaimNames: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    // Saved so leaving a tab for a pushed screen (an expense, settle, bill) and coming back returns to
    // that same tab instead of resetting to initialTab. Stored by name (enum savers are Native-fragile).
    var tab by rememberSaveable(stateSaver = GroupTabSaver) { mutableStateOf(initialTab) }
    val groups = koinInject<GroupRepository>()
    val expenses = koinInject<ExpenseRepository>()
    val conflicts by remember(groupId) { groups.observeConflicts(GroupId(groupId)) }.collectAsStateWithLifecycle(emptyList())
    val editConflicts by remember(groupId) { expenses.observeEditConflicts(GroupId(groupId)) }.collectAsStateWithLifecycle(emptyList())
    // Both kinds of conflict surface in the one Conflicts tab: retroactive-member *and* edit-collisions.
    val conflictCount = conflicts.size + editConflicts.size
    // If the conflicts clear while the tab is open, fall back to Expenses so we don't show a blank tab.
    if (conflictCount == 0 && tab == GroupTab.Conflicts) tab = GroupTab.Expenses

    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim(c.page)
        Box(Modifier.weight(1f)) {
            Crossfade(targetState = tab, animationSpec = EvMotion.standard()) { current ->
                when (current) {
                    GroupTab.Expenses -> GroupExpensesRoute(groupId, onBack, onOpenSettings, onAdd, onOpenExpense, onOpenBill, onSearch, onClaimNames)
                    GroupTab.Balances -> GroupBalancesRoute(groupId, onBack = onBack, onSettleNav = onSettlePeer)
                    GroupTab.Conflicts -> GroupConflictsRoute(groupId = groupId, onBack = onBack, onIncludeNav = onIncludeNav)
                    GroupTab.Overview -> OverviewRoute(groupId, onBack = onBack, onExport = onExport)
                }
            }
        }
        val items = buildList {
            add(BottomNavItem("expenses", "Expenses", EvIcons.Receipt))
            add(BottomNavItem("balances", "Balances", EvIcons.Swap))
            if (conflictCount > 0) add(BottomNavItem("conflicts", "Review", EvIcons.Flag, badge = conflictCount))
            add(BottomNavItem("overview", "Overview", EvIcons.Chart))
        }
        EvBottomNav(
            items = items,
            selectedId = tab.name.lowercase(),
            navBarInset = true,
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
