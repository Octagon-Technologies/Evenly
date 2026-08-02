package app.splitevenly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * The Compose substitute for the design's `box-shadow: 0 -1px 0 var(--border)` list separators:
 * a 1px hairline drawn along the top edge, inside the bounds. Applied to every list row except the
 * first, matching the `.sc-card` row stacks.
 */
fun Modifier.topHairline(color: Color, thickness: Dp = 1.dp): Modifier = drawBehind {
    val t = thickness.toPx()
    drawLine(color = color, start = Offset(0f, t / 2f), end = Offset(size.width, t / 2f), strokeWidth = t)
}

/** `.sc-divider` — a 1px full-width rule. */
@Composable
fun EvDivider(modifier: Modifier = Modifier, color: Color = EvenlyTheme.colors.border) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** `.sc-dot` — the 8dp unread indicator. */
@Composable
fun EvDot(modifier: Modifier = Modifier, color: Color = EvenlyTheme.colors.blue, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** `.sc-section-label` — uppercase 12/600 +0.6 tracking, ink-3, used above cards/sections. */
@Composable
fun EvSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        color = EvenlyTheme.colors.ink3,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
}
