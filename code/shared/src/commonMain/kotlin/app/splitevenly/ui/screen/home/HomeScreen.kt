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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.BannerVariant
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.ChipVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvDot
import app.splitevenly.ui.components.EvSectionLabel
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.EvSkeletonRow
import app.splitevenly.ui.components.groupIconLabel
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.money
import app.splitevenly.ui.theme.EvenlyTheme

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
)

enum class GroupBalanceStatus { Owe, Owed, Settled, Empty }

sealed interface HomeUiState {
    data object Loading : HomeUiState

    data object Empty : HomeUiState

    /**
     * [deletedCount] drives the Recently deleted row. A count rather than a list because the row only
     * ever shows one, and the screen behind it re-reads the groups itself.
     */
    data class Content(
        val active: List<GroupCardUi>,
        val archived: List<GroupCardUi>,
        val deletedCount: Int = 0,
    ) : HomeUiState
}

/** 4 · Home / group list (design/src/screens-home.jsx). */
@Composable
fun HomeScreen(
    state: HomeUiState = HomeSamples.content,
    userName: String = "there",
    onOpenGroup: (String) -> Unit = {},
    onNewGroup: () -> Unit = {},
    onJoin: () -> Unit = {},
    onOpenArchived: () -> Unit = {},
    onOpenRecentlyDeleted: () -> Unit = {},
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
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    var showActions by remember { mutableStateOf(false) }
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
                text = if (isContent) "Your Groups" else "Evenly",
                modifier = Modifier.weight(1f),
                style = t.pageTitle.copy(fontWeight = if (isContent) FontWeight.ExtraBold else FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            EvAvatar(userName, me = true, size = AvatarSize.Md, onClick = onOpenSettings)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state) {
                HomeUiState.Loading -> {
                    Column(Modifier.padding(top = 8.dp)) { repeat(3) { EvSkeletonRow() } }
                }

                HomeUiState.Empty -> {
                    HomeWelcome(onNewGroup, onJoin)
                }

                is HomeUiState.Content -> {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        deletedAlert?.let {
                            EvBanner(
                                it,
                                variant = BannerVariant.Amber,
                                leadingIcon = EvIcons.Trash,
                                modifier = Modifier.clickable(onClick = onOpenRecentlyDeleted),
                            )
                        }
                        Column {
                            EvSectionLabel("Active groups")
                            Spacer(Modifier.height(4.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                state.active.forEach { g ->
                                    EvCard(fill = true, bordered = true) { GroupRow(g, onClick = { onOpenGroup(g.id) }) }
                                }
                            }
                        }
                        if (state.archived.isNotEmpty()) {
                            ShelfRow(
                                icon = EvIcons.Archive,
                                label = "Archived (${state.archived.size})",
                                onClick = onOpenArchived,
                            )
                        }
                        // Only when something is actually in there: nobody should carry a permanent trash
                        // can around, and an always-present "Recently deleted (0)" invites the question of
                        // what was deleted when the honest answer is "nothing".
                        if (state.deletedCount > 0) {
                            ShelfRow(
                                icon = EvIcons.Trash,
                                label = "Recently deleted (${state.deletedCount})",
                                onClick = onOpenRecentlyDeleted,
                            )
                        }
                        Spacer(Modifier.height(88.dp))
                    }
                }
            }
            if (state is HomeUiState.Content) {
                // A single home-level action — you create or join a *group* here; expenses are only
                // ever added from inside a group, so "Add an expense" no longer lives at this level. A
                // full-width bottom pill rather than a corner FAB, matching the approved mockup.
                CreateOrJoinBar(onClick = { showActions = true }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    if (showActions) {
        EvSheetScaffold(onDismiss = { showActions = false }, title = "Create or join a group") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionRow(EvIcons.Users, "New group", "Start splitting with a new crew") {
                    showActions = false
                    onNewGroup()
                }
                ActionRow(EvIcons.Link, "Join with a link", "Paste an invite to hop into a group") {
                    showActions = false
                    onJoin()
                }
            }
        }
    }
}

/**
 * First-run welcome — no template chips, no numbered primer. One hero, one headline, one job: get
 * the first group started. "Join with a link" sits right under the primary action as a full
 * secondary button — same spot as before, just no longer a barely-there link.
 */
@Composable
private fun HomeWelcome(
    onNewGroup: () -> Unit,
    onJoin: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(c.blue, c.bluePressed))),
            contentAlignment = Alignment.Center,
        ) {
            EvIcon(EvIcons.Users, size = 44.dp, tint = c.onAccent)
        }
        Spacer(Modifier.height(24.dp))
        Text("Start your first group", style = t.screenTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Split costs and settle up with friends, no spreadsheets.",
            style = t.description,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 280.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // In dark mode the white-chip Primary reads as barely-there against the near-black page, so
            // this hero action swaps to the solid-blue variant there; Secondary now handles its own
            // light/dark treatment internally.
            EvButton(
                "Start a group",
                onNewGroup,
                variant = if (c.isDark) ButtonVariant.PrimarySolid else ButtonVariant.Primary,
                leadingIcon = EvIcons.Plus,
            )
            EvButton(
                "Join with a link",
                onJoin,
                variant = ButtonVariant.Secondary,
                leadingIcon = EvIcons.Link,
            )
        }
    }
}

/** A tappable "icon tile + title + subtitle" row used in the home Create sheet. */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
            EvIcon(icon, size = 22.dp, tint = c.blueText)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = t.itemTitle)
            Text(sub, style = t.description)
        }
        EvIcon(EvIcons.ChevR, size = 18.dp, tint = c.ink3)
    }
}

/**
 * A full-width "Create or Join Group" pill anchored to the bottom of Home — the one primary action,
 * a solid blue fill (not the white-chip `ButtonVariant.Primary`) so it reads as a floating CTA over
 * the group list, matching the approved mockup.
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
            .shadow(elevation = 10.dp, shape = shape, ambientColor = c.blue.copy(alpha = 0.5f), spotColor = c.blue.copy(alpha = 0.5f))
            .clip(shape)
            .background(c.blue)
            .clickable(onClick = onClick)
            .height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvIcon(EvIcons.Plus, size = 20.dp, tint = c.onAccent)
        Text("Create or Join Group", style = t.button.copy(color = c.onAccent, fontWeight = FontWeight.Bold))
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
@Composable
fun GroupRow(
    g: GroupCardUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    archived: Boolean = false,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
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
                EvChip("Settled", variant = ChipVariant.Green, leadingIcon = EvIcons.Check)
            }

            else -> {
                // Group-level balance, color-coded: you owe → blue (the action); you're owed / in credit →
                // amber (money coming back to you — draws attention without red's "you did wrong").
                val owed = g.status == GroupBalanceStatus.Owed
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (owed) "you're owed" else "you owe",
                        style = t.chip.copy(color = if (owed) c.credit else c.owe),
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
