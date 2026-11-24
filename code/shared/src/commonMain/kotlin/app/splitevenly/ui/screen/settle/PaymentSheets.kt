package app.splitevenly.ui.screen.settle

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAmountInput
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.amountTextToSubunits
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import androidx.compose.ui.draw.clip
import app.splitevenly.ui.screen.expense.ReceiptViewerScreen
import app.splitevenly.ui.screen.expense.ViewerReceipt
import app.splitevenly.ui.screen.expense.format2dp
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.coroutines.launch

/**
 * A recorded payment as a payments list shows it. [amountSubunits] is what the list is about: this
 * bill's portion on a bill screen, the whole payment on the settle-up screen.
 */
data class PaymentUi(
    val id: String,
    val payerName: String,
    val byMe: Boolean,
    val amountSubunits: Long,
    val maxSubunits: Long = amountSubunits,
    val app: String? = null,
    val dateLabel: String = "",
    val payeeName: String = "",
    val toMe: Boolean = false,
    val totalSubunits: Long = amountSubunits,
    val billCount: Int = 1,
    val dateTimeLabel: String = "",
    val recordedBy: String = "",
    // Payment proof (docs/features/payment-proof.md). [chainId] is what a proof hangs off.
    val chainId: String = id,
    val proofId: String? = null,
    val proofUrl: String? = null,
    val proofMine: Boolean = false,
    // The local copy while the screenshot is still uploading.
    val proofUploadingModel: String? = null,
) {
    val hasProof: Boolean get() = proofUrl != null || proofUploadingModel != null

    /** Only the two people in a payment may change it (`docs/features/one-time-splits.md` Part B). */
    val canEdit: Boolean get() = byMe || toMe

    val title: String
        get() =
            when {
                byMe -> "You paid ${payeeName.ifBlank { "them" }}"
                toMe -> "$payerName paid you"
                payeeName.isNotBlank() -> "$payerName paid $payeeName"
                else -> "$payerName paid"
            }
}

/** One bill in the read-only "Now covers" preview. */
data class PaymentPreviewLineUi(
    val title: String,
    val dateLabel: String,
    val beforeSubunits: Long,
    val afterSubunits: Long,
)

/** What the edit sheet shows for an entered amount: the per-bill result, or why it can't be saved. */
data class PaymentPreviewUi(
    val lines: List<PaymentPreviewLineUi> = emptyList(),
    val problem: String? = null,
)

/**
 * Mockup 8: tap a payment you're part of. Amount first, then when and who recorded it. No balance
 * follow-ups and no permission explanations (brief: "Keep payment screens minimal").
 */
@Composable
fun PaymentActionSheet(
    payment: PaymentUi,
    currencyCode: String,
    onDismiss: () -> Unit,
    onChangeAmount: () -> Unit,
    onRemove: () -> Unit,
    onOpenProof: () -> Unit = {},
    onAddProof: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    var closing by remember { mutableStateOf(false) }
    var next by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Removing a payment puts the money back as owed, so it takes a second tap on the same button.
    var confirmRemove by remember { mutableStateOf(false) }
    EvSheetScaffold(
        onDismiss = {
            onDismiss()
            next?.invoke()
        },
        dismissRequested = closing,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(payment.title, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        moneySubunits(payment.amountSubunits, currencyCode),
                        color = c.ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = EvenlyTheme.monoFamily,
                    )
                    Spacer(Modifier.weight(1f))
                    payment.app?.let { EvChip(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (payment.dateTimeLabel.isNotBlank()) Text(payment.dateTimeLabel, color = c.ink2, fontSize = 13.sp)
                    if (payment.recordedBy.isNotBlank()) Text("recorded by ${payment.recordedBy}", color = c.ink3, fontSize = 13.sp)
                }
            }
            PaymentProofRow(payment, onOpen = onOpenProof, onAdd = onAddProof)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EvButton("Change amount", {
                    next = onChangeAmount
                    closing = true
                }, variant = ButtonVariant.Tonal)
                EvButton(
                    when {
                        confirmRemove -> "Remove this ${moneySubunits(payment.amountSubunits, currencyCode)} payment"
                        payment.byMe -> "I didn't pay this"
                        else -> "I didn't get this"
                    },
                    {
                        if (!confirmRemove) {
                            confirmRemove = true
                        } else {
                            next = onRemove
                            closing = true
                        }
                    },
                    variant = if (confirmRemove) ButtonVariant.Danger else ButtonVariant.Secondary,
                )
            }
        }
    }
}

