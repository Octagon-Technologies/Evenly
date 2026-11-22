package app.splitevenly.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.splitevenly.ui.theme.EvMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Past this far down, letting go closes the sheet; short of it, the sheet springs back. */
private val SheetCloseDistance = 120.dp

/** A downward fling at least this fast (px/s) closes the sheet however far it travelled. */
private const val SheetCloseVelocity = 1500f

/**
 * Swipe-down-to-close for a bottom sheet, from anywhere on it, the way the system sheets behave on both OSes.
 *
 * Apply [modifier] to the whole sheet surface. A drag on a non-scrolling part moves the sheet directly; a
 * scrolling body (a long member list) scrolls first and hands the sheet only the pull that is left once
 * it is at the top, through the nested-scroll connection. That is what stops the two gestures fighting:
 * the list never jumps the sheet mid-scroll, and the sheet never eats a scroll back up.
 */
@Composable
internal fun rememberSheetSwipe(onDismiss: () -> Unit): Modifier {
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val closeAfterPx = with(LocalDensity.current) { SheetCloseDistance.toPx() }
    var offsetY by remember { mutableFloatStateOf(0f) }

    fun dragBy(delta: Float): Float {
        val target = (offsetY + delta).coerceAtLeast(0f)
        val consumed = target - offsetY
        offsetY = target
        return consumed
    }

    fun settle(velocity: Float) {
        if (offsetY > closeAfterPx || velocity > SheetCloseVelocity) {
            dismiss()
        } else {
            scope.launch { animate(offsetY, 0f, animationSpec = EvMotion.quick()) { v, _ -> offsetY = v } }
        }
    }

    val connection =
        remember {
            object : NestedScrollConnection {
                // The sheet is pulled down and the finger reverses: raise the sheet before the list scrolls.
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset = if (offsetY > 0f && available.y < 0f) Offset(0f, dragBy(available.y)) else Offset.Zero

                // The list is at its top and the finger keeps pulling down: that pull moves the sheet.
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset =
                    if (source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, dragBy(available.y)) else Offset.Zero

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (offsetY <= 0f) return Velocity.Zero
                    settle(available.y)
                    return available
                }
            }
        }
    val draggableState = rememberDraggableState { delta -> dragBy(delta) }

    return Modifier
        .offset { IntOffset(0, offsetY.roundToInt()) }
        .nestedScroll(connection)
        .draggable(
            state = draggableState,
            orientation = Orientation.Vertical,
            onDragStopped = { velocity -> settle(velocity) },
        )
}
