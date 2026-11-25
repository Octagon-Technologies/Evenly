package app.splitevenly.ui.screen.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.platform.isIOS
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.BannerVariant
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.ChipVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvDivider
import app.splitevenly.ui.components.EvDot
import app.splitevenly.ui.components.EvEmptyState
import app.splitevenly.ui.components.EvSegmented
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.EvUndoToast
import app.splitevenly.ui.components.groupIconLabel
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.money
import app.splitevenly.ui.screen.settings.GroupDeleteImpactUi
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.coroutines.delay

/** A group as shown on the Home list (Phase B maps domain Group → this). */
data class GroupCardUi(
    val id: String,
    val emoji: String,
    val name: String,
    val members: Int,
    val status: GroupBalanceStatus,
    val amount: Double?,
    val last: String,
    val unread: Boolean = false,
    /** Set for a quick split (a hidden one-bill group); Home draws it as a bill card. */
    val quick: QuickCardUi? = null,
)

enum class GroupBalanceStatus { Owe, Owed, Settled, Empty }

sealed interface HomeUiState {
    /** First pull not landed yet: render a blank page, never "No groups". */
    data object Loading : HomeUiState

    /** Nothing local and the first pull failed; [offline] picks the copy. */
    data class Failed(
        val offline: Boolean,
    ) : HomeUiState

    data object Empty : HomeUiState

    /**
     * [deletedCount] drives the Recently deleted row. A count rather than a list because the row only
     * ever shows one, and the screen behind it re-reads the groups itself.
     */
    data class Content(
        val active: List<GroupCardUi>,
        val archived: List<GroupCardUi>,
        val deletedCount: Int = 0,
        /** Quick splits settled more than a day ago. Off the main list, filed under Archived in Put away. */
        val history: List<GroupCardUi> = emptyList(),
        /** How many of [deletedCount] are one-time bills, so the Put away row's count follows the tab. */
        val deletedOneTimeCount: Int = 0,
    ) : HomeUiState {
        /** Archived for the Put away row: what you archived plus the settled bills that moved off Home. */
        fun archivedCount(filter: HomeFilter): Int = (archived + history).count { filter.matches(it) }

        fun deletedCount(filter: HomeFilter): Int =
            when (filter) {
                HomeFilter.All -> deletedCount
                HomeFilter.Groups -> deletedCount - deletedOneTimeCount
                HomeFilter.OneTime -> deletedOneTimeCount
            }
    }
}

