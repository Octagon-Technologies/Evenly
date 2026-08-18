package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.pro.ScanMeter
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * "2 of 5 free scans left", under the scan card (`PRO_PASS_SPEC.md` §8.1).
 *
 * Renders nothing when [meter] is null, which is the common case: a Pro group has nothing to count, and
 * a group with plenty left should not be counting yet. The decision about *when* to appear lives in
 * `scanMeterFor`, not here, so it can be tested without Compose.
 *
 * This is a statement of fact, not a warning. It sits below the card rather than on it, and the low
 * state warms to `credit` (the same burnt amber as "you're owed") rather than `danger`, because nothing
 * has gone wrong. The scan card above it stays fully enabled at zero: tapping it explains, which is the
 * "never a silent dead end" rule.
 */
@Composable
internal fun ScanQuotaMeter(meter: ScanMeter?, modifier: Modifier = Modifier) {
    if (meter == null) return
    val c = EvenlyTheme.colors
    val accent = if (meter.isLow) c.credit else c.blueText
    val label = when (meter.remaining) {
        0 -> "No free scans left"
        1 -> "1 of ${meter.limit} free scans left"
        else -> "${meter.remaining} of ${meter.limit} free scans left"
    }
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.width(96.dp).height(4.dp).clip(RoundedCornerShape(99.dp)).background(c.border),
        ) {
            val fraction = if (meter.limit <= 0) 0f else meter.remaining.toFloat() / meter.limit
            if (fraction > 0f) {
                Box(
                    Modifier.fillMaxWidth(fraction).fillMaxHeight()
                        .clip(RoundedCornerShape(99.dp)).background(accent),
                )
            }
        }
        androidx.compose.material3.Text(
            label,
            color = if (meter.isLow) c.credit else c.ink2,
            fontSize = 12.5.sp,
            fontWeight = if (meter.isLow) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
