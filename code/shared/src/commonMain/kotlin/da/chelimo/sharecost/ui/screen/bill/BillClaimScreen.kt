package da.chelimo.sharecost.ui.screen.bill

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.domain.expense.ItemStatus
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScModalScaffold
import da.chelimo.sharecost.ui.components.ScParticipantChip
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.StatusBarScrim
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.theme.ShareCostTheme

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
    /** Complex = anything the plain "tap who shared it" chips can't express (mixed amounts / multiple slices). */
    val isComplex: Boolean get() = rows.any { it.portionId == null } || rows.size > 1 || rows.any { it.quantity < quantity }
    val perUnitSubunits: Long get() = if (quantity <= 0) lineTotalSubunits else (lineTotalSubunits + quantity / 2) / quantity
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
 * "Who had what?" — the assign screen. You tap the people who had each item (the same selectable chip used
 * to pick participants elsewhere); it works for anyone, even friends without the app. A multi-count line
 * where people had different amounts opens an "Assign amounts" builder (portions: a quantity + who split
 * it). Every figure is a plain "who pays what", never a button. DI-free.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BillClaimScreen(
    state: ClaimBillState,
    onBack: () -> Unit = {},
    // Simple "everyone who's tapped shares the whole line" — one portion covering all units.
    onSetEveryone: (itemId: String, memberIds: List<String>) -> Unit = { _, _ -> },
    onSetSolo: (itemId: String, userId: String, quantity: Int) -> Unit = { _, _, _ -> },
    onAddPortion: (itemId: String, memberIds: List<String>, quantity: Int) -> Unit = { _, _, _ -> },
    onEditPortion: (itemId: String, portionId: String, memberIds: List<String>, quantity: Int) -> Unit = { _, _, _, _ -> },
    onRemovePortion: (itemId: String, portionId: String) -> Unit = { _, _ -> },
    onRemoveSolo: (itemId: String, userId: String) -> Unit = { _, _ -> },
    onClearEveryone: (itemId: String) -> Unit = {},
    onAddPerson: (name: String) -> Unit = {},
    onEditBill: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var assignItemId by remember { mutableStateOf<String?>(null) }
    var showAddPerson by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.page)) {
            StatusBarScrim()
            ScTopBar(title = "Who had what?", navIcon = { ScIconButton(ScIcons.Back, onBack) }, showDivider = false)

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(c.blueTint).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ScIcon(ScIcons.Users, size = 20.dp, tint = c.blue)
                        Column {
                            Text("Tap who had each item.", color = c.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Do it for everyone — even friends without the app.", color = c.ink2, fontSize = 12.sp)
                        }
                    }
                }
                if (state.totals.isNotEmpty()) {
                    item { TotalsStrip(state.totals, state.currency) }
                }
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
                        onAssignAmounts = { onClearEveryone(item.id); assignItemId = item.id },
                        onEditAmounts = { assignItemId = item.id },
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showAddPerson = true }.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ScIcon(ScIcons.Plus, size = 16.dp, tint = c.blue)
                        Text("Add someone new", color = c.blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Column(Modifier.fillMaxWidth().topHairline(c.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.unassignedCount > 0) {
                    Text(
                        "${state.unassignedCount} ${if (state.unassignedCount == 1) "item" else "items"} still need someone",
                        color = c.ink3, fontSize = 12.sp,
                    )
                }
                ScButton("Edit bill", onEditBill, variant = ButtonVariant.Secondary, leadingIcon = ScIcons.Edit, small = true)
                ScButton("Done", onDone, variant = ButtonVariant.Primary)
            }
        }

        val assignItem = assignItemId?.let { id -> state.items.firstOrNull { it.id == id } }
        if (assignItem != null) {
            AssignAmountsSheet(
                item = assignItem,
                participants = state.participants,
                currency = state.currency,
                onSetSolo = onSetSolo,
                onAddPortion = onAddPortion,
                onEditPortion = onEditPortion,
                onRemovePortion = onRemovePortion,
                onRemoveSolo = onRemoveSolo,
                onDismiss = { assignItemId = null },
            )
        }

        if (showAddPerson) {
            var name by remember { mutableStateOf("") }
            ScModalScaffold(onDismiss = { showAddPerson = false }) {
                Text("Add a person", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                Text("They join the bill so you can assign items to them — even if they don't have the app.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                ScField("Name") { ScTextField(name, { name = it }, placeholder = "e.g. Mary") }
                Box(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    ScButton("Add", { if (name.isNotBlank()) { onAddPerson(name.trim()); showAddPerson = false } }, enabled = name.isNotBlank())
                }
            }
        }
    }
}

