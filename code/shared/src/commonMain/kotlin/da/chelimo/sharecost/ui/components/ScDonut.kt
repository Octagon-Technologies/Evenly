package da.chelimo.sharecost.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** One slice of a [ScDonut]: its [color] and its [fraction] (0..1) of the whole. */
data class DonutSlice(val color: Color, val fraction: Float)

/**
 * A ring/donut chart drawn with [Canvas] + `drawArc` (works on Compose-MP / Native). Slices are laid
 * out clockwise from 12 o'clock; an empty/zero-total list renders the [trackColor] ring. [center] is
 * an optional slot drawn in the hole (e.g. the total label). A 2° gap separates adjacent slices so
 * they read apart even when monochrome-ish.
 */
@Composable
fun ScDonut(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    diameter: Dp = 128.dp,
    thickness: Dp = 22.dp,
    center: @Composable () -> Unit = {},
) {
    val track = ShareCostTheme.colors.surface
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val stroke = thickness.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            val total = slices.sumOf { it.fraction.toDouble() }.toFloat()
            if (total <= 0f) {
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                return@Canvas
            }
            val gap = if (slices.size > 1) 2f else 0f
            var start = -90f
            slices.forEach { s ->
                val sweep = (s.fraction / total) * 360f
                if (sweep > 0f) {
                    drawArc(s.color, start + gap / 2f, (sweep - gap).coerceAtLeast(0.5f), false, topLeft, arcSize, style = Stroke(stroke))
                }
                start += sweep
            }
        }
        center()
    }
}
