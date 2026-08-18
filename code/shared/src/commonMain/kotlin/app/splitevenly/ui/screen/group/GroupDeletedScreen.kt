package app.splitevenly.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * What a member sees when they are standing inside a group at the moment someone else's delete
 * arrives from sync.
 *
 * Without this the group screen renders against a null group: a blank shell with live-looking tabs
 * and an Add button that writes expenses into a group nobody else can see. The gate replaces all four
 * tabs at once, says who did it, and puts Restore where the confusion is instead of asking the person
 * to find their way to Home → Recently deleted on their own.
 */
@Composable
fun GroupDeletedScreen(
    groupName: String,
    groupEmoji: String,
    /** Pre-resolved: "Andrew deleted this group" / "You deleted this group" / "This group was deleted". */
    deletedByLine: String,
    daysLeft: Int,
    onRestore: () -> Unit,
    onBackToGroups: () -> Unit,
    restoring: Boolean = false,
    error: String? = null,
) {
    val c = EvenlyTheme.colors
    Box(Modifier.fillMaxSize().background(c.page).systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                Text(groupEmoji, fontSize = 32.sp)
            }
            Text(
                "$groupName was deleted",
                color = c.ink,
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Text(
                "$deletedByLine Nothing is lost yet.",
                color = c.ink2,
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp),
            )
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).background(c.warningTint).padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EvIcon(EvIcons.Clock, size = 13.dp, tint = c.credit)
                Text(
                    when (daysLeft) {
                        0 -> "Deletes for good today"
                        1 -> "1 day left to restore"
                        else -> "$daysLeft days left to restore"
                    },
                    color = c.credit,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            error?.let {
                Text(it, color = c.danger, fontSize = 12.5.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Live even while restoring, so a double tap is a no-op rather than a dead control.
                EvButton(if (restoring) "Restoring…" else "Restore for everyone", onRestore)
                EvButton("Back to groups", onBackToGroups, variant = ButtonVariant.Secondary)
            }
        }
    }
}

@Preview
@Composable
private fun GroupDeletedPreview() {
    EvenlyTheme {
        GroupDeletedScreen(
            groupName = "Tulum Trip",
            groupEmoji = "🏝️",
            deletedByLine = "Andrew deleted this group for everyone on 17 Aug.",
            daysLeft = 27,
            onRestore = {},
            onBackToGroups = {},
        )
    }
}
