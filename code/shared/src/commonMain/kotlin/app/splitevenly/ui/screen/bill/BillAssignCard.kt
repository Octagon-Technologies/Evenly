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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.expense.ItemStatus
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvParticipantChip
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.theme.EvenlyTheme

/** Who holds a line, for a collapsed header or a review row: "You + Purity", or its unfinished state. */
internal fun ClaimItemUi.assigneeLine(): String {
    val names = rows.flatMap { it.memberNames }.distinct()
    return when {
        status == ItemStatus.OVERCLAIMED -> "Too many servings assigned"
        quantity > 1 && status != ItemStatus.RESOLVED -> "$assignedQty of $quantity servings"
        names.isEmpty() -> "Needs someone"
        names.size <= 3 -> names.joinToString(" + ")
        else -> "${names.first()} + ${names.size - 1} others"
    }
}

/**
 * One accordion card. Collapsed it is a header: a receipt-number mark (a check once complete, and the
 * number stays in the label after the card moves), the assignees, and the price. Open it holds the
 * chips for a single-serving line, or the entry into the per-serving sheet for a multi-serving one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccordionCard(
    item: ClaimItemUi,
    open: Boolean,
    participants: List<ClaimParticipantUi>,
    currency: String,
    onHeaderClick: () -> Unit,
    onToggle: (userId: String) -> Unit,
    onEveryone: () -> Unit,
    onOpenServings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    val complete = item.status == ItemStatus.RESOLVED
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (complete && !open) c.surface else c.page)
            .border(if (open) 1.5.dp else 1.dp, if (open) c.blue else c.border, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onHeaderClick).padding(horizontal = 11.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NumberMark(item.number, complete)
            Column(Modifier.weight(1f)) {
                Text(
                    "${item.number}. ${item.label}",
                    color = c.ink,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = item.assigneeLine()
                Text(
                    line,
                    color = if (item.status == ItemStatus.OVERCLAIMED) c.warning else c.ink3,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(moneySubunits(item.lineTotalSubunits, currency), color = c.ink, fontSize = 14.sp, fontFamily = EvenlyTheme.monoFamily)
            EvIcon(if (open) EvIcons.ChevU else EvIcons.ChevD, size = 14.dp, tint = c.ink3)
        }
        if (!open) return@Column
        Column(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (item.quantity > 1) {
                Text("Assign each serving", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.surface)
                        .clickable(onClick = onOpenServings)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        when {
                            item.status == ItemStatus.OVERCLAIMED -> "Too many servings assigned. Tap to fix"
                            item.leftQty == 0 -> "All ${item.quantity} servings assigned"
                            else -> "${item.assignedQty} of ${item.quantity} servings assigned"
                        },
                        color = if (complete) c.ink2 else c.warning,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    EvIcon(EvIcons.ChevR, size = 16.dp, tint = c.ink3)
                }
            } else {
                Text("Who shared this?", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    participants.forEach { p ->
                        val on = p.userId in item.assignedMemberIds
                        EvParticipantChip(
                            if (p.isMe) "You" else p.name,
                            selected = on,
                            leading = { EvAvatar(p.name, me = p.isMe, size = AvatarSize.Xs) },
                            trailing = if (on) ({ EvIcon(EvIcons.Check, size = 14.dp) }) else null,
                            onClick = { onToggle(p.userId) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        resultLine(item, participants, currency),
                        color = c.ink3,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.assignedMemberIds.size < participants.size) {
                        Text(
                            "Everyone",
                            color = c.blueText,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier =
                                Modifier
                                    .clip(
                                        RoundedCornerShape(7.dp),
                                    ).clickable(onClick = onEveryone)
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun resultLine(
    item: ClaimItemUi,
    participants: List<ClaimParticipantUi>,
    currency: String,
): String {
    val ids = item.assignedMemberIds
    val total = moneySubunits(item.lineTotalSubunits, currency)
    return when (ids.size) {
        0 -> {
            "No one selected yet"
        }

        1 -> {
            val p = participants.firstOrNull { it.userId in ids }
            if (p?.isMe == true) "$total assigned to you" else "$total assigned to ${p?.name ?: "1 person"}"
        }

        else -> {
            "Split ${ids.size} ways · ${moneySubunits(item.lineTotalSubunits / ids.size, currency)} each"
        }
    }
}

@Composable
private fun NumberMark(
    number: Int,
    complete: Boolean,
) {
    val c = EvenlyTheme.colors
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (complete) c.blue else c.page)
            .border(1.5.dp, if (complete) c.blue else c.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (complete) {
            EvIcon(EvIcons.Check, size = 14.dp, tint = c.onAccent)
        } else {
            Text("$number", color = c.ink3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = EvenlyTheme.monoFamily)
        }
    }
}
