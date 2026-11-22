package app.splitevenly.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Fixed so a scroll container can reserve exactly this much room with [imeAndDoneBarPadding]. 48.4 is
 * the original 44 plus 10%: at 44 the strip read as a thin seam above the keyboard (owner call, 2026-11-22).
 */
val EvKeyboardDoneBarHeight = 48.4.dp

/** Breathing room between the bottom of the scroll viewport and the Done bar. */
private val FocusedFieldGap = 16.dp

/** Clearance kept around a focused field's box when [bringIntoViewOnFocus] scrolls it into view. */
private val FocusedFieldMargin = 8.dp

/**
 * A right-aligned "Done" bar that appears directly above the software keyboard while it's visible.
 * Numeric/decimal keyboards have no return key, so without this the only way to dismiss them is
 * tapping away, which isn't discoverable and can hide content the user still needs to see.
 *
 * [WindowInsets.isImeVisible] is Android-only in this Compose Multiplatform version (unresolved on
 * Kotlin/Native), so visibility is read from the ime inset height directly, which resolves on both.
 *
 * Mount it only on full screens, and hide it while a dialog or sheet is open over the screen: those
 * close on their own ✕, Back or a tap outside, so a bar peeking out behind them is noise (pass
 * `visible = false`). Single-line text fields don't need it either; their return key is "Done".
 *
 * The fill is `page`, not `surface`: in dark mode `surface` is a 6%-alpha blue tint meant to sit *on*
 * the page, so using it here let the scrolled content read straight through the bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EvKeyboardDoneBar(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val density = LocalDensity.current
    if (!visible || WindowInsets.ime.getBottom(density) <= 0) return
    val c = EvenlyTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime)
            .height(EvKeyboardDoneBarHeight)
            .background(c.page)
            .topHairline(c.border),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            "Done",
            color = c.blueText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .clickable(onClick = onDone)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * Shrinks a scroll container's viewport to end just above [EvKeyboardDoneBar], so the container's own
 * bring-into-view scrolls a newly focused field to sit *above* the bar and the keyboard rather than
 * behind them. Apply it before `verticalScroll` — after it the space would scroll away with the content.
 *
 * Only meaningful on a screen that actually shows the bar; without it the reserved strip is dead space.
 */
@Composable
fun Modifier.imeAndDoneBarPadding(): Modifier {
    val density = LocalDensity.current
    if (WindowInsets.ime.getBottom(density) <= 0) return this
    return this
        .windowInsetsPadding(WindowInsets.ime)
        .padding(bottom = EvKeyboardDoneBarHeight + FocusedFieldGap)
}

/**
 * Scrolls the whole element into view (plus [FocusedFieldMargin]) while it or a child holds focus.
 *
 * A text field's own bring-into-view aims at the cursor only, so a field whose box is taller than its
 * text line stopped half-hidden behind the Done bar. Re-runs as the ime inset settles, because the
 * viewport is still shrinking while the keyboard animates up.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bringIntoViewOnFocus(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(hasFocus, imeBottom, size) {
        if (!hasFocus || imeBottom <= 0 || size == IntSize.Zero) return@LaunchedEffect
        val m = with(density) { FocusedFieldMargin.toPx() }
        requester.bringIntoView(Rect(0f, -m, size.width.toFloat(), size.height + m))
    }
    return this
        .bringIntoViewRequester(requester)
        .onSizeChanged { size = it }
        .onFocusChanged { hasFocus = it.hasFocus }
}