/** Per-person running totals, WRAPPING so everyone's visible (no horizontal swipe). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TotalsStrip(totals: List<Pair<ClaimParticipantUi, Long>>, currency: String) {
    val c = ShareCostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Total per person", color = c.ink2, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            totals.forEach { (p, amount) ->
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(if (p.isMe) c.blueTint else c.surface)
                        .border(1.dp, if (p.isMe) c.blueTint2 else c.border, RoundedCornerShape(10.dp))
                        .padding(start = 6.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ScAvatar(p.name, me = p.isMe, size = AvatarSize.Xs)
                    Text(if (p.isMe) "You" else p.name, color = if (p.isMe) c.blue else c.ink2, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                    Text(moneySubunits(amount, currency), color = c.ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
                }
            }
        }
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
    onAssignAmounts: () -> Unit,
    onEditAmounts: () -> Unit,
) {
    val c = ShareCostTheme.colors
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
            if (item.quantity > 1) Text("${item.quantity} orders", color = c.ink2, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
            Text(moneySubunits(item.lineTotalSubunits, currency), color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
        }

        if (item.isComplex) {
            // Portions summary — a plain "who pays what" list; edit reopens the builder.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.rows.forEach { row -> AssignRowLine(row, currency) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.leftQty > 0) Text("${item.leftQty} of ${item.quantity} left", color = c.warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                else Text("All ${item.quantity} assigned", color = c.ink3, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Edit amounts", color = c.blue, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onEditAmounts).padding(horizontal = 6.dp, vertical = 3.dp))
            }
        } else {
            Text("Who had it?", color = c.ink3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                participants.forEach { p ->
                    val on = p.userId in item.assignedMemberIds
                    ScParticipantChip(
                        if (p.isMe) "You" else p.name, selected = on,
                        leading = { ScAvatar(p.name, me = p.isMe, size = AvatarSize.Xs) },
                        trailing = if (on) ({ ScIcon(ScIcons.Check, size = 14.dp) }) else null,
                        onClick = { onToggleEveryone(p.userId) },
                    )
                }
            }
            // Result line + escape hatch to per-amount assignment.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val n = item.assignedMemberIds.size
                val label = when {
                    n == 0 -> "Nobody yet — tap who had it"
                    n == 1 -> "${participants.firstOrNull { it.userId in item.assignedMemberIds }?.let { if (it.isMe) "You" else it.name } ?: "1 person"} · ${moneySubunits(item.lineTotalSubunits, currency)}"
                    else -> "Split $n ways · ${moneySubunits(item.lineTotalSubunits / n, currency)} each"
                }
                Text(label, color = if (n == 0) c.warning else c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (n == 0) Text("Split among all", color = c.blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onSplitAmongAll).padding(horizontal = 6.dp, vertical = 3.dp))
                else if (item.quantity > 1) Text("Different amounts?", color = c.blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onAssignAmounts).padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
    }
}

/** A single "who pays what" line in a portions summary — plain info, never a button. */
@Composable
private fun AssignRowLine(row: AssignRowUi, currency: String) {
    val c = ShareCostTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ScAvatar(row.memberNames.firstOrNull() ?: "?", size = AvatarSize.Xs)
        Column(Modifier.weight(1f)) {
            Text(row.memberNames.joinToString(" · "), color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (row.isShared) Text("split · ${moneySubunits(row.perHeadSubunits, currency)} each", color = c.ink3, fontSize = 11.sp)
        }
        Text("×${row.quantity}", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
        Text(moneySubunits(row.amountSubunits, currency), color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** The per-item "Assign amounts" builder — portions: pick who (1 = solo, 2+ = split) + how many. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssignAmountsSheet(
    item: ClaimItemUi,
    participants: List<ClaimParticipantUi>,
    currency: String,
    onSetSolo: (String, String, Int) -> Unit,
    onAddPortion: (String, List<String>, Int) -> Unit,
    onEditPortion: (String, String, List<String>, Int) -> Unit,
    onRemovePortion: (String, String) -> Unit,
    onRemoveSolo: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = ShareCostTheme.colors
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var qty by remember { mutableStateOf(1) }
    val left = item.leftQty
    ScModalScaffold(onDismiss = onDismiss) {
        Text("Assign ${item.label}", color = c.ink, style = MaterialTheme.typography.titleMedium)
        Text(
            if (left > 0) "$left of ${item.quantity} still to assign" else "All ${item.quantity} assigned",
            color = if (left > 0) c.warning else c.ink3, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        // Existing assignments, each removable.
        item.rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(row.memberNames.joinToString(" · "), color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("×${row.quantity} · ${moneySubunits(row.amountSubunits, currency)}", color = c.ink2, fontSize = 12.sp, fontFamily = ShareCostTheme.monoFamily)
                Box(Modifier.clip(CircleShape).clickable {
                    if (row.portionId == null) onRemoveSolo(item.id, row.memberIds.first()) else onRemovePortion(item.id, row.portionId)
                }.padding(4.dp)) { ScIcon(ScIcons.Close, size = 15.dp, tint = c.ink3) }
            }
            Box(Modifier.size(6.dp))
        }

        Text("Add a portion", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            participants.forEach { p ->
                val on = p.userId in picked
                ScParticipantChip(
                    if (p.isMe) "You" else p.name, selected = on,
                    leading = { ScAvatar(p.name, me = p.isMe, size = AvatarSize.Xs) },
                    trailing = if (on) ({ ScIcon(ScIcons.Check, size = 14.dp) }) else null,
                    onClick = { picked = if (on) picked - p.userId else picked + p.userId },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("How many?", color = c.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
            StepBtn(ScIcons.Minus, enabled = qty > 1) { qty = (qty - 1).coerceAtLeast(1) }
            Text("$qty", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily, modifier = Modifier.width(30.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepBtn(ScIcons.Plus, enabled = qty < item.quantity) { qty = qty + 1 }
        }
        if (picked.size >= 2) Text("Split ${picked.size} ways · ${moneySubunits(item.perUnitSubunits * qty / picked.size, currency)} each", color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            ScButton(
                "Add this portion",
                {
                    val members = picked.toList()
                    when {
                        members.isEmpty() -> {}
                        members.size == 1 -> onSetSolo(item.id, members.first(), qty)
                        else -> onAddPortion(item.id, members, qty)
                    }
                    picked = emptySet(); qty = 1
                },
                enabled = picked.isNotEmpty(),
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            ScButton("Done", onDismiss, variant = ButtonVariant.Secondary)
        }
    }
}

@Composable
private fun StepBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val c = ShareCostTheme.colors
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, c.borderStrong, RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { ScIcon(icon, size = 16.dp, tint = if (enabled) c.blue else c.ink3) }
}
