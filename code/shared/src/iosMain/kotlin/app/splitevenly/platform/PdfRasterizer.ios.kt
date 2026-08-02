@file:OptIn(ExperimentalForeignApi::class)

package app.splitevenly.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGRectGetHeight
import platform.CoreGraphics.CGRectGetWidth
import platform.CoreGraphics.CGSizeMake
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.PDFKit.PDFDocument
import platform.UIKit.UIImagePNGRepresentation

/**
 * iOS [PdfRasterizer] over PDFKit. [PDFDocument] reads straight from the bytes (no temp file), and
 * [platform.PDFKit.PDFPage.thumbnailOfSize] rasterizes a page to a [platform.UIKit.UIImage] at the size we
 * ask for. We re-encode that to PNG and hand it to skia to produce a Compose [ImageBitmap], mirroring how
 * the rest of the iOS image path bridges UIImage → Compose.
 */
actual class PdfRasterizer actual constructor() {

    actual suspend fun pageCount(bytes: ByteArray): Int = withContext(Dispatchers.Default) {
        document(bytes)?.pageCount?.toInt() ?: 0
    }

    actual suspend fun renderPage(bytes: ByteArray, page: Int, targetWidthPx: Int): ImageBitmap? =
        withContext(Dispatchers.Default) {
            val doc = document(bytes) ?: return@withContext null
            if (page < 0 || page >= doc.pageCount.toInt()) return@withContext null
            val pdfPage = doc.pageAtIndex(page.toULong()) ?: return@withContext null
            val bounds = pdfPage.boundsForBox(kPDFDisplayBoxMediaBox)
            val w = CGRectGetWidth(bounds)
            val h = CGRectGetHeight(bounds)
            if (w <= 0.0 || h <= 0.0) return@withContext null
            val targetW = targetWidthPx.coerceAtLeast(1).toDouble()
            val targetH = targetW * (h / w)
            val uiImage = pdfPage.thumbnailOfSize(CGSizeMake(targetW, targetH), forBox = kPDFDisplayBoxMediaBox)
            val png = UIImagePNGRepresentation(uiImage) ?: return@withContext null
            Image.makeFromEncoded(png.toByteArray()).toComposeImageBitmap()
        }

    private fun document(bytes: ByteArray): PDFDocument? =
        if (bytes.isEmpty()) null else PDFDocument(data = bytes.toNSData())
}
