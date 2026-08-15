package app.splitevenly.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.auth.SignOutOutcome
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.coroutines.launch

/**
 * The sign-out interaction, shared by every screen that offers it, because the two call sites drifting
 * apart is how one of them ends up silently discarding someone's unsynced expenses.
 *
 * Signing out now clears this device's cache (#24), so it first pushes and only wipes once the rows are
 * safely on the server. Two consequences the UI has to carry:
 *
 *  - **It is no longer instant.** It makes a network call, so the control has a pending state. Without
 *    one, tapping "Sign out" looks broken for a couple of seconds and people tap it again.
 *  - **It can come back with a question.** If the push failed and local changes are still pending, the
 *    session stays live and untouched and we ask, rather than choosing silently on the user's behalf.
 *
 * Usage: `val signOut = rememberSignOutFlow(auth, onSignedOut)` then `onSignOut = signOut::start`, and
 * render `signOut.Dialog()` somewhere in the same composition.
 */
@Composable
fun rememberSignOutFlow(
    auth: AuthSession,
    onSignedOut: () -> Unit,
): SignOutFlow {
    val scope = rememberCoroutineScope()
    return remember(auth, onSignedOut) { SignOutFlow(auth, onSignedOut, scope) }
}

@Stable
class SignOutFlow internal constructor(
    private val auth: AuthSession,
    private val onSignedOut: () -> Unit,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    /** True while the final push is in flight, so the caller can show the control as busy. */
    var inProgress by mutableStateOf(false)
        private set

    private var pendingWrites by mutableStateOf<Int?>(null)

    fun start() = attempt(discardUnsynced = false)

    private fun attempt(discardUnsynced: Boolean) {
        if (inProgress) return
        inProgress = true
        scope.launch {
            when (val outcome = auth.signOut(discardUnsynced = discardUnsynced)) {
                is SignOutOutcome.SignedOut -> {
                    inProgress = false
                    pendingWrites = null
                    onSignedOut()
                }

                is SignOutOutcome.UnsyncedChanges -> {
                    inProgress = false
                    pendingWrites = outcome.pendingWrites
                }
            }
        }
    }

    /** Renders the "your changes haven't saved yet" confirmation, or nothing when there is none to ask. */
    @Composable
    fun Dialog() {
        val pending = pendingWrites ?: return
        val c = EvenlyTheme.colors
        EvModalScaffold(onDismiss = { pendingWrites = null }) {
            Text(
                "Not everything is saved yet",
                color = c.ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                // The COUNT is the whole message: it is the only thing that tells the person holding
                // the phone whether this is one stray edit or their whole evening. No "sync", no
                // "server", no "pending writes" (jargon blocklist) — those are our words, not theirs.
                if (pending == 1) {
                    "1 thing you did on this phone isn't saved yet. Signing out now deletes it."
                } else {
                    "$pending things you did on this phone aren't saved yet. Signing out now deletes them."
                },
                color = c.ink2,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                // Names the exact retry, because "try again later" leaves the user guessing which
                // control to press and whether staying signed in has already fixed anything.
                if (pending == 1) {
                    "Get back online, then tap Sign out again to save it first."
                } else {
                    "Get back online, then tap Sign out again to save them first."
                },
                color = c.ink2,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Primary is the safe one: the destructive path must be the deliberate tap, and its
                // label says what it destroys rather than a bare "Sign out".
                EvButton("Stay signed in", { pendingWrites = null }, variant = ButtonVariant.Primary)
                EvButton(
                    if (pending == 1) "Sign out and delete it" else "Sign out and delete them",
                    {
                        pendingWrites = null
                        attempt(discardUnsynced = true)
                    },
                    variant = ButtonVariant.Danger,
                )
            }
        }
    }
}
