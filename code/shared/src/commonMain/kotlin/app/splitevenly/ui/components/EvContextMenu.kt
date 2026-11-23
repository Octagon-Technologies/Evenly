package app.splitevenly.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.splitevenly.platform.isIOS
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.theme.EvenlyTheme

/** One row of a long-press menu. A [destructive] action is drawn red and set apart at the bottom. */
data class EvMenuAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The long-press menu for a card, in each platform's own idiom: on iOS the screen dims around the card
 * and a context menu opens beside it (label left, icon right); on Android a Material dropdown drops from
 * the card without dimming. Place it in a `Box` that wraps exactly the card, since both anchor to that
 * parent. [cornerRadius] is the card's, so the iOS dim cuts out the card's real shape.
 */
@Composable
fun EvContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<EvMenuAction>,
    cornerRadius: Dp = 16.dp,
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(expanded) { if (expanded) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    if (isIOS()) {
        if (expanded) IosContextMenu(onDismiss, actions, cornerRadius)
    } else {
        AndroidDropdown(expanded, onDismiss, actions)
    }
}

@Composable
private fun AndroidDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<EvMenuAction>,
) {
    val c = EvenlyTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = androidx.compose.ui.unit.DpOffset(12.dp, 0.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = c.page,
    ) {
        actions.forEachIndexed { i, a ->
            if (a.destructive && i > 0) HorizontalDivider(color = c.border)
            val tint = if (a.destructive) c.danger else c.ink
            DropdownMenuItem(
                text = { Text(a.label, color = tint, fontSize = 15.sp) },
                leadingIcon = { EvIcon(a.icon, size = 20.dp, tint = if (a.destructive) c.danger else c.ink2) },
                onClick = {
                    onDismiss()
                    a.onClick()
                },
            )
        }
    }
}

/**
 * Covers the whole window from the card's own popup, so it can read the card's bounds in the same
 * coordinates it draws in: the anchor is captured by the position provider, not measured separately.
 */
@Composable
private fun IosContextMenu(
    onDismiss: () -> Unit,
    actions: List<EvMenuAction>,
    cornerRadius: Dp,
) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    val provider =
        remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    if (anchor != anchorBounds) anchor = anchorBounds
                    return IntOffset.Zero
                }
            }
        }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(if (shown) 1f else 0f, tween(180))
    val density = LocalDensity.current
    val radiusPx = with(density) { cornerRadius.toPx() }
    val gapPx = with(density) { 8.dp.roundToPx() }
    val marginPx = with(density) { 16.dp.roundToPx() }

    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.fillMaxSize()) {
            // The dim skips the card itself, so the pressed card stays lit above everything else.
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
                val hole =
                    RoundRect(
                        left = anchor.left.toFloat(),
                        top = anchor.top.toFloat(),
                        right = anchor.right.toFloat(),
                        bottom = anchor.bottom.toFloat(),
                        cornerRadius = CornerRadius(radiusPx),
                    )
                val scrim =
                    Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(size.width, size.height)))
                        addRoundRect(hole)
                    }
                drawPath(scrim, Color.Black.copy(alpha = 0.38f * progress))
            }
            Layout(content = {
                Box(
                    Modifier.graphicsLayer {
                        alpha = progress
                        scaleX = 0.92f + 0.08f * progress
                        scaleY = 0.92f + 0.08f * progress
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                ) { IosMenuCard(onDismiss, actions) }
            }) { measurables, constraints ->
                val menu = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val x = anchor.left.coerceAtMost(constraints.maxWidth - menu.width - marginPx).coerceAtLeast(marginPx)
                    val below = anchor.bottom + gapPx
                    val y =
                        if (below + menu.height <= constraints.maxHeight - marginPx) {
                            below
                        } else {
                            (anchor.top - gapPx - menu.height).coerceAtLeast(marginPx)
                        }
                    menu.place(x, y)
                }
            }
        }
    }
}

@Composable
private fun IosMenuCard(
    onDismiss: () -> Unit,
    actions: List<EvMenuAction>,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    // Surface, not page: on the dark theme the page is black, and a black menu would sink into the dim.
    Column(Modifier.width(250.dp).shadow(18.dp, shape).clip(shape).background(c.surface).border(0.5.dp, c.border, shape)) {
        actions.forEachIndexed { i, a ->
            if (i > 0) {
                // iOS sets a destructive action apart with a thick band, ordinary rows with a hairline.
                if (a.destructive) {
                    Spacer(Modifier.fillMaxWidth().height(7.dp).background(c.border))
                } else {
                    HorizontalDivider(color = c.border, thickness = 0.5.dp)
                }
            }
            val tint = if (a.destructive) c.danger else c.ink
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .clickable {
                        onDismiss()
                        a.onClick()
                    }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(a.label, color = tint, fontSize = 16.sp, modifier = Modifier.weight(1f))
                EvIcon(a.icon, size = 20.dp, tint = tint)
            }
        }
    }
}
