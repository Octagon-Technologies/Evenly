package da.chelimo.sharecost.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScDivider
import da.chelimo.sharecost.ui.components.ScParticipantChip
import da.chelimo.sharecost.ui.components.ScSheetScaffold
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

private data class CatChip(val key: String, val icon: ImageVector, val label: String)

/** 7 · Filter sheet (design/src/screens-group2.jsx). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(onDismiss: () -> Unit = {}, onReset: () -> Unit = {}, onApply: () -> Unit = {}) {
    val c = ShareCostTheme.colors
    var sort by remember { mutableStateOf("Date") }
    var range by remember { mutableStateOf("Trip") }
    var cats by remember { mutableStateOf(setOf("food", "fun")) }
    val members = listOf("You", "Andrew", "Bob", "Maya", "Tyler")
    val catChips = listOf(
        CatChip("food", ScIcons.Food, "Food"), CatChip("groc", ScIcons.Cart, "Groceries"),
        CatChip("transit", ScIcons.Car, "Transport"), CatChip("stay", ScIcons.Bed, "Lodging"),
        CatChip("fun", ScIcons.Ticket, "Activities"), CatChip("utils", ScIcons.Bolt, "Utilities"),
        CatChip("home", ScIcons.Home, "Household"), CatChip("gift", ScIcons.Gift, "Gifts"),
    )

    Box(Modifier.fillMaxSize().background(c.surface)) {
        ScSheetScaffold(onDismiss, title = "Sort & filter") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FilterGroup("Sort by") {
                    listOf("Date", "Amount", "Payer", "Category", "Participant").forEach {
                        ScParticipantChip(it, selected = sort == it, onClick = { sort = it })
                    }
                }
                ScDivider()
                FilterGroup("Member") {
                    members.forEach { m ->
                        ScParticipantChip(m, selected = false, leading = { ScAvatar(m, me = m == "You", size = AvatarSize.Xs) })
                    }
                }
                FilterGroup("Category") {
                    catChips.forEach { cc ->
                        ScParticipantChip(cc.label, selected = cats.contains(cc.key), leading = { ScIcon(cc.icon, size = 15.dp) }, onClick = {
                            cats = if (cats.contains(cc.key)) cats - cc.key else cats + cc.key
                        })
                    }
                }
                FilterGroup("Date range") {
                    listOf("Trip", "7 days", "30 days", "This month", "Custom…").forEach {
                        ScParticipantChip(it, selected = range == it, onClick = { range = it })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScButton("Reset", onReset, variant = ButtonVariant.Secondary, modifier = Modifier.weight(1f))
                    ScButton("Show 14 results", onApply, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(label: String, content: @Composable FlowRowScope.() -> Unit) {
    val c = ShareCostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Preview
@Composable
private fun FilterPreview() {
    ShareCostTheme { FilterSheet() }
}
