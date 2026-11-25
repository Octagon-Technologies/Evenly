package app.splitevenly.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One button revealed behind a swiped card. */
data class EvSwipeAction(
    val label: String,
    val icon: ImageVector,
    val background: Color,
    val onClick: () -> Unit,
)

private val ActionWidth = 76.dp

/**
 * A card that slides left to reveal [actions], iOS Mail style, on both platforms.
 *
 * [open] is hoisted so a list can keep at most one card open: opening one closes the rest. While open,
 * a tap on the card closes it instead of opening the card, so a half-forgotten reveal never turns the
 * next tap into a navigation. Every action here must also be reachable another way (the long-press
 * menu), since a swipe is invisible until someone tries it.
 *
 * The content gets a solid page-colored backing because `EvCard(fill = true)` is translucent in dark
 * mode, and the red Delete button would otherwise show through the card mid-swipe.
 */
@Composable
fun EvSwipeActions(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    actions: List<EvSwipeAction>,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    val revealPx = with(LocalDensity.current) { (ActionWidth * actions.size).toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(open, revealPx) { offset.animateTo(if (open) -revealPx else 0f) }

    Box(modifier.clip(shape)) {
        Row(
            Modifier
                .matchParentSize()
                // Hidden at rest so nothing peeks around the card's rounded corners.
                .graphicsLayer { alpha = if (offset.value < -0.5f) 1f else 0f },
            horizontalArrangement = Arrangement.End,
        ) {
            actions.forEach { action ->
                Column(
                    Modifier
                        .width(ActionWidth)
                        .fillMaxHeight()
                        .background(action.background)
                        .clickable {
                            onOpenChange(false)
                            action.onClick()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    EvIcon(action.icon, size = 20.dp, tint = Color.White)
                    Text(action.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(c.page, shape)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state =
                        rememberDraggableState { delta ->
                            scope.launch { offset.snapTo((offset.value + delta).coerceIn(-revealPx * 1.15f, 0f)) }
                        },
                    onDragStopped = { velocity ->
                        val shouldOpen = velocity < -600f || (velocity <= 600f && offset.value < -revealPx / 2f)
                        // Re-settle even when the hoisted value doesn't change, or a half drag would stick.
                        offset.animateTo(if (shouldOpen) -revealPx else 0f)
                        onOpenChange(shouldOpen)
                    },
                ),
        ) {
            content()
            if (open) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            onOpenChange(false)
                        },
                )
            }
        }
    }
}
