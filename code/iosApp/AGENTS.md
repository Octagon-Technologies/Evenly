# `code/iosApp/` — the Xcode host and Kotlin/Native

Governs the iOS host app and anything that only breaks on Kotlin/Native. The shared framework is built from
`:shared` with `baseName = "Shared"`. Android host lives in `code/androidApp/`.

## Run it

```bash
code/iosApp/run-ios-sim.sh
```

Mirrors the Android Studio "iOS App (Simulator)" run config: boots or reuses a simulator, builds via
`xcodebuild` (which also compiles the shared Kotlin/Native framework), then installs and launches. **This is
the default surface for manual verification** — the owner usually has the simulator open, and running a
simulator and an Android emulator at once burns CPU/RAM for nothing.

Compile-only check, much faster than a full Xcode build:

```bash
cd code && ./gradlew :shared:compileKotlinIosSimulatorArm64
```

**Android-green is not iOS-green.** Several APIs resolve on Android/JVM and fail on Native — most famously
`RoomDatabase.clearAllTables()`. Never declare a change green without the Native compile above.

## Kotlin/Native gotchas, all of them learned the hard way

- **A type-safe nav `Route` arg that is an enum crashes the NavHost on Native.** Use `String`; the tab arg
  does. This is the most repeated iOS-only crash in the repo.
- **`kotlinx-datetime`'s `Clock` moved to `kotlin.time.Clock`.** Import from there.
- **Use `KoinPlatform`, not `GlobalContext`, from iOS host code** — Native has no `GlobalContext`.
- **Coil 3 needs the Ktor network fetcher registered explicitly** (done in `App.kt`). There is no
  `ServiceLoader` on Native, so nothing auto-registers.
- **Empty AppIcon dark/tinted slots break the build.** Fill every slot in the asset catalog or remove the
  variants entirely. The catalog ships a single opaque 1024 `app-icon-1024.png` (no alpha, square: iOS
  applies its own mask) plus `LaunchMark.imageset` / `LaunchBackground.colorset` for the launch screen.
- **The launch screen is `UILaunchScreen` in `Info.plist`, not a storyboard.** It paints
  `LaunchBackground` (brand blue) with `LaunchMark` (white feather) centred. Compose then draws the same
  blue in `SplashScreen.kt`, so the two must stay the same hex or the hand-off flickers. Root
  `AGENTS.md` §4.8 lists all three places that blue is declared.
- **The simulator rejects Supabase TLS on some managed WiFi.** If sync fails only on the sim and only on a
  corporate/university network, suspect the network before the code.

## Host responsibilities the shared module cannot cover

- **Background upload completion.** `AppDelegate` must implement
  `handleEventsForBackgroundURLSession` for suspended-app completion of receipt uploads (session id
  `app.splitevenly.receiptUpload`). The shared side is `ReceiptUploadScheduler.ios.kt`; see
  `../shared/src/commonMain/kotlin/app/splitevenly/data/AGENTS.md` for the outbox design.
- **Push setup** is documented in `PUSH_SETUP.md` (same directory). Delivery is currently inert.
- **Never commit** `GoogleService-Info.plist` containing private data, or APNs `.p8`/`.p12` keys.

## Module shape

`:shared` uses the modern `com.android.kotlin.multiplatform.library` plugin, so it has **no
AndroidManifest** — the Android manifest and Firebase config live in `code/androidApp/`. Package is
`app.splitevenly` (not `com.evenly`).
