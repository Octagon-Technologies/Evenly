package app.splitevenly.platform

import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android [PushService] (06 §5.4) over **Firebase Cloud Messaging**. Token rotation and message
 * delivery arrive on a manifest-registered `FirebaseMessagingService` (host concern), which forwards
 * into [PushBus]; this class re-exposes those streams and serves on-demand token reads. FCM's
 * `Task`-based API is bridged to `suspend` so callers stay in coroutine-land.
 */
actual class PushService {

    private val messaging: FirebaseMessaging get() = FirebaseMessaging.getInstance()

    actual suspend fun currentToken(): AppResult<String?> =
        try {
            AppResult.Ok(messaging.token.await())
        } catch (e: Throwable) {
            AppResult.Err(AppError.Network(AppError.Network.Kind.Unreachable, e))
        }

    actual suspend fun deleteToken(): AppResult<Unit> =
        try {
            messaging.deleteToken().await()
            AppResult.Ok(Unit)
        } catch (e: Throwable) {
            AppResult.Err(AppError.Network(AppError.Network.Kind.Unreachable, e))
        }

    actual val tokenRefreshes: Flow<String> = PushBus.tokens
    actual val messages: Flow<PushMessage> = PushBus.messages
}

/** Bridge a Play-services [Task] to `suspend` without pulling in `kotlinx-coroutines-play-services`. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { error -> cont.resumeWithException(error) }
    addOnCanceledListener { cont.cancel() }
}
