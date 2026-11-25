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
import app.splitevenly.domain.repository.PeopleRepository
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.screen.people.KnownPersonUi
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

/**
 * Adds [name] as a placeholder member. They join from now on only: past expenses are never re-split to
 * include them, because someone who shared a cost is put on it when it is logged.
 */
internal suspend fun GroupRepository.addMemberByName(
    groupId: GroupId,
    name: String,
    me: UserId?,
): String? = (addPlaceholder(groupId, name, createdBy = me) as? AppResult.Ok)?.value?.userId?.value

/**
 * Adds someone the viewer already knows from other groups: a placeholder under their name, linked to that
 * person so the People tab counts them as one (docs/features/people-tab.md, "People you know"). Returns the
 * new member id, or null when the save failed.
 */
internal suspend fun GroupRepository.addKnownPerson(
    groupId: GroupId,
    person: KnownPersonUi,
    me: UserId?,
    people: PeopleRepository,
): String? {
    val added = addPlaceholder(groupId, person.name, createdBy = me)
    if (added !is AppResult.Ok) return null
    if (me != null) people.link(me, listOf(added.value.userId.value), person.key)
    return added.value.userId.value
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

/** A quiet tag on a roster line: tinted at half strength so it's findable without pulling the eye. */
@Composable
private fun RosterTag(text: String) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(50))
            .background(c.blueTint.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = t.caption.copy(color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium),
    )
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
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(m.name, style = t.itemTitle)
            if (m.isMe) RosterTag("You")
            if (m.role == "Admin") RosterTag("Admin")
        }
        // Status is plain trailing text, not a pill: a pill on every row turned the roster into a wall of badges.
        when (m.role) {
            "No account" -> Text("Not joined", style = t.caption.copy(color = c.ink3, fontSize = 13.sp))
            "Left" -> Text("Left", style = t.caption.copy(color = c.danger, fontSize = 13.sp))
        }
        if (onClick != null) EvIcon(EvIcons.ChevR, size = 15.dp, tint = c.ink3)
    }
}
