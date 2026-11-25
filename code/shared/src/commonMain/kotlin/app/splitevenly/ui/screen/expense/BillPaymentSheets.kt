package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.expense.expenseDateChipLabel
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAmountInput
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.amountTextToSubunits
import app.splitevenly.ui.components.clickableClearingFocus
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.screen.settle.PaymentPreviewUi
import app.splitevenly.ui.screen.settle.PaymentUi
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.coroutines.launch

/** How a payment was made, as stored (`settlements.payment_app`) and as shown. Order is the picker's order. */
val PAYMENT_METHODS: List<Pair<String, String>> =
    listOf(
        "MPESA" to "M-Pesa",
        "CASH" to "Cash",
        "BANK" to "Bank transfer",
        "VENMO" to "Venmo",
        "CASH_APP" to "Cash App",
        "PAYPAL" to "PayPal",
        "ZELLE" to "Zelle",
        "OTHER" to "Other",
    )

/** A stored method to its label; an unknown value (an older or newer app's) shows as written. */
fun paymentMethodLabel(stored: String?): String? = stored?.let { s -> PAYMENT_METHODS.firstOrNull { it.first == s }?.second ?: s }

/** One person's line on a bill as the payment sheet needs it. [remainingSubunits] can be negative (overpaid). */
data class BillPaymentPersonUi(
    val userId: String,
    val name: String,
    val isMe: Boolean,
    val owedSubunits: Long,
    val remainingSubunits: Long,
)

/** What the record sheet hands back. [date] is ISO-8601; the route turns it into a timestamp. */
data class PaymentEntry(
    val amountSubunits: Long,
    val method: String?,
    val date: String,
)

/**
 * Tapping "210 left ›" on a bill row: record a payment from [person] to the bill's payer, and see (and
 * edit) the ones already recorded. One sheet for both, so "what I paid" never hides behind "Edit bill".
 * The title says the direction ("Kevin paid you" / "You paid Andrew"), so the row's chip needs no verb.
 */
@Composable
fun RecordPaymentSheet(
    person: BillPaymentPersonUi,
    payeeName: String,
    currencyCode: String,
    history: List<PaymentUi>,
    defaultMethod: String?,
    today: String,
    onRecord: suspend (PaymentEntry) -> String?,
    onEditPayment: (PaymentUi) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val scope = rememberCoroutineScope()
    val left = person.remainingSubunits.coerceAtLeast(0L)
    var amountText by remember(left) { mutableStateOf(if (left > 0) format2dp(left / 100.0) else "") }
    var method by remember { mutableStateOf(defaultMethod) }
    var date by remember { mutableStateOf(today) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var showMethods by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    val entered = amountTextToSubunits(amountText)
    LaunchedEffect(entered) { problem = null }

    val title = if (person.isMe) "You paid $payeeName" else "${person.name} paid you"
    EvSheetScaffold(onDismiss = onDismiss, title = title, dismissRequested = closing) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (left > 0) {
                EvAmountInput(
                    text = amountText,
                    onTextChange = { amountText = it },
                    currency = currencyCode,
                    amountFontSize = 28.sp,
                    helper = problem ?: "${moneySubunits(left, currencyCode)} left of ${moneySubunits(person.owedSubunits, currencyCode)}",
                    helperColor = if (problem != null) c.danger else c.ink3,
                )
                MethodAndDateRow(method, date, today, onMethod = { showMethods = true }, onDate = { showDate = true })
            } else {
                Text(
                    if (person.isMe) "You've paid your share." else "${person.name} has paid their share.",
                    color = c.ink2,
                    fontSize = 14.sp,
                )
            }
            if (history.isNotEmpty()) PaymentHistoryCard(history, currencyCode, onEditPayment)
            if (left > 0) {
                EvButton(if (saving) "Saving…" else "Record payment", {
                    when {
                        saving -> Unit
                        entered <= 0L -> problem = "Enter how much was paid."
                        entered > left -> problem = "That's more than the ${moneySubunits(left, currencyCode)} left."
                        else -> {
                            saving = true
                            scope.launch {
                                val refused = onRecord(PaymentEntry(entered, method, date))
                                saving = false
                                if (refused == null) closing = true else problem = refused
                            }
                        }
                    }
                }, variant = ButtonVariant.Primary)
            }
        }
    }
    if (showMethods) MethodSheet(method, onPick = { method = it }, onDismiss = { showMethods = false })
    if (showDate) ExpenseDateSheet(date, today, onPick = { date = it }, onDismiss = { showDate = false })
}

