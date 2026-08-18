package app.splitevenly.ui.screen.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.splitevenly.core.error.AppResult
import app.splitevenly.domain.auth.AuthSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Home gate knows about this account's pending-deletion state. */
sealed interface HomeGateState {
    /** The server hasn't answered yet. Home holds blank rather than flashing before a possible block. */
    data object Checking : HomeGateState

    data class Blocked(
        val purgeAtMillis: Long,
        val cancelling: Boolean = false,
        val error: String? = null,
    ) : HomeGateState

    data object Allowed : HomeGateState
}

/**
 * Owns the pending-deletion check that gates Home.
 *
 * It is a ViewModel purely for its lifetime: scoped to the Home back-stack entry, the answer is
 * computed once and survives pushing a group (or any other screen) over Home. Held in a `remember`
 * instead, the state reset every time Home was recomposed after a pop, so Back out of a group
 * re-ran a network round trip behind a blank screen — a black flash on every Back, and a long one
 * offline.
 */
class HomeGateViewModel(
    private val auth: AuthSession,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeGateState>(HomeGateState.Checking)
    val state: StateFlow<HomeGateState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // A failed check reads as "not deleting": locking someone out of their own groups because
            // the network hiccuped is the worse error of the two.
            val purgeAt =
                when (val result = auth.pendingDeletionAt()) {
                    is AppResult.Ok -> result.value
                    is AppResult.Err -> null
                }
            _state.value = purgeAt?.let { HomeGateState.Blocked(it) } ?: HomeGateState.Allowed
        }
    }

    fun cancelDeletion() {
        val blocked = _state.value as? HomeGateState.Blocked ?: return
        _state.value = blocked.copy(cancelling = true, error = null)
        viewModelScope.launch {
            _state.value =
                when (auth.cancelAccountDeletion()) {
                    is AppResult.Ok -> {
                        HomeGateState.Allowed
                    }

                    is AppResult.Err -> {
                        blocked.copy(
                            cancelling = false,
                            error = "Couldn't cancel deletion. Check your connection and try again.",
                        )
                    }
                }
        }
    }
}
