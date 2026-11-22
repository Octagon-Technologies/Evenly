package app.splitevenly.ui.navigation

import app.splitevenly.domain.expense.quickSplitTokenFrom
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
        val raw = url ?: return
        // A quick-split link carries a bill token, not an invite; it is resolved to its group first.
        quickSplitTokenFrom(raw)?.let {
            _quickSplitToken.value = it
            return
        }
        InviteLink.tokenFrom(raw)?.let { _token.value = it }
    }

    fun consume() {
        _token.value = null
    }

    private val _quickSplitToken = MutableStateFlow<String?>(null)

    /** A `split-evenly.app/s/<token>` link the app was opened with (docs/features/one-time-splits.md, A3). */
    val quickSplitToken: StateFlow<String?> = _quickSplitToken.asStateFlow()

    fun consumeQuickSplit() {
        _quickSplitToken.value = null
    }
}
