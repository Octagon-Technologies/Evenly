package app.splitevenly.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Is the app currently on screen? Written by the Compose root ([app.splitevenly.App]) off the
 * platform lifecycle, read by [app.splitevenly.data.remote.supabase.SyncManager] to gate the
 * whole sync driver — realtime socket, push-on-write, and the 60s tick all stop when we're not
 * visible, and resume with a catch-up sync.
 *
 * Defaults to **false**: an FCM-woken headless Android process never creates an Activity, so nothing
 * would ever set this back to false and its loops would run forever. Background delivery still pulls
 * via `PushController`, which is deliberately ungated.
 */
class AppForeground {
    private val _state = MutableStateFlow(false)
    val state: StateFlow<Boolean> = _state.asStateFlow()

    fun set(visible: Boolean) {
        _state.value = visible
    }
}
