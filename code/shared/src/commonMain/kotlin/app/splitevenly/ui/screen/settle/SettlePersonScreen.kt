package app.splitevenly.ui.screen.settle

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.settlement.PaymentApp
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAmountInput
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvCheck
import app.splitevenly.ui.components.EvDivider
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvParticipantChip
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.amountTextToSubunits
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.screen.expense.format2dp
import app.splitevenly.ui.theme.EvenlyTheme

/** One expense the current user owes the payee, checkable on the settle screen. */
data class SettleShareUi(
    val expenseId: String,
    val title: String,
    val date: String,
    val amountSubunits: Long,
)

/** One of the payee's payment handles, shown as a "Pay with" choice. */
data class PeerPaymentHandle(
    val app: PaymentApp,
    val label: String,
    val handle: String,
)

/** Human label for a payment app (the "Pay with" chip caption). */
val PaymentApp.appLabel: String
    get() =
        when (this) {
            PaymentApp.VENMO -> "Venmo"
            PaymentApp.CASH_APP -> "Cash App"
            PaymentApp.PAYPAL -> "PayPal"
            PaymentApp.ZELLE -> "Zelle"
        }

/**
 * 15 · Settle a person — **one page** (design/src/screens-settle.jsx). Wired via SettlePersonRoute.
 *
 * You tick the specific expenses you're paying off (default: all), the total sums live and stays
 * editable in place, and the payee's *preferred* handle is highlighted as the default. Two actions
 * resolve the checked expenses: "Pay with <app>" fires the deep link ([onOpenApp]) then the confirm
 * sheet records only on "Yes" ([onConfirmPaid]); "Mark as paid" records directly ([onMarkPaid]). Each
 * callback carries the checked expense ids so the payment is confined to exactly what you selected.
 */
@Composable
fun SettlePersonScreen(
    peerName: String = "Andrew",
    currencyCode: String = "USD",
    shares: List<SettleShareUi> = DemoSettleShares,
    handles: List<PeerPaymentHandle> = DemoHandles,
    preferredApp: PaymentApp? = PaymentApp.VENMO,
    onBack: () -> Unit = {},
    onOpenApp: (amountSubunits: Long, app: PaymentApp, handle: String, expenseIds: List<String>) -> Unit = { _, _, _, _ -> },
    onConfirmPaid: (amountSubunits: Long, app: PaymentApp, expenseIds: List<String>) -> Unit = { _, _, _ -> },
    onMarkPaid: (amountSubunits: Long, expenseIds: List<String>) -> Unit = { _, _ -> },
) {
    val c = EvenlyTheme.colors
    val clipboard = LocalClipboardManager.current
    var checked by remember(shares) { mutableStateOf(shares.map { it.expenseId }.toSet()) }
    // Preferred handle first; that's the default selection and the highlighted way to pay.
    val ordered =
        remember(handles, preferredApp) {
            handles.sortedByDescending { it.app == preferredApp }
        }
    var selectedApp by remember(ordered) { mutableStateOf(ordered.firstOrNull()?.app) }
    var showAllMethods by remember { mutableStateOf(false) }
    var confirmFor by remember { mutableStateOf<PeerPaymentHandle?>(null) }
    // "Yes, mark paid" records first and then asks the sheet to slide out; the sheet is only dropped
    // once that animation ends, so confirming doesn't read as a hard cut.
    var confirmClosing by remember { mutableStateOf(false) }

    val checkedSum = shares.filter { it.expenseId in checked }.sumOf { it.amountSubunits }
    val selectedIds = shares.filter { it.expenseId in checked }.map { it.expenseId }
    // The editable total defaults to (and re-tracks) the checked sum; a manual edit sticks until the
    // next checkbox change. Capped at the checked sum — you can't pay more than you're settling.
    var amountText by remember { mutableStateOf(format2dp(checkedSum / 100.0)) }
    LaunchedEffect(checkedSum) { amountText = format2dp(checkedSum / 100.0) }
    val payAmount = amountTextToSubunits(amountText)
    val payValid = checked.isNotEmpty() && payAmount in 1..checkedSum
    val chosen = ordered.firstOrNull { it.app == selectedApp } ?: ordered.firstOrNull()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
            EvTopBar("Settle with $peerName", subtitle = "in $currencyCode", navIcon = { EvIconButton(EvIcons.Back, onBack) })

            if (shares.isEmpty()) {
                Column(Modifier.weight(1f).background(c.surface).padding(16.dp)) {
                    Text("Nothing to settle with $peerName.", color = c.ink2, fontSize = 14.sp)
                }
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .background(c.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Tick what you're paying off.", color = c.ink2, fontSize = 12.sp)

                    // 1 · Checkable expenses
                    EvCard {
                        shares.forEachIndexed { i, s ->
                            val on = s.expenseId in checked
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (i > 0) Modifier.topHairline(c.border) else Modifier)
                                    .clickable { checked = if (on) checked - s.expenseId else checked + s.expenseId }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                EvCheck(on)
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, color = if (on) c.ink else c.ink3, style = MaterialTheme.typography.titleSmall)
                                    if (s.date.isNotBlank()) Text(s.date, color = c.ink3, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(
                                    moneySubunits(s.amountSubunits, currencyCode),
                                    color = if (on) c.ink else c.ink3,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = EvenlyTheme.monoFamily,
                                )
                            }
                        }
                    }

                    // 2 · Editable total
                    EvCard(padded = true) {
                        Text(
                            "You're paying",
                            color = c.ink2,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        EvAmountInput(
                            text = amountText,
                            onTextChange = { amountText = it },
                            currency = currencyCode,
                            amountFontSize = 28.sp,
                            helper =
                                if (payAmount > checkedSum) {
                                    "Can't exceed the ${moneySubunits(checkedSum, currencyCode)} you're settling"
                                } else {
                                    "Applied to the ticked expenses, oldest first"
                                },
                            helperColor = if (payAmount > checkedSum) c.danger else c.ink2,
                        )
                    }

                    // 3 · How to pay
                    if (handles.isEmpty()) {
                        Text(
                            "$peerName hasn't added a payment handle yet. Pay them however you like, then mark it paid here.",
                            color = c.ink2,
                            fontSize = 13.sp,
                        )
                    } else {
                        MethodPicker(
                            ordered = ordered,
                            selectedApp = selectedApp,
                            preferredApp = preferredApp,
                            peerName = peerName,
                            showAll = showAllMethods,
                            onChangeClick = { showAllMethods = true },
                            onPick = {
                                selectedApp = it
                                showAllMethods = false
                            },
                        )
                    }
                }
            }

            // Actions
            if (shares.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(c.page)) {
                    EvDivider()
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        chosen?.let { h ->
                            EvButton(
                                "Pay ${moneySubunits(payAmount.coerceAtLeast(0), currencyCode)} with ${h.label}",
                                {
                                    onOpenApp(payAmount, h.app, h.handle, selectedIds)
                                    confirmFor = h
                                },
                                leadingIcon = EvIcons.Wallet,
                                enabled = payValid,
                            )
                        }
                        EvButton(
                            "Mark as paid",
                            { onMarkPaid(payAmount, selectedIds) },
                            variant = if (chosen == null) ButtonVariant.Primary else ButtonVariant.Tonal,
                            enabled = payValid,
                        )
                        Text(
                            "${if (chosen != null) "Either one clears" else "This clears"} the ticked ${if (selectedIds.size == 1) "expense" else "expenses"} with $peerName.",
                            color = c.ink3,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        confirmFor?.let { h ->
            DeepLinkConfirmSheet(
                amount = payAmount / 100.0,
                handle = h.handle,
                app = h.label,
                dismissRequested = confirmClosing,
                onDismiss = {
                    confirmFor = null
                    confirmClosing = false
                },
                onYes = {
                    onConfirmPaid(payAmount, h.app, selectedIds)
                    confirmClosing = true
                },
                onCopy = { clipboard.setText(AnnotatedString(h.handle)) },
            )
        }
    }
}

