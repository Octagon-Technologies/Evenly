package da.chelimo.sharecost.ui.screen.group

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScEmptyState
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** One line behind a balance — a single expense the debt is made of (already formatted). */
data class BalanceLineUi(val expenseId: String, val title: String, val date: String, val amount: String)

/**
 * A person you owe / who owes you. [amountText] is the (formatted) net headline; [lines] is the
 * per-expense breakdown revealed on expand. [peerUserId] drives the settle navigation.
 */
data class DebtUi(
    val peerUserId: String,
    val peerName: String,
    val amountText: String,
    val owedToYou: Boolean,
    val lines: List<BalanceLineUi> = emptyList(),
)

data class CategorySpendUi(val icon: ImageVector, val label: String, val amount: Double, val fraction: Float, val color: Color)

/**
 * 9 · Group · Balances tab. Split into two sections — **You owe** and **Owes you** — and every row
 * expands to reveal the individual expenses behind the number. A you-owe row's expansion also carries
 * the "Settle up" action, so you go from "what do I owe" straight to paying it off.
 */
@Composable
fun GroupBalancesTab(
    debts: List<DebtUi> = GroupBalanceSamples.debts,
    onBack: () -> Unit = {},
    onSettle: (DebtUi) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    val youOwe = debts.filter { !it.owedToYou }
    val owesYou = debts.filter { it.owedToYou }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        ScTopBar(title = "Balances", navIcon = { ScIconButton(ScIcons.Back, onBack) })
        if (debts.isEmpty()) {
            ScEmptyState(
                icon = ScIcons.CheckCircle,
                title = "All settled here. 🎉",
                text = "No one owes anyone right now. Nice work keeping the books clean.",
            )
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (youOwe.isNotEmpty()) {
                    BalanceSection("You owe", youOwe, expanded, onSettle) { id ->
                        expanded = if (id in expanded) expanded - id else expanded + id
                    }
                }
                if (owesYou.isNotEmpty()) {
                    BalanceSection("Owes you", owesYou, expanded, onSettle) { id ->
                        expanded = if (id in expanded) expanded - id else expanded + id
                    }
                }
                Text(
                    "Balances are shown per person and never simplified across the group.",
                    color = c.ink2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun BalanceSection(
    label: String,
    rows: List<DebtUi>,
    expanded: Set<String>,
    onSettle: (DebtUi) -> Unit,
    onToggle: (String) -> Unit,
) {
    val c = ShareCostTheme.colors
    Column {
        ScSectionLabel(label)
        ScCard {
            rows.forEachIndexed { i, d ->
                Column(if (i > 0) Modifier.topHairline(c.border) else Modifier) {
                    BalanceRow(d, expanded = d.peerUserId in expanded, onToggle = { onToggle(d.peerUserId) })
                    AnimatedVisibility(visible = d.peerUserId in expanded) {
                        BalanceBreakdown(d, onSettle = { onSettle(d) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceRow(d: DebtUi, expanded: Boolean, onToggle: () -> Unit) {
    val c = ShareCostTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScAvatar(d.peerName, size = AvatarSize.Sm)
        Column(Modifier.weight(1f)) {
            Text(d.peerName, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val n = d.lines.size
            Text(
                if (n == 0) "Tap to see details" else "$n ${if (n == 1) "expense" else "expenses"} · tap to ${if (expanded) "hide" else "see"}",
                color = c.ink2, style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Owed-to-you → amber (in your favour); you-owe → blue (the action). Mirrors the home card.
        ScChip(d.amountText, variant = if (d.owedToYou) ChipVariant.Owed else ChipVariant.Owe, mono = true)
        ScIcon(if (expanded) ScIcons.ChevU else ScIcons.ChevD, size = 18.dp, tint = c.ink3)
    }
}

@Composable
private fun BalanceBreakdown(d: DebtUi, onSettle: () -> Unit) {
    val c = ShareCostTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        d.lines.forEach { line ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(line.title, color = c.ink, fontSize = 14.sp)
                    if (line.date.isNotBlank()) Text(line.date, color = c.ink3, fontSize = 12.sp)
                }
                Text(line.amount, color = c.ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = ShareCostTheme.monoFamily)
            }
        }
        if (!d.owedToYou) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                ScButton("Settle up with ${d.peerName}", onSettle, leadingIcon = ScIcons.ChevR)
            }
        }
    }
}

internal object GroupBalanceSamples {
    val debts = listOf(
        DebtUi(
            "u1", "Dave", "$138.00", owedToYou = false,
            lines = listOf(
                BalanceLineUi("e1", "Uber to airport", "Jul 1", "$18.00"),
                BalanceLineUi("e2", "Airbnb (2 nights)", "Jun 29", "$120.00"),
            ),
        ),
        DebtUi("u2", "Maya", "$18.00", owedToYou = false, lines = listOf(BalanceLineUi("e3", "Drinks Fri night", "Jun 28", "$18.00"))),
        DebtUi("u3", "Bob", "$14.50", owedToYou = true, lines = listOf(BalanceLineUi("e4", "Groceries", "Jun 27", "$14.50"))),
    )
}

@Preview
@Composable
private fun GroupBalancesPreview() {
    ShareCostTheme { GroupBalancesTab() }
}
