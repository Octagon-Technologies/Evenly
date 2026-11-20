package app.splitevenly.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.auth.SignOutOutcome
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.EvSpinner
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

    /** The question to ask, or null when there is none. Boxed rather than kept as a bare count because
     *  the count can legitimately be unknown — see [SignOutOutcome.UnsyncedChanges.pendingWrites]. */
    private var unsynced by mutableStateOf<SignOutOutcome.UnsyncedChanges?>(null)

    /** True once "Save and sign out" came back still unsaved, so the dialog can say so instead of
     *  silently showing the same question again. */
    private var saveFailed by mutableStateOf(false)

    fun start() {
        saveFailed = false
        attempt(discardUnsynced = false)
    }

    /** Retries the save-first sign-out from the dialog. [AuthSession.signOut] already pushes before it
     *  counts, so a retry is the whole "save, then sign out" path: it signs out only once nothing is
     *  left pending, and otherwise leaves the session and the cache untouched. */
    private fun saveAndSignOut() = attempt(discardUnsynced = false, fromDialog = true)

    private fun attempt(
        discardUnsynced: Boolean,
        fromDialog: Boolean = false,
    ) {
        if (inProgress) return
        inProgress = true
        scope.launch {
            when (val outcome = auth.signOut(discardUnsynced = discardUnsynced)) {
                is SignOutOutcome.SignedOut -> {
                    inProgress = false
                    unsynced = null
                    saveFailed = false
                    onSignedOut()
                }

                is SignOutOutcome.UnsyncedChanges -> {
                    inProgress = false
                    unsynced = outcome
                    saveFailed = fromDialog
                }
            }
        }
    }

    /** Renders the "changes aren't saved yet" question, or nothing when there is none to ask. */
    @Composable
    fun Dialog() {
        val question = unsynced ?: return
        val pending = question.pendingWrites
        val c = EvenlyTheme.colors
        val busy = inProgress
        // Always clear on dismiss: the scaffold has already animated out, so keeping the question would
        // leave it invisible, and an equal question from the next tap would never bring it back. A save
        // still in flight then either signs out or re-asks with the failure line.
        EvModalScaffold(onDismiss = { unsynced = null }) {
            Text(
                // The count is the whole message: it says whether this is one stray edit or a whole
                // evening. An unknown count stays vague rather than inventing a number.
                when {
                    pending == null -> "Some changes aren't saved yet"
                    pending == 1 -> "1 change isn't saved yet"
                    else -> "$pending changes aren't saved yet"
                },
                color = c.ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            if (saveFailed && !busy) {
                Spacer(Modifier.height(8.dp))
                Text("Couldn't save. Check your connection.", color = c.danger, fontSize = 15.sp)
            }
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Saving is the primary path: the user asked to sign out, and this gets them there
                // without losing anything. The destructive path is a small text link, never a peer.
                EvButton(onClick = { if (!busy) saveAndSignOut() }, variant = ButtonVariant.Primary) {
                    if (busy) {
                        EvSpinner(color = LocalContentColor.current)
                        Text("Saving…", color = LocalContentColor.current, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text(
                            if (saveFailed) "Try again" else "Save and sign out",
                            color = LocalContentColor.current,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                EvButton(
                    "Stay signed in",
                    { if (!busy) unsynced = null },
                    variant = ButtonVariant.Tonal,
                )
                EvButton(
                    onClick = {
                        if (!busy) {
                            unsynced = null
                            attempt(discardUnsynced = true)
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    variant = ButtonVariant.Text,
                ) {
                    Text("Sign out without saving", color = c.danger, fontSize = 15.sp)
                }
            }
        }
    }
}