/**
 * The "how to pay" block. Collapsed, it shows only the highlighted default (the payee's preferred
 * handle) with a "Change" affordance; expanded, it shows every handle as a chip with the preferred one
 * starred. Keeps the common path to a single glance while the choice is one tap away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MethodPicker(
    ordered: List<PeerPaymentHandle>,
    selectedApp: PaymentApp?,
    preferredApp: PaymentApp?,
    peerName: String,
    showAll: Boolean,
    onChangeClick: () -> Unit,
    onPick: (PaymentApp) -> Unit,
) {
    val c = EvenlyTheme.colors
    val chosen = ordered.firstOrNull { it.app == selectedApp } ?: ordered.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("How to pay", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (!showAll && chosen != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.blueTint)
                    .clickable(enabled = ordered.size > 1, onClick = onChangeClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (chosen.app == preferredApp) {
                    EvIcon(EvIcons.Star, size = 16.dp, tint = c.credit)
                } else {
                    EvIcon(EvIcons.Wallet, size = 16.dp, tint = c.blueText)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (chosen.app == preferredApp) "$peerName's preferred · ${chosen.label}" else "Pay with ${chosen.label}",
                        color = c.blueText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(chosen.handle, color = c.ink2, fontSize = 12.sp, fontFamily = EvenlyTheme.monoFamily)
                }
                if (ordered.size > 1) Text("Change", color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ordered.forEach { h ->
                    EvParticipantChip(
                        h.label,
                        selected = h.app == selectedApp,
                        leading = { EvIcon(if (h.app == preferredApp) EvIcons.Star else EvIcons.Wallet, size = 15.dp) },
                        onClick = { onPick(h.app) },
                    )
                }
            }
            chosen?.let { Text(it.handle, color = c.ink3, fontSize = 12.sp, fontFamily = EvenlyTheme.monoFamily) }
        }
    }
}

internal val DemoSettleShares =
    listOf(
        SettleShareUi("e1", "Dinner at La Negra", "Wed, May 23", 2400),
        SettleShareUi("e2", "Cenote day trip", "Tue, May 22", 1800),
        SettleShareUi("e3", "Airport taxi", "Mon, May 21", 1450),
    )

internal val DemoHandles =
    listOf(
        PeerPaymentHandle(PaymentApp.VENMO, "Venmo", "@andrew-p"),
        PeerPaymentHandle(PaymentApp.CASH_APP, "Cash App", "\$andrewp"),
    )

@Preview
@Composable
private fun SettlePersonPreview() {
    EvenlyTheme { SettlePersonScreen() }
}
