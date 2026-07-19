package da.chelimo.sharecost.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ScMotion
import da.chelimo.sharecost.ui.theme.ShareCostTheme
import kotlinx.coroutines.launch

/** How much a focused card (sheet/modal) dims the screen behind it — just enough to read as
 *  "this has focus," not so much it reads as "the app is blocked." Paired with an explicit close
 *  control on the card itself, since tap-outside-to-dismiss alone isn't discoverable. */
private const val ScScrimAlpha = 0.22f

private val CardShape = RoundedCornerShape(16.dp)

/**
 * `.sc-card` — page surface, 1px hairline, 16dp radius. `fill` → surface bg (a translucent blue wash
 * in dark mode, so it needs `bordered = true` to stay legible against the true-black page — see the
 * individually-elevated group rows on Home). Defaults preserve prior behavior everywhere else.
 */
@Composable
fun ScCard(
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    padded: Boolean = false,
    bordered: Boolean = !fill,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ShareCostTheme.colors
    Column(
        // clip BEFORE clickable so the press ripple is bounded to the rounded card, not a rectangle.
        modifier = modifier
            .clip(CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (fill) c.surface else c.page)
            .then(if (bordered) Modifier.border(1.dp, c.border, CardShape) else Modifier)
            .then(if (padded) Modifier.padding(16.dp) else Modifier),
        content = content,
    )
}

/** A `.sc-card` stacking [items] as rows separated by [topHairline] — the design's list idiom. */
@Composable
fun <T> ScListCard(
    items: List<T>,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    row: @Composable (T) -> Unit,
) {
    val c = ShareCostTheme.colors
    ScCard(modifier = modifier, fill = fill) {
        items.forEachIndexed { i, item ->
            Box(if (i > 0) Modifier.topHairline(c.border) else Modifier) { row(item) }
        }
    }
}

/** Visual of `.sc-sheet` (close button + title/sub) without the scrim. */
@Composable
fun ScSheetSurface(
    modifier: Modifier = Modifier,
    title: String? = null,
    sub: String? = null,
    onClose: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ShareCostTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(c.page)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
    ) {
        if (onClose != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                ScSheetCloseButton(onClose)
                title?.let { Text(it, color = c.ink, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) }
            }
        } else {
            title?.let {
                Text(it, Modifier.fillMaxWidth().padding(bottom = 4.dp), color = c.ink, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
            }
        }
        sub?.let {
            Text(it, Modifier.fillMaxWidth().padding(bottom = 16.dp), color = c.ink2, style = MaterialTheme.typography.bodyMedium, textAlign = if (onClose != null) TextAlign.Start else TextAlign.Center)
        }
        content()
    }
}

/** The small circular ✕ used on every sheet/modal — an explicit, discoverable way to close, since
 *  a tap on the dimmed backdrop alone isn't something a first-time user reliably finds. */
@Composable
private fun ScSheetCloseButton(onClose: () -> Unit) {
    val c = ShareCostTheme.colors
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(c.surface).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        ScIcon(ScIcons.Close, size = 14.dp, tint = c.ink2)
    }
}

/** `.sc-scrim` + bottom-aligned [ScSheetSurface]. A tap on the (lightly) dimmed backdrop dismisses,
 *  same as an explicit close button in the sheet's top-left — belt and suspenders, not either/or.
 *  Slides up + fades in on entry, and reverses on dismiss before actually leaving composition, so
 *  opening/closing reads as one continuous motion instead of a hard cut. */
@Composable
fun ScSheetScaffold(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    sub: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { visibleState.targetState = true }
    LaunchedEffect(visibleState.currentState, visibleState.targetState) {
        if (visibleState.isIdle && !visibleState.targetState) onDismiss()
    }
    val dismiss = { visibleState.targetState = false }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(ScMotion.standard()),
        exit = fadeOut(ScMotion.standard()),
    ) {
        Box(modifier.fillMaxSize().background(ShareCostTheme.colors.ink.copy(alpha = ScScrimAlpha)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = dismiss)) {
            AnimatedVisibility(
                visibleState = visibleState,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(ScMotion.standard()) { it } + fadeIn(ScMotion.standard()),
                exit = slideOutVertically(ScMotion.standard()) { it } + fadeOut(ScMotion.quick()),
            ) {
                ScSheetSurface(
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                    title = title,
                    sub = sub,
                    onClose = dismiss,
                    content = content,
                )
            }
        }
    }
}

/** `.sc-scrim--center` + `.sc-modal` (centered dialog card, max 320dp) — with an explicit close
 *  button pinned top-left of the card, for the same reason [ScSheetScaffold] has one. Fades + scales
 *  in/out in step with the scrim, mirroring [ScSheetScaffold]'s coordinated-dismiss pattern. */
@Composable
fun ScModalScaffold(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ShareCostTheme.colors
    val visibleState = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { visibleState.targetState = true }
    LaunchedEffect(visibleState.currentState, visibleState.targetState) {
        if (visibleState.isIdle && !visibleState.targetState) onDismiss()
    }
    val dismiss = { visibleState.targetState = false }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(ScMotion.standard()),
        exit = fadeOut(ScMotion.standard()),
    ) {
        Box(
            modifier.fillMaxSize().background(c.ink.copy(alpha = ScScrimAlpha)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = dismiss).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(ScMotion.standard()) + scaleIn(ScMotion.standard(), initialScale = 0.92f),
                exit = fadeOut(ScMotion.quick()) + scaleOut(ScMotion.quick(), targetScale = 0.92f),
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.page)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}).padding(24.dp),
                ) {
                    Box(Modifier.padding(bottom = 12.dp)) { ScSheetCloseButton(dismiss) }
                    content()
                }
            }
        }
    }
}

/**
 * A bottom sheet you can DRAG to resize: opens at ~55% of the screen, drag the grab handle up to expand
 * toward full height, or down (past the collapse point) to dismiss. Deliberately has **no dimming scrim**
 * — the page behind stays at full brightness so you keep your bearings while the sheet has focus; only an
 * invisible tap-catcher above the sheet closes it on an outside tap. The handle is the sole draggable
 * region, so a scrolling [content] never fights the resize gesture.
 */
@Composable
fun ScDragSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ShareCostTheme.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxPx = constraints.maxHeight.toFloat()
        val collapsedH = maxPx * 0.55f
        val expandedH = maxPx * 0.92f
        val dismissBelow = maxPx * 0.40f
        val height = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

        LaunchedEffect(collapsedH) { height.animateTo(collapsedH, ScMotion.standard()) }
        fun dismiss() {
            scope.launch {
                height.animateTo(0f, ScMotion.quick())
                onDismiss()
            }
        }

        // Invisible tap-to-dismiss above the sheet — the page shows through unchanged (no scrim).
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { dismiss() },
            ),
        )

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(with(density) { height.value.toDp() })
                .clip(sheetShape)
                .background(c.page)
                .border(1.dp, c.border, sheetShape)
                // Swallow taps on the sheet itself so they don't fall through to the dismiss catcher.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        ) {
            Box(
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, delta ->
                            scope.launch { height.snapTo((height.value - delta).coerceIn(maxPx * 0.2f, expandedH)) }
                        },
                        onDragEnd = {
                            if (height.value <= dismissBelow) dismiss()
                            else scope.launch {
                                height.animateTo(if (height.value > (collapsedH + expandedH) / 2f) expandedH else collapsedH)
                            }
                        },
                    )
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.padding(vertical = 9.dp).size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(c.borderStrong))
            }
            content()
        }
    }
}
