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
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

data class ConflictUi(val title: String, val amount: Double, val by: String)

/** 10 · Group · Conflicts tab (design/src/screens-group2.jsx). */
@Composable
fun GroupConflictsTab(
    memberName: String = "Tyler",
    conflicts: List<ConflictUi> = listOf(
        ConflictUi("Dinner at La Negra", 96.0, "Andrew"),
        ConflictUi("Cenote day trip", 72.0, "Bob"),
    ),
    onInclude: (ConflictUi) -> Unit = {},
    onSkip: (ConflictUi) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    Column(Modifier.fillMaxSize().background(c.surface)) {
        ScTopBar("Conflicts", subtitle = "$memberName joined after these expenses")
        ScBanner("Decide whether $memberName shares these costs.", variant = BannerVariant.Amber, leadingIcon = ScIcons.Alert)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            ScButton("Include $memberName", { onInclude(item) }, leadingIcon = ScIcons.Plus, modifier = Modifier.weight(1f))
                            ScButton("Skip $memberName", { onSkip(item) }, variant = ButtonVariant.Text)
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun GroupConflictsPreview() {
    ShareCostTheme { GroupConflictsTab() }
}
