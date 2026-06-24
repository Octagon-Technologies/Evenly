package da.chelimo.sharecost.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import coil3.compose.AsyncImage
import da.chelimo.sharecost.ui.components.ScTextField
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
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
import da.chelimo.sharecost.ui.components.ScDivider
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScModalScaffold
import da.chelimo.sharecost.ui.components.ScProgress
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScSkeleton
import da.chelimo.sharecost.ui.components.ScSkeletonRow
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.platform.PickSource
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

enum class ExpenseDetailState { Loading, Content, Error }

data class DetailShareUi(
    val name: String,
    val owedSubunits: Long,
    val paidSubunits: Long,
    val remainingSubunits: Long,
    val me: Boolean = false,
    val payer: Boolean = false,
)

/** A receipt thumbnail (F5). [url] null = still uploading / unavailable; [isPdf] renders a file tile. */
data class ReceiptUi(val id: String, val url: String?, val isPdf: Boolean = false)

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
data class CommentUi(val id: String, val authorName: String, val body: String, val timeLabel: String, val me: Boolean)

/** One activity-log entry (F5): a rendered sentence + a short relative stamp. */
data class HistoryUi(val text: String, val timeLabel: String)

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
    onOpenReceipt: (ReceiptUi) -> Unit = {},
    onBack: () -> Unit = {},
    onSettleThis: () -> Unit = {},
    onReload: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var overflow by remember { mutableStateOf(false) }
    var showReceiptSource by remember { mutableStateOf(false) }
    val split = splitRows
    // Tapping empty space anywhere on the page drops focus from the comment field (and hides the
    // keyboard). Child clickables consume their own taps, so only taps on blank areas reach this.
    val focusManager = LocalFocusManager.current

    Column(Modifier.fillMaxSize().background(if (state == ExpenseDetailState.Content) c.surface else c.page).systemBarsPadding()) {
        ScTopBarDetail(
            title = if (state == ExpenseDetailState.Error) "" else title,
            sub = if (state == ExpenseDetailState.Content) category.ifBlank { null } else null,
            onBack = onBack,
            onMore = if (state == ExpenseDetailState.Content) ({ overflow = true }) else null,
        )
        when (state) {
            ExpenseDetailState.Loading -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ScSkeleton(height = 132.dp, radius = 16.dp)
                ScSkeleton(width = 140.dp, height = 14.dp)
                ScSkeletonRow(); ScSkeletonRow(); ScSkeletonRow()
            }
            ExpenseDetailState.Error -> ErrorContent(onReload = onReload)
            ExpenseDetailState.Content -> Column(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // header card
                ScCard(padded = true) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            if (remainingSubunits != amountSubunits) {
                                Text(moneySubunits(amountSubunits, currencyCode), style = ShareCostTheme.amounts.original.copy(fontSize = 14.sp), color = c.ink3)
                            }
                            Text(moneySubunits(remainingSubunits, currencyCode), style = ShareCostTheme.amounts.hero, color = c.ink)
                            Text("remaining of original", color = c.ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (category.isNotBlank()) {
                            ScChip(category.substringBefore(" "), variant = ChipVariant.Blue, leadingIcon = ScIcons.Food, large = true)
                        }
                    }
                    ScDivider(Modifier.padding(vertical = 14.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScAvatar(payerName, me = payerName == "You", size = AvatarSize.Sm)
                            Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(payerName) }; append(" paid") }, color = c.ink, fontSize = 14.sp)
                        }
                        Text(dateLabel, color = c.ink2, fontSize = 12.sp)
                    }
                }

                // receipts
                Column {
                    ScSectionLabel("Receipts")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        receipts.forEach { r -> ReceiptThumb(r, onClick = { onOpenReceipt(r) }) }
                        // In-flight uploads render right after the published ones, each with its own ring.
                        pendingUploads.forEach { u ->
                            UploadingThumb(u, onRetry = { onRetryUpload(u.id) }, onCancel = { onCancelUpload(u.id) })
                        }
                        AddReceiptTile(onClick = { showReceiptSource = true })
                    }
                }

                // split breakdown
                Column {
                    ScSectionLabel(splitLabel)
                    ScCard {
                        split.forEachIndexed { i, s ->
                            // You can't settle your own share when you're the one who paid.
                            val canSettle = s.me && !s.payer && s.remainingSubunits > 0
                            val cleared = s.remainingSubunits == 0L
                            Row(
                                Modifier.fillMaxWidth().then(if (i > 0) Modifier.topHairline(c.border) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ScAvatar(s.name, me = s.me, size = AvatarSize.Sm)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    // name + remaining amount, paired on one line
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            buildAnnotatedString {
                                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.name) }
                                                if (s.payer) withStyle(SpanStyle(color = c.ink2, fontWeight = FontWeight.Medium)) { append(" · paid") }
                                                if (s.me) withStyle(SpanStyle(color = c.ink2, fontWeight = FontWeight.Medium)) { append(" · you") }
                                            },
                                            color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f),
                                        )
                                        Text(moneySubunits(s.remainingSubunits, currencyCode), color = if (cleared) c.ink3 else c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                                    }
                                    // progress bar spanning the full content width
                                    ScProgress(if (s.owedSubunits > 0) s.paidSubunits.toFloat() / s.owedSubunits else 0f, Modifier.fillMaxWidth())
                                    // caption, with the settle action tucked inline on the right for your own share
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("Paid ${moneySubunits(s.paidSubunits, currencyCode)} of ${moneySubunits(s.owedSubunits, currencyCode)}", color = c.ink3, fontSize = 12.sp, fontFamily = ShareCostTheme.monoFamily, modifier = Modifier.weight(1f))
                                        if (canSettle) ScButton("Settle this", onSettleThis, variant = ButtonVariant.Secondary, small = true, fillMaxWidth = false)
                                    }
                                }
                            }
                        }
                    }
                }

                // comments
                Column {
                    ScSectionLabel("Comments")
                    if (comments.isEmpty()) {
                        Text("No comments yet — start the conversation.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            comments.forEach { cm -> CommentBubble(cm.authorName, cm.body, cm.timeLabel, me = cm.me) }
                        }
                    }
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScTextField(
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
                            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (canSend) c.blue else c.blueTint2)
                                .clickable(enabled = canSend, onClick = onSendComment),
                            contentAlignment = Alignment.Center,
                        ) {
                            ScIcon(ScIcons.Send, size = 18.dp, tint = if (canSend) c.onAccent else c.disabledInk)
                        }
                    }
                }

                Collapsible("History", ScIcons.History, "${historyEvents.size} ${if (historyEvents.size == 1) "event" else "events"}") {
                    if (historyEvents.isEmpty()) {
                        Text("No activity recorded yet.", color = c.ink2, fontSize = 12.sp)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            historyEvents.forEach { ev ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                    Text(ev.text, color = c.ink2, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
                                    Text(ev.timeLabel, color = c.ink3, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                Collapsible("Refunds", ScIcons.Refund, "None yet") {
                    Text("No refunds on this expense.", color = c.ink2, fontSize = 12.sp)
                }
            }
        }
    }

    if (overflow) {
        ScModalScaffold(onDismiss = { overflow = false }) {
            OverflowRow(ScIcons.Edit, "Edit", c.ink2, c.ink) { overflow = false; onEdit() }
            listOf(ScIcons.Refund to "Issue refund", ScIcons.Camera to "Add receipt", ScIcons.Share to "Share").forEach { (ic, label) ->
                OverflowRow(ic, label, c.ink2, c.ink) {
                    overflow = false
                    if (label == "Add receipt") showReceiptSource = true
                }
            }
            OverflowRow(ScIcons.Trash, "Delete", c.danger, c.danger) { overflow = false; onDelete() }
        }
    }

    if (showReceiptSource) {
        ScModalScaffold(onDismiss = { showReceiptSource = false }) {
            Text("Add a receipt", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            Text("Receipts hide in different places — pick from anywhere.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            OverflowRow(ScIcons.Image, "Photos", c.blue, c.ink) { showReceiptSource = false; onPickReceipts(PickSource.Photos) }
            OverflowRow(ScIcons.Archive, "Files", c.blue, c.ink) { showReceiptSource = false; onPickReceipts(PickSource.Files) }
            OverflowRow(ScIcons.Camera, "Camera", c.blue, c.ink) { showReceiptSource = false; onPickReceipts(PickSource.Camera) }
        }
    }
}

@Composable
private fun ScTopBarDetail(title: String, sub: String?, onBack: () -> Unit, onMore: (() -> Unit)?) {
    val c = ShareCostTheme.colors
    Column(Modifier.fillMaxWidth().background(c.page)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ScIconButton(ScIcons.Back, onBack)
            Column(Modifier.weight(1f)) {
                if (title.isNotEmpty()) Text(title, color = c.ink, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (sub != null) Text(sub, color = c.ink2, fontSize = 12.sp)
            }
            if (onMore != null) ScIconButton(ScIcons.More, onMore)
        }
        ScDivider()
    }
}

@Composable
private fun CommentBubble(name: String, text: String, time: String, me: Boolean) {
    val c = ShareCostTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
        if (!me) ScAvatar(name, size = AvatarSize.Sm)
        Column(Modifier.padding(horizontal = 8.dp).widthIn(max = 250.dp), horizontalAlignment = if (me) Alignment.End else Alignment.Start) {
            // Mine: solid blue. Others': white with a hairline so the bubble lifts off the surface bg
            // (which is itself c.surface) instead of melting into it.
            val bubbleShape = RoundedCornerShape(14.dp)
            Box(
                Modifier.clip(bubbleShape)
                    .background(if (me) c.blue else c.page)
                    .then(if (me) Modifier else Modifier.border(1.dp, c.border, bubbleShape))
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) {
                Text(text, color = if (me) c.onAccent else c.ink, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Text("$name · $time", color = c.ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (me) ScAvatar(name, me = true, size = AvatarSize.Sm)
    }
}

@Composable
private fun Collapsible(title: String, icon: ImageVector, sub: String, content: @Composable () -> Unit) {
    val c = ShareCostTheme.colors
    var open by remember { mutableStateOf(false) }
    ScCard {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ScIcon(icon, size = 20.dp, tint = c.ink2)
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(sub, color = c.ink2, fontSize = 12.sp)
            ScIcon(if (open) ScIcons.ChevU else ScIcons.ChevD, size = 16.dp, tint = c.ink3)
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
private fun ReceiptThumb(receipt: ReceiptUi, onClick: () -> Unit) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val box = Modifier.size(width = 84.dp, height = 108.dp).clip(shape).background(c.surface).border(1.dp, c.border, shape).clickable(onClick = onClick)
    if (receipt.isPdf || receipt.url == null) {
        Column(box, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            ScIcon(ScIcons.Receipt, size = 22.dp, tint = c.ink3)
            Text(if (receipt.isPdf) "PDF" else "receipt", color = c.ink3, fontSize = 10.sp, fontFamily = ShareCostTheme.monoFamily, modifier = Modifier.padding(top = 4.dp))
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
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.size(width = 84.dp, height = 108.dp).clip(shape).background(c.page)
            .border(1.dp, c.borderStrong, shape).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ScIcon(ScIcons.Plus, size = 22.dp, tint = c.blue)
        Text("Add", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * A receipt that's still uploading (or has failed): the local thumbnail with a centered determinate
 * progress ring over a scrim. A failed upload swaps the ring for a tap-to-retry alert + a corner cancel.
 */
@Composable
private fun UploadingThumb(upload: ReceiptUploadUi, onRetry: () -> Unit, onCancel: () -> Unit) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val box = Modifier.size(width = 84.dp, height = 108.dp).clip(shape).background(c.surface).border(1.dp, c.border, shape)
    Box(box, contentAlignment = Alignment.Center) {
        // Base thumbnail: the local image, or a file glyph for PDFs.
        if (upload.isPdf || upload.model == null) {
            ScIcon(ScIcons.Receipt, size = 22.dp, tint = c.ink3)
        } else {
            AsyncImage(model = upload.model, contentDescription = "Receipt", modifier = Modifier.fillMaxSize().clip(shape), contentScale = ContentScale.Crop)
        }
        // Dim scrim so the ring/percent reads over any image.
        Box(Modifier.fillMaxSize().background(c.ink.copy(alpha = 0.35f)))
        if (upload.failed) {
            Column(
                Modifier.fillMaxSize().clickable(onClick = onRetry),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ScIcon(ScIcons.Reload, size = 20.dp, tint = androidx.compose.ui.graphics.Color.White)
                Text("Retry", color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
            // Corner cancel.
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(RoundedCornerShape(99.dp)).background(c.ink.copy(alpha = 0.55f)).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
                ScIcon(ScIcons.Close, size = 13.dp, tint = androidx.compose.ui.graphics.Color.White)
            }
        } else {
            CircularProgressIndicator(
                progress = { upload.fraction },
                modifier = Modifier.size(34.dp),
                color = androidx.compose.ui.graphics.Color.White,
                trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f),
                strokeWidth = 3.dp,
            )
            Text("${(upload.fraction * 100).toInt()}%", color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
        }
    }
}

@Composable
private fun OverflowRow(icon: ImageVector, label: String, iconTint: androidx.compose.ui.graphics.Color, textColor: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ScIcon(icon, size = 20.dp, tint = iconTint)
        Text(label, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ErrorContent(onReload: () -> Unit) {
    val c = ShareCostTheme.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.dangerTint), contentAlignment = Alignment.Center) {
            ScIcon(ScIcons.Alert, size = 30.dp, tint = c.danger)
        }
        Text("Couldn't load this expense", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text("Something went wrong on our side. Check your connection and try again.", color = c.ink2, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 240.dp))
        Column(Modifier.widthIn(max = 240.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ScButton("Reload", onReload, leadingIcon = ScIcons.Reload)
            ScButton("Send feedback", {}, variant = ButtonVariant.Text, leadingIcon = ScIcons.Mail)
        }
    }
}

private val DemoSplit = listOf(
    DetailShareUi("You", owedSubunits = 2400, paidSubunits = 0, remainingSubunits = 2400, me = true),
    DetailShareUi("Andrew", owedSubunits = 2400, paidSubunits = 2400, remainingSubunits = 0, payer = true),
    DetailShareUi("Bob", owedSubunits = 2400, paidSubunits = 0, remainingSubunits = 2400),
    DetailShareUi("Maya", owedSubunits = 2400, paidSubunits = 1600, remainingSubunits = 800),
)

private val DemoComments = listOf(
    CommentUi("1", "Maya", "I already sent Andrew \$16 in cash 🙌", "2h", me = false),
    CommentUi("2", "You", "Nice — I'll settle my half tonight.", "1h", me = true),
)

private val DemoHistory = listOf(
    HistoryUi("Andrew added this expense", "May 23"),
    HistoryUi("Maya commented", "2h"),
    HistoryUi("You settled a share", "1h"),
)

@Preview
@Composable
private fun ExpenseDetailPreview() {
    ShareCostTheme { ExpenseDetailScreen() }
}
