package app.splitevenly.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
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

/** Space between a lifted container and the keyboard when no Done bar sits between them. */
private val KeyboardGap = 8.dp

/**
 * Which focused field, if any, has a working return key. Only one field holds focus at a time, so one
 * app-wide owner is enough. A field that never registers (a number pad, a multi-line note, a raw
 * `BasicTextField`) leaves it null, and the Done bar shows: that keyboard has no other way to close.
 */
private object FocusedReturnKey {
    var owner by mutableStateOf<Any?>(null)
}

/** True while the keyboard belongs to a field whose own return key closes it, so no Done bar is needed. */
private val focusedFieldHasReturnKey: Boolean get() = FocusedReturnKey.owner != null

/**
 * Marks this field as one whose return key finishes it (single-line, text keyboard), which hides the
 * Done bar while it holds focus. [EvTextField] applies it; a number pad must not.
 */
@Composable
fun Modifier.claimsReturnKey(enabled: Boolean): Modifier {
    val token = remember { Any() }
    DisposableEffect(token) { onDispose { if (FocusedReturnKey.owner === token) FocusedReturnKey.owner = null } }
    if (!enabled) return this
    return onFocusChanged {
        if (it.isFocused) {
            FocusedReturnKey.owner = token
        } else if (FocusedReturnKey.owner === token) {
            FocusedReturnKey.owner = null
        }
    }
}

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
 * Full screens mount it themselves and hide it while a dialog or sheet is open over them (pass
 * `visible = false`), because [EvSheetScaffold] mounts its own above the sheet. It stays hidden while a
 * single-line text field has focus ([claimsReturnKey]): that keyboard's own return key is "Done", and a
 * second Done above it only takes space from the field the person is typing into.
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
    if (!visible || focusedFieldHasReturnKey || WindowInsets.ime.getBottom(density) <= 0) return
    val c = EvenlyTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime)
            .height(EvKeyboardDoneBarHeight)
            // Swallows taps on the bar's empty part, which in a sheet would otherwise reach the dismiss backdrop.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
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
 * Lifts a container's bottom edge to just above the keyboard, and above [EvKeyboardDoneBar] when it shows,
 * so the container's own bring-into-view lands a focused field above them rather than behind them. Apply
 * it before `verticalScroll`; after it the space would scroll away with the content.
 *
 * Only the part of the keyboard that overlaps the container is padded. A sheet on the group page already
 * ends above the tab bar, and padding it by the full keyboard height lifted it a tab bar's height too far,
 * leaving the page showing through between the sheet and the keyboard.
 */
@Composable
fun Modifier.imeAndDoneBarPadding(): Modifier {
    val density = LocalDensity.current
    var spaceBelowPx by remember { mutableStateOf(0) }
    val measured = onGloballyPositioned { coords ->
        val rootHeight = coords.findRootCoordinates().size.height
        spaceBelowPx = (rootHeight - (coords.positionInRoot().y + coords.size.height)).toInt().coerceAtLeast(0)
    }
    val imePx = WindowInsets.ime.getBottom(density)
    if (imePx <= 0) return this.then(measured)
    val overlap = with(density) { (imePx - spaceBelowPx).coerceAtLeast(0).toDp() }
    val extra = if (focusedFieldHasReturnKey) KeyboardGap else EvKeyboardDoneBarHeight + FocusedFieldGap
    return this.then(measured).padding(bottom = overlap + extra)
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
