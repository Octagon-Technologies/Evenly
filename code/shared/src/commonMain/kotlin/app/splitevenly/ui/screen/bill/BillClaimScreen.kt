package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.expense.ItemStatus
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvAvatarStack
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvDragSheet
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.EvParticipantChip
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/** A person the bill is for. */
data class ClaimParticipantUi(val userId: String, val name: String, val isMe: Boolean)

/** One assignment on a line: a solo claim ([portionId] == null) or a shared slice ([portionId] set). */
data class AssignRowUi(
    val portionId: String?,
    val memberIds: List<String>,
    val memberNames: List<String>,
    val quantity: Int,
    val amountSubunits: Long,
) {
    val isShared: Boolean get() = memberIds.size >= 2
    val perHeadSubunits: Long get() = if (memberIds.isEmpty()) amountSubunits else amountSubunits / memberIds.size
}

/** One line on the assign screen, as who-had-what. */
data class ClaimItemUi(
    val id: String,
    val label: String,
    val quantity: Int,
    val lineTotalSubunits: Long,
    val rows: List<AssignRowUi>,
    val status: ItemStatus,
) {
    val assignedQty: Int get() = rows.sumOf { it.quantity }
    val leftQty: Int get() = (quantity - assignedQty).coerceAtLeast(0)
    val assignedMemberIds: Set<String> get() = rows.flatMapTo(HashSet()) { it.memberIds }

    /** Flattens [rows] into one member-set per physical unit ("serving"). Rows are ordered by the slot
     *  index encoded in their portionId (`"<itemId>__slot<N>"`, written by the servings sheet) rather
     *  than arrival order — the DB read order isn't guaranteed to match, and getting this wrong silently
     *  scrambles which serving shows which names. Anything not in that shape (legacy data) sorts last.
     *  This is purely a display/edit convenience: every write from the sheet re-derives a fresh
     *  one-portion-per-serving shape, so real data converges on the slot-indexed form after one edit. */
    fun servingSlots(): List<Set<String>> {
        val ordered = rows.sortedBy { row -> row.portionId?.let { SlotSuffix.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() } ?: Int.MAX_VALUE }
        val slots = ArrayList<Set<String>>(quantity)
        for (row in ordered) repeat(row.quantity) { if (slots.size < quantity) slots.add(row.memberIds.toSet()) }
        while (slots.size < quantity) slots.add(emptySet())
        return slots
    }

    private companion object {
        val SlotSuffix = Regex(".*__slot(\\d+)$")
    }
}

data class ClaimBillState(
    val title: String,
    val currency: String,
    val totals: List<Pair<ClaimParticipantUi, Long>>,
    val items: List<ClaimItemUi>,
    val participants: List<ClaimParticipantUi> = emptyList(),
    val myUserId: String? = null,
) {
    val unassignedCount: Int get() = items.count { it.leftQty > 0 || it.rows.isEmpty() }
}

