package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import app.splitevenly.data.upload.ReceiptUploadManager
import app.splitevenly.data.upload.StagedReceipt
import app.splitevenly.platform.PdfRasterizer
import app.splitevenly.ui.screen.expense.PickedReceiptUi

/** UI view of a staged receipt; the strip and viewer render it straight off disk. */
internal fun StagedReceipt.toUi(): PickedReceiptUi =
    PickedReceiptUi(id = id, localPath = localPath, model = imageModel, isPdf = isPdf)

/**
 * PDF rendering for receipts that exist only in the sandbox, shared by the two editors that can hold one.
 * The viewer addresses pages by the `file://` url it was handed, so unwrap that back into a path and read
 * the bytes through the manager rather than over the network.
 */
internal class StagedPdfRenderers(
    val pageCount: suspend (url: String) -> Int,
    val renderPage: suspend (url: String, page: Int, widthPx: Int) -> ImageBitmap?,
)

@Composable
internal fun rememberStagedPdfRenderers(manager: ReceiptUploadManager?): StagedPdfRenderers {
    val rasterizer = remember { PdfRasterizer() }
    return remember(manager, rasterizer) {
        StagedPdfRenderers(
            pageCount = { url -> manager?.readStagedAt(url)?.let { rasterizer.pageCount(it) } ?: 0 },
            renderPage = { url, page, width -> manager?.readStagedAt(url)?.let { rasterizer.renderPage(it, page, width) } },
        )
    }
}

private suspend fun ReceiptUploadManager.readStagedAt(url: String): ByteArray? =
    readStaged(url.removePrefix("file://"))
