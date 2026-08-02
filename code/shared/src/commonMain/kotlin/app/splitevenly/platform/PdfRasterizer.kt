package app.splitevenly.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Rasterizes PDF pages to bitmaps natively — Android [android.graphics.pdf.PdfRenderer], iOS PDFKit —
 * so receipts render fully in-app with no external viewer (the whole point of the receipt viewer: never
 * route the user out to a browser).
 *
 * Stateless per call: hand it the document bytes, get a page back. This keeps the actuals dependency-free
 * (no open-document lifecycle to leak across the KMP boundary). The caller is responsible for efficiency —
 * [app.splitevenly.ui.screen.expense.ReceiptViewerScreen] downloads a PDF's bytes once and caches the
 * rendered pages, and only renders a page when the user actually opens that PDF (never up-front for the
 * filmstrip), so we don't waste memory or network on documents the user never looks at.
 */
expect class PdfRasterizer() {

    /** Number of pages, or 0 if [bytes] isn't a readable PDF. */
    suspend fun pageCount(bytes: ByteArray): Int

    /**
     * Render 0-based [page] to a bitmap roughly [targetWidthPx] wide (height preserves the page aspect).
     * Returns null if the page can't be rendered. Decoding happens off the main thread.
     */
    suspend fun renderPage(bytes: ByteArray, page: Int, targetWidthPx: Int): ImageBitmap?
}