/**
 * "Who had what?" — the assign screen. A single-order item assigns directly: tap the people who had
 * it (the same selectable chip used to pick participants elsewhere) — works for anyone, even friends
 * without the app. A multi-order item ("4 bowls of chicken") opens a sheet that breaks it into one
 * card per physical serving, each independently assignable to one or more people. Every figure is a
 * plain "who pays what", never a button. DI-free.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BillClaimScreen(
    state: ClaimBillState,
    onBack: () -> Unit = {},
    // Simple "everyone who's tapped shares the whole line" — one portion covering all units (qty == 1 only).
    onSetEveryone: (itemId: String, memberIds: List<String>) -> Unit = { _, _ -> },
    onClearEveryone: (itemId: String) -> Unit = {},
    // Replaces the item's entire serving assignment: one member-id list per physical unit, in order.
    onSetServings: (itemId: String, servings: List<List<String>>) -> Unit = { _, _ -> },
    onAddPerson: (name: String) -> Unit = {},
    onEditBill: () -> Unit = {},
    onDone: () -> Unit = {},
    // True on a user's first couple of visits — auto-expands the numbered how-to. Reopenable anytime.
    guideAutoOpen: Boolean = false,
) {
    val c = EvenlyTheme.colors
    var servingsItemId by remember { mutableStateOf<String?>(null) }
    var showAddPerson by remember { mutableStateOf(false) }
    // The one-liner is the resting state; the full numbered guide expands over it on first visits or on tap.
    var guideOpen by remember { mutableStateOf(false) }
    LaunchedEffect(guideAutoOpen) { if (guideAutoOpen) guideOpen = true }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.page)) {
            StatusBarScrim()
            EvTopBar(title = "Who had what?", navIcon = { EvIconButton(EvIcons.Back, onBack) }, showDivider = false)

            if (state.totals.isNotEmpty()) {
                TotalsBar(state.totals, state.currency, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            ClaimGuide(
                open = guideOpen,
                onExpand = { guideOpen = true },
                onCollapse = { guideOpen = false },
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp),
            )

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    AssignItemCard(
                        item = item,
                        participants = state.participants,
                        currency = state.currency,
                        onToggleEveryone = { uid ->
                            val next = if (uid in item.assignedMemberIds) item.assignedMemberIds - uid else item.assignedMemberIds + uid
                            onSetEveryone(item.id, next.toList())
                        },
                        onSplitAmongAll = { onSetEveryone(item.id, state.participants.map { it.userId }) },
                        onOpenServings = { servingsItemId = item.id },
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showAddPerson = true }.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EvIcon(EvIcons.Plus, size = 16.dp, tint = c.blueText)
                        Text("Add someone new", color = c.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Column(Modifier.fillMaxWidth().topHairline(c.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.unassignedCount > 0) {
                    PendingPill(state.unassignedCount)
                }
                EvButton("Edit menu & prices", onEditBill, variant = ButtonVariant.Secondary, leadingIcon = EvIcons.Edit)
                EvButton("Done", onDone, variant = ButtonVariant.Primary)
            }
        }

        val servingsItem = servingsItemId?.let { id -> state.items.firstOrNull { it.id == id } }
        if (servingsItem != null) {
            ServingsSheet(
                item = servingsItem,
                participants = state.participants,
                currency = state.currency,
                onSetServings = { servings -> onSetServings(servingsItem.id, servings) },
                onDismiss = { servingsItemId = null },
            )
        }

        if (showAddPerson) {
            var name by remember { mutableStateOf("") }
            EvModalScaffold(onDismiss = { showAddPerson = false }) {
                Text("Add a person", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                Text("They join the bill so you can assign items to them, even if they don't have the app.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                EvField("Name") { EvTextField(name, { name = it }, placeholder = "e.g. Mary") }
                Box(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    EvButton("Add", { if (name.isNotBlank()) { onAddPerson(name.trim()); showAddPerson = false } }, enabled = name.isNotBlank())
                }
            }
        }
    }
}

/** Persistent, collapsed-by-default totals bar shown above the item list (outside the scroll, so it's
 *  always visible). Collapsed it's a one-line status strip: a few avatars + the current user's running
 *  total, split onto its own label/amount lines so they never read as one concatenated string. Tapping
 *  expands it in place to show everyone's line; tapping again collapses it. */
@Composable
private fun TotalsBar(totals: List<Pair<ClaimParticipantUi, Long>>, currency: String, modifier: Modifier = Modifier) {
    val c = EvenlyTheme.colors
    var open by remember { mutableStateOf(false) }
    val myTotal = totals.firstOrNull { it.first.isMe }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.surface)
            .border(1.dp, if (open) c.blue else c.border, shape)
            .clickable { open = !open },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EvAvatarStack(totals.map { if (it.first.isMe) "You" else it.first.name }.take(3), size = AvatarSize.Xs)
            Text("Totals", color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (myTotal != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("Your total", color = c.ink3, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(moneySubunits(myTotal.second, currency), color = c.blueText, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = EvenlyTheme.monoFamily)
                }
            }
            EvIcon(if (open) EvIcons.ChevU else EvIcons.ChevD, size = 12.dp, tint = c.ink3)
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().topHairline(c.border).padding(horizontal = 13.dp, vertical = 6.dp),
            ) {
                totals.forEach { (p, amount) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        EvAvatar(p.name, me = p.isMe, size = AvatarSize.Xs)
                        Text(if (p.isMe) "You" else p.name, color = if (p.isMe) c.blue else c.ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(moneySubunits(amount, currency), color = if (p.isMe) c.blue else c.ink2, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, fontFamily = EvenlyTheme.monoFamily)
                    }
                }
            }
        }
    }
}

