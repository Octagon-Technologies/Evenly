package da.chelimo.sharecost.ui.screen.home

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAvatarStack
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScSheetScaffold
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

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
    onJoin: () -> Unit = {},
    onOpen: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    Box(Modifier.fillMaxSize().background(c.surface)) {
        ScSheetScaffold(onDismiss) {
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
                        ScAvatarStack(names = memberNames, size = AvatarSize.Sm)
                        Text(
                            "${memberNames.size} ${if (memberNames.size == 1) "member" else "members"}",
                            color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                when {
                    !found -> ScButton("Close", onDismiss, variant = ButtonVariant.Text)
                    already -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScChip("You're already in this group", variant = ChipVariant.Blue, leadingIcon = ScIcons.Check)
                        ScButton("Open group", onOpen, leadingIcon = ScIcons.ChevR)
                    }
                    else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ScButton("Join group", onJoin, leadingIcon = ScIcons.Users)
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

@Preview
@Composable
private fun JoinPreview() {
    ShareCostTheme { JoinGroupSheet() }
}

@Preview
@Composable
private fun JoinAlreadyPreview() {
    ShareCostTheme { JoinGroupSheet(already = true) }
}
