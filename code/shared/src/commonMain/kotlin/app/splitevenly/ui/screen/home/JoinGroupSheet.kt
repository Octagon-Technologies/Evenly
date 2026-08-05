package app.splitevenly.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.ChipVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvAvatarStack
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCheck
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** A claimable placeholder identity offered on the Join sheet: the joiner can pick "I'm this person". */
data class JoinPlaceholderOption(val id: String, val name: String)

/** 20 · Join group sheet (design/src/screens-home.jsx). Wired by `JoinRoute` from an invite token. */
@Composable
fun JoinGroupSheet(
    groupName: String = "Tulum Trip",
    emoji: String = "🏝️",
    subtitle: String = "Tap join to start sharing costs",
    memberNames: List<String> = emptyList(),
    placeholders: List<JoinPlaceholderOption> = emptyList(),
    found: Boolean = true,
    already: Boolean = false,
    onDismiss: () -> Unit = {},
    // Carries the picked placeholder id to claim on join, or null to join as a brand-new member.
    onJoin: (claimPlaceholderId: String?) -> Unit = {},
    onOpen: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    // null = "I'm new here" (the default, matching the legacy join-as-a-fresh-member behaviour).
    var claimId by remember(placeholders) { mutableStateOf<String?>(null) }
    // A real full-screen destination (deep-link + "Join with a link" both land here), not a sheet on a
    // blank page: back arrow up top, the group preview centered as a hero, matching [NewGroupSheet].
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(title = "", navIcon = { EvIconButton(EvIcons.Back, onClick = onDismiss) }, showDivider = false)
        Column(
            Modifier.fillMaxSize().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(80.dp).clip(RoundedCornerShape(24.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                    Text(if (found) emoji else "🔗", fontSize = 42.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (found) groupName else "Group not found", color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (found) subtitle else "This invite link isn't available on this device yet. Ask the inviter to make sure it's synced.",
                        color = c.ink2,
                        fontSize = 12.sp,
                    )
                }
                // Member count (placeholders + everyone who's joined via the link) so the user can
                // confirm "is this actually my group?" before tapping Join.
                if (found && memberNames.isNotEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EvAvatarStack(names = memberNames, size = AvatarSize.Sm)
                        Text(
                            "${memberNames.size} ${if (memberNames.size == 1) "member" else "members"}",
                            color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                when {
                    !found -> EvButton("Close", onDismiss, variant = ButtonVariant.Text)
                    already -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EvChip("You're already in this group", variant = ChipVariant.Blue, leadingIcon = EvIcons.Check)
                        EvButton("Open group", onOpen, leadingIcon = EvIcons.ChevR)
                    }
                    else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Identity picker: if the group has been tracking placeholders, let the joiner
                        // claim one ("I'm Dave") so its history merges onto them on join, instead of
                        // forcing a separate Reconcile trip in Group settings.
                        if (placeholders.isNotEmpty()) {
                            IdentityPicker(
                                placeholders = placeholders,
                                selectedId = claimId,
                                onSelect = { claimId = it },
                            )
                        }
                        val claimName = placeholders.firstOrNull { it.id == claimId }?.name
                        EvButton(
                            text = if (claimName != null) "Join as $claimName" else "Join group",
                            onClick = { onJoin(claimId) },
                            leadingIcon = EvIcons.Users,
                        )
                        // "Not now" mirrors Join's full width + centering, but sits on a muted fill
                        // (a hair off the white sheet) so it reads as a quiet escape, not a tap target
                        // you'd hit by reflex.
                        Box(
                            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
                                .background(c.surface).clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Not now", color = c.ink2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Are you one of these?" — single-select identity picker. Each placeholder the group already tracks
 * is a claimable row; a final "I'm new here" row (selected by default) keeps the join-fresh path.
 * Blue-led selection: [selectionTint] fill + [selectionStroke] border + a check (never colour alone),
 * matching the Reconcile card.
 */
@Composable
private fun IdentityPicker(
    placeholders: List<JoinPlaceholderOption>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    val c = EvenlyTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .border(1.dp, c.border, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Are you one of these?", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Pick the name you've been tracked under. We'll merge its expenses onto you.",
                color = c.ink2, fontSize = 12.sp,
            )
        }
        placeholders.forEach { p ->
            IdentityRow(name = p.name, selected = selectedId == p.id, isNew = false, onClick = { onSelect(p.id) })
        }
        IdentityRow(name = "I'm new here", selected = selectedId == null, isNew = true, onClick = { onSelect(null) })
    }
}

/** One identity row: check + avatar (or a person glyph for the "new" option) + name. */
@Composable
private fun IdentityRow(name: String, selected: Boolean, isNew: Boolean, onClick: () -> Unit) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) c.selectionTint else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.selectionStroke else c.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EvCheck(checked = selected)
        if (isNew) {
            Box(
                Modifier.size(AvatarSize.Sm.dp).clip(RoundedCornerShape(50))
                    .border(1.dp, c.border, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                EvIcon(EvIcons.User, size = 16.dp, tint = c.ink2)
            }
        } else {
            EvAvatar(name = name, me = selected, size = AvatarSize.Sm)
        }
        Text(name, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Preview
@Composable
private fun JoinPreview() {
    EvenlyTheme { JoinGroupSheet() }
}

@Preview
@Composable
private fun JoinWithPlaceholdersPreview() {
    EvenlyTheme {
        JoinGroupSheet(
            memberNames = listOf("Sarah K.", "Dave", "Sarah A.", "Max"),
            placeholders = listOf(JoinPlaceholderOption("p1", "Dave"), JoinPlaceholderOption("p2", "Sarah A.")),
        )
    }
}

@Preview
@Composable
private fun JoinAlreadyPreview() {
    EvenlyTheme { JoinGroupSheet(already = true) }
}
