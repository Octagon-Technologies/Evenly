package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.receipt.ReceiptSummary
import app.splitevenly.domain.receipt.sourceLabel
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** Where the "Split one amount" receipt quick read stands. Driven by the route, rendered by the editor. */
sealed interface ReceiptReadUi {
    /** No read in progress or done (no receipt, or reading isn't available). */
    data object None : ReceiptReadUi

    data object Reading : ReceiptReadUi

    /** [suggestedTitle] is the fixed-table name ("Lunch at Sweetgreen"), worked out by the route. */
    data class Read(val summary: ReceiptSummary, val suggestedTitle: String?) : ReceiptReadUi

    data object Unreadable : ReceiptReadUi
}

/**
 * The single receipt on "Split one amount". Attaching one starts a quick read; this row shows it reading,
 * then who was paid and what kind of receipt it was. The amount is deliberately not repeated here, since
 * the read puts it in the amount box. One receipt per expense on this path, so the action is "Replace".
 */
@Composable
internal fun QuickReadReceiptSection(
    receipt: PickedReceiptUi?,
    read: ReceiptReadUi,
    onAdd: () -> Unit,
    onRemove: () -> UndoRemoval,
    onOpen: () -> Unit,
) {
    val c = EvenlyTheme.colors
    var undo by remember { mutableStateOf<UndoRemoval?>(null) }
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(RECEIPT_UNDO_MS)
            undo = null
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Receipt", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (receipt == null && undo == null) Text("optional", color = c.ink3, fontSize = 12.sp)
        }
        when {
            undo != null ->
                Row(
                    Modifier.fillMaxWidth().clip(shape).border(1.dp, c.border, shape).padding(start = 14.dp, end = 4.dp)
                        .heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Receipt removed", color = c.ink2, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    TextAction("Undo") {
                        undo?.invoke()
                        undo = null
                    }
                }

            receipt == null -> {
                AddReceiptCard(onAdd)
                OrFillItIn()
            }

            else -> {
                val (title, caption) = rowText(read)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape)
                        .clickable(onClick = onOpen).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Thumb(receipt, reading = read == ReceiptReadUi.Reading)
                    Column(Modifier.weight(1f)) {
                        Text(title, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (caption != null) Text(caption, color = c.ink3, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    TextAction("Replace", onClick = onAdd)
                    val btn = RoundedCornerShape(10.dp)
                    Box(
                        Modifier.size(40.dp).clip(btn).border(1.dp, c.borderStrong, btn).clickable { undo = onRemove() },
                        contentAlignment = Alignment.Center,
                    ) { EvIcon(EvIcons.Trash, size = 18.dp, tint = c.ink2, contentDescription = "Remove receipt") }
                }
            }
        }
    }
}

/** The empty receipt slot at the top of the editor: a dashed drop-target card, so it reads as the place to start. */
@Composable
private fun AddReceiptCard(onAdd: () -> Unit) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.blueTint)
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = c.blueText,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(14.dp.toPx() - stroke / 2),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .clickable(role = Role.Button, onClick = onAdd)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(c.blue), contentAlignment = Alignment.Center) {
            EvIcon(EvIcons.Receipt, size = 20.dp, tint = c.onAccent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Add a receipt", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Automatically fills in expense details.", color = c.ink2, fontSize = 12.sp, lineHeight = 16.sp)
        }
        EvIcon(EvIcons.Plus, size = 16.dp, tint = c.blueText)
    }
}

/** Says the receipt is a shortcut, not a step: the fields below work just as well typed by hand. */
@Composable
private fun OrFillItIn() {
    val c = EvenlyTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.border))
        Text("or fill it in", color = c.ink3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.border))
    }
}

private fun rowText(read: ReceiptReadUi): Pair<String, String?> =
    when (read) {
        ReceiptReadUi.Reading -> "Reading receipt…" to null
        is ReceiptReadUi.Read ->
            (read.summary.merchantName ?: read.suggestedTitle ?: "Receipt attached") to read.summary.sourceLabel()
        ReceiptReadUi.Unreadable -> "Receipt attached" to "Unable to read receipt. You can fill in the details manually."
        ReceiptReadUi.None -> "Receipt attached" to "Uploads when you save."
    }

@Composable
private fun Thumb(
    receipt: PickedReceiptUi,
    reading: Boolean,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.size(width = 44.dp, height = 56.dp).clip(shape).background(c.blueTint).border(1.dp, c.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (receipt.model == null) {
            EvIcon(if (receipt.isPdf) EvIcons.Receipt else EvIcons.Image, size = 20.dp, tint = c.blueText)
        } else {
            AsyncImage(
                model = receipt.model,
                contentDescription = "Receipt",
                modifier = Modifier.fillMaxSize().clip(shape),
                contentScale = ContentScale.Crop,
            )
        }
        if (reading) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun TextAction(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = EvenlyTheme.colors.blueText,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
    )
}
