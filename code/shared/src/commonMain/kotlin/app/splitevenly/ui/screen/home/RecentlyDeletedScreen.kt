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
)

/**
 * Recently deleted: groups deleted for everyone in the last 30 days, and the one tap that brings one
 * back.
 *
 * **Who deleted it** leads every row, because a group vanishing with no explanation is the failure
 * mode of letting any member delete one.
 *
 * **Tapping the row restores**, exactly as tapping an Archived row unarchives, with the chip as the
 * affordance rather than a separate target. A row that opened some read-only view of a group that no
 * longer exists for anybody would be the more obvious design and a worse one, and a chip that is the
 * only live thing in a row makes the other 90% of that row a dead control.
 *
 * Restoring has no confirmation step. It is not destructive, it is undone by deleting again, and an
 * "are you sure you want to un-delete this" dialog would be friction on the safe direction.
 */
@Composable
fun RecentlyDeletedScreen(
    onBack: () -> Unit = {},
    groups: List<DeletedGroupCardUi> = RecentlyDeletedSamples.groups,
    onRestore: (String) -> Unit = {},
    restoredMessage: String? = null,
) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(
            "Recently deleted",
            subtitle = if (groups.isEmpty()) null else "${groups.size} ${if (groups.size == 1) "group" else "groups"}",
            navIcon = { EvIconButton(EvIcons.Back, onBack) },
        )
        Box(Modifier.fillMaxSize()) {
            if (groups.isEmpty()) {
                // Also the state the day after a purge. There is deliberately no "permanently deleted"
                // tombstone row: by then there is genuinely nothing left to show.
                EvEmptyState(
                    title = "Nothing deleted",
                    text = "Deleted groups wait here for 30 days so anyone in the group can bring them back.",
                    icon = EvIcons.Trash,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    EvCard {
                        groups.forEachIndexed { i, g ->
                            Box(if (i > 0) Modifier.topHairline(c.border) else Modifier) {
                                DeletedGroupCardRow(g, onRestore = { onRestore(g.id) })
                            }
                        }
                    }
                    Text(
                        "Groups here are deleted for everyone. Anyone who was a member can bring one back. " +
                            "After 30 days they are permanently deleted, including from our servers.",
                        Modifier.fillMaxWidth(),
                        color = c.ink2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(48.dp))
                }
            }
            restoredMessage?.let {
                EvToast(it, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
}

@Composable
private fun DeletedGroupCardRow(
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

@Preview
@Composable
private fun RecentlyDeletedPreview() {
    EvenlyTheme { RecentlyDeletedScreen() }
}

@Preview
@Composable
private fun RecentlyDeletedEmptyPreview() {
    EvenlyTheme { RecentlyDeletedScreen(groups = emptyList()) }
}
