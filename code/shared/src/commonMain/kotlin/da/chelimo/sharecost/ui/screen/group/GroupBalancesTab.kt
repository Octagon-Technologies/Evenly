package da.chelimo.sharecost.ui.screen.group

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScEmptyState
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScModalScaffold
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScTopBar
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
 * One payment inside the overpayment review (P1 #9), already formatted. [settlementId] drives the void.
 * [sub] is "who added it · when · app"; [covers] names the expenses it paid toward (blank if none).
 */
data class PaymentReviewUi(
    val settlementId: String,
    val label: String,
    val sub: String,
    val amountText: String,
    val covers: String = "",
)

/**
 * A "possible double payment" (P1 #9): [peerName]'s balance has gone [overpaidText] past settled with
 * you — the tell-tale of one payment recorded twice. [payments] are the payments between you and them,
 * shown on expand so you can remove the duplicate. [youOverpaid] flips the wording (you paid them over,
 * vs. they paid you over).
 */
data class OverpaymentUi(
    val peerUserId: String,
    val peerName: String,
    val overpaidText: String,
    val youOverpaid: Boolean,
    val payments: List<PaymentReviewUi>,
)

/**
 * 9 · Group · Balances tab. Split into two sections — **You owe** and **Owes you** — and every row
 * expands to reveal the individual expenses behind the number. A you-owe row's expansion also carries
 * the "Settle up" action, so you go from "what do I owe" straight to paying it off.
 */
@Composable
fun GroupBalancesTab(
    debts: List<DebtUi> = GroupBalanceSamples.debts,
    overpayments: List<OverpaymentUi> = emptyList(),
    onBack: () -> Unit = {},
    onSettle: (DebtUi) -> Unit = {},
    onVoidPayment: (settlementId: String) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    val youOwe = debts.filter { !it.owedToYou }
    val owesYou = debts.filter { it.owedToYou }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Session-local: "Both are correct — dismiss" hides a banner until you leave and come back. There's no
    // stored dismissal (the overpayment is derived), and it re-derives away the instant a payment is voided.
    var dismissed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val alerts = overpayments.filter { it.peerUserId !in dismissed }

    Column(Modifier.fillMaxSize().background(c.page)) {
        ScTopBar(title = "Balances", navIcon = { ScIconButton(ScIcons.Back, onBack) })
        if (debts.isEmpty() && alerts.isEmpty()) {
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
                // Double-payment alerts sit at the very top — the money that would otherwise silently vanish.
                if (alerts.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        alerts.forEach { o ->
                            OverpaymentBanner(o, onVoidPayment) { dismissed = dismissed + o.peerUserId }
                        }
                    }
                }
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
            }
        }
    }
}

/**
 * P1 #9 · the "possible double payment" alert. Warm `credit` amber (money paid extra, not an error).
 * Collapsed: what's over-paid + a Review action. Expanded: the payments between the two of you, each with
 * a Remove (→ [onVoid], a `voidSettlement`) so the duplicate can be undone; balance re-derives to correct.
 */
@Composable
private fun OverpaymentBanner(o: OverpaymentUi, onVoid: (String) -> Unit, onDismiss: () -> Unit) {
    val c = ShareCostTheme.colors
    var open by remember(o.peerUserId) { mutableStateOf(false) }
    // Removing a payment is a money action → confirm first (heuristic 6). Holds the payment pending confirm.
    var confirmRemove by remember(o.peerUserId) { mutableStateOf<PaymentReviewUi?>(null) }
    val body = if (o.youOverpaid) {
        "You’ve paid ${o.peerName} ${o.overpaidText} more than you owed them. That usually means the same payment was recorded twice."
    } else {
        "${o.peerName} has paid you ${o.overpaidText} more than they owed. That usually means the same payment was recorded twice."
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.page)
            .border(1.dp, c.credit.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.credit.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { ScIcon(ScIcons.Alert, size = 19.dp, tint = c.credit) }
            Column(Modifier.weight(1f)) {
                Text("Possible double payment", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(body, color = c.ink2, fontSize = 13.sp)
            }
        }
        ScButton(
            if (open) "Hide payments" else "Review payments",
            onClick = { open = !open },
            variant = ButtonVariant.Tonal,
            leadingIcon = if (open) ScIcons.ChevU else ScIcons.ChevD,
            small = true,
            fillMaxWidth = false,
        )
        AnimatedVisibility(visible = open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                o.payments.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.surface)
                            .border(1.dp, c.border, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.label, color = c.ink, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            if (p.covers.isNotBlank()) Text(p.covers, color = c.ink2, fontSize = 11.5.sp)
                            if (p.sub.isNotBlank()) Text(p.sub, color = c.ink3, fontSize = 11.5.sp)
                        }
                        Text(p.amountText, color = c.ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                        ScButton("Remove", onClick = { confirmRemove = p }, variant = ButtonVariant.Danger, small = true, fillMaxWidth = false)
                    }
                }
                ScButton("Both are correct, dismiss", onClick = onDismiss, variant = ButtonVariant.Text, small = true, fillMaxWidth = false)
            }
        }
    }

    confirmRemove?.let { p ->
        ScModalScaffold(onDismiss = { confirmRemove = null }) {
            Text("Remove this payment?", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "${o.peerName}’s balance goes back up by ${p.amountText}. You can always record the payment again if you remove it by mistake.",
                color = c.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScButton("Cancel", { confirmRemove = null }, variant = ButtonVariant.Secondary, modifier = Modifier.weight(1f))
                ScButton("Remove", { confirmRemove = null; onVoid(p.settlementId) }, variant = ButtonVariant.Danger, modifier = Modifier.weight(1f))
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
    Column {
        ScSectionLabel(label)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { d ->
                ScCard {
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
    val overpayments = listOf(
        OverpaymentUi(
            "u5", "Bob", "$12.00", youOverpaid = false,
            payments = listOf(
                PaymentReviewUi("s1", "Bob paid you", "Added by Bob · Jul 7 · Venmo", "$12.00", covers = "For Groceries"),
                PaymentReviewUi("s2", "Bob paid you", "Added by you · Jul 8 · Venmo", "$12.00", covers = "For Groceries"),
            ),
        ),
    )
}

@Preview
@Composable
private fun GroupBalancesPreview() {
    ShareCostTheme { GroupBalancesTab() }
}

@Preview
@Composable
private fun GroupBalancesOverpaidPreview() {
    ShareCostTheme { GroupBalancesTab(overpayments = GroupBalanceSamples.overpayments) }
}
