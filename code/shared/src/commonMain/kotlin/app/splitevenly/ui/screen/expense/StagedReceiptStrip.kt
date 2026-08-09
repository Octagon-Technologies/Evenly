package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
)

/**
 * The receipt strip shared by both editors: the divide flow's optional attachment and the itemized flow's
 * scanned pages. Tiles show the actual image and open the full-screen viewer on tap, because a receipt you
 * cannot look at is the one thing a receipt is for. Only the caption differs between the two callers.
 */
@Composable
fun PickedReceiptStrip(
    receipts: List<PickedReceiptUi>,
    label: String,
    caption: String?,
    onAddClick: (() -> Unit)?,
    onRemoveReceipt: (Int) -> Unit,
    onOpenReceipt: (Int) -> Unit,
) {
    val c = EvenlyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (onAddClick != null && receipts.isEmpty()) {
                Text("optional", color = c.ink3, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            receipts.forEachIndexed { i, r ->
                val shape = RoundedCornerShape(8.dp)
                Box(Modifier.size(width = 56.dp, height = 72.dp)) {
                    Box(
                        Modifier.matchParentSize().clip(shape).background(c.blueTint).border(1.dp, c.border, shape)
                            .clickable { onOpenReceipt(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (r.model == null) {
                            EvIcon(if (r.isPdf) EvIcons.Receipt else EvIcons.Image, size = 22.dp, tint = c.blueText)
                        } else {
                            AsyncImage(
                                model = r.model,
                                contentDescription = "Receipt",
                                modifier = Modifier.fillMaxSize().clip(shape),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp).clip(CircleShape)
                            .background(c.surface).border(1.dp, c.borderStrong, CircleShape)
                            .clickable { onRemoveReceipt(i) },
                        contentAlignment = Alignment.Center,
                    ) { EvIcon(EvIcons.Close, size = 11.dp, tint = c.ink2) }
                }
            }
            if (onAddClick != null) {
                Column(
                    Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(8.dp))
                        .border(1.dp, c.borderStrong, RoundedCornerShape(8.dp)).clickable(onClick = onAddClick),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    EvIcon(EvIcons.Plus, size = 18.dp, tint = c.blueText)
                    Text("Add", color = c.blueText, fontSize = 11.sp)
                }
            }
        }
        if (caption != null && receipts.isNotEmpty()) {
            Text(caption, color = c.ink3, fontSize = 12.sp)
        }
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
        receipts = receipts.map { ViewerReceipt(id = it.id, url = "file://${it.localPath}", isPdf = it.isPdf) },
        initialIndex = initialIndex,
        loadPdfPageCount = loadPdfPageCount,
        renderPdfPage = renderPdfPage,
        onClose = onClose,
    )
}
