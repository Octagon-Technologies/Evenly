package app.splitevenly.ui.screen.settle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.settlement.PaymentApp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAmountInput
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvParticipantChip
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.amountTextToSubunits
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.screen.expense.format2dp
import app.splitevenly.ui.theme.EvenlyTheme

internal val DemoSettleHandles =
    listOf(
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
    // Set once the payment is recorded, so the sheet slides out instead of vanishing mid-frame.
    dismissRequested: Boolean = false,
    onDismiss: () -> Unit = {},
    onOpenApp: (amountSubunits: Long, PaymentApp, String) -> Unit = { _, _, _ -> },
    onConfirmPaid: (amountSubunits: Long, PaymentApp) -> Unit = { _, _ -> },
    onMarkPaid: (amountSubunits: Long) -> Unit = {},
) {
    val c = EvenlyTheme.colors
    var selected by remember(handles) { mutableStateOf(handles.firstOrNull()) }
    var awaitingConfirm by remember { mutableStateOf(false) }
    // Partial settle: the amount defaults to the full share but is editable (capped at the share — you
    // can't pay more than you owe on this expense). Buttons gate on a valid 1..share amount.
    var amountText by remember(shareAmountSubunits) { mutableStateOf(format2dp(shareAmountSubunits / 100.0)) }
    val enteredSubunits = amountTextToSubunits(amountText)
    val amountValid = enteredSubunits in 1..shareAmountSubunits
    EvSheetScaffold(onDismiss, title = "Settle up", dismissRequested = dismissRequested) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Identity hero: who you're paying, then a single fused line — how much you owe, for
            // which expense — so the sheet doesn't repeat the expense name in both title and body.
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EvAvatar(payee, size = AvatarSize.Lg)
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
            EvField("Amount") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EvAmountInput(
                        text = amountText,
                        onTextChange = { amountText = it },
                        currency = currency,
                        helper =
                            if (enteredSubunits > shareAmountSubunits) {
                                "Can't exceed your ${moneySubunits(shareAmountSubunits, currency)} share"
                            } else {
                                "Paying back up to your ${moneySubunits(shareAmountSubunits, currency)} share"
                            },
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
                            EvParticipantChip(h.label, selected = selected?.app == h.app, leading = {
                                EvIcon(
                                    EvIcons.Wallet,
                                    size = 15.dp,
                                )
                            }, onClick = {
                                selected =
                                    h
                            })
                        }
                    }
                    selected?.let { Text(it.handle, color = c.ink2, fontSize = 12.sp) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val sel = selected
                if (awaitingConfirm && sel != null) {
                    Text("We opened ${sel.label}. Did the payment go through?", color = c.ink2, fontSize = 13.sp)
                    EvButton(
                        "Yes, mark paid",
                        { onConfirmPaid(enteredSubunits, sel.app) },
                        leadingIcon = EvIcons.Check,
                        enabled = amountValid,
                    )
                    EvButton("Not yet", { awaitingConfirm = false }, variant = ButtonVariant.Tonal)
                } else {
                    if (sel != null) {
                        EvButton("Open in ${sel.label}", {
                            onOpenApp(enteredSubunits, sel.app, sel.handle)
                            awaitingConfirm = true
                        }, leadingIcon = EvIcons.Link, enabled = amountValid)
                    }
                    EvButton("Mark paid manually", {
                        onMarkPaid(enteredSubunits)
                    }, variant = ButtonVariant.Tonal, leadingIcon = EvIcons.Check, enabled = amountValid)
                }
            }
        }
    }
}

@Preview
@Composable
private fun SettleSinglePreview() {
    EvenlyTheme { SettleSingleSheet() }
}
