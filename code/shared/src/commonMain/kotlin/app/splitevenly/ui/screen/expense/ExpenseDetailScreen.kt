package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.platform.PickSource
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.ChipVariant
import app.splitevenly.ui.components.EvAmountInput
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvDivider
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.EvProgress
import app.splitevenly.ui.components.EvSectionLabel
import app.splitevenly.ui.components.EvSkeleton
import app.splitevenly.ui.components.EvSkeletonRow
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.amountTextToSubunits
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme
import coil3.compose.AsyncImage

enum class ExpenseDetailState { Loading, Content, Error }

data class DetailShareUi(
    val name: String,
    val owedSubunits: Long,
    val paidSubunits: Long,
    val remainingSubunits: Long,
    val me: Boolean = false,
    val payer: Boolean = false,
)

/**
 * A payment recorded against this expense (a single-expense settlement). [maxSubunits] is the ceiling an
 * edit can raise it to (what the payer owes on this expense) — used for inline validation; the repository
 * is the true guard. [app] is a display label ("Venmo") or null; [dateLabel] a short relative stamp.
 */
data class PaymentUi(
    val id: String,
    val payerName: String,
    val byMe: Boolean,
    val amountSubunits: Long,
    val maxSubunits: Long,
    val app: String? = null,
    val dateLabel: String = "",
)

/** A receipt thumbnail (F5). [url] null = still uploading / unavailable; [isPdf] renders a file tile. */
data class ReceiptUi(
    val id: String,
    val url: String?,
    val isPdf: Boolean = false,
)

/**
 * An in-flight (or failed) receipt upload (D-22). Renders as a local thumbnail with a centered progress
 * ring; a failed one shows a retry/cancel affordance. [model] is what Coil loads (a `file://` path for
 * images, null for PDFs).
 */
data class ReceiptUploadUi(
    val id: String,
    val model: String?,
    val isPdf: Boolean,
    val fraction: Float,
    val failed: Boolean,
)

/** A comment in the thread (F5). [timeLabel] is a short relative stamp ("2h"). */
data class CommentUi(
    val id: String,
    val authorName: String,
    val body: String,
    val timeLabel: String,
    val me: Boolean,
)

/** One activity-log entry (F5): a rendered sentence + a short relative stamp. */
data class HistoryUi(
    val text: String,
    val timeLabel: String,
)

