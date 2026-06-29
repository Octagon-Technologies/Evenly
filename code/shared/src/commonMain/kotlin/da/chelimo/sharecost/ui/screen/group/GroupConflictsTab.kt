package da.chelimo.sharecost.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.BannerVariant
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScAmountText
import da.chelimo.sharecost.ui.components.ScBanner
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** One unresolved conflict row. Carries the ids the route needs to resolve / open the include sheet. */
data class ConflictUi(
    val conflictId: String,
    val expenseId: String,
    val memberUserId: String,
    val memberName: String,
    val title: String,
    val amount: Double,
    val by: String,
)

/**
 * One parked edit-collision: two devices edited the same expense from the same base version. Shows the
 * canonical (current) side vs the rejected side so the user picks one — we never auto-merge two splits.
 */
data class EditConflictUi(
    val conflictId: String,
    val by: String,
    val currentTitle: String,
    val currentAmount: Double,
    val rejectedTitle: String,
    val rejectedAmount: Double,
)

/** 10 · Group · Conflicts tab (design/src/screens-group2.jsx). */
@Composable
fun GroupConflictsTab(
    memberName: String = "Tyler",
    conflicts: List<ConflictUi> = DemoConflicts,
    editConflicts: List<EditConflictUi> = emptyList(),
    onBack: () -> Unit = {},
    onInclude: (ConflictUi) -> Unit = {},
    onSkip: (ConflictUi) -> Unit = {},
    onKeepCurrent: (EditConflictUi) -> Unit = {},
    onUseRejected: (EditConflictUi) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    Column(Modifier.fillMaxSize().background(c.surface)) {
        ScTopBar("Conflicts", subtitle = "$memberName joined after these expenses", navIcon = { ScIconButton(ScIcons.Back, onBack) })
        ScBanner("Decide who shares these costs.", variant = BannerVariant.Amber, leadingIcon = ScIcons.Alert)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Edit-collisions first: two people edited the same expense offline. Pick a side; never auto-merge.
            if (editConflicts.isNotEmpty()) {
                Text("Edited at the same time", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            editConflicts.forEach { item ->
                ScCard(padded = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${item.by} edited this while you did too", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("Keep current", color = c.ink2, fontSize = 12.sp); Text(item.currentTitle, color = c.ink, fontSize = 14.sp) }
                            ScAmountText(money(item.currentAmount))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("Use ${item.by}'s", color = c.ink2, fontSize = 12.sp); Text(item.rejectedTitle, color = c.ink, fontSize = 14.sp) }
                            ScAmountText(money(item.rejectedAmount))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ScButton("Keep current", { onKeepCurrent(item) }, modifier = Modifier.weight(1f))
                            ScButton("Use ${item.by}'s", { onUseRejected(item) }, variant = ButtonVariant.Text)
                        }
                    }
                }
            }
            conflicts.forEach { item ->
                ScCard(padded = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(item.title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text("Added by ${item.by}", color = c.ink2, fontSize = 12.sp)
                            }
                            ScAmountText(money(item.amount))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ScButton("Include ${item.memberName}", { onInclude(item) }, leadingIcon = ScIcons.Plus, modifier = Modifier.weight(1f))
                            ScButton("Skip ${item.memberName}", { onSkip(item) }, variant = ButtonVariant.Text)
                        }
                    }
                }
            }
        }
    }
}

private val DemoConflicts = listOf(
    ConflictUi("c1", "e1", "tyler", "Tyler", "Dinner at La Negra", 96.0, "Andrew"),
    ConflictUi("c2", "e2", "tyler", "Tyler", "Cenote day trip", 72.0, "Bob"),
)

@Preview
@Composable
private fun GroupConflictsPreview() {
    ShareCostTheme { GroupConflictsTab() }
}
