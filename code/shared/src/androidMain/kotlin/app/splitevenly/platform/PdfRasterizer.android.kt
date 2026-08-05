package app.splitevenly.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Android [PdfRasterizer] over the framework [PdfRenderer] (API 21+; we're minSdk 24). [PdfRenderer] needs
 * a seekable file descriptor, so we stage the bytes to a private cache file per call and tear it down
 * immediately. Each call opens its own renderer (PdfRenderer isn't safe to share across threads/pages),
 * which is cheap relative to render and keeps concurrent page renders independent.
 */
actual class PdfRasterizer actual constructor() {

    actual suspend fun pageCount(bytes: ByteArray): Int = withContext(Dispatchers.IO) {
        open(bytes)?.use { it.renderer.pageCount } ?: 0
    }

    actual suspend fun renderPage(bytes: ByteArray, page: Int, targetWidthPx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            open(bytes)?.use { handle ->
                val renderer = handle.renderer
                if (page < 0 || page >= renderer.pageCount) return@use null
                renderer.openPage(page).use { p ->
                    val width = targetWidthPx.coerceAtLeast(1)
                    val height = (p.height.toFloat() / p.width.toFloat() * width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // PDFs render with transparency where there's no ink; fill white so receipts read on a
                    // dark viewer background.
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp.asImageBitmap()
                }
            }
        }

    private class Handle(
        val renderer: PdfRenderer,
        private val pfd: ParcelFileDescriptor,
        private val file: File,
    ) : Closeable {
        override fun close() {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
            runCatching { file.delete() }
        }
    }

    private fun open(bytes: ByteArray): Handle? {
        // A foreground Activity is a Context; the viewer is only on screen while one is resumed.
        val ctx = CurrentActivity.get()?.applicationContext ?: return null
        return runCatching {
            val dir = File(ctx.cacheDir, "pdf_view").apply { mkdirs() }
            val file = File.createTempFile("rcpt", ".pdf", dir).apply { writeBytes(bytes) }
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            Handle(PdfRenderer(pfd), pfd, file)
        }.getOrNull()
    }
}
