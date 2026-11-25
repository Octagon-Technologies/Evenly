package app.splitevenly.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

private const val ARCHIVED = "archived"
private const val DELETED = "deleted"

/**
 * Put away: Archived and Recently deleted as two cards, exactly one open. The open card takes the
 * whole height left over by the closed card's header and scrolls inside itself, so both cards stay on
 * screen however long either list gets. A card with nothing in it is not drawn at all.
 *
 * [history] (one-time bills settled over a day ago) is filed under Archived: it left Home on its own
 * and has nothing to restore, so its rows say "Settled" instead of offering Restore.
 */
@Composable
fun PutAwayScreen(
    filter: HomeFilter = HomeFilter.All,
    archived: List<GroupCardUi> = HomeSamples.content.archived,
    history: List<GroupCardUi> = emptyList(),
    deleted: List<DeletedGroupCardUi> = RecentlyDeletedSamples.groups,
    startOnDeleted: Boolean = false,
    onBack: () -> Unit = {},
    onOpen: (GroupCardUi) -> Unit = {},
    onUnarchive: (String) -> Unit = {},
    onRestoreDeleted: (String) -> Unit = {},
    /** Fires whenever the Recently deleted card is showing; the route marks deletions as seen. */
    onDeletedShown: () -> Unit = {},
    message: String? = null,
) {
    val c = EvenlyTheme.colors
    val archivedRows = archived.filter { filter.matches(it) }
    val historyRows = history.filter { filter.matches(it) }
    val deletedRows = deleted.filter { filter.matches(it.isQuick) }
    val hasArchived = archivedRows.isNotEmpty() || historyRows.isNotEmpty()
    val hasDeleted = deletedRows.isNotEmpty()
    var openKey by rememberSaveable { mutableStateOf(if (startOnDeleted) DELETED else ARCHIVED) }
    // Only one card can be open, and an absent card can never be the open one.
    val open =
        when {
            !hasDeleted -> ARCHIVED
            !hasArchived -> DELETED
            else -> openKey
        }
    if (open == DELETED && hasDeleted) LaunchedEffect(deletedRows.size) { onDeletedShown() }

    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(
            "Put away",
            subtitle =
                when (filter) {
                    HomeFilter.All -> null
                    HomeFilter.Groups -> "Groups"
                    HomeFilter.OneTime -> "One-time bills"
                },
            navIcon = { EvIconButton(EvIcons.Back, onBack) },
        )
        Box(Modifier.fillMaxSize()) {
            if (!hasArchived && !hasDeleted) {
                EvEmptyState(
                    title = "Nothing put away",
                    text = "Swipe left on anything on Home to archive or delete it.",
                    icon = EvIcons.Archive,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hasArchived) {
                        AccordionCard(
                            title = "Archived",
                            icon = EvIcons.Archive,
                            count = archivedRows.size + historyRows.size,
                            open = open == ARCHIVED,
                            collapsible = hasDeleted,
                            onToggle = { openKey = if (open == ARCHIVED) DELETED else ARCHIVED },
                            hint = "Hidden from your list only. You still get notified about changes.",
                        ) {
                            archivedRows.forEachIndexed { i, g ->
                                PutAwayRow(g, divider = i > 0, onOpen = { onOpen(g) }, onRestore = { onUnarchive(g.id) })
                            }
                            historyRows.forEachIndexed { i, g ->
                                PutAwayRow(g, divider = archivedRows.isNotEmpty() || i > 0, onOpen = { onOpen(g) }, onRestore = null)
                            }
                        }
                    }
                    if (hasDeleted) {
                        AccordionCard(
                            title = "Recently deleted",
                            icon = EvIcons.Trash,
                            count = deletedRows.size,
                            open = open == DELETED,
                            collapsible = hasArchived,
                            onToggle = { openKey = if (open == DELETED) ARCHIVED else DELETED },
                            hint = "Deleted for everyone. Anyone in it can restore it within 30 days.",
                        ) {
                            deletedRows.forEachIndexed { i, g ->
                                Box(if (i > 0) Modifier.topHairline(c.border) else Modifier) {
                                    DeletedGroupCardRow(g, onRestore = { onRestoreDeleted(g.id) })
                                }
                            }
                        }
                    }
                }
            }
            message?.let { EvToast(it, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) }
        }
    }
}

/**
 * One accordion card. Open, it fills the space its parent leaves it (weight 1) and scrolls its own
 * body; closed, it is just the header. Tapping an open header hands the space to the other card,
 * since there is always exactly one open.
 */
@Composable
private fun ColumnScope.AccordionCard(
    title: String,
    icon: ImageVector,
    count: Int,
    open: Boolean,
    collapsible: Boolean,
    onToggle: () -> Unit,
    hint: String,
    body: @Composable ColumnScope.() -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    EvCard(
        modifier = if (open) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth(),
        fill = true,
        bordered = true,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EvIcon(icon, size = 18.dp, tint = c.ink2)
            Text(title, style = t.itemTitle, modifier = Modifier.weight(1f))
            Text("$count", style = t.description)
            if (collapsible) {
                EvIcon(EvIcons.ChevR, size = 18.dp, tint = c.ink3, modifier = Modifier.rotate(if (open) 90f else 0f))
            }
        }
        if (open) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                Text(hint, style = t.caption.copy(color = c.ink2), modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp))
                body()
            }
        }
    }
}

/** An archived (or settled) row: the row opens it, the Restore chip brings it back to Home. */
@Composable
private fun PutAwayRow(
    g: GroupCardUi,
    divider: Boolean,
    onOpen: () -> Unit,
    onRestore: (() -> Unit)?,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Row(
        (if (divider) Modifier.topHairline(c.border) else Modifier)
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
            if (g.quick != null) {
                EvIcon(EvIcons.Receipt, size = 20.dp, tint = c.blueText)
            } else {
                Text(groupIconLabel(g.emoji, g.name), fontSize = 20.sp, color = c.ink)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(g.name, style = t.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (g.quick != null) g.last else "${g.members} ${if (g.members == 1) "member" else "members"} · ${g.last}",
                style = t.description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRestore != null) {
            EvChip(
                "Restore",
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRestore),
                variant = ChipVariant.Blue,
                leadingIcon = EvIcons.Reload,
            )
        } else {
            Text("Settled", style = t.chip.copy(color = c.settled))
        }
    }
}

@Preview
@Composable
private fun PutAwayPreview() {
    EvenlyTheme { PutAwayScreen() }
}
