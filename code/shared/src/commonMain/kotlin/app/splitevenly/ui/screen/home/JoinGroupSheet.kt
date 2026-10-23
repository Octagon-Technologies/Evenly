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
import app.splitevenly.ui.components.EvAvatarStack
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.groupIconLabel
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** 20 · Join group sheet (design/src/screens-home.jsx). Wired by `JoinRoute` from an invite token. */
@Composable
fun JoinGroupSheet(
    groupName: String = "Tulum Trip",
    emoji: String = "🏝️",
    subtitle: String = "Tap join to start sharing costs",
    memberNames: List<String> = emptyList(),
    found: Boolean = true,
    already: Boolean = false,
    onDismiss: () -> Unit = {},
    // Not a join yet when the group has unclaimed names: the route asks "Are any of these you?" first.
    onJoin: () -> Unit = {},
    onOpen: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    // A real full-screen destination (deep-link + "Join with a link" both land here), not a sheet on a
    // blank page: back arrow up top, the group preview centered as a hero, matching [NewGroupSheet].
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(title = "", navIcon = { EvIconButton(EvIcons.Back, onClick = onDismiss) }, showDivider = false)
        Column(
            Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    Modifier
                        .size(
                            80.dp,
                        ).clip(RoundedCornerShape(24.dp))
                        .background(c.surface)
                        .border(1.dp, c.border, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (found) groupIconLabel(emoji, groupName) else "🔗", fontSize = 42.sp, color = c.ink)
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
                            color = c.ink2,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                when {
                    !found -> {
                        EvButton("Close", onDismiss, variant = ButtonVariant.Text)
                    }

                    already -> {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            EvChip("You're already in this group", variant = ChipVariant.Blue, leadingIcon = EvIcons.Check)
                            EvButton("Open group", onOpen, leadingIcon = EvIcons.ChevR)
                        }
                    }

                    else -> {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            EvButton(text = "Join group", onClick = onJoin, leadingIcon = EvIcons.Users)
                            // "Not now" mirrors Join's full width + centering, but sits on a muted fill
                            // (a hair off the white sheet) so it reads as a quiet escape, not a tap target
                            // you'd hit by reflex.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(c.surface)
                                    .clickable(onClick = onDismiss),
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
}

@Preview
@Composable
private fun JoinPreview() {
    EvenlyTheme { JoinGroupSheet() }
}

@Preview
@Composable
private fun JoinAlreadyPreview() {
    EvenlyTheme { JoinGroupSheet(already = true) }
}