/** 4 · Home / group list (design/src/screens-home.jsx). */
@Composable
fun HomeScreen(
    state: HomeUiState = HomeSamples.content,
    userName: String = "there",
    onOpenGroup: (String) -> Unit = {},
    /**
     * A quick split's card. Carries the bill id the card already loaded so the tap lands on the bill in
     * one navigation, instead of routing through the group screen's quick-split redirect.
     */
    onOpenQuickSplit: (groupId: String, expenseId: String?) -> Unit = { id, _ -> onOpenGroup(id) },
    onNewGroup: () -> Unit = {},
    onJoin: () -> Unit = {},
    onSplitBill: () -> Unit = {},
    /** Put away, opened on the current tab. [showDeleted] opens its Recently deleted card first. */
    onOpenPutAway: (filter: HomeFilter, showDeleted: Boolean) -> Unit = { _, _ -> },
    /**
     * "Andrew deleted Tulum Trip." Shown until the person opens Recently deleted.
     *
     * This is the in-app stand-in for the push that a group deletion is supposed to send, and it is
     * not decoration: any member can delete a group for all of them, push delivery is inert until FCM
     * is configured, and without this the group simply vanishes from someone's list with a muted grey
     * row below the fold as the only explanation. Null once seen.
     */
    deletedAlert: String? = null,
    onOpenSettings: () -> Unit = {},
    onRetry: () -> Unit = {},
    onEditQuickSplit: (groupId: String, expenseId: String, itemized: Boolean) -> Unit = { _, _, _ -> },
    onDeleteQuickSplit: (groupId: String) -> Unit = {},
    onArchive: (groupId: String) -> Unit = {},
    onUnarchive: (groupId: String) -> Unit = {},
    onLeaveGroup: (groupId: String) -> Unit = {},
    onDeleteGroup: (groupId: String) -> Unit = {},
    /** Undo for a delete made from Home; the same restore Put away offers for 30 days. */
    onRestoreGroup: (groupId: String) -> Unit = {},
    loadDeleteImpact: suspend (groupId: String) -> GroupDeleteImpactUi = { GroupDeleteImpactUi() },
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    var showActions by remember { mutableStateOf(false) }
    // Saved as its String key: enum saving is not guaranteed on every Compose target.
    var filterKey by rememberSaveable { mutableStateOf(HomeFilter.All.key) }
    val filter = HomeFilter.fromKey(filterKey)
    var swipedId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<GroupCardUi?>(null) }
    var undo by remember { mutableStateOf<HomeUndo?>(null) }
    undo?.let { u ->
        LaunchedEffect(u) {
            delay(UNDO_WINDOW_MS)
            if (undo == u) undo = null
        }
    }
    val archive: (GroupCardUi) -> Unit = { g ->
        onArchive(g.id)
        undo = HomeUndo("${g.name} archived") { onUnarchive(g.id) }
    }
    val isContent = state is HomeUiState.Content || state is HomeUiState.Loading

    // No systemBarsPadding/StatusBarScrim here: the root MainShell paints the status-bar scrim above
    // (both now the true-black page color, so there's no seam at the status bar). Home has no bottom
    // nav (MainShell hides it while the Groups tab is active); the avatar is the way into Settings,
    // and its own bottom nav's "Groups" tab is the way back.
    Column(Modifier.fillMaxSize().background(c.page)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Evenly",
                modifier = Modifier.weight(1f),
                style = t.pageTitle.copy(fontWeight = if (isContent) FontWeight.ExtraBold else FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            EvAvatar(userName, me = true, size = AvatarSize.Md, onClick = onOpenSettings)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state) {
                // Deliberately blank: most accounts are empty, so a skeleton would flash and then vanish.
                HomeUiState.Loading -> {}

                is HomeUiState.Failed -> {
                    EvEmptyState(
                        title = "Couldn't load your groups",
                        text =
                            if (state.offline) {
                                "You seem to be offline. Your groups are safe and will show up once you're connected."
                            } else {
                                "Something went wrong on our side. Your groups are safe."
                            },
                        ctaText = "Try again",
                        onCta = onRetry,
                    )
                }

                HomeUiState.Empty -> {
                    HomeWelcome(onSplitBill, onNewGroup, onJoin)
                }

                is HomeUiState.Content -> {
                    val shown = state.active.filter { filter.matches(it) }
                    val archivedCount = state.archivedCount(filter)
                    val deletedCount = state.deletedCount(filter)
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        deletedAlert?.let {
                            EvBanner(
                                it,
                                variant = BannerVariant.Amber,
                                leadingIcon = EvIcons.Trash,
                                modifier = Modifier.clickable { onOpenPutAway(HomeFilter.All, true) },
                            )
                        }
                        EvSegmented(
                            options = HomeFilter.entries.map { it.label },
                            selected = filter.label,
                            onSelect = { label ->
                                filterKey = HomeFilter.entries.first { it.label == label }.key
                                swipedId = null
                            },
                        )
                        if (shown.isEmpty()) {
                            HomeTabEmpty(filter, onSplitBill = onSplitBill, onNewGroup = onNewGroup)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                shown.forEach { g ->
                                    key(g.id) {
                                        if (g.quick != null) {
                                            QuickSplitCardWithMenu(
                                                g,
                                                onOpen = { onOpenQuickSplit(g.id, g.quick.expenseId) },
                                                onEdit = { expenseId, itemized -> onEditQuickSplit(g.id, expenseId, itemized) },
                                                onDelete = {
                                                    onDeleteQuickSplit(g.id)
                                                    undo = HomeUndo("${g.name} deleted") { onRestoreGroup(g.id) }
                                                },
                                                onArchive = { archive(g) },
                                                swipeOpen = swipedId == g.id,
                                                onSwipeOpenChange = { open -> swipedId = if (open) g.id else null },
                                            )
                                        } else {
                                            GroupCardWithActions(
                                                g,
                                                onOpen = { onOpenGroup(g.id) },
                                                onArchive = { archive(g) },
                                                onDelete = { deleting = g },
                                                swipeOpen = swipedId == g.id,
                                                onSwipeOpenChange = { open -> swipedId = if (open) g.id else null },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // Only when something is actually put away: nobody should carry a permanent trash
                        // can around, and "Recently deleted (0)" invites the question of what was deleted.
                        if (archivedCount + deletedCount > 0) {
                            ShelfRow(
                                icon = EvIcons.Archive,
                                label =
                                    listOfNotNull(
                                        archivedCount.takeIf { it > 0 }?.let { "Archived $it" },
                                        deletedCount.takeIf { it > 0 }?.let { "Recently deleted $it" },
                                    ).joinToString("  ·  "),
                                onClick = { onOpenPutAway(filter, archivedCount == 0) },
                            )
                        }
                        Spacer(Modifier.height(88.dp))
                    }
                }
            }
            undo?.let { u ->
                EvUndoToast(
                    u.text,
                    onUndo = {
                        u.undo()
                        undo = null
                    },
                    modifier =
                        Modifier
                            .align(
                                Alignment.BottomCenter,
                            ).navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, bottom = 84.dp),
                )
            }
            if (state is HomeUiState.Content) {
                // A single home-level action — you create or join a *group* here; expenses are only
                // ever added from inside a group, so "Add an expense" no longer lives at this level. A
                // full-width bottom pill rather than a corner FAB, matching the approved mockup.
                CreateOrJoinBar(onClick = { showActions = true }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    deleting?.let { g ->
        HomeGroupDeleteFlow(
            g,
            loadImpact = loadDeleteImpact,
            onArchive = {
                deleting = null
                archive(g)
            },
            onLeave = {
                deleting = null
                onLeaveGroup(g.id)
            },
            onDelete = {
                deleting = null
                onDeleteGroup(g.id)
                undo = HomeUndo("${g.name} deleted for everyone") { onRestoreGroup(g.id) }
            },
            onDismiss = { deleting = null },
        )
    }

    if (showActions) {
        EvSheetScaffold(onDismiss = { showActions = false }, title = "What are you splitting?") {
            // The one-time bill gets its own panel; the two group actions share a second one, so the
            // sheet reads as "a bill, or a group" rather than three peers.
            Column {
                ActionPanel {
                    ActionRow(EvIcons.Receipt, "Split a one-time bill", "For a single restaurant bill\nNo group needed") {
                        showActions = false
                        onSplitBill()
                    }
                }
                Spacer(Modifier.height(16.dp))
                ActionPanel {
                    ActionRow(EvIcons.Users, "Start a trip group", "Track every cost on a trip and settle up at the end.") {
                        showActions = false
                        onNewGroup()
                    }
                    EvDivider(Modifier.padding(start = 58.dp))
                    ActionRow(EvIcons.Link, "Join a friend's group", "Paste the invite link they sent you.") {
                        showActions = false
                        onJoin()
                    }
                }
            }
        }
    }
}

/**
 * A full-width "Create or Join Group" pill anchored to the bottom of Home, the one primary action.
 *
 * It floats over the list, so Android gives it M3's neutral FAB shadow; iOS has no button shadows,
 * so there it is a flat prominent fill (design/brainstorm/button-states.html).
 */
@Composable
private fun CreateOrJoinBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(16.dp)
            .then(if (isIOS()) Modifier else Modifier.shadow(elevation = 6.dp, shape = shape))
            .clip(shape)
            .background(c.blue)
            .clickable(onClick = onClick)
            .height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvIcon(EvIcons.Plus, size = 20.dp, tint = c.onAccent)
        Text("New", style = t.button.copy(color = c.onAccent, fontWeight = FontWeight.Bold))
    }
}

/**
 * A muted "shelf" row under the active groups: Archived, Recently deleted. Both are places groups go
 * rather than groups themselves, so they share one shape and read as secondary to the list above.
 */
@Composable
private fun ShelfRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    EvCard(fill = true, bordered = true, padded = true, onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EvIcon(icon, size = 18.dp, tint = c.ink2)
                Text(label, style = t.fieldLabel)
            }
            EvIcon(EvIcons.ChevR, size = 18.dp, tint = c.ink3)
        }
    }
}