/**
 * Mockup 9: change a payment's amount. [preview] runs for every entered amount and fills the read-only
 * "Now covers" list (shown only when the payment spans more than one bill). Save stays live: an amount
 * that can't be saved shows why on tap, and a refusal from [onSave] keeps the sheet open with the reason.
 */
@Composable
fun PaymentEditSheet(
    payment: PaymentUi,
    currencyCode: String,
    onDismiss: () -> Unit,
    preview: suspend (amountSubunits: Long) -> PaymentPreviewUi,
    onSave: suspend (amountSubunits: Long) -> String?,
) {
    val c = EvenlyTheme.colors
    val scope = rememberCoroutineScope()
    var amountText by remember(payment.id) { mutableStateOf(format2dp(payment.amountSubunits / 100.0)) }
    val entered = amountTextToSubunits(amountText)
    var result by remember { mutableStateOf(PaymentPreviewUi()) }
    var shownProblem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(entered) {
        result = preview(entered)
        shownProblem = null
    }
    val who = if (payment.byMe) "your" else "${payment.payerName}'s"
    EvSheetScaffold(onDismiss, title = "Change $who payment", dismissRequested = closing) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val meta = listOfNotNull(payment.app, payment.dateTimeLabel.ifBlank { null }).joinToString("  ")
                Text(meta, color = c.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                // On a bill screen a settle-up shows only this bill's portion; say so, or "Was $9.00" reads as the whole payment.
                val onThisBill = payment.billCount > 1 && payment.amountSubunits != payment.totalSubunits
                Text(
                    "Was ${moneySubunits(payment.amountSubunits, currencyCode)}${if (onThisBill) " on this bill" else ""}",
                    color = c.ink3,
                    fontSize = 13.sp,
                )
            }
            EvAmountInput(
                text = amountText,
                onTextChange = { amountText = it },
                currency = currencyCode,
                amountFontSize = 28.sp,
                helper = shownProblem,
                helperColor = c.danger,
            )
            if (payment.billCount > 1 && result.lines.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Now covers", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    EvCard {
                        result.lines.forEachIndexed { i, line ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (i > 0) Modifier.topHairline(c.border) else Modifier)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    listOf(line.title, line.dateLabel).filter { it.isNotBlank() }.joinToString(" · "),
                                    color = c.ink,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (line.beforeSubunits != line.afterSubunits) {
                                    Text(
                                        moneySubunits(line.beforeSubunits, currencyCode),
                                        color = c.ink3,
                                        fontSize = 13.sp,
                                        fontFamily = EvenlyTheme.monoFamily,
                                        textDecoration = TextDecoration.LineThrough,
                                    )
                                }
                                Text(
                                    moneySubunits(line.afterSubunits, currencyCode),
                                    color = c.ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = EvenlyTheme.monoFamily,
                                )
                            }
                        }
                    }
                }
            }
            EvButton("Save", {
                val problem = result.problem
                if (problem != null) {
                    shownProblem = problem
                } else if (!saving) {
                    saving = true
                    scope.launch {
                        val refused = onSave(entered)
                        saving = false
                        if (refused == null) closing = true else shownProblem = refused
                    }
                }
            })
        }
    }
}

@Preview
@Composable
private fun PaymentEditSheetPreview() {
    EvenlyTheme {
        PaymentEditSheet(
            payment =
                PaymentUi(
                    id = "p1",
                    payerName = "Alice",
                    byMe = false,
                    toMe = true,
                    payeeName = "Bob",
                    amountSubunits = 1500,
                    billCount = 2,
                    app = "Venmo",
                    dateTimeLabel = "Nov 19, 9:58 PM",
                ),
            currencyCode = "USD",
            onDismiss = {},
            preview = {
                PaymentPreviewUi(
                    listOf(
                        PaymentPreviewLineUi("Groceries", "Nov 12", 900, 500),
                        PaymentPreviewLineUi("Internet", "Nov 15", 600, 0),
                    ),
                )
            },
            onSave = { null },
        )
    }
}

