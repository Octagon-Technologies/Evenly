package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.expense.ItemStatus
import app.splitevenly.ui.components.BannerVariant
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.EvSkeleton
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Everything the accordion draws. The section lists come from `AssignOrder.layout`, so the screen never
 * decides ordering itself; [resolvedCount] comes from the bill's reconcile, never from chip state.
 */
data class AssignScreenState(
    val title: String,
    val currency: String,
    val totalSubunits: Long,
    val items: List<ClaimItemUi>,
    val completedIds: List<String>,
    val unfinishedIds: List<String>,
    val activeItemId: String?,
    val participants: List<ClaimParticipantUi>,
    val resolvedCount: Int,
) {
    val remainingCount: Int get() = items.size - resolvedCount
}

/**
 * "Assign the items" — one open card at a time (frames 5A, 6A, 11). Tapping a header opens that card
 * and closes the last one; the route owns the completion order, so a card just completed under the
 * user's finger stays put until focus moves. The phone holder may assign for anyone, or only their own
 * items and leave the rest. Multi-serving lines open the existing per-serving sheet. The web-claim doors
 * (QR, progress, "What changed") and bill editing sit in the top bar's menu. DI-free.
 */
@Composable
fun BillAssignScreen(
    state: AssignScreenState,
    onBack: () -> Unit = {},
    onOpenItem: (itemId: String?) -> Unit = {},
    // Replaces a single-serving line's assignees: they share the whole line.
    onSetMembers: (itemId: String, memberIds: List<String>) -> Unit = { _, _ -> },
    onSetServings: (itemId: String, servings: List<List<String>>) -> Unit = { _, _ -> },
    onAddPerson: (name: String) -> Unit = {},
    onEditBill: () -> Unit = {},
    onShowQr: () -> Unit = {},
    onClaimProgress: () -> Unit = {},
    onReviewEdits: () -> Unit = {},
    onReview: () -> Unit = {},
    unseenChangeCount: Int = 0,
    changeCount: Int = 0,
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    var servingsItemId by remember { mutableStateOf<String?>(null) }
    var showAddPerson by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val byId = state.items.associateBy { it.id }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.page)) {
            StatusBarScrim()
            EvTopBar(
                title = "Assign the items",
                subtitle = "${state.title} · ${moneySubunits(state.totalSubunits, state.currency)}",
                navIcon = { EvIconButton(EvIcons.Back, onBack) },
                actions = { EvIconButton(EvIcons.More, { showMenu = true }) },
                showDivider = false,
            )
            notice?.let {
                Row(Modifier.fillMaxWidth().clickable { onDismissNotice() }) {
                    EvBanner(it, variant = BannerVariant.Amber, leadingIcon = EvIcons.WifiOff)
                }
            }
            PendingEditsBanner(unseenChangeCount, onReviewEdits)
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                AssignProgressHeader(state.resolvedCount, state.items.size)
            }

            val listState = rememberLazyListState()
            // A card opened from elsewhere ("Assign" on review or progress) may be off screen: bring it
            // in. One opened by tapping is already visible, so this leaves the list where it is.
            LaunchedEffect(state.activeItemId) {
                val id = state.activeItemId ?: return@LaunchedEffect
                val keys =
                    buildList {
                        if (state.completedIds.isNotEmpty()) add("h-done")
                        addAll(state.completedIds)
                        if (state.unfinishedIds.isNotEmpty()) add("h-todo")
                        addAll(state.unfinishedIds)
                    }
                val index = keys.indexOf(id)
                if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.key == id }) listState.animateScrollToItem(index)
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fun section(
                    key: String,
                    label: String,
                    ids: List<String>,
                ) {
                    if (ids.isEmpty()) return
                    item(key = key) { SectionHeader(label, ids.size) }
                    items(ids, key = { it }) { id ->
                        val item = byId[id] ?: return@items
                        AccordionCard(
                            item = item,
                            open = id == state.activeItemId,
                            participants = state.participants,
                            currency = state.currency,
                            onHeaderClick = { onOpenItem(if (id == state.activeItemId) null else id) },
                            onToggle = { uid ->
                                val now = item.assignedMemberIds
                                onSetMembers(id, (if (uid in now) now - uid else now + uid).toList())
                            },
                            onEveryone = { onSetMembers(id, state.participants.map { it.userId }) },
                            onOpenServings = { servingsItemId = id },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                section("h-done", "Assigned", state.completedIds)
                section("h-todo", "Still to assign", state.unfinishedIds)
                item(key = "add-person") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showAddPerson = true }
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EvIcon(EvIcons.Plus, size = 16.dp, tint = c.blueText)
                        Text("Add someone new", color = c.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Box(Modifier.fillMaxWidth().topHairline(c.border).padding(16.dp)) {
                EvButton(onReview) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Review assignments", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (state.remainingCount) {
                                0 -> "Every item is assigned"
                                1 -> "1 item still needs someone"
                                else -> "${state.remainingCount} items still need someone"
                            },
                            fontSize = 11.5.sp,
                        )
                    }
                }
            }
        }

        servingsItemId?.let { id -> byId[id] }?.let { item ->
            ServingsSheet(
                item = item,
                participants = state.participants,
                currency = state.currency,
                onSetServings = { servings -> onSetServings(item.id, servings) },
                onDismiss = { servingsItemId = null },
            )
        }

        if (showMenu) {
            EvModalScaffold(onDismiss = { showMenu = false }) {
                MenuRow(EvIcons.Qr, "Show the QR code") {
                    showMenu = false
                    onShowQr()
                }
                MenuRow(EvIcons.Users, "Claim progress") {
                    showMenu = false
                    onClaimProgress()
                }
                if (changeCount > 0) {
                    MenuRow(EvIcons.History, "What changed ($changeCount)") {
                        showMenu = false
                        onReviewEdits()
                    }
                }
                MenuRow(EvIcons.Plus, "Add someone new") {
                    showMenu = false
                    showAddPerson = true
                }
                MenuRow(EvIcons.Edit, "Edit bill") {
                    showMenu = false
                    onEditBill()
                }
            }
        }

        if (showAddPerson) {
            var name by remember { mutableStateOf("") }
            EvModalScaffold(onDismiss = { showAddPerson = false }) {
                Text(
                    "Add a person",
                    color = c.ink,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    "They join the bill so you can assign items to them, even if they don't have the app.",
                    color = c.ink2,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                EvField("Name") { EvTextField(name, { name = it }, placeholder = "e.g. Mary") }
                Box(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    EvButton("Add", {
                        if (name.isNotBlank()) {
                            onAddPerson(name.trim())
                            showAddPerson = false
                        }
                    }, enabled = name.isNotBlank())
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    label: String,
    count: Int,
) {
    val c = EvenlyTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp)) {
        Text(label.uppercase(), style = EvenlyTheme.text.sectionLabel, modifier = Modifier.weight(1f))
        Text("$count", color = c.ink3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EvIcon(icon, size = 19.dp, tint = c.blueText)
        Text(label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

private fun previewItem(
    n: Int,
    label: String,
    qty: Int,
    total: Long,
    rows: List<AssignRowUi>,
    status: ItemStatus,
) = ClaimItemUi("i$n", label, qty, total, rows, status, number = n)

@Preview
@Composable
private fun BillAssignPreview() {
    val you = ClaimParticipantUi("u1", "You", true)
    val purity = ClaimParticipantUi("u2", "Purity", false)
    val items =
        listOf(
            previewItem(
                1,
                "Spicy tuna rolls",
                1,
                1800,
                listOf(AssignRowUi("p", listOf("u1", "u2"), listOf("You", "Purity"), 1, 1800)),
                ItemStatus.RESOLVED,
            ),
            previewItem(2, "Garlic noodles", 1, 1600, listOf(AssignRowUi("p", listOf("u1"), listOf("You"), 1, 1600)), ItemStatus.RESOLVED),
            previewItem(3, "Three lemonades", 3, 1500, emptyList(), ItemStatus.UNCLAIMED),
        )
    EvenlyTheme {
        BillAssignScreen(
            AssignScreenState("Large dinner", "USD", 8600, items, listOf("i1"), listOf("i2", "i3"), "i2", listOf(you, purity), 2),
        )
    }
}

/** The assign route before the bill and its session are ready: the page, the bar, and shaped rows. */
@Composable
fun BillAssignLoading(onBack: () -> Unit) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim()
        EvTopBar(title = "Assign the items", navIcon = { EvIconButton(EvIcons.Back, onBack) }, showDivider = false)
        BillAssignSkeleton()
    }
}

@Composable
internal fun BillAssignSkeleton() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EvSkeleton(Modifier.fillMaxWidth(), height = 18.dp)
        repeat(4) { EvSkeleton(Modifier.fillMaxWidth(), height = 52.dp, radius = 13.dp) }
    }
}
