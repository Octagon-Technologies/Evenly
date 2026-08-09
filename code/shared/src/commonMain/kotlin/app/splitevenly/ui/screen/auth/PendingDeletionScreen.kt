package app.splitevenly.ui.screen.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.core.time.shortDate
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Full-screen gate shown right after sign-in when the account has a deletion request pending
 * (`AuthSession.pendingDeletionAt`). Blocks the rest of the app: the user just asked to leave, so
 * letting them keep adding expenses to groups they're about to leave would be confusing, not helpful.
 */
@Composable
fun PendingDeletionScreen(
    purgeAtMillis: Long,
    cancelling: Boolean = false,
    error: String? = null,
    onCancelDeletion: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    Box(Modifier.fillMaxSize().background(c.page), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(64.dp).background(c.blueTint, shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                EvIcon(EvIcons.Calendar, size = 28.dp, tint = c.blueText)
            }
            Text(
                "Account scheduled for deletion",
                modifier = Modifier.padding(top = 20.dp),
                color = c.ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                "Your account and profile will be permanently removed on ${shortDate(purgeAtMillis)}. " +
                    "Sign back in any time before then to keep your account.",
                modifier = Modifier.padding(top = 8.dp),
                color = c.ink2,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            error?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 16.dp),
                    color = c.danger,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (cancelling) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.blueText, modifier = Modifier.size(22.dp))
                    }
                } else {
                    EvButton("Keep my account", onCancelDeletion)
                    EvButton("Sign out", onSignOut, variant = ButtonVariant.Secondary)
                }
            }
        }
    }
}

@Preview
@Composable
private fun PendingDeletionScreenPreview() {
    EvenlyTheme { PendingDeletionScreen(purgeAtMillis = 1_800_000_000_000L) }
}
