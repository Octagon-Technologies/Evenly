package da.chelimo.sharecost.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScDot
import da.chelimo.sharecost.ui.components.ScEmptyState
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScSkeletonRow
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.components.topHairline
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

enum class GroupBalanceStatus { Owe, Owed, Settled }

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Empty : HomeUiState
    data class Content(val active: List<GroupCardUi>, val archived: List<GroupCardUi>) : HomeUiState
}

/** 4 · Home / group list (design/src/screens-home.jsx). */
@Composable
fun HomeScreen(
    state: HomeUiState = HomeSamples.content,
    onOpenGroup: (String) -> Unit = {},
    onNewGroup: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    Column(Modifier.fillMaxSize().background(c.surface)) {
        ScTopBar(
            title = "ShareCost",
            center = true,
            navIcon = { Box(Modifier.clickable { onOpenProfile() }) { ScAvatar("Alex Rivera", me = true) } },
            actions = { ScIconButton(ScIcons.Plus, onNewGroup, tint = c.blue, size = 24.dp) },
        )
        when (state) {
            HomeUiState.Loading -> Column(Modifier.padding(top = 8.dp)) { repeat(3) { ScSkeletonRow() } }
            HomeUiState.Empty -> ScEmptyState(
                icon = ScIcons.Users,
                title = "No groups yet",
                text = "Create a group to start tracking who owes whom on your next trip or shared bill.",
                ctaText = "Create a group",
                onCta = onNewGroup,
            )
            is HomeUiState.Content -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column {
                    ScSectionLabel("Active groups")
                    ScCard {
                        state.active.forEachIndexed { i, g ->
                            Box(if (i > 0) Modifier.topHairline(c.border) else Modifier) {
                                GroupRow(g, onClick = { onOpenGroup(g.id) })
                            }
                        }
                    }
                }
                if (state.archived.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    ScCard(padded = true, modifier = Modifier.clickable { expanded = !expanded }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ScIcon(ScIcons.Archive, size = 18.dp, tint = c.ink2)
                                Text("Archived (${state.archived.size})", color = c.ink2, fontWeight = FontWeight.SemiBold)
                            }
                            ScIcon(if (expanded) ScIcons.ChevU else ScIcons.ChevD, size = 18.dp, tint = c.ink3)
                        }
                    }
                    if (expanded) {
                        ScCard {
                            state.archived.forEachIndexed { i, g ->
                                Box(if (i > 0) Modifier.topHairline(c.border) else Modifier) { GroupRow(g, archived = true) }
                            }
                        }
                    }
                }
            }
        }
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
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(c.surface), contentAlignment = Alignment.Center) {
            Text(g.emoji, fontSize = 24.sp)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(g.name, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (g.unread) ScDot()
            }
            Text("${g.members} members · ${g.last}", color = c.ink2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            archived -> ScChip("Unarchive", variant = ChipVariant.Ghost, leadingIcon = ScIcons.Archive)
            g.status == GroupBalanceStatus.Settled -> ScChip("Settled", variant = ChipVariant.Blue, leadingIcon = ScIcons.Check)
            else -> Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (g.status == GroupBalanceStatus.Owe) "you owe" else "you're owed", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                ScChip(money(g.amount ?: 0.0), variant = if (g.status == GroupBalanceStatus.Owed) ChipVariant.Solid else ChipVariant.Neutral, mono = true)
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
    ShareCostTheme { HomeScreen() }
}

@Preview
@Composable
private fun HomeEmptyPreview() {
    ShareCostTheme { HomeScreen(state = HomeUiState.Empty) }
}
