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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvDragSheet
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvParticipantChip
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * The per-serving assignment sheet for a multi-order line, split out of `BillClaimScreen.kt` when that
 * file reached the 600-line ceiling in `ui/AGENTS.md` (which names this sheet as the thing to take with
 * you on the way past). Its types still live next door: it is one surface of the assign screen, not a
 * separate feature.
 */

/** The per-item servings sheet — one card per physical unit ("Serving N"), each independently
 *  assignable to one or more people. Every toggle re-derives and writes the item's full serving set
 *  immediately (same live-commit pattern as the simple chip path); there's no separate confirm step.
 *  Opens in a [EvDragSheet] (drag to expand, no dimming), and leads with the three figures that matter —
 *  servings, cost each, and the line total — so opening an item is also how you inspect it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServingsSheet(
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