/**
 * How-to guidance for a technique first-timers won't know. Two states: a **resting one-liner** (always
 * there once you've seen it), and an **expanded numbered card** — the actual walkthrough — that auto-opens
 * on a user's first couple of visits (see [BillClaimScreen.guideAutoOpen]) and is reopenable via "How it
 * works". "Got it" collapses it back to the one-liner. The "works without the app" reassurance lives at
 * the very end, where it belongs — it's context, not the instruction.
 */
@Composable
private fun ClaimGuide(open: Boolean, onExpand: () -> Unit, onCollapse: () -> Unit, modifier: Modifier = Modifier) {
    val c = EvenlyTheme.colors
    if (!open) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            EvIcon(EvIcons.Check, size = 14.dp, tint = c.ink3)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) { append("Tap a name on each item.") }
                },
                color = c.ink3, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f),
            )
            Text(
                "How it works", color = c.blueText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onExpand).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        return
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("How to split this", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "Got it", color = c.ink3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onCollapse).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        GuideStep(1, "Tap an item below")
        GuideStep(2, "Tap everyone who had it")
        GuideStep(3, "Hit Done when each item has a name")
        Text(
            "No app? You tap for friends too, so no one gets left out.",
            color = c.ink3, fontSize = 11.5.sp, lineHeight = 15.sp,
            modifier = Modifier.fillMaxWidth().topHairline(c.border).padding(top = 10.dp),
        )
    }
}

/** One numbered step in [ClaimGuide]: a blue badge + instruction. */
@Composable
private fun GuideStep(n: Int, text: String) {
    val c = EvenlyTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(c.blue), contentAlignment = Alignment.Center) {
            Text("$n", color = c.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = c.ink, fontSize = 13.5.sp)
    }
}

/** Neutral, always-visible reminder of how many items still need an assignee. Grey chip + a solid
 *  count badge for emphasis, deliberately not amber/warning-tinted (this isn't an error state). */
@Composable
private fun PendingPill(count: Int) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(c.ink), contentAlignment = Alignment.Center) {
            Text("$count", color = c.page, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = EvenlyTheme.monoFamily)
        }
        Text(
            "${if (count == 1) "item" else "items"} still need someone",
            color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssignItemCard(
    item: ClaimItemUi,
    participants: List<ClaimParticipantUi>,
    currency: String,
    onToggleEveryone: (String) -> Unit,
    onSplitAmongAll: () -> Unit,
    onOpenServings: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val needsSomeone = item.rows.isEmpty() || item.leftQty > 0
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (needsSomeone && item.rows.isEmpty()) c.warningTint else c.page)
            .border(1.dp, if (needsSomeone && item.rows.isEmpty()) c.warning else c.border, shape)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (item.quantity > 1) Text("${item.quantity} orders", color = c.ink2, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = EvenlyTheme.monoFamily)
            Text(moneySubunits(item.lineTotalSubunits, currency), color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = EvenlyTheme.monoFamily)
        }

        if (item.quantity > 1) {
            // Multi-order line: a progress summary that opens the per-serving sheet.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface)
                    .clickable(onClick = onOpenServings).padding(horizontal = 11.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (item.leftQty > 0) "${item.assignedQty} of ${item.quantity} servings assigned" else "All ${item.quantity} servings assigned",
                    color = if (item.leftQty > 0) c.warning else c.ink2, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                EvIcon(EvIcons.ChevR, size = 16.dp, tint = c.ink3)
            }
        } else {
            Text("Who had it?", color = c.ink3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                participants.forEach { p ->
                    val on = p.userId in item.assignedMemberIds
                    EvParticipantChip(
                        if (p.isMe) "You" else p.name, selected = on,
                        leading = { EvAvatar(p.name, me = p.isMe, size = AvatarSize.Xs) },
                        trailing = if (on) ({ EvIcon(EvIcons.Check, size = 14.dp) }) else null,
                        onClick = { onToggleEveryone(p.userId) },
                    )
                }
            }
            // Result line + escape hatch to per-amount assignment.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val n = item.assignedMemberIds.size
                val label = when {
                    n == 0 -> "Nobody yet, tap who had it"
                    n == 1 -> "${participants.firstOrNull { it.userId in item.assignedMemberIds }?.let { if (it.isMe) "You" else it.name } ?: "1 person"} · ${moneySubunits(item.lineTotalSubunits, currency)}"
                    else -> "Split $n ways · ${moneySubunits(item.lineTotalSubunits / n, currency)} each"
                }
                Text(label, color = if (n == 0) c.warning else c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (n == 0) Text("Split among all", color = c.blueText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onSplitAmongAll).padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
    }
}