/** "Via [M-Pesa ▾]  On [Today ▾]": one line, both tappable. */
@Composable
private fun MethodAndDateRow(
    method: String?,
    date: String,
    today: String,
    onMethod: () -> Unit,
    onDate: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Via", color = c.ink3, fontSize = 13.sp)
        DropChip(paymentMethodLabel(method) ?: "Pick one", onMethod)
        Text("On", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
        DropChip(expenseDateChipLabel(date, today), onDate)
    }
}

@Composable
private fun DropChip(
    label: String,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(c.surface)
            .clickableClearingFocus(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        EvIcon(EvIcons.ChevD, size = 14.dp, tint = c.ink3)
    }
}

/**
 * This person's payments on this bill, collapsed to a count until tapped. Its fill is the page color, a
 * step off the sheet, so it reads as a record rather than more inputs. Long histories scroll inside the
 * card so "Record payment" below never leaves the screen.
 */
@Composable
private fun PaymentHistoryCard(
    history: List<PaymentUi>,
    currencyCode: String,
    onEdit: (PaymentUi) -> Unit,
) {
    val c = EvenlyTheme.colors
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickableClearingFocus { open = !open }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("History", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!open) {
                val total = history.sumOf { it.amountSubunits }
                val count = if (history.size == 1) "1 payment" else "${history.size} payments"
                Text("$count · ${moneySubunits(total, currencyCode)}", color = c.ink3, fontSize = 12.sp)
            }
            EvIcon(if (open) EvIcons.ChevU else EvIcons.ChevD, size = 16.dp, tint = c.ink3, modifier = Modifier.padding(start = 6.dp))
        }
        if (open) {
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                history.forEach { p -> HistoryLine(p, currencyCode, onEdit) }
            }
        }
    }
}

@Composable
private fun HistoryLine(
    p: PaymentUi,
    currencyCode: String,
    onEdit: (PaymentUi) -> Unit,
) {
    val c = EvenlyTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .topHairline(c.border)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    moneySubunits(p.amountSubunits, currencyCode),
                    color = c.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = EvenlyTheme.monoFamily,
                )
                p.app?.let { MethodTag(it) }
            }
            val by = p.recordedBy.ifBlank { null }?.let { "by $it" }
            Text(
                listOfNotNull(p.dateLabel.ifBlank { null }, by).joinToString(" · "),
                color = c.ink3,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (p.canEdit) {
            Text(
                "Edit",
                color = c.blueText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickableClearingFocus { onEdit(p) }.padding(6.dp),
            )
        }
    }
}

