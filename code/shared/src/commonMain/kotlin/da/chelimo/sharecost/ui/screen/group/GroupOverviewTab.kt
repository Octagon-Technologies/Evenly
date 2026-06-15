package da.chelimo.sharecost.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScDebtRow
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** 11 · Trip overview (design/src/screens-group2.jsx). */
@Composable
fun GroupOverviewTab(
    groupEmoji: String = "🏝️",
    groupName: String = "Tulum Trip",
    onExport: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    val byDay = listOf("Mon" to 40, "Tue" to 96, "Wed" to 132, "Thu" to 58, "Fri" to 210, "Sat" to 180, "Sun" to 64)
    val byMember = listOf("You" to 198, "Andrew" to 264, "Bob" to 142, "Maya" to 210, "Tyler" to 100)
    val maxD = 220f
    val maxM = 280f
    val balances = GroupBalanceSamples.debts.take(3)

    Column(Modifier.fillMaxSize().background(c.surface)) {
        ScTopBar("Overview", actions = { ScChip("Export", variant = ChipVariant.Ghost, leadingIcon = ScIcons.Download) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // header
            ScCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(groupEmoji, fontSize = 26.sp)
                            Text(groupName, color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        ScChip("May 21 – 27", variant = ChipVariant.Ghost)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatChip("Total spend", "\$914.00", Modifier.weight(1f))
                        StatChip("Expenses", "14", Modifier.weight(1f))
                        StatChip("Per person", "\$182.80", Modifier.weight(1f))
                    }
                }
            }
            // spend by day
            ScCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScSectionLabel("Spend by day")
                    Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        byDay.forEach { (d, v) ->
                            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                                Box(Modifier.width(22.dp).height(((v / maxD) * 96f).dp).clip(RoundedCornerShape(6.dp)).background(if (v >= maxD) c.blue else c.blueTint2))
                                Text(d, color = c.ink2, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
            // spend by member
            ScCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScSectionLabel("Spend by member")
                    byMember.forEach { (n, v) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(n, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
                            Box(Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(6.dp)).background(c.surface)) {
                                Box(Modifier.fillMaxWidth(v / maxM).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(c.blue))
                            }
                            Text("\$$v", color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                        }
                    }
                }
            }
            // resulting balances
            Column {
                ScSectionLabel("Resulting balances")
                ScCard {
                    balances.forEachIndexed { i, d ->
                        Box(if (i > 0) Modifier.topHairline(c.border) else Modifier) {
                            ScDebtRow(d.from, d.to, money(d.amount), owedToYou = d.owedToYou)
                        }
                    }
                }
            }
            ScButton("Export as image", onExport, variant = ButtonVariant.Secondary, leadingIcon = ScIcons.Image)
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    val c = ShareCostTheme.colors
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, color = c.ink2, fontSize = 12.sp)
        Text(value, color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily, modifier = Modifier.padding(top = 2.dp))
    }
}

@Preview
@Composable
private fun GroupOverviewPreview() {
    ShareCostTheme { GroupOverviewTab() }
}
