package da.chelimo.sharecost.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScDebtRow
import da.chelimo.sharecost.ui.components.ScDivider
import da.chelimo.sharecost.ui.components.ScEmptyState
import da.chelimo.sharecost.ui.components.ScProgress
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

data class DebtUi(val from: String, val to: String, val amount: Double, val owedToYou: Boolean = false)
data class CategorySpendUi(val icon: ImageVector, val label: String, val amount: Double, val fraction: Float)

/** 9 · Group · Balances tab (design/src/screens-group.jsx). */
@Composable
fun GroupBalancesTab(
    empty: Boolean = false,
    debts: List<DebtUi> = GroupBalanceSamples.debts,
    spend: List<CategorySpendUi> = GroupBalanceSamples.spend,
    total: Double = 914.0,
    onSettle: (DebtUi) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    Column(Modifier.fillMaxSize().background(c.surface)) {
        ScTopBar(
            title = "Balances",
            actions = { ScChip("USD", variant = ChipVariant.Ghost, leadingIcon = ScIcons.Globe) },
        )
        if (empty) {
            ScEmptyState(
                icon = ScIcons.CheckCircle,
                title = "All settled in this currency. 🎉",
                text = "No one owes anyone right now. Nice work keeping the books clean.",
            )
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    ScSectionLabel("Who owes whom")
                    ScCard {
                        debts.forEachIndexed { i, d ->
                            Box(if (i > 0) Modifier.topHairline(c.border) else Modifier) {
                                ScDebtRow(d.from, d.to, money(d.amount), owedToYou = d.owedToYou, onClick = { onSettle(d) })
                            }
                        }
                    }
                    Text("Balances are shown as plain pairs, never netted or simplified.", color = c.ink2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                }
                Column {
                    ScSectionLabel("Spending by category")
                    ScCard(padded = true) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            spend.forEach { s ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ScIcon(s.icon, size = 17.dp, tint = c.ink2)
                                            Text(s.label, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                        Text(money(s.amount), color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                                    }
                                    ScProgress(s.fraction)
                                }
                            }
                            ScDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Total group spend", color = c.ink2, fontSize = 14.sp)
                                Text(money(total), color = c.ink, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal object GroupBalanceSamples {
    val debts = listOf(
        DebtUi("You", "Andrew", 42.0),
        DebtUi("Bob", "You", 14.5, owedToYou = true),
        DebtUi("You", "Maya", 18.0),
        DebtUi("Tyler", "Andrew", 26.75),
    )
    val spend = listOf(
        CategorySpendUi(ScIcons.Bed, "Lodging", 420.0, 0.46f),
        CategorySpendUi(ScIcons.Food, "Food & Drink", 268.0, 0.29f),
        CategorySpendUi(ScIcons.Ticket, "Activities", 142.0, 0.16f),
        CategorySpendUi(ScIcons.Car, "Transport", 84.0, 0.09f),
    )
}

@Preview
@Composable
private fun GroupBalancesPreview() {
    ShareCostTheme { GroupBalancesTab() }
}
