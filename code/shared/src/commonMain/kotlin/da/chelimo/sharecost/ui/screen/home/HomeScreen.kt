package da.chelimo.sharecost.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScDot
import da.chelimo.sharecost.ui.components.ScFab
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScSheetScaffold
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
    userName: String = "there",
    onOpenGroup: (String) -> Unit = {},
    onNewGroup: () -> Unit = {},
    onNewGroupTemplate: (emoji: String, name: String) -> Unit = { _, _ -> },
    onJoin: () -> Unit = {},
    onAddExpenseInGroup: (String) -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenArchived: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var showActions by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
    val activeGroups = (state as? HomeUiState.Content)?.active ?: emptyList()

    Column(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        ScTopBar(
            title = "ShareCost",
            center = true,
            navIcon = { Box(Modifier.clip(RoundedCornerShape(99.dp)).clickable { onOpenProfile() }) { ScAvatar(userName, me = true) } },
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state) {
                HomeUiState.Loading -> Column(Modifier.padding(top = 8.dp)) { repeat(3) { ScSkeletonRow() } }
                HomeUiState.Empty -> HomeWelcome(userName, onNewGroup, onJoin, onNewGroupTemplate)
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
                        ScCard(padded = true, modifier = Modifier.clickable { onOpenArchived() }) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ScIcon(ScIcons.Archive, size = 18.dp, tint = c.ink2)
                                    Text("Archived (${state.archived.size})", color = c.ink2, fontWeight = FontWeight.SemiBold)
                                }
                                ScIcon(ScIcons.ChevR, size = 18.dp, tint = c.ink3)
                            }
                        }
                    }
                    Spacer(Modifier.height(80.dp))
                }
            }
            if (state is HomeUiState.Content) {
                ScFab(onClick = { showActions = true }, label = "New", modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        }
    }

    if (showActions) {
        ScSheetScaffold(onDismiss = { showActions = false }, title = "Create") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionRow(ScIcons.Users, "New group", "Start splitting with a new crew") { showActions = false; onNewGroup() }
                if (activeGroups.isNotEmpty()) {
                    ActionRow(ScIcons.Receipt, "Add an expense", "Log a cost in one of your groups") {
                        showActions = false
                        if (activeGroups.size == 1) onAddExpenseInGroup(activeGroups.first().id) else showGroupPicker = true
                    }
                }
            }
        }
    }

    if (showGroupPicker) {
        ScSheetScaffold(onDismiss = { showGroupPicker = false }, title = "Add to which group?") {
            Column {
                activeGroups.forEachIndexed { i, g ->
                    Box(if (i > 0) Modifier.topHairline(c.border) else Modifier) {
                        Row(
                            Modifier.fillMaxWidth().clickable { showGroupPicker = false; onAddExpenseInGroup(g.id) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                                Text(g.emoji, fontSize = 20.sp)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(g.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${g.members} members", color = c.ink2, fontSize = 13.sp)
                            }
                            ScIcon(ScIcons.ChevR, size = 18.dp, tint = c.ink3)
                        }
                    }
                }
            }
        }
    }
}

/** First-run welcome: greeting, the two entry paths, a 3-step primer, and one-tap group templates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeWelcome(
    userName: String,
    onNewGroup: () -> Unit,
    onJoin: () -> Unit,
    onTemplate: (emoji: String, name: String) -> Unit,
) {
    val c = ShareCostTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Welcome, $userName", color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Track who owes whom and settle up — no spreadsheets, no awkward texts.",
                color = c.ink2, fontSize = 14.sp, lineHeight = 21.sp,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ScButton("Start a group", onNewGroup, leadingIcon = ScIcons.Plus)
            ScButton("Join with a link", onJoin, variant = ButtonVariant.Secondary, leadingIcon = ScIcons.Link)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScSectionLabel("How it works")
            HowStep(1, "Create a group & invite people")
            HowStep(2, "Add expenses as you go")
            HowStep(3, "Settle up in a tap")
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ScSectionLabel("Or start from a template")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TemplateChip("🏝️", "Trip", onTemplate)
                TemplateChip("🏠", "Apartment", onTemplate)
                TemplateChip("🍝", "Dinner", onTemplate)
                TemplateChip("🎉", "Event", onTemplate)
            }
        }
    }
}

@Composable
private fun HowStep(n: Int, text: String) {
    val c = ShareCostTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
            Text("$n", color = c.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = c.ink, fontSize = 14.sp)
    }
}

@Composable
private fun TemplateChip(emoji: String, label: String, onTemplate: (emoji: String, name: String) -> Unit) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(11.dp)
    Row(
        Modifier.clip(shape).background(c.page).border(1.dp, c.border, shape).clickable { onTemplate(emoji, label) }.padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(emoji, fontSize = 16.sp)
        Text(label, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
            ScIcon(icon, size = 22.dp, tint = c.blue)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = c.ink2, fontSize = 13.sp)
        }
        ScIcon(ScIcons.ChevR, size = 18.dp, tint = c.ink3)
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
    ShareCostTheme { HomeScreen(userName = "Alex") }
}

@Preview
@Composable
private fun HomeEmptyPreview() {
    ShareCostTheme { HomeScreen(state = HomeUiState.Empty, userName = "Alex") }
}
