# iOS push delivery — setup steps (F7)

The Kotlin side is **already done**: `PushService.ios.kt` reads the latest token from `IosPushTokenHolder`,
and `PushController` (shared) registers it to `device_tokens` + pulls on each delivered message. All that
remains is wiring the Firebase Messaging iOS SDK in the Swift host so it *feeds* `IosPushTokenHolder` /
`PushBus`. None of this can be done from Kotlin — it needs Xcode-project + Apple-account artifacts.

You can do every step below **except the APNs key**, which needs the paid Apple Developer account
(\$99/yr). Everything compiles and registers without it; only actual delivery to a device requires APNs,
so finish step 3's key when the account exists.

## 1. Add the Firebase SDK (Swift Package Manager)
Xcode → **File → Add Package Dependencies** → `https://github.com/firebase/firebase-ios-sdk` → add the
**FirebaseMessaging** product to the `iosApp` target.

## 2. Add the config file
Firebase console → add an **iOS app** (bundle id `da.chelimo.sharecost`) → download **`GoogleService-Info.plist`**
→ drag it into the `iosApp` target in Xcode (✓ "Copy if needed", target membership = iosApp).

## 3. Capabilities  ← the APNs-key part is deferred
Select the `iosApp` target → **Signing & Capabilities** → **+ Capability**:
- **Push Notifications**
- **Background Modes** → check **Remote notifications**

Then (needs the Apple Developer account): create an **APNs Auth Key** (`.p8`) in the Apple Developer
portal and upload it in Firebase console → Project settings → Cloud Messaging → *Apple app configuration*.
**Defer this until the paid account exists** — the rest works without it; delivery just won't reach a
physical device until it's uploaded.

## 4. Add an AppDelegate and bridge to the shared framework
Create `iosApp/AppDelegate.swift`:

```swift
import UIKit
import Shared
import FirebaseCore
import FirebaseMessaging
import UserNotifications

class AppDelegate: NSObject, UIApplicationDelegate, MessagingDelegate, UNUserNotificationCenterDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        FirebaseApp.configure()
        Messaging.messaging().delegate = self
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in }
        application.registerForRemoteNotifications()
        return true
    }

    // APNs device token → FCM (needs the APNs key from step 3 to actually mint an FCM token).
    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Messaging.messaging().apnsToken = deviceToken
    }

    // FCM registration token → the Kotlin bridge (PushController upserts it to device_tokens).
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        IosPushTokenHolder.shared.update(token: fcmToken)
    }

    // Delivered while foregrounded → forward into PushBus (PushController pulls fresh data).
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        forward(notification.request.content)
        completionHandler([.banner, .badge, .sound])
    }

    // Tapped notification → forward into PushBus.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        forward(response.notification.request.content)
        completionHandler()
    }

    private func forward(_ content: UNNotificationContent) {
        var data: [String: String] = [:]
        for (key, value) in content.userInfo {
            if let k = key as? String, let v = value as? String { data[k] = v }
        }
        PushBus.shared.emitMessage(message: PushMessage(title: content.title, body: content.body, data: data))
    }
}
```

## 5. Attach the delegate to the SwiftUI app
In `iOSApp.swift`:

```swift
@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate   // ← add this line

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in MainViewControllerKt.handleAuthDeeplink(url: url) }
        }
    }
}
```

That's it — `IosPushTokenHolder.shared` and `PushBus.shared` are already exported by the `Shared`
framework, and the shared `PushController` does the rest (token registration + pull-on-message),
symmetric with the Android `ShareCostMessagingService`.