/** The per-item servings sheet — one card per physical unit ("Serving N"), each independently
 *  assignable to one or more people. Every toggle re-derives and writes the item's full serving set
 *  immediately (same live-commit pattern as the simple chip path); there's no separate confirm step.
 *  Opens in a [EvDragSheet] (drag to expand, no dimming), and leads with the three figures that matter —
 *  servings, cost each, and the line total — so opening an item is also how you inspect it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServingsSheet(
    item: ClaimItemUi,
    participants: List<ClaimParticipantUi>,
    currency: String,
    onSetServings: (List<List<String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    var expandedSlot by remember { mutableStateOf<Int?>(null) }
    val slots = item.servingSlots()
    val perUnit = if (item.quantity > 0) item.lineTotalSubunits / item.quantity else item.lineTotalSubunits

    EvDragSheet(onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(item.label, color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            EvIconButton(EvIcons.Close, onDismiss, size = 18.dp, tint = c.ink2)
        }

        // The three figures the owner asked to keep prominent: how many servings, the per-serving price,
        // and the whole-line total.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile("servings", "${item.quantity}", Modifier.weight(1f))
            StatTile("each", moneySubunits(perUnit, currency), Modifier.weight(1f), mono = true)
            StatTile("total", moneySubunits(item.lineTotalSubunits, currency), Modifier.weight(1f), mono = true)
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 10.dp)) {
            Text("Tap a name for each serving", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                if (item.leftQty > 0) "${item.assignedQty} of ${item.quantity} assigned" else "All ${item.quantity} assigned",
                color = if (item.leftQty > 0) c.warning else c.ink3, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            slots.forEachIndexed { index, members ->
                ServingCard(
                    index = index,
                    members = members,
                    participants = participants,
                    expanded = expandedSlot == index,
                    onToggleExpand = { expandedSlot = if (expandedSlot == index) null else index },
                    onToggleMember = { uid ->
                        val nextMembers = if (uid in members) members - uid else members + uid
                        val nextSlots = slots.toMutableList().also { it[index] = nextMembers }
                        onSetServings(nextSlots.map { it.toList() })
                    },
                )
            }
        }

        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            EvButton("Done", onDismiss, variant = ButtonVariant.Primary)
        }
    }
}

/** A compact "big number over small label" figure used in the sheet header (servings / each / total). */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    val c = EvenlyTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(c.surface).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(value, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = if (mono) EvenlyTheme.monoFamily else null)
        Text(label, color = c.ink3, fontSize = 10.5.sp)
    }
}

/** One physical serving — label left, assigned names right-aligned and capped at 40% of the card's
 *  width; tapping opens an inline picker to add/deselect people, matching the participant chip used
 *  everywhere else people are picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServingCard(
    index: Int,
    members: Set<String>,
    participants: List<ClaimParticipantUi>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleMember: (String) -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (expanded) c.surface else c.page)
            .border(1.dp, if (expanded) c.blueTint2 else c.border, shape)
            .clickable(onClick = onToggleExpand)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Serving ${index + 1}", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.55f))
            if (members.isEmpty()) {
                // Bigger + accented so the "this is where you assign" affordance is obvious in the sheet.
                Text(
                    "Tap to assign", color = c.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(0.45f),
                )
            } else {
                val names = participants.filter { it.userId in members }.joinToString(", ") { if (it.isMe) "You" else it.name }
                Text(
                    names, color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.4f),
                )
            }
        }
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                participants.forEach { p ->
                    val on = p.userId in members
                    EvParticipantChip(
                        if (p.isMe) "You" else p.name, selected = on,
                        leading = { EvAvatar(p.name, me = p.isMe, size = AvatarSize.Xs) },
                        trailing = if (on) ({ EvIcon(EvIcons.Check, size = 14.dp) }) else null,
                        onClick = { onToggleMember(p.userId) },
                    )
                }
            }
        }
    }
}
