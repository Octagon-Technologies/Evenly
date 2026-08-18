package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvAvatarStack
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvCheck
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * "Who's on this bill" — the roster editor. Small groups get inline chips, larger ones a summary row
 * that opens [ParticipantPickerSheet]. The sheets are the caller's to render: they must sit OUTSIDE the
 * editor's scrolling column, which measures its children with an unbounded height.
 */
@Composable
internal fun BillPeopleRow(
    participants: List<ParticipantChipUi>,
    selected: Set<String>,
    allIds: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onAddPersonClick: () -> Unit,
    onOpenPicker: () -> Unit,
) {
    if (participants.isEmpty()) return
    if (participants.size <= 6) {
        ParticipantChips(
            participants = participants,
            selected = selected,
            allIds = allIds,
            onToggle = onToggle,
            onSelectAll = onSelectAll,
            onDeselectAll = onDeselectAll,
            onAddClick = onAddPersonClick,
        )
    } else {
        ParticipantSummary(
            participants = participants,
            selected = selected,
            allCount = allIds.size,
            onEdit = onOpenPicker,
        )
    }
}

/** A pending "take them off the bill" that would discard claims. [next] is the selection once confirmed. */
internal data class PendingRemoval(val names: List<String>, val items: Int, val next: Set<String>)

/**
 * The gate in front of a removal: null when [next] can just be applied, otherwise the confirmation to
 * show. Taking someone off a bill discards their claims (`BillRepositoryImpl.editBill`), so that case is
 * confirmed. Someone with nothing claimed comes off silently, which is the common "they weren't there"
 * correction, and adding is never gated.
 */
internal fun pendingRemovalFor(
    participants: List<ParticipantChipUi>,
    selected: Set<String>,
    next: Set<String>,
    claimedItemsByUser: Map<String, Int>,
): PendingRemoval? {
    val claiming = participants.filter {
        it.userId in selected && it.userId !in next && (claimedItemsByUser[it.userId] ?: 0) > 0
    }
    if (claiming.isEmpty()) return null
    return PendingRemoval(
        names = claiming.map { if (it.isMe) "You" else it.name },
        items = claiming.sumOf { claimedItemsByUser[it.userId] ?: 0 },
        next = next,
    )
}

/** Says what taking them off costs, in claims, before it happens. Their money goes with the claims. */
@Composable
internal fun RemoveParticipantSheet(removal: PendingRemoval, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val c = EvenlyTheme.colors
    val one = removal.names.size == 1
    val who = if (one) removal.names.first() else "${removal.names.size} people"
    val items = if (removal.items == 1) "1 item" else "${removal.items} items"
    EvModalScaffold(onDismiss = onCancel) {
        Text(
            if (one) "Take $who off this bill?" else "Take these $who off the bill?",
            color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp),
        )
        Text(
            if (one) "$who claimed $items. Taking them off clears those claims, and the items go back to needing someone."
            else "They claimed $items between them. Taking them off clears those claims, and the items go back to needing someone.",
            color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 16.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EvButton("Take them off", onConfirm, variant = ButtonVariant.Danger)
            EvButton("Keep them", onCancel, variant = ButtonVariant.Secondary)
        }
    }
}

/** Small-group "who's on this bill": every member as an inline toggle chip, plus select/deselect all. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParticipantChips(
    participants: List<ParticipantChipUi>,
    selected: Set<String>,
    allIds: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onAddClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Who's on this bill", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val allOn = selected.size >= allIds.size
            Text(
                if (allOn) "Deselect all" else "Select all",
                color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { if (allOn) onDeselectAll() else onSelectAll() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            participants.forEach { p ->
                val on = p.userId in selected
                val shape = RoundedCornerShape(999.dp)
                Row(
                    Modifier.clip(shape)
                        .background(if (on) c.blue else c.page)
                        .then(if (on) Modifier else Modifier.border(1.dp, c.borderStrong, shape))
                        .clickable { onToggle(p.userId) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (on) EvIcon(EvIcons.Check, size = 14.dp, tint = c.onAccent)
                    Text(if (p.isMe) "You" else p.name, color = if (on) c.onAccent else c.ink, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            // Add a person who isn't in the group yet, right from the roster — mirrors the add-expense editor.
            val addShape = RoundedCornerShape(999.dp)
            Row(
                Modifier.clip(addShape).border(1.dp, c.borderStrong, addShape)
                    .clickable(onClick = onAddClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EvIcon(EvIcons.Plus, size = 14.dp, tint = c.blueText)
                Text("Add", color = c.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Large-group "who's on this bill": a compact summary (avatars + count) that opens the picker sheet. */
@Composable
private fun ParticipantSummary(
    participants: List<ParticipantChipUi>,
    selected: Set<String>,
    allCount: Int,
    onEdit: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val chosen = participants.filter { it.userId in selected }
    val count = chosen.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Who's on this bill", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        EvCard {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (count > 0) {
                    EvAvatarStack(names = chosen.take(4).map { if (it.isMe) "You" else it.name }, size = AvatarSize.Sm)
                    if (count > 4) Text("+${count - 4}", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.weight(1f))
                Text(
                    when {
                        count == 0 -> "Add people"
                        count >= allCount -> "Everyone · $allCount"
                        else -> "$count of $allCount"
                    },
                    color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
                EvIcon(EvIcons.ChevR, size = 18.dp, tint = c.blueText)
            }
        }
    }
}

/** The searchable member picker for larger groups — one tappable row each, with select/deselect all. */
@Composable
internal fun ParticipantPickerSheet(
    participants: List<ParticipantChipUi>,
    selected: Set<String>,
    allCount: Int,
    query: String,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onAddPerson: () -> Unit,
    onDone: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val allOn = selected.size >= allCount
    val filtered = participants.filter {
        val label = if (it.isMe) "You" else it.name
        query.isBlank() || label.contains(query.trim(), ignoreCase = true)
    }
    EvSheetScaffold(
        onDismiss = onDone,
        title = "Who's on this bill",
        sub = "${selected.size} of $allCount selected",
    ) {
        EvTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = "Search members",
            leading = { EvIcon(EvIcons.Search, size = 18.dp, tint = c.ink3) },
            minHeight = 44.dp,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${filtered.size} ${if (filtered.size == 1) "member" else "members"}",
                color = c.ink3, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            Text(
                if (allOn) "Deselect all" else "Select all",
                color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { if (allOn) onDeselectAll() else onSelectAll() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            // Add a person who isn't in the group yet (a placeholder) — they'll be added and put on the bill.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onAddPerson)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(999.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                    EvIcon(EvIcons.Plus, size = 16.dp, tint = c.blueText)
                }
                Text("Add a person", color = c.blueText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            filtered.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onToggle(p.userId) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EvAvatar(name = if (p.isMe) "You" else p.name, me = p.isMe, size = AvatarSize.Sm)
                    Text(if (p.isMe) "You" else p.name, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    EvCheck(checked = p.userId in selected, onCheckedChange = { onToggle(p.userId) })
                }
            }
            if (filtered.isEmpty()) {
                Text("No one matches your search", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            EvButton(text = "Done", onClick = onDone)
        }
    }
}