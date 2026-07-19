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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScListCard
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScSheetScaffold
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.currencySymbol
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme
import kotlin.math.roundToLong

/**
 * 10b · Include-member sheet (design/src/screens-group2.jsx `IncludeSheet`). The user types the new
 * member's share (no pre-fill, per App_Overview §6); the rest is covered by reducing the existing
 * participants proportionally, shown live as "Others now cover …". Confirm is gated to a share in
 * `(0, total)`. [onConfirm] hands back the share in subunits.
 */
@Composable
fun IncludeMemberSheet(
    memberName: String = "Tyler",
    expenseTitle: String = "Dinner at La Negra",
    expenseAmountSubunits: Long = 9600,
    currencyCode: String = "USD",
    currentSplit: List<Pair<String, Long>> = DemoCurrentSplit,
    saving: Boolean = false,
    onDismiss: () -> Unit = {},
    onConfirm: (newShareSubunits: Long) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var shareText by remember { mutableStateOf("") }
    val shareSubunits = parseSubunits(shareText)
    val valid = shareSubunits in 1 until expenseAmountSubunits
    val othersCover = (expenseAmountSubunits - shareSubunits).coerceAtLeast(0)

    Box(Modifier.fillMaxSize().background(c.page)) {
        ScSheetScaffold(onDismiss, title = "Include $memberName", sub = "$expenseTitle · ${moneySubunits(expenseAmountSubunits, currencyCode)}") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    ScSectionLabel("Current split")
                    ScListCard(items = currentSplit) { (name, owed) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ScAvatar(name, me = name == "You", size = AvatarSize.Sm)
                            Text(name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(moneySubunits(owed, currencyCode), color = c.ink2, fontFamily = ShareCostTheme.monoFamily)
                        }
                    }
                }
                ScField("$memberName's share") {
                    ScTextField(
                        shareText, { shareText = it.filter { ch -> ch.isDigit() || ch == '.' } }, placeholder = "0.00", mono = true,
                        keyboardType = KeyboardType.Decimal,
                        leading = { Text(currencySymbol(currencyCode), color = c.ink3, fontSize = 20.sp, fontFamily = ShareCostTheme.monoFamily) },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScIcon(if (valid) ScIcons.Info else ScIcons.Alert, size = 15.dp, tint = if (valid) c.bluePressed else c.danger)
                        Text(if (valid) "Others now cover" else "Enter a share below ${moneySubunits(expenseAmountSubunits, currencyCode)}", color = if (valid) c.bluePressed else c.danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (valid) Text(moneySubunits(othersCover, currencyCode), color = c.ink, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
                }
                ScButton("Confirm split", { onConfirm(shareSubunits) }, leadingIcon = ScIcons.Check, enabled = valid && !saving)
            }
        }
    }
}

private fun parseSubunits(text: String): Long {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return 0
    return ((cleaned.toDoubleOrNull() ?: 0.0) * 100.0).roundToLong()
}

private val DemoCurrentSplit = listOf("You" to 2400L, "Andrew" to 2400L, "Bob" to 2400L, "Maya" to 2400L)

@Preview
@Composable
private fun IncludeMemberPreview() {
    ShareCostTheme { IncludeMemberSheet() }
}
