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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme
import coil3.compose.AsyncImage

/**
 * A receipt the user has picked but not yet saved. The bytes are already compressed and sitting in the
 * sandbox (`ReceiptUploadManager.stage`), which is what lets the editor show the real photo instead of a
 * placeholder: [model] is a `file://` path Coil renders straight off disk.
 */
data class PickedReceiptUi(
    val id: String,
    val localPath: String,
    val model: String?,
    val isPdf: Boolean,
    /** Set for a receipt already saved to the bill: the viewer opens this instead of a local file. */
    val remoteUrl: String? = null,
    /** False for a saved receipt, which the bill editor shows but does not delete. */
    val removable: Boolean = true,
)

/** How long a removed receipt can be brought back. Routes wait a little longer before deleting for good. */
const val RECEIPT_UNDO_MS = 5_000L

/** A removal the strip can take back: called with no arguments, it puts the receipt back where it was. */
typealias UndoRemoval = () -> Unit

/**
 * The receipt list shared by both editors: the divide flow's optional attachment and the itemized flow's
 * scanned pages. Each receipt is a full-width row with its real thumbnail (tap to view) and a delete button
 * big enough to hit. Delete takes effect at once, so a save in the next few seconds never keeps it, and the
 * row turns into "Receipt removed · Undo" for [RECEIPT_UNDO_MS]: [onRemoveReceipt] returns the way back.
 */
@Composable
fun PickedReceiptStrip(
    receipts: List<PickedReceiptUi>,
    label: String,
    caption: String?,
    onAddClick: (() -> Unit)?,
    onRemoveReceipt: (Int) -> UndoRemoval,
    onOpenReceipt: (Int) -> Unit,
) {
    val c = EvenlyTheme.colors
    // The last removal, its row position, and how to undo it. One at a time; a new one ends the last.
    var removed by remember { mutableStateOf<Triple<PickedReceiptUi, Int, UndoRemoval>?>(null) }
    LaunchedEffect(removed) {
        if (removed != null) {
            delay(RECEIPT_UNDO_MS)
            removed = null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (onAddClick != null && receipts.isEmpty() && removed == null) {
                Text("optional", color = c.ink3, fontSize = 12.sp)
            }
        }
        val rows: List<PickedReceiptUi?> = receipts.toMutableList<PickedReceiptUi?>().also { list ->
            removed?.let { (_, at, _) -> list.add(at.coerceIn(0, list.size), null) }
        }
        var index = 0
        rows.forEach { r ->
            if (r == null) {
                UndoRow(onUndo = {
                    removed?.third?.invoke()
                    removed = null
                })
            } else {
                val i = index++
                ReceiptRow(
                    receipt = r,
                    title = receiptTitle(r, i, receipts.size),
                    caption = if (r.remoteUrl != null) "Saved" else caption,
                    onOpen = { onOpenReceipt(i) },
                    onRemove = if (r.removable) {
                        { removed = Triple(r, i, onRemoveReceipt(i)) }
                    } else null,
                )
            }
        }
        if (onAddClick != null) {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).border(1.dp, c.borderStrong, shape).clickable(onClick = onAddClick)
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EvIcon(EvIcons.Plus, size = 16.dp, tint = c.blueText)
                Text(
                    if (receipts.isEmpty()) " Add a receipt" else " Add another",
                    color = c.blueText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun receiptTitle(r: PickedReceiptUi, index: Int, count: Int): String =
    when {
        count > 1 -> "Page ${index + 1}"
        r.isPdf -> "PDF"
        else -> "Photo"
    }

@Composable
private fun ReceiptRow(
    receipt: PickedReceiptUi,
    title: String,
    caption: String?,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape)
            .clickable(onClick = onOpen).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val thumb = RoundedCornerShape(7.dp)
        Box(
            Modifier.size(width = 44.dp, height = 56.dp).clip(thumb).background(c.blueTint).border(1.dp, c.border, thumb),
            contentAlignment = Alignment.Center,
        ) {
            if (receipt.model == null) {
                EvIcon(if (receipt.isPdf) EvIcons.Receipt else EvIcons.Image, size = 20.dp, tint = c.blueText)
            } else {
                AsyncImage(
                    model = receipt.model,
                    contentDescription = "Receipt",
                    modifier = Modifier.fillMaxSize().clip(thumb),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (caption != null) Text(caption, color = c.ink3, fontSize = 12.sp)
        }
        if (onRemove != null) {
            val btn = RoundedCornerShape(10.dp)
            Box(
                Modifier.size(40.dp).clip(btn).border(1.dp, c.borderStrong, btn).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { EvIcon(EvIcons.Trash, size = 18.dp, tint = c.ink2, contentDescription = "Remove receipt") }
        }
    }
}

@Composable
private fun UndoRow(onUndo: () -> Unit) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, c.border, shape).padding(start = 14.dp, end = 4.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Receipt removed", color = c.ink2, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            "Undo",
            color = c.blueText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onUndo)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * The full-screen viewer over receipts that only exist on this device. Reuses [ReceiptViewerScreen] by
 * pointing it at `file://` paths, so a staged receipt pinches and swipes exactly like a saved one.
 */
@Composable
fun StagedReceiptViewer(
    receipts: List<PickedReceiptUi>,
    initialIndex: Int,
    loadPdfPageCount: suspend (url: String) -> Int,
    renderPdfPage: suspend (url: String, page: Int, widthPx: Int) -> ImageBitmap?,
    onClose: () -> Unit,
) {
    ReceiptViewerScreen(
        receipts = receipts.map { ViewerReceipt(id = it.id, url = it.remoteUrl ?: "file://${it.localPath}", isPdf = it.isPdf) },
        initialIndex = initialIndex,
        loadPdfPageCount = loadPdfPageCount,
        renderPdfPage = renderPdfPage,
        onClose = onClose,
    )
}
