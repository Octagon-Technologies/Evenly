package da.chelimo.sharecost.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScParticipantChip
import da.chelimo.sharecost.ui.components.ScSegmented
import da.chelimo.sharecost.ui.components.ScSelectField
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme
import kotlin.math.abs

/** 13 · Add / edit expense with live split math (design/src/screens-addexpense.jsx). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddExpenseScreen(
    editing: Boolean = false,
    onBack: () -> Unit = {},
    onSave: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    val members = listOf("You", "Andrew", "Bob", "Maya")
    val total = 99.95
    var split by remember { mutableStateOf("%") }
    var title by remember { mutableStateOf("Group dinner — La Negra") }
    val pct = mapOf("You" to 25.0, "Andrew" to 25.0, "Bob" to 25.0, "Maya" to 24.95)
    val pctTotal = pct.values.sum()
    val exact = mapOf("You" to 25.0, "Andrew" to 25.0, "Bob" to 20.0, "Maya" to 25.0)
    val off = total - exact.values.sum()

    Column(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        ScTopBar(
            title = if (editing) "Edit expense" else "New expense",
            navIcon = { ScIconButton(ScIcons.Close, onBack) },
            actions = {
                Box(Modifier.clip(RoundedCornerShape(11.dp)).background(c.blue).clickable { onSave() }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Save", color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // amount
            ScCard(padded = true) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("\$", color = c.ink3, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                        Text("99.95", color = c.ink, style = ShareCostTheme.amounts.input)
                    }
                    ScChip("USD", variant = ChipVariant.Ghost, leadingIcon = ScIcons.Globe)
                }
            }

            ScField("Title") { ScTextField(title, { title = it }) }

            ScField("Paid by") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ScSelectField("You", {}, leading = { ScAvatar("You", me = true, size = AvatarSize.Sm) })
                    Text("Can also be \"Someone outside the group\"", color = c.ink2, fontSize = 12.sp)
                }
            }

            // participants
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Participants", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    members.forEach { m ->
                        ScParticipantChip(m, selected = true, leading = { ScAvatar(m, me = m == "You", size = AvatarSize.Xs) }, trailing = { ScIcon(ScIcons.Check, size = 14.dp) })
                    }
                    ScParticipantChip("Tyler", selected = false, leading = { ScAvatar("?", size = AvatarSize.Xs) })
                    ScParticipantChip("Add", selected = false, leading = { ScIcon(ScIcons.Plus, size = 15.dp, tint = c.blue) })
                }
            }

            // split
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Split", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                ScSegmented(options = listOf("Even", "Share", "%", "Exact"), selected = split, onSelect = { split = it })
                ScCard(modifier = Modifier.padding(top = 4.dp)) {
                    members.forEachIndexed { i, m ->
                        Row(
                            Modifier.fillMaxWidth().then(if (i > 0) Modifier.topHairline(c.border) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ScAvatar(m, me = m == "You", size = AvatarSize.Sm)
                            Text(m, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            when (split) {
                                "Even" -> Text("\$24.99", color = c.ink, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                                "Share" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ScChip("1×")
                                    Text("\$24.99", color = c.ink2, fontFamily = ShareCostTheme.monoFamily)
                                }
                                "%" -> SplitValueBox("${formatPct(pct[m] ?: 0.0)}%", width = 78.dp)
                                else -> SplitValueBox(money(exact[m] ?: 0.0), width = 92.dp)
                            }
                        }
                    }
                }
                // live math footer
                if (split == "%") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScIcon(if (pctTotal == 100.0) ScIcons.CheckCircle else ScIcons.Info, size = 15.dp, tint = if (pctTotal == 100.0) c.blue else c.warning)
                            Text("Total: ${formatPct(pctTotal)}%", color = if (pctTotal == 100.0) c.blue else c.warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (pctTotal != 100.0) Text("Distribute remainder", color = c.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { })
                    }
                }
                if (split == "Exact") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScIcon(if (abs(off) < 0.005) ScIcons.CheckCircle else ScIcons.Alert, size = 15.dp, tint = if (abs(off) < 0.005) c.blue else c.danger)
                            Text(if (abs(off) < 0.005) "Splits add up" else "Off by ${money(abs(off))}", color = if (abs(off) < 0.005) c.blue else c.danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (abs(off) >= 0.005) Text("${money(95.0)} of ${money(total)}", color = c.ink3, fontSize = 12.sp, fontFamily = ShareCostTheme.monoFamily)
                    }
                }
            }

            // meta rows
            ScCard {
                MetaRow(ScIcons.Calendar, "Date", "Today, May 23")
                Box(Modifier.topHairline(c.border)) { MetaRow(ScIcons.Tag, "Category", "Food & Drink · Dinner") }
                Box(Modifier.topHairline(c.border)) { MetaRow(ScIcons.Edit, "Notes", "Add a note", muted = true) }
            }

            // receipts
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Receipts", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Text("receipt", color = c.ink3, fontSize = 10.sp, fontFamily = ShareCostTheme.monoFamily)
                    }
                    Column(Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).clickable { }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        ScIcon(ScIcons.Camera, size = 22.dp, tint = c.ink2)
                        Text("Add", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                ScIcon(ScIcons.Check, size = 14.dp, tint = c.ink3)
                Text(" Draft saved", color = c.ink3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SplitValueBox(text: String, width: androidx.compose.ui.unit.Dp) {
    val c = ShareCostTheme.colors
    Box(
        Modifier.width(width).clip(RoundedCornerShape(10.dp)).background(c.page).border(1.dp, c.borderStrong, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(text, color = c.ink, fontFamily = ShareCostTheme.monoFamily, fontSize = 14.sp)
    }
}

@Composable
private fun MetaRow(icon: ImageVector, label: String, value: String, muted: Boolean = false) {
    val c = ShareCostTheme.colors
    Row(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ScIcon(icon, size = 20.dp, tint = c.ink2)
        Text(label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, color = if (muted) c.ink3 else c.ink2, fontSize = 14.sp)
        ScIcon(ScIcons.ChevR, size = 15.dp, tint = c.ink3)
    }
}

private fun formatPct(v: Double): String {
    val rounded = (v * 100).toLong()
    val whole = rounded / 100
    val frac = (rounded % 100).toString().padStart(2, '0')
    return "$whole.$frac"
}

@Preview
@Composable
private fun AddExpensePreview() {
    ShareCostTheme { AddExpenseScreen() }
}
