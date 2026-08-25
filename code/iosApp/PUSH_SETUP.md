# iOS push delivery — setup steps (F7)

The Kotlin side is **already done**: `PushService.ios.kt` reads the latest token from `IosPushTokenHolder`,
and `PushController` (shared) registers it to `device_tokens` + pulls on each delivered message.

**The Swift host side is now done too** — `iosApp/AppDelegate.swift` exists and is attached via
`@UIApplicationDelegateAdaptor` in `iOSApp.swift`, and `GoogleService-Info.plist` sits inside
`iosApp/iosApp/`, which is a `PBXFileSystemSynchronizedRootGroup`, so it is bundled automatically with no
`.pbxproj` entry. Its Firebase imports are `#if canImport(...)`-guarded, so the app builds and runs
whether or not the SDK package has been added yet; adding the package turns push on with no source edit.

**What is left is only step 3** below — an Xcode/Apple-console action that cannot be done from Kotlin or
from a text editor. Steps 1, 2, 4 and 5 are already applied; they are kept here as a record of what the
wiring does.

## 1. Add the Firebase SDK (Swift Package Manager)  ✅ done
`firebase-ios-sdk` is a committed SPM dependency of the `iosApp` target, pinned
`upToNextMajorVersion` from **12.18.0**. Two products are linked:

| Product | Why |
| --- | --- |
| `FirebaseMessaging` | Push. What `AppDelegate` actually calls. |
| `FirebaseCrashlytics` | iOS crash reports, matching the Android Crashlytics Gradle plugin. No Kotlin code calls it - linking it plus `FirebaseApp.configure()` is the whole integration. |

**`FirebaseAnalytics` is deliberately NOT linked.** PostHog is this app's analytics
(`libs.posthog.kmp`, commonMain) and nothing calls the Firebase Analytics API on either platform.
Linking it would pull `GoogleAppMeasurement` into the binary and add IDFA/tracking-domain obligations
to App Privacy for no measurable benefit. Add it only if something starts genuinely reading it.

A build phase, **Upload Crashlytics dSYMs**, runs the SDK's `Crashlytics/run` script so crash reports
symbolicate. It is guarded by an `[ -f ]` test, so a checkout whose packages have not resolved yet still
builds instead of failing the phase.

Verify: `grep -c firebase-ios-sdk iosApp.xcodeproj/project.pbxproj` returns non-zero, and the linked
classes are present in the built binary:

```bash
nm <built>/Evenly.app/Evenly.debug.dylib | grep -oE '_OBJC_CLASS_\$_(FIRApp|FIRMessaging|FIRCrashlytics)'
```

## 2. Add the config file  ✅ done
`code/iosApp/iosApp/GoogleService-Info.plist` (bundle id `app.splitevenly`, Firebase project
`split-evenly`). It used to sit one directory up, outside the synchronized folder, which meant it was
**never copied into the app bundle** — `FirebaseApp.configure()` would have trapped at launch.

## 3. Capabilities  ← STILL TO DO
Select the `iosApp` target → **Signing & Capabilities** → **+ Capability** → **Push Notifications**.

Do this in the Xcode UI rather than by hand-editing `iosApp/iosApp/iosApp.entitlements`: the click both
adds `aps-environment` to the entitlements *and* enables the Push Notifications service on the
`app.splitevenly` App ID in the developer portal. Adding the key by hand without the portal side makes
**automatic signing fail**, which would break the archive.

`UIBackgroundModes` → `remote-notification` is already declared in `Info.plist`, so the Background Modes
capability does not need adding separately; it is inert until `aps-environment` exists.

Then create an **APNs Auth Key** (`.p8`) in the Apple Developer portal and upload it in Firebase console
→ Project settings → Cloud Messaging → *Apple app configuration*. This was previously deferred for want
of a paid Apple Developer account; the project now signs with team `3VC8F74G23`, so it is no longer
blocked. Delivery to a real device does not work until the key is uploaded.

Verify: `plutil -p iosApp/iosApp/iosApp.entitlements` shows an `aps-environment` key.

## 4. AppDelegate and the bridge to the shared framework  ✅ done
Implemented in `iosApp/iosApp/AppDelegate.swift`. It differs from the sketch below in three ways worth
knowing: the Firebase imports are `canImport`-guarded (so the file compiles before step 1 is done),
`FirebaseApp.configure()` is skipped when the plist is missing from the bundle rather than trapping, and
it also implements `handleEventsForBackgroundURLSession` — bridged into Kotlin through
`IosBackgroundUploadEvents`, which `ReceiptUploadScheduler.ios.kt`'s delegate fires once the background
receipt-upload session drains. That last hook was a separate, previously-missing host responsibility
called out in `AGENTS.md`, not part of push.

Original sketch, kept for reference:

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
        // Do NOT call requestAuthorization here. iOS gives one prompt per install and never re-asks, so
        // asking at launch spends it on a user who has not signed in, has no group, and has been told
        // nothing. The ask belongs to the shared onboarding "Stay in the loop" step, which explains it
        // first and then goes through `platform/NotificationPermission` (iOS actual =
        // UNUserNotificationCenter). Android made exactly this mistake in MainActivity.onCreate; don't
        // reintroduce it here.
        //
        // registerForRemoteNotifications() is safe and belongs here: it mints the APNs token and shows no
        // UI. Without authorization iOS simply won't display what arrives.
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

## 5. Attach the delegate to the SwiftUI app  ✅ done
Applied in `iOSApp.swift`:

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
symmetric with the Android `EvenlyMessagingService`.
