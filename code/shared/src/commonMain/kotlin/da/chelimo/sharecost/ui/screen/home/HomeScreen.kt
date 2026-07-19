package da.chelimo.sharecost.ui.screen.home

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.shadow
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScDot
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScSheetScaffold
import da.chelimo.sharecost.ui.components.ScSkeletonRow
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

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
    data class Content(val active: List<GroupCardUi>, val archived: List<GroupCardUi>) : HomeUiState
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
    onOpenSettings: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
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
                text = if (isContent) "Your Groups" else "ShareCost",
                modifier = Modifier.weight(1f),
                color = c.ink,
                fontSize = 28.sp,
                fontWeight = if (isContent) FontWeight.ExtraBold else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ScAvatar(userName, me = true, size = AvatarSize.Md, onClick = onOpenSettings)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state) {
                HomeUiState.Loading -> Column(Modifier.padding(top = 8.dp)) { repeat(3) { ScSkeletonRow() } }
                HomeUiState.Empty -> HomeWelcome(onNewGroup, onJoin)
                is HomeUiState.Content -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column {
                        ScSectionLabel("Active groups")
                        Spacer(Modifier.height(4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            state.active.forEach { g ->
                                ScCard(fill = true, bordered = true) { GroupRow(g, onClick = { onOpenGroup(g.id) }) }
                            }
                        }
                    }
                    if (state.archived.isNotEmpty()) {
                        ScCard(fill = true, bordered = true, padded = true, onClick = onOpenArchived) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ScIcon(ScIcons.Archive, size = 18.dp, tint = c.ink2)
                                    Text("Archived (${state.archived.size})", color = c.ink2, fontWeight = FontWeight.SemiBold)
                                }
                                ScIcon(ScIcons.ChevR, size = 18.dp, tint = c.ink3)
                            }
                        }
                    }
                    Spacer(Modifier.height(88.dp))
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
        ScSheetScaffold(onDismiss = { showActions = false }, title = "Create or join a group") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionRow(ScIcons.Users, "New group", "Start splitting with a new crew") { showActions = false; onNewGroup() }
                ActionRow(ScIcons.Link, "Join with a link", "Paste an invite to hop into a group") { showActions = false; onJoin() }
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
    val c = ShareCostTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(c.blue, c.bluePressed))),
            contentAlignment = Alignment.Center,
        ) {
            ScIcon(ScIcons.Users, size = 44.dp, tint = c.onAccent)
        }
        Spacer(Modifier.height(24.dp))
        Text("Start your first group", color = c.ink, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Split costs and settle up with friends, no spreadsheets.",
            color = c.ink2, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 280.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // In dark mode the white-chip Primary reads as barely-there against the near-black page, so
            // this hero action swaps to the solid-blue variant there; Secondary now handles its own
            // light/dark treatment internally.
            ScButton(
                "Start a group", onNewGroup,
                variant = if (c.isDark) ButtonVariant.PrimarySolid else ButtonVariant.Primary,
                leadingIcon = ScIcons.Plus,
            )
            ScButton(
                "Join with a link", onJoin,
                variant = ButtonVariant.Secondary,
                leadingIcon = ScIcons.Link,
            )
        }
    }
}

/** A tappable "icon tile + title + subtitle" row used in the home Create sheet. */
@Composable
private fun ActionRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val c = ShareCostTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
            ScIcon(icon, size = 22.dp, tint = c.blueText)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = c.ink2, fontSize = 13.sp)
        }
        ScIcon(ScIcons.ChevR, size = 18.dp, tint = c.ink3)
    }
}

/**
 * A full-width "Create or Join Group" pill anchored to the bottom of Home — the one primary action,
 * a solid blue fill (not the white-chip `ButtonVariant.Primary`) so it reads as a floating CTA over
 * the group list, matching the approved mockup.
 */
@Composable
private fun CreateOrJoinBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = ShareCostTheme.colors
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
        ScIcon(ScIcons.Plus, size = 20.dp, tint = c.onAccent)
        Text("Create or Join Group", color = c.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** A group row (Home + Archived). */
@Composable
fun GroupRow(g: GroupCardUi, modifier: Modifier = Modifier, onClick: () -> Unit = {}, archived: Boolean = false) {
    val c = ShareCostTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
            Text(g.emoji, fontSize = 24.sp)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(g.name, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (g.unread) ScDot()
            }
            Text("${g.members} ${if (g.members == 1) "member" else "members"} · ${g.last}", color = c.ink2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            archived -> ScChip("Unarchive", variant = ChipVariant.Ghost, leadingIcon = ScIcons.Archive)
            g.status == GroupBalanceStatus.Empty -> Unit // no expenses yet → no status chip, just the "No expenses yet" subtitle
            g.status == GroupBalanceStatus.Settled -> ScChip("Settled", variant = ChipVariant.Green, leadingIcon = ScIcons.Check)
            else -> {
                // Group-level balance, color-coded: you owe → blue (the action); you're owed / in credit →
                // amber (money coming back to you — draws attention without red's "you did wrong").
                val owed = g.status == GroupBalanceStatus.Owed
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (owed) "you're owed" else "you owe", color = if (owed) c.credit else c.owe, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    ScChip(money(g.amount ?: 0.0), variant = if (owed) ChipVariant.Owed else ChipVariant.Owe, mono = true)
                }
            }
        }
    }
}

/** Sample data for previews + the static default (ignored once a ViewModel drives state in Phase B). */
internal object HomeSamples {
    val content = HomeUiState.Content(
        active = listOf(
            GroupCardUi("1", "🏝️", "Tulum Trip", 5, GroupBalanceStatus.Owe, 60.0, "Andrew added Dinner · \$96 · 2h ago", unread = true),
            GroupCardUi("2", "🏠", "The Mission Flat", 3, GroupBalanceStatus.Owed, 128.4, "You added Internet · \$80 · yesterday"),
            GroupCardUi("3", "💸", "Weekend in Austin", 4, GroupBalanceStatus.Settled, null, "Maya settled up · 3d ago"),
            GroupCardUi("4", "🎂", "Dad's 60th", 6, GroupBalanceStatus.Owe, 45.0, "Tyler added Cake · \$120 · 5d ago"),
        ),
        archived = listOf(
            GroupCardUi("5", "⛷️", "Tahoe 2025", 5, GroupBalanceStatus.Settled, null, "Settled · Mar 2025"),
            GroupCardUi("6", "🍝", "Supper Club", 8, GroupBalanceStatus.Settled, null, "Settled · Jan 2025"),
        ),
    )
}

@Preview
@Composable
private fun HomePopulatedPreview() {
    ShareCostTheme { HomeScreen(userName = "Alex") }
}

@Preview
@Composable
private fun HomeEmptyPreview() {
    ShareCostTheme { HomeScreen(state = HomeUiState.Empty, userName = "Alex") }
}
