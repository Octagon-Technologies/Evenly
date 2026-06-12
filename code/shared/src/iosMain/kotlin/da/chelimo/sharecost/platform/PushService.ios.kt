package da.chelimo.sharecost.platform

import da.chelimo.sharecost.core.error.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge fed by the `iosApp` `AppDelegate`. The Firebase Messaging iOS SDK is a Swift/CocoaPods
 * dependency, so the FCM token is delivered to Kotlin from Swift: `messaging(_:didReceiveRegistrationToken:)`
 * calls [update], and `UNUserNotificationCenterDelegate` calls [PushBus.emitMessage]. Keeping the latest
 * token here lets [PushService.currentToken] answer synchronously on a platform where Kotlin can't poll FCM
 * directly.
 */
object IosPushTokenHolder {
    private val _latest = MutableStateFlow<String?>(null)
    val latest: StateFlow<String?> = _latest.asStateFlow()

    /** Host hook: the Firebase `MessagingDelegate` received a (possibly null, on delete) registration token. */
    fun update(token: String?) {
        _latest.value = token
        if (token != null) PushBus.emitToken(token)
    }
}

/**
 * iOS [PushService] (06 §5.4). Firebase Messaging runs in the Swift host and feeds [IosPushTokenHolder];
 * this reads the latest token from there and re-exposes [PushBus] for refreshes/messages — symmetric with
 * the Android actual, which reads the token straight off the FCM SDK.
 */
actual class PushService {

    actual suspend fun currentToken(): AppResult<String?> =
        AppResult.Ok(IosPushTokenHolder.latest.value)

    actual suspend fun deleteToken(): AppResult<Unit> {
        IosPushTokenHolder.update(null)
        return AppResult.Ok(Unit)
    }

    actual val tokenRefreshes: Flow<String> = PushBus.tokens
    actual val messages: Flow<PushMessage> = PushBus.messages
}
