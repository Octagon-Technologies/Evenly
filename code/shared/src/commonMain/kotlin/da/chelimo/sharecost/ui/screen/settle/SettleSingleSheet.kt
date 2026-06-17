package da.chelimo.sharecost.ui.screen.settle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import da.chelimo.sharecost.domain.settlement.PaymentApp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScParticipantChip
import da.chelimo.sharecost.ui.components.ScSheetScaffold
import da.chelimo.sharecost.ui.components.moneySubunits
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
    onOpenApp: (PaymentApp, String) -> Unit = { _, _ -> },
    onConfirmPaid: (PaymentApp) -> Unit = {},
    onMarkPaid: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var selected by remember(handles) { mutableStateOf(handles.firstOrNull()) }
    var awaitingConfirm by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(c.surface)) {
        ScSheetScaffold(onDismiss, title = "Settle '$expenseTitle'") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    ScAvatar(payee, size = AvatarSize.Lg)
                    Column {
                        Text("Pay $payee", color = c.ink, fontWeight = FontWeight.SemiBold)
                        Text("for your ${moneySubunits(shareAmountSubunits, currency)} share", color = c.ink2, fontSize = 12.sp)
                    }
                }
                ScField("Amount") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(12.dp)).background(c.page).border(1.dp, c.borderStrong, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ScChip(currency, variant = ChipVariant.Ghost, leadingIcon = ScIcons.Globe)
                            Text(moneySubunits(shareAmountSubunits, currency), color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily, modifier = Modifier.weight(1f))
                        }
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
                        ScButton("Yes — mark paid", { onConfirmPaid(sel.app) }, leadingIcon = ScIcons.Check)
                        ScButton("Not yet", { awaitingConfirm = false }, variant = ButtonVariant.Text)
                    } else {
                        if (sel != null) {
                            ScButton("Open in ${sel.label}", { onOpenApp(sel.app, sel.handle); awaitingConfirm = true }, leadingIcon = ScIcons.Link)
                        }
                        ScButton("Mark paid manually", onMarkPaid, variant = ButtonVariant.Text, leadingIcon = ScIcons.Check)
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
