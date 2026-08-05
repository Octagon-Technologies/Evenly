# Firebase setup (required to build the Android app)

Firebase (FCM, Analytics, Crashlytics) is wired into the build, but the build needs your
per-project config files. Until they are added, `:androidApp:assembleDebug` fails at
`processDebugGoogleServices` — this is expected.

## 1. Create the Firebase project + apps

In the [Firebase console](https://console.firebase.google.com/):

1. Create (or open) a Firebase project.
2. **Add an Android app** with package name **`app.splitevenly`**.
3. **Add an iOS app** with bundle ID **`app.splitevenly`**
   (match whatever `PRODUCT_BUNDLE_IDENTIFIER` resolves to in `iosApp/Configuration/Config.xcconfig`).

> **Renamed from ShareCost.** The old Firebase apps were registered under `da.chelimo.sharecost`, so
> the tracked config files no longer match the build. Register the two apps above in the *existing*
> project and re-download both files: the checked-in ones were rewritten by hand during the rename so
> the build would stay green, which means FCM registration will fail at runtime until they are
> replaced. The Firebase **project ID stays `sharecost-98e79`** (Google does not allow renaming it);
> only its display name is cosmetic.

## 2. Download and place the config files

| Platform | Download | Put it here (exact path) |
|---|---|---|
| Android | `google-services.json` | `code/androidApp/google-services.json` |
| iOS | `GoogleService-Info.plist` | `code/iosApp/iosApp/GoogleService-Info.plist` |

- **Android**: dropping `google-services.json` at `code/androidApp/google-services.json` is all the
  Gradle build needs — `assembleDebug` will then succeed.
- **iOS**: also add the `.plist` to the Xcode target (drag into the `iosApp` group, tick the
  *iosApp* target → it lands in "Copy Bundle Resources").

## 3. iOS only — add the Firebase SDK in Xcode

The Gradle build does **not** manage the iOS Firebase SDK. In Xcode:

1. **File → Add Packages…** → `https://github.com/firebase/firebase-ios-sdk` → add
   `FirebaseMessaging`, `FirebaseAnalytics`, `FirebaseCrashlytics`.
2. In `iosApp/iosApp/iOSApp.swift`, `import FirebaseCore` and call `FirebaseApp.configure()` in the
   app init (and register for remote notifications / forward the APNs token to FCM — see 06 §5.4).

## Don't have the files yet and want a green build?

Temporarily comment these two lines in `androidApp/build.gradle.kts`:

```kotlin
//    alias(libs.plugins.googleServices)
//    alias(libs.plugins.crashlytics)
```

and the four `firebase.*` lines in `shared/build.gradle.kts` (`androidMain`). Re-enable once the
config files are in place.
