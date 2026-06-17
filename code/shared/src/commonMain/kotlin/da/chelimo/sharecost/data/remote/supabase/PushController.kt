package da.chelimo.sharecost.data.remote.supabase

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.platform.PushService
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Wire DTO for `device_tokens` — the FCM/APNs token is the PK (snake_case via the client serializer). */
@Serializable
data class DeviceTokenRow(
    val id: String,
    val userId: String,
    val platform: String,
    val updatedAt: Long,
)

/**
 * Push registration + delivery glue (F7). Re-exposes nothing itself; it just connects the platform
 * [PushService] streams to the rest of the app:
 *  - registers the device's FCM token against the signed-in user (`device_tokens`) so a server-side
 *    sender can target it,
 *  - on every delivered push, pulls fresh data (the payload is a nudge; the DB is authoritative).
 *
 * Token upserts are best-effort (offline / unconfigured FCM just no-ops). The actual *sending* of a
 * push is a server/edge-function concern, out of the client's scope.
 */
@OptIn(ExperimentalTime::class)
class PushController(
    private val pushService: PushService,
    private val syncEngine: SyncEngine,
    private val client: SupabaseClient,
    private val clock: Clock = Clock.System,
) {
    fun bind(scope: CoroutineScope, currentUserId: StateFlow<UserId?>) {
        // Register the current token whenever a user becomes known (sign-in / restored session).
        scope.launch {
            currentUserId.collect { uid -> if (uid != null) registerCurrentToken(uid.value) }
        }
        // Token rotations from the OS.
        scope.launch {
            pushService.tokenRefreshes.collect { token -> currentUserId.value?.let { upsert(it.value, token) } }
        }
        // A delivered push → pull the authoritative data (and let the UI's flows update).
        scope.launch {
            pushService.messages.collect { currentUserId.value?.let { syncEngine.pull(it.value) } }
        }
    }

    private suspend fun registerCurrentToken(userId: String) {
        when (val token = pushService.currentToken()) {
            is AppResult.Ok -> token.value?.let { upsert(userId, it) }
            is AppResult.Err -> Unit // FCM not available right now; a later token refresh will retry.
        }
    }

    private suspend fun upsert(userId: String, token: String) {
        runCatching {
            client.from("device_tokens").upsert(
                DeviceTokenRow(id = token, userId = userId, platform = PLATFORM, updatedAt = clock.nowEpochMillis()),
            )
        }
    }

    private companion object {
        const val PLATFORM = "fcm"
    }
}
