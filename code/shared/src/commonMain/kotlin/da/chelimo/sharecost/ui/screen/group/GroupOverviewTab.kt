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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.domain.expense.ExpenseCategory
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.DonutSlice
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScDebtRow
import da.chelimo.sharecost.ui.components.ScDonut
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScSegmented
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.currencySymbol
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.theme.ShareCostTheme
import da.chelimo.sharecost.ui.theme.categoryColor
import kotlin.math.roundToInt

/**
 * 11 · Trip overview (design/src/screens-group2.jsx) + spending tracker. A Personal/Group toggle
 * drives a donut + category breakdown and a history list of cards: Personal shows your own share of
 * each expense ("what you spent on the trip"); Group shows every expense at its full amount plus the
 * group-level day/member charts and resulting balances.
 */
@Composable
fun GroupOverviewTab(
    groupEmoji: String = "🏝️",
    groupName: String = "Tulum Trip",
    personalSpend: List<CategorySpendUi> = GroupOverviewSamples.personalSpend,
    groupSpend: List<CategorySpendUi> = GroupOverviewSamples.groupSpend,
    personalHistory: List<SpendItemUi> = GroupOverviewSamples.personalHistory,
    groupHistory: List<SpendItemUi> = GroupOverviewSamples.groupHistory,
    byDay: List<Pair<String, Long>> = listOf("Mon" to 4000L, "Tue" to 9600L, "Wed" to 13200L, "Thu" to 5800L, "Fri" to 21000L, "Sat" to 18000L, "Sun" to 6400L),
    byMember: List<Pair<String, Long>> = listOf("You" to 19800L, "Andrew" to 26400L, "Bob" to 14200L, "Maya" to 21000L, "Tyler" to 10000L),
    totalSubunits: Long = 91400L,
    expenseCount: Int = 14,
    perPersonSubunits: Long = 18280L,
    currencyCode: String = "USD",
    balances: List<Triple<String, String, Long>> = listOf(
        Triple("Bob", "You", 4200L),
        Triple("Maya", "Andrew", 9800L),
        Triple("Tyler", "You", 3100L),
    ),
    onBack: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var mode by remember { mutableStateOf("Personal") }
    val personal = mode == "Personal"
    val spend = if (personal) personalSpend else groupSpend
    val history = if (personal) personalHistory else groupHistory
    val maxD = (byDay.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val maxM = (byMember.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)

    Column(Modifier.fillMaxSize().background(c.surface)) {
        ScTopBar("Overview", navIcon = { ScIconButton(ScIcons.Back, onBack) }, actions = { ScChip("Export", variant = ChipVariant.Ghost, leadingIcon = ScIcons.Download) })
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
                        StatChip("Total spend", moneySubunits(totalSubunits, currencyCode), Modifier.weight(1f))
                        StatChip("Expenses", expenseCount.toString(), Modifier.weight(1f))
                        StatChip("Per person", moneySubunits(perPersonSubunits, currencyCode), Modifier.weight(1f))
                    }
                }
            }

            // Personal / Group toggle — drives the donut + breakdown + history below.
            ScSegmented(listOf("Personal", "Group"), selected = mode, onSelect = { mode = it })

            // spending donut + category breakdown
            ScCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ScSectionLabel(if (personal) "Your spending by category" else "Group spending by category")
                    if (spend.isEmpty()) {
                        Text(
                            if (personal) "Nothing attributed to you yet." else "No spending yet.",
                            color = c.ink2, fontSize = 13.sp,
                        )
                    } else {
                        val totalAmt = spend.sumOf { it.amount }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ScDonut(
                                slices = spend.map { DonutSlice(it.color, it.fraction) },
                                center = {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(if (personal) "You" else "Total", color = c.ink2, fontSize = 11.sp)
                                        Text(money(totalAmt, currencySymbol(currencyCode)), color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
                                    }
                                },
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                spend.forEach { s ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                                        Text(s.label, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Text("${(s.fraction * 100).roundToInt()}%", color = c.ink2, fontSize = 12.sp)
                                        Text(money(s.amount, currencySymbol(currencyCode)), color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // history list of cards (your share / full amount)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScSectionLabel(if (personal) "Your spending" else "All expenses")
                if (history.isEmpty()) {
                    Text(
                        if (personal) "You haven't spent anything on this trip yet." else "No expenses yet.",
                        color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                } else {
                    history.forEach { item ->
                        ScCard(padded = true) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                                    ScIcon(item.icon, size = 18.dp, tint = c.ink2)
                                }
                                Text(item.title, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(moneySubunits(item.amountSubunits, item.currency), color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
                            }
                        }
                    }
                }
            }

            // group-level analytics — only meaningful for the whole group
            if (!personal) {
                ScCard(padded = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ScSectionLabel("Spend by day")
                        Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            byDay.forEach { (d, v) ->
                                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                                    Box(Modifier.width(22.dp).height(((v.toFloat() / maxD) * 96f).dp).clip(RoundedCornerShape(6.dp)).background(if (v >= maxD) c.blue else c.blueTint2))
                                    Text(d, color = c.ink2, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
                ScCard(padded = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ScSectionLabel("Spend by member")
                        byMember.forEach { (n, v) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(n, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
                                Box(Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(6.dp)).background(c.surface)) {
                                    Box(Modifier.fillMaxWidth(v.toFloat() / maxM).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(c.blue))
                                }
                                Text(moneySubunits(v, currencyCode), color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                            }
                        }
                    }
                }
                Column {
                    ScSectionLabel("Resulting balances")
                    ScCard {
                        balances.forEachIndexed { i, (debtor, creditor, amountSubunits) ->
                            Box(if (i > 0) Modifier.topHairline(c.border) else Modifier) {
                                ScDebtRow(debtor, creditor, moneySubunits(amountSubunits, currencyCode))
                            }
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

internal object GroupOverviewSamples {
    val personalSpend = listOf(
        CategorySpendUi(categoryIcon(ExpenseCategory.LODGING), "Lodging", 84.0, 0.46f, categoryColor(ExpenseCategory.LODGING)),
        CategorySpendUi(categoryIcon(ExpenseCategory.FOOD), "Food", 53.6, 0.29f, categoryColor(ExpenseCategory.FOOD)),
        CategorySpendUi(categoryIcon(ExpenseCategory.ENTERTAINMENT), "Entertainment", 28.4, 0.16f, categoryColor(ExpenseCategory.ENTERTAINMENT)),
        CategorySpendUi(categoryIcon(ExpenseCategory.TRANSPORT), "Transport", 16.8, 0.09f, categoryColor(ExpenseCategory.TRANSPORT)),
    )
    val groupSpend = listOf(
        CategorySpendUi(categoryIcon(ExpenseCategory.LODGING), "Lodging", 420.0, 0.46f, categoryColor(ExpenseCategory.LODGING)),
        CategorySpendUi(categoryIcon(ExpenseCategory.FOOD), "Food", 268.0, 0.29f, categoryColor(ExpenseCategory.FOOD)),
        CategorySpendUi(categoryIcon(ExpenseCategory.ENTERTAINMENT), "Entertainment", 142.0, 0.16f, categoryColor(ExpenseCategory.ENTERTAINMENT)),
        CategorySpendUi(categoryIcon(ExpenseCategory.TRANSPORT), "Transport", 84.0, 0.09f, categoryColor(ExpenseCategory.TRANSPORT)),
    )
    val personalHistory = listOf(
        SpendItemUi("Beachfront villa", 8400L, "USD", categoryIcon(ExpenseCategory.LODGING)),
        SpendItemUi("Dinner at Hartwood", 3200L, "USD", categoryIcon(ExpenseCategory.FOOD)),
        SpendItemUi("Cenote tour", 2840L, "USD", categoryIcon(ExpenseCategory.ENTERTAINMENT)),
        SpendItemUi("Airport taxi", 1680L, "USD", categoryIcon(ExpenseCategory.TRANSPORT)),
    )
    val groupHistory = listOf(
        SpendItemUi("Beachfront villa", 42000L, "USD", categoryIcon(ExpenseCategory.LODGING)),
        SpendItemUi("Dinner at Hartwood", 16000L, "USD", categoryIcon(ExpenseCategory.FOOD)),
        SpendItemUi("Cenote tour", 14200L, "USD", categoryIcon(ExpenseCategory.ENTERTAINMENT)),
        SpendItemUi("Airport taxi", 8400L, "USD", categoryIcon(ExpenseCategory.TRANSPORT)),
    )
}

@Preview
@Composable
private fun GroupOverviewPreview() {
    ShareCostTheme { GroupOverviewTab() }
}
