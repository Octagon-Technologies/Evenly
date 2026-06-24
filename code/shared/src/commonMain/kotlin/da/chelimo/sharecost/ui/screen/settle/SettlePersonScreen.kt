package da.chelimo.sharecost.ui.screen.settle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.domain.settlement.PaymentApp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScAmountInput
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScCheck
import da.chelimo.sharecost.ui.components.ScDivider
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScParticipantChip
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.amountTextToSubunits
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.screen.expense.format2dp
import da.chelimo.sharecost.ui.theme.ShareCostTheme

data class SettleShareUi(val title: String, val date: String, val amountSubunits: Long)

/** One of the payee's payment handles, shown as a "Pay with" choice in step 2. */
data class PeerPaymentHandle(val app: PaymentApp, val label: String, val handle: String)

/** Human label for a payment app (the "Pay with" chip caption). */
val PaymentApp.appLabel: String
    get() = when (this) {
        PaymentApp.VENMO -> "Venmo"
        PaymentApp.CASH_APP -> "Cash App"
        PaymentApp.PAYPAL -> "PayPal"
        PaymentApp.ZELLE -> "Zelle"
    }

/**
 * 15 · Settle a person — 2 steps (design/src/screens-settle.jsx). Wired via SettlePersonRoute.
 *
 * Step 2 lists the *payee's* real handles ([handles]); picking one and tapping "Open in <app>" fires
 * the deep link ([onOpenApp]) and raises the confirm sheet. Only "Yes — mark paid" records the payment
 * ([onConfirmPaid]); "Not yet" backs out without recording. "Mark paid manually" records directly.
 */
@Composable
fun SettlePersonScreen(
    peerName: String = "Andrew",
    currencyCode: String = "USD",
    shares: List<SettleShareUi> = DemoSettleShares,
    handles: List<PeerPaymentHandle> = DemoHandles,
    onBack: () -> Unit = {},
    onOpenApp: (amountSubunits: Long, app: PaymentApp, handle: String) -> Unit = { _, _, _ -> },
    onConfirmPaid: (amountSubunits: Long, app: PaymentApp) -> Unit = { _, _ -> },
    onMarkPaid: (Long) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    val clipboard = LocalClipboardManager.current
    var step by remember { mutableStateOf(1) }
    var checked by remember { mutableStateOf(shares.indices.toSet()) }
    var selectedApp by remember(handles) { mutableStateOf(handles.firstOrNull()?.app) }
    var confirmFor by remember { mutableStateOf<PeerPaymentHandle?>(null) }
    val total = shares.filterIndexed { i, _ -> i in checked }.sumOf { it.amountSubunits }
    val chosen = handles.firstOrNull { it.app == selectedApp } ?: handles.firstOrNull()
    // Partial settle: step 2 shows the balance as an *editable* hero amount, defaulting to the selected
    // total and capped at it (you can't overpay a balance). Buttons gate on a valid 1..total amount.
    var amountText by remember { mutableStateOf("") }
    LaunchedEffect(step, total) { if (step == 2) amountText = format2dp(total / 100.0) }
    val payAmount = amountTextToSubunits(amountText)
    val payValid = payAmount in 1..total

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
            if (step == 1) {
                ScTopBar("Settle with $peerName", subtitle = "in $currencyCode", navIcon = { ScIconButton(ScIcons.Back, onBack) })
                Column(Modifier.weight(1f).background(c.surface).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Pick which of your shares to pay back. You can settle one, some, or all.", color = c.ink2, fontSize = 12.sp)
                    if (shares.isEmpty()) {
                        Text("Nothing to settle with $peerName.", color = c.ink2, fontSize = 14.sp)
                    } else {
                        ScCard {
                            shares.forEachIndexed { i, s ->
                                Row(
                                    Modifier.fillMaxWidth().then(if (i > 0) Modifier.topHairline(c.border) else Modifier).clickable { checked = if (i in checked) checked - i else checked + i }.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ScCheck(i in checked)
                                    Column(Modifier.weight(1f)) {
                                        Text(s.title, color = c.ink, style = MaterialTheme.typography.titleSmall)
                                        if (s.date.isNotBlank()) Text(s.date, color = c.ink2, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Text(moneySubunits(s.amountSubunits, currencyCode), color = if (i in checked) c.ink else c.ink3, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                                }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().background(c.page)) {
                    ScDivider()
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Total to settle", color = c.ink2, fontWeight = FontWeight.SemiBold)
                            Text(moneySubunits(total, currencyCode), color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
                        }
                        ScButton("Continue", { step = 2 }, leadingIcon = ScIcons.ChevR, enabled = total > 0)
                    }
                }
            } else {
                ScTopBar("Pay $peerName", subtitle = "Step 2 of 2", navIcon = { ScIconButton(ScIcons.Back, { step = 1 }) })
                Column(Modifier.weight(1f).background(c.surface).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ScCard(padded = true) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ScAvatar(peerName, size = AvatarSize.Lg)
                            ScAmountInput(
                                text = amountText,
                                onTextChange = { amountText = it },
                                currency = currencyCode,
                                amountFontSize = 30.sp,
                                helper = if (payAmount > total)
                                    "Can't exceed the ${moneySubunits(total, currencyCode)} you owe"
                                else "Editable — paying $peerName, up to ${moneySubunits(total, currencyCode)}",
                                helperColor = if (payAmount > total) c.danger else c.ink2,
                            )
                        }
                    }
                    if (handles.isEmpty()) {
                        Text(
                            "$peerName hasn't added a payment handle yet. Pay them however you like, then mark it paid here.",
                            color = c.ink2, fontSize = 13.sp,
                        )
                        ScButton("Mark paid", { onMarkPaid(payAmount) }, leadingIcon = ScIcons.Check, enabled = payValid)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Pay with", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                handles.forEach { h ->
                                    ScParticipantChip(
                                        h.label, selected = h.app == selectedApp,
                                        leading = { ScIcon(ScIcons.Wallet, size = 15.dp) },
                                        onClick = { selectedApp = h.app },
                                    )
                                }
                            }
                            chosen?.let { Text(it.handle, color = c.ink3, fontSize = 12.sp, fontFamily = ShareCostTheme.monoFamily) }
                        }
                        chosen?.let { h ->
                            ScButton("Open in ${h.label}", { onOpenApp(payAmount, h.app, h.handle); confirmFor = h }, leadingIcon = ScIcons.Link, enabled = payValid)
                        }
                        ScButton("Mark paid manually", { onMarkPaid(payAmount) }, variant = ButtonVariant.Text, leadingIcon = ScIcons.Check, enabled = payValid)
                    }
                }
            }
        }

        confirmFor?.let { h ->
            DeepLinkConfirmSheet(
                amount = payAmount / 100.0,
                handle = h.handle,
                app = h.label,
                onDismiss = { confirmFor = null },
                onYes = { confirmFor = null; onConfirmPaid(payAmount, h.app) },
                onCopy = { clipboard.setText(AnnotatedString(h.handle)) },
            )
        }
    }
}

internal val DemoSettleShares = listOf(
    SettleShareUi("Dinner at La Negra", "May 23", 2400),
    SettleShareUi("Cenote day trip", "May 22", 1800),
    SettleShareUi("Airport taxi", "May 21", 1450),
)

internal val DemoHandles = listOf(
    PeerPaymentHandle(PaymentApp.VENMO, "Venmo", "@andrew-p"),
    PeerPaymentHandle(PaymentApp.CASH_APP, "Cash App", "\$andrewp"),
)

@Preview
@Composable
private fun SettlePersonPreview() {
    ShareCostTheme { SettlePersonScreen() }
}