/** 12 · Expense detail (design/src/screens-expense.jsx). */
@Composable
fun ExpenseDetailScreen(
    state: ExpenseDetailState = ExpenseDetailState.Content,
    title: String = "Dinner at La Negra",
    category: String = "Food & Drink",
    payerName: String = "Andrew",
    dateLabel: String = "May 23 · 8:40 PM",
    amountSubunits: Long = 9600,
    remainingSubunits: Long = 4800,
    currencyCode: String = "USD",
    splitLabel: String = "Split between 4 · even",
    splitRows: List<DetailShareUi> = DemoSplit,
    payments: List<PaymentUi> = emptyList(),
    receipts: List<ReceiptUi> = emptyList(),
    pendingUploads: List<ReceiptUploadUi> = emptyList(),
    comments: List<CommentUi> = DemoComments,
    historyEvents: List<HistoryUi> = DemoHistory,
    commentDraft: String = "",
    onCommentDraftChange: (String) -> Unit = {},
    onSendComment: () -> Unit = {},
    onPickReceipts: (PickSource) -> Unit = {},
    onRetryUpload: (String) -> Unit = {},
    onCancelUpload: (String) -> Unit = {},
    // Receipts open in-app (ReceiptViewerScreen) — never handed off to an external browser. PDFs are
    // fetched + rasterized through these loaders, supplied by the Route wrapper.
    loadPdfPageCount: suspend (url: String) -> Int = { 0 },
    renderPdfPage: suspend (url: String, page: Int, widthPx: Int) -> ImageBitmap? = { _, _, _ -> null },
    onBack: () -> Unit = {},
    onSettleThis: () -> Unit = {},
    // Correct a recorded payment: raise/lower its amount (void + re-record, guarded ≤ owed).
    onEditPayment: (paymentId: String, newAmountSubunits: Long) -> Unit = { _, _ -> },
    // Remove a recorded payment entirely (void it); its balance is restored.
    onRemovePayment: (paymentId: String) -> Unit = {},
    onReload: () -> Unit = {},
    // Null hides the "Send feedback" button in the error state rather than rendering a dead one. It
    // used to be wired to an empty lambda, which is the dead end `ui/AGENTS.md` forbids.
    onSendFeedback: (() -> Unit)? = null,
    onEdit: () -> Unit = {},
    // Non-null only for an itemized ("Split the bill") expense — renders a prominent "claim or edit your
    // items" button under the split, the durable way into the claim screen once the home card is gone.
    onClaimItems: (() -> Unit)? = null,
    // Non-null only for an itemized bill — the ⋯ menu's "Edit bill" opens the menu/items/extras editor
    // (Route.SplitBill). For a plain expense this is null and the menu shows the generic "Edit" → onEdit.
    onEditBill: (() -> Unit)? = null,
    // Track F one-sided nudge: this device's split edit was superseded by a newer one while it was behind.
    // Shows a dismissible banner inviting the author to review the current split (never a two-sided card).
    supersededNotice: Boolean = false,
    onDismissSupersededNotice: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    var overflow by remember { mutableStateOf(false) }
    var showReceiptSource by remember { mutableStateOf(false) }
    // The payment whose action sheet (Edit amount / Remove) is open, then the one being amount-edited.
    var actionPayment by remember { mutableStateOf<PaymentUi?>(null) }
    var editingPayment by remember { mutableStateOf<PaymentUi?>(null) }
    // Index of the receipt the in-app viewer is showing, or null when it's closed.
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    // Your own row leads the breakdown — it's what you're here to check. `sortedByDescending` is stable,
    // so everyone else keeps their incoming order behind you.
    val split = splitRows.sortedByDescending { it.me }
    // Tapping empty space anywhere on the page drops focus from the comment field (and hides the
    // keyboard). Child clickables consume their own taps, so only taps on blank areas reach this.
    val focusManager = LocalFocusManager.current

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
            EvTopBarDetail(
                title = if (state == ExpenseDetailState.Error) "" else title,
                sub = if (state == ExpenseDetailState.Content) category.ifBlank { null } else null,
                onBack = onBack,
                onMore = if (state == ExpenseDetailState.Content) ({ overflow = true }) else null,
            )
            when (state) {
                ExpenseDetailState.Loading -> {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        EvSkeleton(height = 132.dp, radius = 16.dp)
                        EvSkeleton(width = 140.dp, height = 14.dp)
                        EvSkeletonRow()
                        EvSkeletonRow()
                        EvSkeletonRow()
                    }
                }

                ExpenseDetailState.Error -> {
                    ErrorContent(onReload = onReload, onSendFeedback = onSendFeedback)
                }

                ExpenseDetailState.Content -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = {
                                    focusManager.clearFocus()
                                })
                            }.verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Track F one-sided nudge — the author's split edit was superseded by a newer one while
                        // this device was behind. A calm, dismissible "review?" prompt; never a two-sided card.
                        if (supersededNotice) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(c.warningTint)
                                    .border(1.dp, c.warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                EvIcon(EvIcons.Info, size = 16.dp, tint = c.warning, modifier = Modifier.padding(top = 2.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Your change was superseded", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "The split changed again while your edit was offline, so a newer version was kept. Review the current split below.",
                                        color = c.ink2,
                                        fontSize = 12.5.sp,
                                    )
                                }
                                EvIconButton(EvIcons.Close, onDismissSupersededNotice, tint = c.ink3, size = 16.dp)
                            }
                        }
                        // header card — the expense total is the headline; your personal stake is the band below
                        // it (what you actually track), and the group-wide remainder is demoted to that band's caption.
                        EvCard(padded = true) {
                            val myRow = split.firstOrNull { it.me }
                            val iPaid = myRow?.payer == true
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(moneySubunits(amountSubunits, currencyCode), style = EvenlyTheme.amounts.hero, color = c.ink)
                                    Text("total expense", color = c.ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                                }
                                if (category.isNotBlank()) {
                                    EvChip(
                                        category.substringBefore(" "),
                                        variant = ChipVariant.Blue,
                                        leadingIcon = EvIcons.Food,
                                        large = true,
                                    )
                                }
                            }
                            val debtors = split.filterNot { it.payer }
                            PersonalStatusBand(
                                myRow = myRow,
                                othersOwedSubunits = debtors.sumOf { it.owedSubunits },
                                othersRemainingSubunits = debtors.sumOf { it.remainingSubunits },
                                currencyCode = currencyCode,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                            EvDivider(Modifier.padding(vertical = 14.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    EvAvatar(payerName, me = iPaid, size = AvatarSize.Sm)
                                    Text(
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(if (iPaid) "You" else payerName) }
                                            append(if (iPaid) " paid this" else " paid")
                                        },
                                        color = c.ink,
                                        fontSize = 14.sp,
                                    )
                                }
                                Text(dateLabel, color = c.ink2, fontSize = 12.sp)
                            }
                        }

                        // receipts
                        Column {
                            EvSectionLabel("Receipts")
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                receipts.forEachIndexed { i, r -> ReceiptThumb(r, onClick = { viewerIndex = i }) }
                                // In-flight uploads render right after the published ones, each with its own ring.
                                pendingUploads.forEach { u ->
                                    UploadingThumb(u, onRetry = { onRetryUpload(u.id) }, onCancel = { onCancelUpload(u.id) })
                                }
                                AddReceiptTile(onClick = { showReceiptSource = true })
                            }
                        }

                        // payments — the recorded settlements against this expense. Each is tappable to correct or
                        // remove it (the only way to fix a wrong amount or clear an overpayment after a split edit).
                        if (payments.isNotEmpty()) {
                            Column {
                                EvSectionLabel("Payments")
                                EvCard {
                                    payments.forEachIndexed { i, p ->
                                        val divider = if (i > 0) Modifier.topHairline(c.border) else Modifier
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .then(divider)
                                                .clickable { actionPayment = p }
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            EvAvatar(p.payerName, me = p.byMe, size = AvatarSize.Sm)
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    if (p.byMe) "You paid" else "${p.payerName} paid",
                                                    color = c.ink,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                val sub = listOfNotNull(p.app, p.dateLabel.ifBlank { null }).joinToString(" · ")
                                                if (sub.isNotEmpty()) Text(sub, color = c.ink2, fontSize = 12.sp)
                                            }
                                            Text(
                                                moneySubunits(p.amountSubunits, currencyCode),
                                                color = c.ink,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = EvenlyTheme.monoFamily,
                                            )
                                            EvIcon(EvIcons.ChevR, size = 18.dp, tint = c.ink3)
                                        }
                                    }
                                }
                            }
                        }

                        // split breakdown
                        Column {
                            EvSectionLabel(splitLabel)
                            // For an itemized bill, the split is derived from who claimed what — so give people an
                            // obvious way to open the claim screen and change what they had, instead of hiding it in
                            // the ⋯ menu. This is the durable entry once the home "claim your items" card is gone.
                            onClaimItems?.let { open ->
                                EvButton(
                                    "Claim or edit your items",
                                    open,
                                    modifier = Modifier.padding(bottom = 10.dp),
                                    variant = ButtonVariant.Tonal,
                                    leadingIcon = EvIcons.Receipt,
                                )
                            }
                            EvCard {
                                split.forEachIndexed { i, s ->
                                    val divider = if (i > 0) Modifier.topHairline(c.border) else Modifier
                                    // Your own row is tinted + given a blue rail so "my money" leads at a glance.
                                    val mineRail = if (s.me) Modifier.background(c.selectionTint) else Modifier
                                    if (s.payer) {
                                        // The bill-fronter can't owe themselves, so the debt/progress/"settled"
                                        // machinery is just noise here — drop it. Show only who paid and their share.
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .then(divider)
                                                .then(mineRail)
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            EvAvatar(s.name, me = s.me, size = AvatarSize.Sm)
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    buildAnnotatedString {
                                                        withStyle(
                                                            SpanStyle(fontWeight = FontWeight.SemiBold),
                                                        ) { append(if (s.me) "You" else s.name) }
                                                        if (s.me) {
                                                            withStyle(
                                                                SpanStyle(color = c.blueText, fontWeight = FontWeight.SemiBold),
                                                            ) { append(" · your share") }
                                                        }
                                                    },
                                                    color = c.ink,
                                                    fontSize = 15.sp,
                                                )
                                                Text("Paid the bill", color = c.ink3, fontSize = 12.sp)
                                            }
                                            if (s.owedSubunits > 0) {
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        moneySubunits(s.owedSubunits, currencyCode),
                                                        color = if (s.me) c.blue else c.ink,
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontFamily = EvenlyTheme.monoFamily,
                                                    )
                                                    Text(if (s.me) "your share" else "their share", color = c.ink3, fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    } else {
                                        // A participant who actually owes: name + what's left, a progress bar, and
                                        // (for your own share) an inline settle action. Green once fully settled.
                                        val canSettle = s.me && s.remainingSubunits > 0
                                        val cleared = s.remainingSubunits == 0L
                                        // Overpaid (a split edited *down* after payment): show it as an in-credit
                                        // "you're owed back" row — orange outline + orange number, calm not alarming.
                                        val overpaid = s.remainingSubunits < 0L
                                        val rowShape = RoundedCornerShape(12.dp)
                                        val rowMod =
                                            if (overpaid) {
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(10.dp)
                                                    .clip(rowShape)
                                                    .background(c.page)
                                                    .border(1.5.dp, c.credit, rowShape)
                                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                            } else {
                                                Modifier
                                                    .fillMaxWidth()
                                                    .then(divider)
                                                    .then(mineRail)
                                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                                            }
                                        Row(
                                            rowMod,
                                            verticalAlignment = Alignment.Top,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            EvAvatar(s.name, me = s.me, size = AvatarSize.Sm)
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                                Row(
                                                    Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.Top,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    Text(
                                                        buildAnnotatedString {
                                                            withStyle(
                                                                SpanStyle(fontWeight = FontWeight.SemiBold),
                                                            ) { append(if (s.me) "You" else s.name) }
                                                            if (s.me) {
                                                                withStyle(
                                                                    SpanStyle(color = c.blueText, fontWeight = FontWeight.SemiBold),
                                                                ) { append(" · your share") }
                                                            }
                                                        },
                                                        color = c.ink,
                                                        fontSize = 15.sp,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    Text(
                                                        when {
                                                            overpaid -> moneySubunits(-s.remainingSubunits, currencyCode) + " back"
                                                            cleared -> "settled"
                                                            else -> moneySubunits(s.remainingSubunits, currencyCode) + " left"
                                                        },
                                                        color =
                                                            when {
                                                                overpaid -> c.credit
                                                                cleared -> c.settled
                                                                s.me -> c.blue
                                                                else -> c.ink
                                                            },
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontFamily = EvenlyTheme.monoFamily,
                                                    )
                                                }
                                                EvProgress(
                                                    if (s.owedSubunits >
                                                        0
                                                    ) {
                                                        (s.paidSubunits.toFloat() / s.owedSubunits).coerceIn(0f, 1f)
                                                    } else {
                                                        0f
                                                    },
                                                    Modifier.fillMaxWidth(),
                                                    fill =
                                                        if (overpaid) {
                                                            c.credit
                                                        } else if (cleared) {
                                                            c.settled
                                                        } else {
                                                            c.blue
                                                        },
                                                    track =
                                                        if (overpaid) {
                                                            c.creditTint
                                                        } else if (cleared) {
                                                            c.settledTint2
                                                        } else {
                                                            c.blueTint2
                                                        },
                                                )
                                                Row(
                                                    Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    Text(
                                                        "Paid ${moneySubunits(
                                                            s.paidSubunits,
                                                            currencyCode,
                                                        )} of ${moneySubunits(s.owedSubunits, currencyCode)}",
                                                        color = if (s.me) c.ink2 else c.ink3,
                                                        fontSize = 12.sp,
                                                        fontFamily = EvenlyTheme.monoFamily,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    if (canSettle) {
                                                        EvButton(
                                                            "Settle this",
                                                            onSettleThis,
                                                            variant = ButtonVariant.Secondary,
                                                            small = true,
                                                            fillMaxWidth = false,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // comments — collapsible. Defaults open for a short thread (≤2), collapsed once it's 3+
                        // so a long back-and-forth doesn't bury the split below it. The composer stays visible.
                        Column {
                            var commentsOverride by remember { mutableStateOf<Boolean?>(null) }
                            val commentsOpen = commentsOverride ?: (comments.size <= 2)
                            Row(
                                Modifier.fillMaxWidth().clickable { commentsOverride = !commentsOpen }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                EvIcon(EvIcons.Comment, size = 18.dp, tint = c.ink2)
                                Text(
                                    "Comments",
                                    color = c.ink,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                if (comments.isNotEmpty()) Text("${comments.size}", color = c.ink2, fontSize = 12.sp)
                                EvIcon(if (commentsOpen) EvIcons.ChevU else EvIcons.ChevD, size = 16.dp, tint = c.ink3)
                            }
                            if (commentsOpen) {
                                if (comments.isEmpty()) {
                                    Text("No comments yet.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                                        comments.forEach { cm -> CommentBubble(cm.authorName, cm.body, cm.timeLabel, me = cm.me) }
                                    }
                                }
                            } else if (comments.isNotEmpty()) {
                                // Collapsed peek: the latest comment so the thread isn't fully hidden.
                                comments.last().let { cm ->
                                    Text(
                                        "${cm.authorName}: ${cm.body}",
                                        color = c.ink2,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                            Row(
                                Modifier.padding(top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                EvTextField(
                                    value = commentDraft,
                                    onValueChange = onCommentDraftChange,
                                    modifier = Modifier.weight(1f),
                                    placeholder = "Add a comment…",
                                    singleLine = false,
                                    minHeight = 44.dp,
                                )
                                val canSend = commentDraft.isNotBlank()
                                // Keep the button on a solid, page-distinct fill in both states — a low-alpha
                                // ghost reads as "not there". Idle is a muted blue tint, active is full blue.
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (canSend) c.blue else c.blueTint2)
                                        .clickable(enabled = canSend) {
                                            onSendComment()
                                            commentsOverride = true
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    EvIcon(EvIcons.Send, size = 18.dp, tint = if (canSend) c.onAccent else c.disabledInk)
                                }
                            }
                        }

                        Collapsible(
                            "History",
                            EvIcons.History,
                            "${historyEvents.size} ${if (historyEvents.size == 1) "event" else "events"}",
                        ) {
                            if (historyEvents.isEmpty()) {
                                Text("No activity recorded yet.", color = c.ink2, fontSize = 12.sp)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    historyEvents.forEach { ev ->
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            Text(
                                                ev.text,
                                                color = c.ink2,
                                                fontSize = 12.sp,
                                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                            )
                                            Text(ev.timeLabel, color = c.ink3, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                        Collapsible("Refunds", EvIcons.Refund, "None yet") {
                            Text("No refunds on this expense.", color = c.ink2, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        viewerIndex?.let { idx ->
            ReceiptViewerScreen(
                receipts = receipts.map { ViewerReceipt(it.id, it.url, it.isPdf) },
                initialIndex = idx,
                loadPdfPageCount = loadPdfPageCount,
                renderPdfPage = renderPdfPage,
                onClose = { viewerIndex = null },
            )
        }
    }

    if (overflow) {
        EvModalScaffold(onDismiss = { overflow = false }) {
            // For an itemized bill, "Edit" means the menu/items/extras editor — labelled "Edit bill" and
            // routed there. Assigning who-had-what stays on the prominent "Claim or edit your items" button.
            if (onEditBill != null) {
                OverflowRow(EvIcons.Edit, "Edit bill", c.ink2, c.ink) {
                    overflow = false
                    onEditBill()
                }
            } else {
                OverflowRow(EvIcons.Edit, "Edit", c.ink2, c.ink) {
                    overflow = false
                    onEdit()
                }
            }
            listOf(EvIcons.Refund to "Issue refund", EvIcons.Camera to "Add receipt", EvIcons.Share to "Share").forEach { (ic, label) ->
                OverflowRow(ic, label, c.ink2, c.ink) {
                    overflow = false
                    if (label == "Add receipt") showReceiptSource = true
                }
            }
            OverflowRow(EvIcons.Trash, "Delete", c.danger, c.danger) {
                overflow = false
                onDelete()
            }
        }
    }

    if (showReceiptSource) {
        EvModalScaffold(onDismiss = { showReceiptSource = false }) {
            Text("Add a receipt", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            OverflowRow(EvIcons.Image, "Photos", c.blueText, c.ink) {
                showReceiptSource = false
                onPickReceipts(PickSource.Photos)
            }
            OverflowRow(EvIcons.Archive, "Files", c.blueText, c.ink) {
                showReceiptSource = false
                onPickReceipts(PickSource.Files)
            }
            OverflowRow(EvIcons.Camera, "Camera", c.blueText, c.ink) {
                showReceiptSource = false
                onPickReceipts(PickSource.Camera)
            }
        }
    }

    // Tap a payment → correct or remove it. Remove is destructive (voids the payment); Edit opens the
    // amount editor below. Both are how a wrong amount — or a post-split-edit overpayment — gets fixed.
    actionPayment?.let { p ->
        EvModalScaffold(onDismiss = { actionPayment = null }) {
            Text(moneySubunits(p.amountSubunits, currencyCode), color = c.ink, style = MaterialTheme.typography.titleMedium)
            val sub =
                listOfNotNull(
                    if (p.byMe) "You paid" else "${p.payerName} paid",
                    p.app,
                    p.dateLabel.ifBlank { null },
                ).joinToString(" · ")
            Text(sub, color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            OverflowRow(EvIcons.Edit, "Edit amount", c.blueText, c.ink) {
                actionPayment = null
                editingPayment = p
            }
            OverflowRow(EvIcons.Trash, "Remove payment", c.danger, c.danger) {
                actionPayment = null
                onRemovePayment(p.id)
            }
        }
    }

    editingPayment?.let { p ->
        var amountText by remember(p.id) { mutableStateOf(format2dp(p.amountSubunits / 100.0)) }
        val enteredSubunits = amountTextToSubunits(amountText)
        val tooHigh = enteredSubunits > p.maxSubunits
        val valid = enteredSubunits in 1..p.maxSubunits
        EvModalScaffold(onDismiss = { editingPayment = null }) {
            Text("Edit payment", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                "Correct what ${if (p.byMe) "you" else p.payerName} actually paid.",
                color = c.ink2,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            EvAmountInput(
                text = amountText,
                onTextChange = { amountText = it },
                currency = currencyCode,
                helper =
                    if (tooHigh) {
                        "Can't exceed the ${moneySubunits(
                            p.maxSubunits,
                            currencyCode,
                        )} owed"
                    } else {
                        "Up to the ${moneySubunits(p.maxSubunits, currencyCode)} owed on this expense"
                    },
                helperColor = if (tooHigh) c.danger else c.ink2,
            )
            // Keep Save live and validate on tap (guide-when-blocked): a valid amount commits, an invalid
            // one just leaves the helper showing why. The repository re-guards regardless.
            EvButton("Save", {
                if (valid) {
                    val id = p.id
                    editingPayment = null
                    onEditPayment(id, enteredSubunits)
                }
            }, leadingIcon = EvIcons.Check, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/**
 * Your personal stake in this expense, surfaced right under the total because it's what you actually
 * track (priorities 1–2: what you still owe, what you've paid). Two viewer-relative shapes:
 *  - **participant** → "You still owe $X" + "Paid Y of your share";
 *  - **payer** → "You're owed back $X" + "Collected Y of total" — the only time settle-up is about *you*.
 * Fully done collapses to a calm settled state. Color tracks the balance: calm blue while there's still
 * something owed/owing, calm green once it's fully settled (a deliberate, owner-approved exception to the
 * house "never green" rule, scoped to settle-up states). Renders nothing when the viewer has no share
 * (e.g. an outside-payer expense) or there's nothing to collect (solo).
 */
@Composable
private fun PersonalStatusBand(
    myRow: DetailShareUi?,
    othersOwedSubunits: Long,
    othersRemainingSubunits: Long,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    if (myRow == null) return
    val state =
        if (myRow.payer) {
            if (othersOwedSubunits == 0L) return // solo expense — no one to collect from
            val collected = othersOwedSubunits - othersRemainingSubunits
            val done = othersRemainingSubunits == 0L
            BandState(
                label = if (done) "Everyone paid you back" else "You're owed back",
                amount = if (done) null else moneySubunits(othersRemainingSubunits, currencyCode),
                fraction = collected.toFloat() / othersOwedSubunits,
                caption = "Collected ${moneySubunits(collected, currencyCode)} of ${moneySubunits(othersOwedSubunits, currencyCode)}",
                done = done,
            )
        } else {
            if (myRow.owedSubunits == 0L) return
            // Negative remaining = you paid more than you now owe (a split edited down after you paid) → in credit.
            val overpaid = myRow.remainingSubunits < 0L
            val done = myRow.remainingSubunits == 0L
            BandState(
                label =
                    when {
                        overpaid -> "You're owed back"
                        done -> "You're all settled up"
                        else -> "You still owe"
                    },
                amount =
                    when {
                        overpaid -> moneySubunits(-myRow.remainingSubunits, currencyCode)
                        done -> null
                        else -> moneySubunits(myRow.remainingSubunits, currencyCode)
                    },
                fraction = myRow.paidSubunits.toFloat() / myRow.owedSubunits,
                caption = "Paid ${moneySubunits(
                    myRow.paidSubunits,
                    currencyCode,
                )} of your ${moneySubunits(myRow.owedSubunits, currencyCode)} share",
                done = done,
                credit = overpaid,
            )
        }
    // Owing → calm blue (action); fully settled → green (done); overpaid → warm amber "in credit" — a
    // white band with an orange outline so only the number pops (per design), not a filled block.
    val accent =
        when {
            state.credit -> c.credit
            state.done -> c.settled
            else -> c.blue
        }
    val fill =
        when {
            state.credit -> c.page
            state.done -> c.settledTint
            else -> c.blueTint
        }
    val track =
        when {
            state.credit -> c.creditTint
            state.done -> c.settledTint2
            else -> c.blueTint2
        }
    val bandShape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(bandShape)
            .background(fill)
            .then(if (state.credit) Modifier.border(1.5.dp, c.credit, bandShape) else Modifier)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.done) EvIcon(EvIcons.Check, size = 16.dp, tint = accent)
                // Label stays ink on the credit band — only the amount is orange.
                Text(state.label, color = if (state.credit) c.ink else accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            state.amount?.let {
                Text(it, color = accent, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, fontFamily = EvenlyTheme.monoFamily)
            }
        }
        // Contrast bar: a tint-2 track + accent fill, legible on the band.
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(track),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(state.fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(99.dp))
                    .background(accent),
            )
        }
        Text(state.caption, color = c.ink2, fontSize = 12.sp, fontFamily = EvenlyTheme.monoFamily)
    }
}

private data class BandState(
    val label: String,
    val amount: String?,
    val fraction: Float,
    val caption: String,
    val done: Boolean,
    val credit: Boolean = false,
)

@Composable
private fun EvTopBarDetail(
    title: String,
    sub: String?,
    onBack: () -> Unit,
    onMore: (() -> Unit)?,
) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxWidth().background(c.page)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EvIconButton(EvIcons.Back, onBack)
            Column(Modifier.weight(1f)) {
                if (title.isNotEmpty()) Text(title, color = c.ink, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (sub != null) Text(sub, color = c.ink2, fontSize = 12.sp)
            }
            if (onMore != null) EvIconButton(EvIcons.More, onMore)
        }
        EvDivider()
    }
}

@Composable
private fun CommentBubble(
    name: String,
    text: String,
    time: String,
    me: Boolean,
) {
    val c = EvenlyTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
        if (!me) EvAvatar(name, size = AvatarSize.Sm)
        Column(
            Modifier.padding(horizontal = 8.dp).widthIn(max = 250.dp),
            horizontalAlignment = if (me) Alignment.End else Alignment.Start,
        ) {
            // Mine: solid blue. Others': white with a hairline so the bubble lifts off the surface bg
            // (which is itself c.surface) instead of melting into it.
            val bubbleShape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .clip(bubbleShape)
                    .background(if (me) c.blue else c.page)
                    .then(if (me) Modifier else Modifier.border(1.dp, c.border, bubbleShape))
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) {
                Text(text, color = if (me) c.onAccent else c.ink, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Text("$name · $time", color = c.ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (me) EvAvatar(name, me = true, size = AvatarSize.Sm)
    }
}

@Composable
private fun Collapsible(
    title: String,
    icon: ImageVector,
    sub: String,
    content: @Composable () -> Unit,
) {
    val c = EvenlyTheme.colors
    var open by remember { mutableStateOf(false) }
    EvCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    open = !open
                }.padding(
                    horizontal = 16.dp,
                    vertical = 14.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EvIcon(icon, size = 20.dp, tint = c.ink2)
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(sub, color = c.ink2, fontSize = 12.sp)
            EvIcon(if (open) EvIcons.ChevU else EvIcons.ChevD, size = 16.dp, tint = c.ink3)
        }
        if (open) {
            Box(Modifier.topHairline(c.border).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
                content()
            }
        }
    }
}

/** A single receipt thumbnail: image via Coil, or a file tile for PDFs / not-yet-resolved URLs. */
@Composable
private fun ReceiptThumb(
    receipt: ReceiptUi,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val box =
        Modifier
            .size(
                width = 84.dp,
                height = 108.dp,
            ).clip(shape)
            .background(c.surface)
            .border(1.dp, c.border, shape)
            .clickable(onClick = onClick)
    if (receipt.isPdf || receipt.url == null) {
        Column(box, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            EvIcon(EvIcons.Receipt, size = 22.dp, tint = c.ink3)
            Text(
                if (receipt.isPdf) "PDF" else "receipt",
                color = c.ink3,
                fontSize = 10.sp,
                fontFamily = EvenlyTheme.monoFamily,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    } else {
        AsyncImage(
            model = receipt.url,
            contentDescription = "Receipt",
            modifier = box,
            contentScale = ContentScale.Crop,
        )
    }
}

/** The trailing "Add" tile in the receipts strip; opens the Photos / Files / Camera source sheet. */
@Composable
private fun AddReceiptTile(onClick: () -> Unit) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .size(width = 84.dp, height = 108.dp)
            .clip(shape)
            .background(c.page)
            .border(1.dp, c.borderStrong, shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EvIcon(EvIcons.Plus, size = 22.dp, tint = c.blueText)
        Text("Add", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * A receipt that's still uploading (or has failed): the local thumbnail with a centered determinate
 * progress ring over a scrim. A failed upload swaps the ring for a tap-to-retry alert + a corner cancel.
 */
@Composable
private fun UploadingThumb(
    upload: ReceiptUploadUi,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val box =
        Modifier
            .size(width = 84.dp, height = 108.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.border, shape)
    Box(box, contentAlignment = Alignment.Center) {
        // Base thumbnail: the local image, or a file glyph for PDFs.
        if (upload.isPdf || upload.model == null) {
            EvIcon(EvIcons.Receipt, size = 22.dp, tint = c.ink3)
        } else {
            AsyncImage(
                model = upload.model,
                contentDescription = "Receipt",
                modifier = Modifier.fillMaxSize().clip(shape),
                contentScale = ContentScale.Crop,
            )
        }
        // Dim scrim so the ring/percent reads over any image.
        Box(Modifier.fillMaxSize().background(c.ink.copy(alpha = 0.35f)))
        if (upload.failed) {
            Column(
                Modifier.fillMaxSize().clickable(onClick = onRetry),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EvIcon(EvIcons.Reload, size = 20.dp, tint = androidx.compose.ui.graphics.Color.White)
                Text(
                    "Retry",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Corner cancel.
            Box(
                Modifier
                    .align(
                        Alignment.TopEnd,
                    ).padding(
                        4.dp,
                    ).size(22.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(c.ink.copy(alpha = 0.55f))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                EvIcon(EvIcons.Close, size = 13.dp, tint = androidx.compose.ui.graphics.Color.White)
            }
        } else {
            CircularProgressIndicator(
                progress = { upload.fraction },
                modifier = Modifier.size(34.dp),
                color = androidx.compose.ui.graphics.Color.White,
                trackColor =
                    androidx.compose.ui.graphics.Color.White
                        .copy(alpha = 0.3f),
                strokeWidth = 3.dp,
            )
            Text(
                "${(upload.fraction * 100).toInt()}%",
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = EvenlyTheme.monoFamily,
            )
        }
    }
}

@Composable
private fun OverflowRow(
    icon: ImageVector,
    label: String,
    iconTint: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EvIcon(icon, size = 20.dp, tint = iconTint)
        Text(label, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ErrorContent(
    onReload: () -> Unit,
    onSendFeedback: (() -> Unit)?,
) {
    val c = EvenlyTheme.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.dangerTint), contentAlignment = Alignment.Center) {
            EvIcon(EvIcons.Alert, size = 30.dp, tint = c.danger)
        }
        Text("Couldn't load this expense", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            "Something went wrong on our side. Check your connection and try again.",
            color = c.ink2,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 240.dp),
        )
        Column(Modifier.widthIn(max = 240.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EvButton("Reload", onReload, leadingIcon = EvIcons.Reload)
            onSendFeedback?.let {
                EvButton("Send feedback", it, variant = ButtonVariant.Text, leadingIcon = EvIcons.Mail)
            }
        }
    }
}

private val DemoSplit =
    listOf(
        DetailShareUi("You", owedSubunits = 2400, paidSubunits = 0, remainingSubunits = 2400, me = true),
        DetailShareUi("Andrew", owedSubunits = 2400, paidSubunits = 2400, remainingSubunits = 0, payer = true),
        DetailShareUi("Bob", owedSubunits = 2400, paidSubunits = 0, remainingSubunits = 2400),
        DetailShareUi("Maya", owedSubunits = 2400, paidSubunits = 1600, remainingSubunits = 800),
    )

private val DemoComments =
    listOf(
        CommentUi("1", "Maya", "I already sent Andrew \$16 in cash 🙌", "2h", me = false),
        CommentUi("2", "You", "Nice — I'll settle my half tonight.", "1h", me = true),
    )

private val DemoHistory =
    listOf(
        HistoryUi("Andrew added this expense", "May 23"),
        HistoryUi("Maya commented", "2h"),
        HistoryUi("You settled a share", "1h"),
    )

@Preview
@Composable
private fun ExpenseDetailPreview() {
    EvenlyTheme { ExpenseDetailScreen() }
}
