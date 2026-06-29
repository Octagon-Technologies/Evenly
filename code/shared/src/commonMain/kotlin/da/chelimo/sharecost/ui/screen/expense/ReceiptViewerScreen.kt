package da.chelimo.sharecost.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import kotlinx.coroutines.launch

/** One openable receipt in the viewer. [url] null = not yet resolved; [isPdf] renders via [PdfRasterizer]. */
data class ViewerReceipt(val id: String, val url: String?, val isPdf: Boolean)

private val ViewerBg = Color(0xFF0E1726)
private val ViewerChrome = Color(0x33FFFFFF)
private val Accent = Color(0xFF378ADD)

/**
 * Full-screen, in-app receipt viewer (replaces the old "open in browser" hand-off). A [HorizontalPager]
 * swipes across every file for the expense — images pinch-to-zoom, PDFs render natively page-by-page — and
 * a bottom filmstrip of rounded-square thumbnails jumps between them. Mixed images + PDFs share one strip
 * (PDFs carry a corner badge) so nothing is hidden in a separate lane.
 *
 * Efficiency: images go through Coil (sized requests + its cache); PDF bytes are fetched + rendered lazily
 * via [renderPdfPage] only when the user actually opens that PDF, and the rendered pages are remembered so
 * a swipe-away/back doesn't re-render. The filmstrip shows a cheap badge tile for PDFs — it never downloads
 * or rasterizes a document just to draw a thumbnail.
 */
@Composable
fun ReceiptViewerScreen(
    receipts: List<ViewerReceipt>,
    initialIndex: Int = 0,
    loadPdfPageCount: suspend (url: String) -> Int = { 0 },
    renderPdfPage: suspend (url: String, page: Int, widthPx: Int) -> ImageBitmap? = { _, _, _ -> null },
    onClose: () -> Unit = {},
) {
    if (receipts.isEmpty()) {
        onClose()
        return
    }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, receipts.lastIndex),
    ) { receipts.size }

    Column(Modifier.fillMaxSize().background(ViewerBg).systemBarsPadding()) {
        // Top bar: close + position.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(ViewerChrome)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { ScIcon(ScIcons.Close, size = 18.dp, tint = Color.White) }
            Text(
                "${pagerState.currentPage + 1} of ${receipts.size}",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
            Box(Modifier.size(36.dp))
        }

        // Main pager.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            beyondViewportPageCount = 1,
        ) { page ->
            val r = receipts[page]
            if (r.isPdf && r.url != null) {
                PdfPage(url = r.url, loadPdfPageCount = loadPdfPageCount, renderPdfPage = renderPdfPage)
            } else {
                ZoomableImage(url = r.url)
            }
        }

        // Filmstrip.
        LazyRow(
            Modifier.fillMaxWidth().background(Color(0x40000000)),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(receipts) { i, r ->
                FilmstripThumb(
                    receipt = r,
                    selected = i == pagerState.currentPage,
                    onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                )
            }
        }
    }
}

/** Pinch-to-zoom / pan image page. Resets to fit when scaled back down. */
@Composable
private fun ZoomableImage(url: String?) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().pointerInput(url) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 4f)
                offset = if (scale > 1f) offset + pan else Offset.Zero
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        // SubcomposeAsyncImage shows an indeterminate spinner until the bytes are ready, then the loader's
        // crossfade(true) fades the receipt in. Re-opens hit Coil's memory/disk cache, so the spinner only
        // appears on the genuine first load.
        SubcomposeAsyncImage(
            model = url,
            contentDescription = "Receipt",
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
            contentScale = ContentScale.Fit,
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                }
            },
        )
    }
}

/** A PDF receipt: render its pages natively at the page width and show them in a vertical scroll. */
@Composable
private fun PdfPage(
    url: String,
    loadPdfPageCount: suspend (url: String) -> Int,
    renderPdfPage: suspend (url: String, page: Int, widthPx: Int) -> ImageBitmap?,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // constraints.maxWidth is already px; cap to bound memory on huge pages.
        val widthPx = constraints.maxWidth.coerceIn(1, 1080)
        // null = still loading; emptyList after load = couldn't open.
        var pages by remember(url, widthPx) { mutableStateOf<List<ImageBitmap?>?>(null) }

        LaunchedEffect(url, widthPx) {
            val count = loadPdfPageCount(url)
            if (count <= 0) {
                pages = emptyList()
                return@LaunchedEffect
            }
            pages = arrayOfNulls<ImageBitmap?>(count).asList()
            for (i in 0 until count) {
                val bmp = renderPdfPage(url, i, widthPx)
                pages = pages?.toMutableList()?.also { it[i] = bmp }
            }
        }

        val current = pages
        when {
            current == null -> CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            current.isEmpty() -> Text("Couldn't open this PDF.", color = Color.White, fontSize = 14.sp)
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                current.forEach { bmp ->
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp,
                            contentDescription = "Receipt page",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth,
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                        }
                    }
                }
            }
        }
    }
}

/** Rounded-square filmstrip tile. Images load via Coil; PDFs show a badge tile (no download). */
@Composable
private fun FilmstripThumb(receipt: ViewerReceipt, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val border = if (selected) Modifier.border(2.dp, Accent, shape) else Modifier
    Box(
        Modifier.size(48.dp).clip(shape).background(Color(0xFF2A2F3A)).then(border).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (receipt.isPdf || receipt.url == null) {
            ScIcon(ScIcons.Receipt, size = 20.dp, tint = Color(0xFFB4B2A9))
            if (receipt.isPdf) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(3.dp)
                        .clip(RoundedCornerShape(3.dp)).background(Color(0xFFA32D2D))
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                ) { Text("PDF", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Medium) }
            }
        } else {
            AsyncImage(
                model = receipt.url,
                contentDescription = "Receipt",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
