package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvCheck
import app.splitevenly.ui.components.EvParticipantChip
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/** Small groups render inline; past this count the list collapses into an expandable row (below). */
private const val PARTICIPANTS_INLINE_THRESHOLD = 6

/**
 * "Participants" — inline toggle chips for a small group (unchanged; it already reads well at that size).
 * Past [PARTICIPANTS_INLINE_THRESHOLD] members that same chip grid gets long, so it collapses to a single
 * summary row ("5 of 15 selected" — a count, not names, to stay compact) that expands in place into a
 * checkbox list, mirroring the collapsed-row language already used for Category/Paid by above.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ParticipantsField(
    participants: List<AddParticipantUi>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onAddClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    if (participants.size <= PARTICIPANTS_INLINE_THRESHOLD) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Who's splitting this?", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (participants.size <= 1) {
                Text("Add the people splitting this bill.", color = c.ink3, fontSize = 12.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                participants.forEach { p ->
                    val on = p.userId in selected
                    EvParticipantChip(
                        p.name, selected = on,
                        leading = { EvAvatar(p.name, me = p.isMe, size = AvatarSize.Xs) },
                        trailing = if (on) ({ EvIcon(EvIcons.Check, size = 14.dp) }) else null,
                        onClick = { onToggle(p.userId) },
                    )
                }
                EvParticipantChip("Add", selected = false, leading = { EvIcon(EvIcons.Plus, size = 15.dp, tint = c.blueText) }, onClick = onAddClick)
            }
        }
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val count = selected.size
    val allOn = count >= participants.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Who's splitting this?", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val rowShape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(rowShape).background(c.page)
                .border(1.dp, c.borderStrong, rowShape).clickable { expanded = !expanded }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                EvIcon(EvIcons.Users, size = 15.dp, tint = c.blueText)
            }
            Text("$count of ${participants.size} selected", color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
            EvIcon(if (expanded) EvIcons.ChevU else EvIcons.ChevD, size = 16.dp, tint = c.ink3)
        }
        if (expanded) {
            EvCard {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (allOn) "Deselect all" else "Select all",
                            color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable { if (allOn) onDeselectAll() else onSelectAll() }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                        Box(Modifier.weight(1f))
                        Text(
                            "Add", color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAddClick).padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        participants.forEachIndexed { i, p ->
                            val on = p.userId in selected
                            Row(
                                Modifier.fillMaxWidth().then(if (i > 0) Modifier.topHairline(c.border) else Modifier)
                                    .clickable { onToggle(p.userId) }.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                EvAvatar(p.name, me = p.isMe, size = AvatarSize.Xs)
                                Text(p.name, color = c.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                EvCheck(checked = on, onCheckedChange = { onToggle(p.userId) })
                            }
                        }
                    }
                }
            }
        }
    }
}