/**
 * "Payments" between you and one person, newest first. Rows you're a party to open [onOpen]; the rest
 * have no chevron and do nothing (mockup 8: the missing chevron is the whole explanation).
 */
@Composable
fun PaymentListSection(
    payments: List<PaymentUi>,
    currencyCode: String,
    onOpen: (PaymentUi) -> Unit,
    onOpenProof: (PaymentUi) -> Unit = {},
    onAddProof: (PaymentUi) -> Unit = {},
) {
    if (payments.isEmpty()) return
    val c = EvenlyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Payments", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        EvCard {
            payments.forEachIndexed { i, p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (i > 0) Modifier.topHairline(c.border) else Modifier)
                        .then(if (p.canEdit) Modifier.clickable { onOpen(p) } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PaymentProofThumb(
                        url = p.proofUrl,
                        uploadingModel = p.proofUploadingModel,
                        onOpen = { onOpenProof(p) },
                        onAdd = if (p.canEdit) ({ onAddProof(p) }) else null,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(p.title, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        val sub =
                            listOfNotNull(
                                p.app,
                                p.dateLabel.ifBlank { null },
                                if (p.billCount > 1) "${p.billCount} bills" else null,
                            ).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, color = c.ink2, fontSize = 12.sp)
                    }
                    Text(
                        moneySubunits(p.amountSubunits, currencyCode),
                        color = c.ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = EvenlyTheme.monoFamily,
                    )
                    if (p.canEdit) EvIcon(EvIcons.ChevR, size = 18.dp, tint = c.ink3)
                }
            }
        }
    }
}

/**
 * The proof line inside [PaymentActionSheet]: the screenshot (tap to open) or, for the two people in the
 * payment, a way to add one. A payment recorded without one can always get it later from here.
 */
@Composable
private fun PaymentProofRow(
    payment: PaymentUi,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
) {
    val c = EvenlyTheme.colors
    if (!payment.hasProof && !payment.canEdit) return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.page)
            .clickable(onClick = if (payment.proofUrl != null) onOpen else onAdd)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PaymentProofThumb(payment.proofUrl, payment.proofUploadingModel, onOpen = onOpen, onAdd = onAdd, width = 36.dp, height = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when {
                    payment.proofUploadingModel != null -> "Screenshot uploading"
                    payment.proofUrl != null -> "Screenshot"
                    else -> "Add a screenshot"
                },
                color = c.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    payment.proofUploadingModel != null -> "It finishes in the background."
                    payment.proofUrl != null -> "Tap to see it full size."
                    else -> "Optional. Shows the payment went through."
                },
                color = c.ink2,
                fontSize = 12.sp,
            )
        }
        if (payment.proofUrl != null && payment.canEdit) {
            Text("Replace", color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onAdd))
        }
    }
}

/**
 * A payment's screenshot full screen, captioned with the payment it belongs to. Only whoever added it may
 * delete it ([onRemove] null otherwise).
 */
@Composable
fun PaymentProofViewer(
    payment: PaymentUi,
    currencyCode: String,
    onClose: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val url = payment.proofUrl ?: return
    ReceiptViewerScreen(
        receipts = listOf(ViewerReceipt(payment.proofId ?: payment.id, url, isPdf = false)),
        onClose = onClose,
        onDeleteReceipt = onRemove?.let { remove -> { _ -> remove() } },
        title = "Payment screenshot",
        caption = listOfNotNull("${payment.title} ${moneySubunits(payment.totalSubunits, currencyCode)}", payment.app).joinToString(" · "),
        captionDetail = listOfNotNull(payment.dateLabel.ifBlank { null }, payment.recordedBy.ifBlank { null }?.let { "recorded by $it" })
            .joinToString(" · ")
            .ifBlank { null },
        noun = "screenshot",
    )
}