/** A group row (Home + Archived). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupRow(
    g: GroupCardUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    archived: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
            Text(groupIconLabel(g.emoji, g.name), fontSize = 24.sp, color = c.ink)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    g.name,
                    style = t.itemTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (g.unread) EvDot()
            }
            Text(
                "${g.members} ${if (g.members == 1) "member" else "members"} · ${g.last}",
                style = t.description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            archived -> {
                EvChip("Unarchive", variant = ChipVariant.Ghost, leadingIcon = EvIcons.Archive)
            }

            g.status == GroupBalanceStatus.Empty -> {
                Unit
            }

            // no expenses yet → no status chip, just the "No expenses yet" subtitle
            g.status == GroupBalanceStatus.Settled -> {
                Text("Settled", style = t.chip.copy(color = c.settled))
            }

            else -> {
                // Group-level balance, color-coded: you owe → blue (the action); you're owed / in credit →
                // green (money coming back to you, matching settled balances).
                val owed = g.status == GroupBalanceStatus.Owed
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (owed) "you're owed" else "you owe",
                        style = t.chip.copy(color = if (owed) c.settled else c.owe),
                    )
                    EvChip(money(g.amount ?: 0.0), variant = if (owed) ChipVariant.Owed else ChipVariant.Owe, mono = true)
                }
            }
        }
    }
}

/** Sample data for previews + the static default (ignored once a ViewModel drives state in Phase B). */
internal object HomeSamples {
    val content =
        HomeUiState.Content(
            active =
                listOf(
                    GroupCardUi(
                        "1",
                        "🏝️",
                        "Tulum Trip",
                        5,
                        GroupBalanceStatus.Owe,
                        60.0,
                        "Andrew added Dinner · \$96 · 2h ago",
                        unread = true,
                    ),
                    GroupCardUi("2", "🏠", "The Mission Flat", 3, GroupBalanceStatus.Owed, 128.4, "You added Internet · \$80 · yesterday"),
                    GroupCardUi("3", "💸", "Weekend in Austin", 4, GroupBalanceStatus.Settled, null, "Maya settled up · 3d ago"),
                    GroupCardUi("4", "🎂", "Dad's 60th", 6, GroupBalanceStatus.Owe, 45.0, "Tyler added Cake · \$120 · 5d ago"),
                ),
            archived =
                listOf(
                    GroupCardUi("5", "⛷️", "Tahoe 2025", 5, GroupBalanceStatus.Settled, null, "Settled · Mar 2025"),
                    GroupCardUi("6", "🍝", "Supper Club", 8, GroupBalanceStatus.Settled, null, "Settled · Jan 2025"),
                ),
        )
}

@Preview
@Composable
private fun HomePopulatedPreview() {
    EvenlyTheme { HomeScreen(userName = "Alex") }
}

@Preview
@Composable
private fun HomeEmptyPreview() {
    EvenlyTheme { HomeScreen(state = HomeUiState.Empty, userName = "Alex") }
}
