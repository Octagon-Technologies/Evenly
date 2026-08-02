package app.splitevenly.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * iOS [NotificationPermission] — `UNUserNotificationCenter` authorization, symmetric with the Android
 * actual (no Activity to reach, so no constructor arg).
 *
 * A grant here only covers *displaying* notifications. Getting an APNs token still needs
 * `registerForRemoteNotifications()` from the Swift host on the main thread, which the `AppDelegate`
 * owns — see `iosApp/PUSH_SETUP.md`.
 */
actual class NotificationPermission {

    actual suspend fun status(): NotificationPermissionStatus = suspendCancellableCoroutine { cont ->
        UNUserNotificationCenter.currentNotificationCenter()
            .getNotificationSettingsWithCompletionHandler { settings ->
                cont.resume(
                    when (settings?.authorizationStatus) {
                        // Provisional = quiet delivery already allowed; we may post.
                        UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional ->
                            NotificationPermissionStatus.Granted
                        UNAuthorizationStatusDenied -> NotificationPermissionStatus.Denied
                        else -> NotificationPermissionStatus.NotDetermined
                    },
                )
            }
    }

    actual suspend fun request(): Boolean = suspendCancellableCoroutine { cont ->
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(options) { isGranted, _ ->
                // A refusal isn't an error here — the caller finishes onboarding either way.
                cont.resume(isGranted)
            }
    }
}
