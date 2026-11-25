package app.splitevenly.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.ChipVariant
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvEmptyState
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvToast
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.groupIconLabel
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * A group in Recently deleted. [deletedByLabel] is pre-resolved ("Deleted by Andrew" / "Deleted by
 * you") because the screen is DI-free and cannot look a user id up; [urgent] is the last-week state
 * the Route computes from [daysLeft].
 */
data class DeletedGroupCardUi(
    val id: String,
    val emoji: String,
    val name: String,
    val deletedByLabel: String,
    val daysLeft: Int,
    val urgent: Boolean = false,
    val isQuick: Boolean = false,
)

/** A Recently deleted row in Put away. Tapping anywhere restores; the chip is the affordance. */
@Composable
internal fun DeletedGroupCardRow(
    g: DeletedGroupCardUi,
    onRestore: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onRestore).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Muted tile rather than the live blue one: this group is not somewhere you can go right now.
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(c.surface), contentAlignment = Alignment.Center) {
            Text(groupIconLabel(g.emoji, g.name), fontSize = 24.sp, color = c.ink2)
        }
        Column(Modifier.weight(1f)) {
            Text(
                g.name,
                color = c.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${g.deletedByLabel} · ${daysLeftLabel(g.daysLeft)}",
                // The countdown is the only urgency signal on the screen, and it earns the color only
                // in the last week. Coloring all 30 days would make the last day look like every other.
                color = if (g.urgent) c.credit else c.ink2,
                fontSize = 13.sp,
                fontWeight = if (g.urgent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Affordance only — the whole row carries the tap, same as Archived's "Unarchive" chip.
        EvChip("Restore", variant = ChipVariant.Blue, leadingIcon = EvIcons.Reload)
    }
}

/** "27 days left" / "1 day left" / "Deletes today" for the final hours. */
internal fun daysLeftLabel(daysLeft: Int): String =
    when (daysLeft) {
        0 -> "Deletes today"
        1 -> "1 day left"
        else -> "$daysLeft days left"
    }

internal object RecentlyDeletedSamples {
    val groups =
        listOf(
            DeletedGroupCardUi("1", "🏝️", "Tulum Trip", "Deleted by Andrew", 27),
            DeletedGroupCardUi("2", "⛷️", "Tahoe 2025", "Deleted by you", 2, urgent = true),
        )
}
