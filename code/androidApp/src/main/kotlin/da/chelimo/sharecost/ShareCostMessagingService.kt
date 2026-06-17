package da.chelimo.sharecost

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import da.chelimo.sharecost.platform.PushBus
import da.chelimo.sharecost.platform.PushMessage

/**
 * Manifest-registered FCM entry point (06 §5.4). The OS owns this callback site, so it can't be a pure
 * `expect/actual` — it simply forwards token rotations + delivered payloads into the shared [PushBus],
 * which `PushService` re-exposes and `PushController` consumes (register token / pull on message).
 */
class ShareCostMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushBus.emitToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        PushBus.emitMessage(
            PushMessage(
                title = message.notification?.title,
                body = message.notification?.body,
                data = message.data,
            ),
        )
    }
}
