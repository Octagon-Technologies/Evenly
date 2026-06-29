package da.chelimo.sharecost.ui.screen.settle

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import da.chelimo.sharecost.domain.settlement.PaymentApp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScAmountInput
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.amountTextToSubunits
import da.chelimo.sharecost.ui.components.ScParticipantChip
import da.chelimo.sharecost.ui.components.ScSheetScaffold
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.screen.expense.format2dp
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

internal val DemoSettleHandles = listOf(
    PeerPaymentHandle(PaymentApp.VENMO, "Venmo", "@andrew-p"),
    PeerPaymentHandle(PaymentApp.CASH_APP, "Cash App", "\$andrewp"),
    PeerPaymentHandle(PaymentApp.ZELLE, "Zelle", "andrew@…"),
)

/**
 * 14 · Settle single expense (modal sheet). Wired by `SettleSingleRoute`: shows the current user's
 * remaining share of one expense, the payee's real payment handles, and records the payment via
 * `applySettlement`. [onOpenApp] fires the deep link; [onMarkPaid] records directly.
 */
@Composable
fun SettleSingleSheet(
    expenseTitle: String = "Dinner at La Negra",
    payee: String = "Andrew",
    shareAmountSubunits: Long = 2400,
    currency: String = "USD",
    handles: List<PeerPaymentHandle> = DemoSettleHandles,
    fxLine: String? = null,
    onDismiss: () -> Unit = {},
    onOpenApp: (amountSubunits: Long, PaymentApp, String) -> Unit = { _, _, _ -> },
    onConfirmPaid: (amountSubunits: Long, PaymentApp) -> Unit = { _, _ -> },
    onMarkPaid: (amountSubunits: Long) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var selected by remember(handles) { mutableStateOf(handles.firstOrNull()) }
    var awaitingConfirm by remember { mutableStateOf(false) }
    // Partial settle: the amount defaults to the full share but is editable (capped at the share — you
    // can't pay more than you owe on this expense). Buttons gate on a valid 1..share amount.
    var amountText by remember(shareAmountSubunits) { mutableStateOf(format2dp(shareAmountSubunits / 100.0)) }
    val enteredSubunits = amountTextToSubunits(amountText)
    val amountValid = enteredSubunits in 1..shareAmountSubunits
    Box(Modifier.fillMaxSize().background(c.surface)) {
        ScSheetScaffold(onDismiss, title = "Settle up") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Identity hero: who you're paying, then a single fused line — how much you owe, for
                // which expense — so the sheet doesn't repeat the expense name in both title and body.
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ScAvatar(payee, size = AvatarSize.Lg)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Pay $payee", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            buildAnnotatedString {
                                append("Your ")
                                withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) {
                                    append(moneySubunits(shareAmountSubunits, currency))
                                }
                                append(" share of $expenseTitle")
                            },
                            color = c.ink2,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                ScField("Amount") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ScAmountInput(
                            text = amountText,
                            onTextChange = { amountText = it },
                            currency = currency,
                            helper = if (enteredSubunits > shareAmountSubunits)
                                "Can't exceed your ${moneySubunits(shareAmountSubunits, currency)} share"
                            else "Paying back up to your ${moneySubunits(shareAmountSubunits, currency)} share",
                            helperColor = if (enteredSubunits > shareAmountSubunits) c.danger else c.ink2,
                        )
                        fxLine?.let { Text(it, color = c.ink2, fontSize = 12.sp) }
                    }
                }
                if (handles.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Pay with", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            handles.forEach { h ->
                                ScParticipantChip(h.label, selected = selected?.app == h.app, leading = { ScIcon(ScIcons.Wallet, size = 15.dp) }, onClick = { selected = h })
                            }
                        }
                        selected?.let { Text(it.handle, color = c.ink2, fontSize = 12.sp) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sel = selected
                    if (awaitingConfirm && sel != null) {
                        Text("We opened ${sel.label}. Did the payment go through?", color = c.ink2, fontSize = 13.sp)
                        ScButton("Yes — mark paid", { onConfirmPaid(enteredSubunits, sel.app) }, leadingIcon = ScIcons.Check, enabled = amountValid)
                        ScButton("Not yet", { awaitingConfirm = false }, variant = ButtonVariant.Tonal)
                    } else {
                        if (sel != null) {
                            ScButton("Open in ${sel.label}", { onOpenApp(enteredSubunits, sel.app, sel.handle); awaitingConfirm = true }, leadingIcon = ScIcons.Link, enabled = amountValid)
                        }
                        ScButton("Mark paid manually", { onMarkPaid(enteredSubunits) }, variant = ButtonVariant.Tonal, leadingIcon = ScIcons.Check, enabled = amountValid)
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun SettleSinglePreview() {
    ShareCostTheme { SettleSingleSheet() }
}