@Composable
private fun MethodTag(label: String) {
    val c = EvenlyTheme.colors
    Box(Modifier.clip(RoundedCornerShape(99.dp)).background(c.settledTint).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(label, color = c.settled, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MethodSheet(
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    var closing by remember { mutableStateOf(false) }
    EvSheetScaffold(onDismiss = onDismiss, title = "Paid with", dismissRequested = closing) {
        Column {
            PAYMENT_METHODS.forEachIndexed { i, (key, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (i > 0) Modifier.topHairline(c.border) else Modifier)
                        .clickableClearingFocus {
                            onPick(key)
                            closing = true
                        }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (key == current) EvIcon(EvIcons.Check, size = 18.dp, tint = c.blueText)
                }
            }
        }
    }
}

/**
 * Opened from "Edit" in a person's History: change how much, how, or when, or cancel the payment. Only
 * this bill's portion is edited ([PaymentUi.amountSubunits]); a "Settle up" payment's other bills stay put.
 * "Cancel payment" asks for a second tap, because the money goes back to being owed.
 */
@Composable
fun EditBillPaymentSheet(
    payment: PaymentUi,
    shareSubunits: Long,
    leftBeforeSubunits: Long,
    currencyCode: String,
    today: String,
    paymentDate: String,
    preview: suspend (amountSubunits: Long) -> PaymentPreviewUi,
    onSave: suspend (PaymentEntry) -> String?,
    onCancelPayment: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val scope = rememberCoroutineScope()
    var amountText by remember(payment.id) { mutableStateOf(format2dp(payment.amountSubunits / 100.0)) }
    var method by remember(payment.id) { mutableStateOf(payment.storedMethod) }
    var date by remember(payment.id) { mutableStateOf(paymentDate) }
    var problem by remember { mutableStateOf<String?>(null) }
    var previewProblem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var showMethods by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    val entered = amountTextToSubunits(amountText)
    LaunchedEffect(entered) {
        problem = null
        previewProblem = preview(entered).problem
    }
    val who = if (payment.byMe) "Your" else "${payment.payerName}'s"
    val sub = payment.recordedBy.ifBlank { null }?.let { "Recorded by $it" }
    EvSheetScaffold(onDismiss = onDismiss, title = "$who payment", sub = sub, dismissRequested = closing) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            EvAmountInput(
                text = amountText,
                onTextChange = { amountText = it },
                currency = currencyCode,
                amountFontSize = 28.sp,
                helper = problem,
                helperColor = c.danger,
            )
            MethodAndDateRow(method, date, today, onMethod = { showMethods = true }, onDate = { showDate = true })
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val owner = if (payment.byMe) "Your" else "${payment.payerName}'s"
                ShareLine("$owner share", moneySubunits(shareSubunits, currencyCode), c.ink)
                val after = leftBeforeSubunits + payment.amountSubunits - entered
                ShareLine("Left after this", moneySubunits(after.coerceAtLeast(0L), currencyCode), if (after > 0) c.credit else c.settled)
            }
            EvButton(if (saving) "Saving…" else "Save", {
                val p = previewProblem
                when {
                    saving -> Unit
                    entered <= 0L -> problem = "Enter an amount. To take the payment off, use Cancel payment."
                    p != null -> problem = p
                    else -> {
                        saving = true
                        scope.launch {
                            val refused = onSave(PaymentEntry(entered, method, date))
                            saving = false
                            if (refused == null) closing = true else problem = refused
                        }
                    }
                }
            }, variant = ButtonVariant.Primary)
            if (confirmCancel) {
                EvButton("Tap again to cancel this payment", {
                    onCancelPayment()
                    closing = true
                }, variant = ButtonVariant.Danger)
            } else {
                Text(
                    "Cancel payment",
                    color = c.danger,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickableClearingFocus { confirmCancel = true }
                            .padding(vertical = 12.dp),
                )
            }
        }
    }
    if (showMethods) MethodSheet(method, onPick = { method = it }, onDismiss = { showMethods = false })
    if (showDate) ExpenseDateSheet(date, today, onPick = { date = it }, onDismiss = { showDate = false })
}

@Composable
private fun ShareLine(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
) {
    val c = EvenlyTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = EvenlyTheme.monoFamily)
    }
}

/**
 * A row's balance as the way in to its payments: "210 left ›" in blue, "✓ Paid ›" in green, "50 back ›"
 * in amber. The chevron is the cue that it taps; rows the viewer can't act on show plain text instead.
 */
@Composable
internal fun BalanceChip(
    label: String,
    cleared: Boolean,
    overpaid: Boolean,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val (bg, fg) =
        when {
            overpaid -> c.creditTint to c.credit
            cleared -> c.settledTint to c.settled
            else -> c.blueTint to c.blueText
        }
    Row(
        Modifier.clip(RoundedCornerShape(99.dp)).background(bg).clickableClearingFocus(onClick = onClick).padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (cleared) "✓ Paid" else label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (cleared) null else EvenlyTheme.monoFamily,
        )
        EvIcon(EvIcons.ChevR, size = 14.dp, tint = fg)
    }
}
