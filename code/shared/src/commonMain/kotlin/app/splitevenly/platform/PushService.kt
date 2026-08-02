package app.splitevenly.platform

import app.splitevenly.core.error.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A delivered push payload (06 §5.4), normalised across FCM (Android) and APNs/FCM (iOS). */
data class PushMessage(
    val title: String?,
    val body: String?,
    val data: Map<String, String>,
)

/**
 * Process-wide relay that the platform notification entry points forward into — the part of push
 * that can't be expressed as a pure `expect/actual` because the OS owns the callback site. On
 * **Android** a manifest-registered `FirebaseMessagingService` calls [emitToken] / [emitMessage] from
 * `onNewToken` / `onMessageReceived`; on **iOS** the `AppDelegate` (Firebase `MessagingDelegate` +
 * `UNUserNotificationCenterDelegate`) does the same. [PushService] simply exposes these streams.
 *
 * Replay/extra-buffer = 0: late collectors don't get a stale token/notification re-delivered. For the
 * "what's my token right now" question, use [PushService.currentToken] instead of this stream.
 */
object PushBus {
    private val _tokens = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val _messages = MutableSharedFlow<PushMessage>(extraBufferCapacity = 8)

    val tokens: SharedFlow<String> = _tokens.asSharedFlow()
    val messages: SharedFlow<PushMessage> = _messages.asSharedFlow()

    /** Host hook: a new registration token was issued. */
    fun emitToken(token: String) { _tokens.tryEmit(token) }

    /** Host hook: a push payload was delivered. */
    fun emitMessage(message: PushMessage) { _messages.tryEmit(message) }
}

/**
 * E-4 — push registration + delivery surface (06 §5.4). **Firebase Messaging on both platforms** (iOS
 * forwards its APNs token to FCM). [currentToken] reads the device's FCM token on demand; [tokenRefreshes]
 * and [messages] re-expose [PushBus] so consumers needn't know about the relay.
 *
 * Constructor is platform-specific — instances come from `platformModule()`.
 */
expect class PushService {
    /** The current FCM registration token, or an [AppResult.Err] if it can't be obtained. */
    suspend fun currentToken(): AppResult<String?>

    /** Invalidate the current token (e.g. on sign-out); a new one is minted on next request. */
    suspend fun deleteToken(): AppResult<Unit>

    /** New tokens as the OS rotates them (from [PushBus]). */
    val tokenRefreshes: Flow<String>

    /** Delivered push payloads (from [PushBus]). */
    val messages: Flow<PushMessage>
}
