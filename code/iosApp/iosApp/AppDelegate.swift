import UIKit
import UserNotifications
import Shared

#if canImport(FirebaseCore)
import FirebaseCore
#endif
#if canImport(FirebaseMessaging)
import FirebaseMessaging
#endif

/// Host-side hooks the shared Compose/Kotlin module cannot own, because the OS owns the callback site:
/// Firebase Messaging's APNs↔FCM handshake, notification delivery, and background-URLSession completion.
///
/// The Firebase imports are `canImport`-guarded so this file compiles whether or not the
/// `firebase-ios-sdk` Swift Package has been added to the target yet (see `PUSH_SETUP.md` §1). Without
/// the package the app builds and runs exactly as before; adding the package activates push with no
/// further source changes. Do not remove the guards until the package is a committed dependency.
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    /// Handed to us by iOS when it relaunches the app to finish background uploads. Held until the
    /// upload session tells us it has drained; iOS throttles our future background scheduling if we
    /// never call it.
    private var backgroundSessionCompletionHandler: (() -> Void)?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        #if canImport(FirebaseCore)
        // Guarded: FirebaseApp.configure() traps if the plist is not in the bundle, which would turn a
        // packaging mistake into a launch crash rather than a silent loss of push.
        if Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            FirebaseApp.configure()
        } else {
            NSLog("[Evenly] GoogleService-Info.plist missing from bundle; Firebase not configured.")
        }
        #endif

        #if canImport(FirebaseMessaging)
        Messaging.messaging().delegate = self
        #endif

        UNUserNotificationCenter.current().delegate = self

        // Do NOT call requestAuthorization here. iOS gives one prompt per install and never re-asks, so
        // asking at launch spends it on a user who has not signed in, has no group, and has been told
        // nothing. The ask belongs to the shared onboarding "Stay in the loop" step, which explains it
        // first and then goes through `platform/NotificationPermission`.
        //
        // registerForRemoteNotifications() is safe and belongs here: it mints the APNs token and shows no
        // UI. Without authorization iOS simply won't display what arrives.
        application.registerForRemoteNotifications()

        return true
    }

    // MARK: - APNs

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        #if canImport(FirebaseMessaging)
        Messaging.messaging().apnsToken = deviceToken
        #endif
    }

    /// Expected until the Push Notifications capability and the APNs key are both in place — log rather
    /// than fail, so a build without push still runs normally.
    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        NSLog("[Evenly] Remote notification registration failed: \(error.localizedDescription)")
    }

    // MARK: - Background uploads

    /// iOS relaunched us to deliver receipt-upload completions for `app.splitevenly.receiptUpload`.
    /// The shared `ReceiptUploadScheduler` delegate fires the closure once its session has drained.
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        backgroundSessionCompletionHandler = completionHandler
        IosBackgroundUploadEvents.shared.setOnSessionFinished { [weak self] in
            // Delegate callbacks arrive on the session's background queue; UIKit wants this on main.
            DispatchQueue.main.async {
                self?.backgroundSessionCompletionHandler?()
                self?.backgroundSessionCompletionHandler = nil
            }
        }
    }

    // MARK: - Notification delivery

    /// Delivered while foregrounded; forward into `PushBus` so `PushController` pulls fresh data.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        forward(notification.request.content)
        completionHandler([.banner, .badge, .sound])
    }

    /// The user tapped the notification.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        forward(response.notification.request.content)
        completionHandler()
    }

    private func forward(_ content: UNNotificationContent) {
        var data: [String: String] = [:]
        for (key, value) in content.userInfo {
            if let k = key as? String, let v = value as? String { data[k] = v }
        }
        PushBus.shared.emitMessage(
            message: PushMessage(title: content.title, body: content.body, data: data)
        )
    }
}

#if canImport(FirebaseMessaging)
extension AppDelegate: MessagingDelegate {
    /// FCM registration token → the Kotlin bridge; `PushController` upserts it to `device_tokens`.
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        IosPushTokenHolder.shared.update(token: fcmToken)
    }
}
#endif
