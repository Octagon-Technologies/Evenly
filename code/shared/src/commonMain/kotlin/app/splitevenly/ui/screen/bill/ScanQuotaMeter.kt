package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.pro.ScanMeter
import app.splitevenly.ui.components.clickableClearingFocus
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * The free-scan meter under the scan card: the count on the left, the way to Pro on the right
 * (`PRO_PASS_SPEC.md` §8.1).
 *
 * Renders nothing when [meter] is null, which is the common case: a Pro group has nothing to count, and
 * a group with plenty left should not be counting yet. The decision about *when* to appear lives in
 * `scanMeterFor`, not here, so it can be tested without Compose.
 *
 * **It escalates exactly once.** While scans remain it is a statement of fact with a quiet text link,
 * and the second line names what happens after the last one, so nobody meets the wall cold. On the
 * empty state the button drops to full width and names the group. That is the only moment the ask gets
 * loud, and it is the moment buying is the only way to keep scanning.
 *
 * Empty warms to `credit` (the same burnt amber as "you're owed") rather than `danger`: spending a free
 * allowance is not a fault, and a red row over a working feature reads as a bug in the app. The scan
 * card above stays fully enabled either way. Tapping it explains, which is the "never a silent dead
 * end" rule.
 *
 * [onGetPro] is null in the unconfigured-RevenueCat build, and the meter is then a count and nothing
 * else: no door named that cannot open.
 */
@Composable
internal fun ScanQuotaMeter(
    meter: ScanMeter?,
    modifier: Modifier = Modifier,
    groupName: String? = null,
    onGetPro: (() -> Unit)? = null,
) {
    if (meter == null) return
    val c = EvenlyTheme.colors
    val empty = meter.remaining <= 0
    val accent = if (meter.isLow) c.credit else c.blueText
    val title =
        if (empty) "No free scans left" else "${meter.remaining} of ${meter.limit} free scans left"
    val sub =
        if (empty) {
            "Scanning needs a group pass or a subscription. You can still type the bill in."
        } else {
            "After that, a pass or subscription keeps scanning."
        }

    val count = @Composable {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ScanPips(remaining = meter.remaining, limit = meter.limit, accent = accent)
            Text(title, color = if (empty) c.credit else c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = if (empty) c.credit.copy(alpha = 0.85f) else c.ink3, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (empty) c.creditTint else c.surface)
            .border(1.dp, if (empty) c.credit.copy(alpha = 0.34f) else c.border, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
    ) {
        // Empty stacks so the button can go full width and carry the group's name; while scans remain the
        // link sits beside the count, where it cannot be mistaken for the next step.
        if (empty && onGetPro != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                count()
                GetProButton(
                    text = groupName?.let { "Get Pro for $it" } ?: "Get Evenly Pro",
                    onClick = onGetPro,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { count() }
                if (onGetPro != null) GetProLink(onClick = onGetPro)
            }
        }
    }
}

/** One pip per free scan, spent ones greyed. A count you can read without reading. */
@Composable
private fun ScanPips(
    remaining: Int,
    limit: Int,
    accent: androidx.compose.ui.graphics.Color,
) {
    val c = EvenlyTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        // Guarded because the limit arrives from the server: a bad or absent one must not draw a row of
        // hundreds of pips across the card.
        repeat(limit.coerceIn(0, MAX_PIPS)) { i ->
            Box(
                Modifier
                    .width(16.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (i < remaining) accent else c.borderStrong),
            )
        }
    }
}

/** The loud one: only ever drawn on the empty state. Amber, matching the row it sits in. */
@Composable
private fun GetProButton(
    text: String,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(c.credit)
            .clickableClearingFocus(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** The quiet one, for every state that still has a scan in it. */
@Composable
private fun GetProLink(onClick: () -> Unit) {
    val c = EvenlyTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickableClearingFocus(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Get Pro", color = c.blueText, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        EvIcon(EvIcons.ChevR, size = 13.dp, tint = c.blueText)
    }
}

/** Ceiling on the pip row. Past this the number carries the count on its own. */
private const val MAX_PIPS = 8
