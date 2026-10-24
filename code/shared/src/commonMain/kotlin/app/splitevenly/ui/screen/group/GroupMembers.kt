package app.splitevenly.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.group.Member
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.ChipVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvToggle
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.screen.settings.MemberRowUi
import app.splitevenly.ui.theme.EvenlyTheme

private const val STRIP_MAX_AVATARS = 4

/** Roster rows for the members strip, sheet and Group settings: one mapping so they never disagree. */
internal fun List<Member>.toMemberRows(me: UserId?): List<MemberRowUi> =
    map { m ->
        MemberRowUi(
            userId = m.userId.value,
            name = m.displayName ?: "Someone",
            role =
                when {
                    m.isAdmin -> "Admin"
                    m.isPlaceholder -> "No account"
                    else -> ""
                },
            isMe = m.userId == me,
        )
    }

/** Adds [name] as a placeholder member; "all past" also sweeps them into existing expenses (03 §8.1). */
internal suspend fun GroupRepository.addMemberByName(
    groupId: GroupId,
    name: String,
    addToPast: Boolean,
    me: UserId?,
) {
    val added = addPlaceholder(groupId, name, createdBy = me)
    if (added is AppResult.Ok && addToPast && me != null) addMemberToPastExpenses(groupId, added.value.userId, me)
}

/** Members strip copy: "Just you" when solo, else "N members". */
internal fun membersStripLabel(count: Int): String = if (count <= 1) "Just you" else "$count members"

/**
 * The group page's front door to its roster (T2, option A): who is here, and a one-tap way to add more.
 * Solo is tinted and louder, because "how do I add someone?" was the first question a tester had.
 * The whole strip opens [MembersSheet]; so does the pill, which is only a bigger target for the same thing.
 */
@Composable
internal fun MembersStrip(
    members: List<MemberRowUi>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    val solo = members.size <= 1
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .background(if (solo) c.blueTint else c.surface)
            .border(1.dp, if (solo) c.blueTint else c.border, shape)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            members.take(STRIP_MAX_AVATARS).forEach { m -> EvAvatar(m.name, me = m.isMe, size = AvatarSize.Sm) }
            val extra = members.size - STRIP_MAX_AVATARS
            if (extra > 0) {
                Box(
                    Modifier.size(AvatarSize.Sm.dp).clip(CircleShape).background(c.border),
                    contentAlignment = Alignment.Center,
                ) { Text("+$extra", color = c.ink2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(membersStripLabel(members.size), color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (solo) Text("Invite friends to split with", color = c.ink2, fontSize = 12.sp)
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(if (solo) c.blue else c.blueTint)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val fg = if (solo) c.onAccent else c.blueText
            EvIcon(EvIcons.Plus, size = 14.dp, tint = fg)
            Text(if (solo) "Add people" else "Add", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Who is in the group, with the two ways in at the top. "Invite with link" goes straight to the native
 * share sheet so a link is two taps from the group page; the QR stays one tap further for in-person use.
 */
@Composable
internal fun MembersSheet(
    members: List<MemberRowUi>,
    onInviteWithLink: () -> Unit,
    onShowQr: () -> Unit,
    onAddByName: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    EvSheetScaffold(
        onDismiss = onDismiss,
        title = "Members",
        sub = "",
    ) {
        EvButton("Invite with link", onInviteWithLink, leadingIcon = EvIcons.Share)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            EvButton(
                "Show QR code",
                onShowQr,
                modifier = Modifier.weight(1f),
                variant = ButtonVariant.Tonal,
                leadingIcon = EvIcons.Qr,
            )
            EvButton(
                "Add by name",
                onAddByName,
                modifier = Modifier.weight(1f),
                variant = ButtonVariant.Tonal,
                leadingIcon = EvIcons.Plus,
            )
        }
        EvCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            members.forEachIndexed { i, m -> MemberRow(m, showDivider = i > 0) }
        }
    }
}

/** One roster line, shared by the Members sheet and Group settings so the two never drift. */
@Composable
internal fun MemberRow(
    m: MemberRowUi,
    showDivider: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (showDivider) Modifier.topHairline(c.border) else Modifier)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EvAvatar(m.name, me = m.isMe, size = AvatarSize.Sm)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, style = t.itemTitle)
            if (m.isMe) Text(" · you", style = t.itemTitle.copy(color = c.ink2, fontWeight = FontWeight.Medium))
        }
        if (m.role.isNotEmpty()) {
            EvChip(
                m.role,
                variant =
                    when (m.role) {
                        "Admin" -> ChipVariant.Blue
                        "Left" -> ChipVariant.Red
                        else -> ChipVariant.Amber
                    },
            )
        }
        if (onClick != null) EvIcon(EvIcons.ChevR, size = 15.dp, tint = c.ink3)
    }
}

/**
 * Add someone by name as a placeholder member, shared by the group page and Group settings. The Add button
 * stays live: tapping it with no name says what is missing instead of sitting greyed out.
 */
@Composable
internal fun AddMemberSheet(
    onAdd: (name: String, addToPast: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    var name by remember { mutableStateOf("") }
    var addToPast by remember { mutableStateOf(false) }
    var missingName by remember { mutableStateOf(false) }
    EvModalScaffold(onDismiss = onDismiss) {
        Text("Add a member", style = t.sheetTitle, modifier = Modifier.padding(bottom = 12.dp))
        EvField("Name") {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                EvTextField(
                    name,
                    {
                        name = it
                        if (it.isNotBlank()) missingName = false
                    },
                    placeholder = "e.g. Tyler",
                    isError = missingName,
                )
                if (missingName) Text("Type their name first", color = c.danger, fontSize = 12.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Add to all past expenses", style = t.itemTitle)
                Text("Even splits update automatically; others become conflicts to resolve.", style = t.caption)
            }
            EvToggle(addToPast, { addToPast = it })
        }
        Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            EvButton("Add", {
                if (name.isBlank()) {
                    missingName = true
                } else {
                    onAdd(name.trim(), addToPast)
                    onDismiss()
                }
            })
        }
    }
}
