package app.splitevenly.data.remote.supabase

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.platform.PushService
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
    fun bind(
        scope: CoroutineScope,
        currentUserId: StateFlow<UserId?>,
    ) {
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

    private suspend fun upsert(
        userId: String,
        token: String,
    ) {
        runCatching {
            client.from("device_tokens").upsert(
                DeviceTokenRow(id = token, userId = userId, platform = PLATFORM, updatedAt = clock.nowEpochMillis()),
            )
        }
    }

    /**
     * Drop this device's `device_tokens` row for [userId], so notifications for the account that just
     * signed out stop arriving here.
     *
     * Registration only ever *upserts*, so before this existed the server went on believing this handset
     * belonged to A after A signed out. Every push for one of A's groups then rendered A's group name,
     * expense title and amount on the lock screen of a phone A no longer had a session on — and, until
     * B's sign-in completed and re-registered the token, on B's phone. It also woke
     * `PushController.bind`'s pull, which is the vector the sign-out fence exists to close.
     *
     * Must run while A's session can still authenticate the delete, i.e. before `client.auth.signOut()`.
     * Best-effort like the rest of sign-out: a failure here self-heals at the next registration, and
     * refusing to sign out because a DELETE failed is a dead end. A hard delete is correct and
     * deliberate here — `device_tokens` is the one table `data/AGENTS.md` names as ephemeral,
     * non-financial data that carries no tombstone.
     */
    suspend fun unregisterCurrentToken(userId: String) {
        val token = (pushService.currentToken() as? AppResult.Ok)?.value ?: return
        runCatching {
            client.from("device_tokens").delete {
                filter {
                    eq("id", token)
                    // Scoped to the departing account as well as the token: if the row has somehow already
                    // been claimed by whoever signs in next, it is theirs and must not be deleted.
                    eq("user_id", userId)
                }
            }
        }
    }

    private companion object {
        const val PLATFORM = "fcm"
    }
}
