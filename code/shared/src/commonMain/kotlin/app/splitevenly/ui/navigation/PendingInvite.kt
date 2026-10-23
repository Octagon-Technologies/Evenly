package app.splitevenly.ui.navigation

import app.splitevenly.domain.group.InviteLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The invite link the app was opened with, waiting for [EvenlyNavHost] to navigate to it.
 *
 * Both platforms push here instead of relying on `navDeepLink`: Compose Navigation only reads the
 * launching Activity intent on Android, so a link that arrived while the app was already running
 * (`onNewIntent`) and every iOS `onOpenURL` were dropped. Holding the token also lets a signed-out user
 * finish sign-in or onboarding first; the nav host consumes it only once they reach the app proper.
 */
object PendingInvite {
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    /** Records [url] if it is an invite link; any other URL (auth callbacks, etc.) is ignored. */
    fun offer(url: String?) {
        InviteLink.tokenFrom(url ?: return)?.let { _token.value = it }
    }

    fun consume() {
        _token.value = null
    }
}
