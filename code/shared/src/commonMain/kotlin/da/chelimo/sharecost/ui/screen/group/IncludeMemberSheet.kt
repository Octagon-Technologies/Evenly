package da.chelimo.sharecost.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScListCard
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScSheetScaffold
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** 10b · Include-member sheet (design/src/screens-group2.jsx `IncludeSheet`). */
@Composable
fun IncludeMemberSheet(
    memberName: String = "Tyler",
    expenseTitle: String = "Dinner at La Negra",
    expenseAmount: Double = 96.0,
    onDismiss: () -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    val currentSplit = listOf("You" to 24.0, "Andrew" to 24.0, "Bob" to 24.0, "Maya" to 24.0)
    Box(Modifier.fillMaxSize().background(c.surface)) {
        ScSheetScaffold(onDismiss, title = "Include $memberName", sub = "$expenseTitle · ${money(expenseAmount)}") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    ScSectionLabel("Current split")
                    ScListCard(items = currentSplit) { (name, amount) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ScAvatar(name, me = name == "You", size = AvatarSize.Sm)
                            Text(name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(money(amount), color = c.ink2, fontFamily = ShareCostTheme.monoFamily)
                        }
                    }
                }
                ScField("$memberName's share") {
                    ScTextField(
                        "", {}, placeholder = "0.00", mono = true,
                        leading = { Text("\$", color = c.ink3, fontSize = 20.sp, fontFamily = ShareCostTheme.monoFamily) },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScIcon(ScIcons.Info, size = 15.dp, tint = c.bluePressed)
                        Text("Remaining to distribute", color = c.bluePressed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(money(expenseAmount), color = c.ink, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
                }
                ScButton("Confirm split", onConfirm, leadingIcon = ScIcons.Check)
            }
        }
    }
}

@Preview
@Composable
private fun IncludeMemberPreview() {
    ShareCostTheme { IncludeMemberSheet() }
}
