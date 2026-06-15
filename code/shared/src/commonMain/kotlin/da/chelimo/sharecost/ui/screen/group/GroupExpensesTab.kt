package da.chelimo.sharecost.ui.screen.group

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.ScBanner
import da.chelimo.sharecost.ui.components.ScEmptyState
import da.chelimo.sharecost.ui.components.ScExpenseRow
import da.chelimo.sharecost.ui.components.ScFab
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScSkeletonRow
import da.chelimo.sharecost.ui.components.ScSubTabs
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

data class ExpenseItemUi(
    val id: String,
    val title: String,
    val sub: String,
    val icon: ImageVector,
    val remaining: Double,
    val original: Double,
    val settled: Boolean = false,
    val unread: Boolean = false,
    val currencySymbol: String = "$",
)

data class ExpenseDayUi(val label: String, val items: List<ExpenseItemUi>)

enum class ExpensesState { Loading, Empty, Populated }

/** 6 · Group · Expenses tab (design/src/screens-group.jsx). Hosted inside GroupHomeScreen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupExpensesTab(
    groupEmoji: String = "🏝️",
    groupName: String = "Tulum Trip",
    state: ExpensesState = ExpensesState.Populated,
    days: List<ExpenseDayUi> = GroupSamples.days,
    drafts: Int = 1,
    offline: Boolean = false,
    onOpenGroup: () -> Unit = {},
    onAdd: () -> Unit = {},
    onOpenExpense: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onFilter: () -> Unit = {},
    onOpenDrafts: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var sub by remember { mutableStateOf("Active") }
    Column(Modifier.fillMaxSize().background(c.surface)) {
        ScTopBar(
            title = groupName,
            navIcon = { Text(groupEmoji, fontSize = 22.sp, modifier = Modifier.clickable { onOpenGroup() }) },
            actions = {
                ScIconButton(ScIcons.Search, onSearch)
                ScIconButton(ScIcons.Filter, onFilter)
            },
        )
        if (offline) ScBanner("Offline — your changes will sync.")
        ScSubTabs(tabs = listOf("Active", "All", "Settled"), selected = sub, onSelect = { sub = it })

        when (state) {
            ExpensesState.Loading -> Column(Modifier.padding(top = 8.dp)) { repeat(5) { ScSkeletonRow() } }
            ExpensesState.Empty -> ScEmptyState(
                icon = ScIcons.Receipt,
                title = "No expenses yet",
                text = "Add the first shared cost and ShareCost tracks who owes whom.",
                ctaText = "Add expense",
                onCta = onAdd,
            )
            ExpensesState.Populated -> Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (drafts > 0) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
                                    .clip(RoundedCornerShape(12.dp)).background(c.blueTint).clickable { onOpenDrafts() }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ScIcon(ScIcons.Edit, size = 16.dp, tint = c.bluePressed)
                                    Text("Drafts ($drafts)", color = c.bluePressed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                                ScIcon(ScIcons.ChevR, size = 16.dp, tint = c.blue)
                            }
                        }
                    }
                    days.forEach { day ->
                        stickyHeader(key = day.label) { ScDayHeader(day.label) }
                        val active = day.items.filter { !it.settled }
                        itemsIndexed(active, key = { _, it -> it.id }) { i, it ->
                            Box(if (i > 0) Modifier.topHairline(c.border) else Modifier) { ExpenseRowFrom(it, onOpenExpense) }
                        }
                        val settled = day.items.filter { it.settled }
                        if (settled.isNotEmpty()) {
                            item(key = "settled-${day.label}") { SettledTray(settled, onOpenExpense) }
                        }
                    }
                    item { Spacer(Modifier.height(150.dp)) }
                }
                ScFab(onAdd, Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        }
    }
}

@Composable
private fun ExpenseRowFrom(it: ExpenseItemUi, onOpen: (String) -> Unit) {
    ScExpenseRow(
        title = it.title,
        sub = it.sub,
        icon = it.icon,
        remaining = money(it.remaining, it.currencySymbol),
        original = money(it.original, it.currencySymbol),
        settled = it.settled,
        unread = it.unread,
        onClick = { onOpen(it.id) },
    )
}

@Composable
private fun ScDayHeader(label: String) {
    val c = ShareCostTheme.colors
    Text(
        label,
        Modifier.fillMaxWidth().background(c.surface).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        color = c.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
    )
}

@Composable
private fun SettledTray(items: List<ExpenseItemUi>, onOpen: (String) -> Unit) {
    val c = ShareCostTheme.colors
    var open by remember { mutableStateOf(false) }
    Column(Modifier.topHairline(c.border)) {
        Row(
            Modifier.fillMaxWidth().background(c.surface).clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                ScIcon(ScIcons.Check, size = 20.dp, tint = c.blue)
            }
            Text("Settled (${items.size})", color = c.ink2, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            ScIcon(if (open) ScIcons.ChevU else ScIcons.ChevD, size = 16.dp, tint = c.ink3)
        }
        if (open) {
            items.forEach { Box(Modifier.topHairline(c.border)) { ExpenseRowFrom(it, onOpen) } }
        }
    }
}

internal object GroupSamples {
    val days = listOf(
        ExpenseDayUi("Today", listOf(
            ExpenseItemUi("1", "Dinner at La Negra", "Andrew paid · you owe \$24.00", ScIcons.Food, 24.0, 96.0, unread = true),
            ExpenseItemUi("2", "Airport taxi", "You paid · Bob owes \$14.50", ScIcons.Car, 14.5, 58.0),
        )),
        ExpenseDayUi("Wed, May 22", listOf(
            ExpenseItemUi("3", "Beach villa — night 2", "Maya paid · you owe \$0 of \$60", ScIcons.Bed, 0.0, 60.0, settled = true),
            ExpenseItemUi("4", "Cenote day trip", "Bob paid · you owe \$18.00", ScIcons.Ticket, 18.0, 72.0),
            ExpenseItemUi("5", "Supermarket run", "You paid · 4 owe you", ScIcons.Cart, 33.2, 41.5),
        )),
    )
}

@Preview
@Composable
private fun GroupExpensesPreview() {
    ShareCostTheme { GroupExpensesTab() }
}
