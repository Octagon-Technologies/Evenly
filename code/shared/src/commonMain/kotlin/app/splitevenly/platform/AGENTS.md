# `platform/` — `expect`/`actual` boundaries

Governs `platform/**` across **all three source sets**: the `expect` declarations in `commonMain` and their
`actual`s in `androidMain` and `iosMain`. Most of the app's non-common code is here — 34 of the 37 files
outside `commonMain` are `platform/` actuals (verified 2026-07-23).

**Read this file when editing any `*.android.kt` or `*.ios.kt` under `platform/`,** even though the file you
are editing sits in a different source set than this document. `.claude/rules/kmp-source-sets.md` routes this
automatically for Claude; other agents should follow the pointer from the root `AGENTS.md`.

## The boundary is the abstraction

A platform `expect`/`actual` is one of the few places where **an interface with a single conceptual caller is
correct** — it marks a boundary the architecture requires, and it keeps `commonMain` testable without I/O.
Do not "simplify" one away because there is only one call site.

The inverse also holds: **do not add a platform abstraction on anticipation.** If the behavior is already
expressible in `commonMain`, keep it there. The smell is a `expect` that only forwards to a Kotlin stdlib
call on both sides.

**Both `actual`s must exist and compile.** Adding an `expect` without both actuals breaks the Native build,
which the Android build will not catch:

```bash
cd code && ./gradlew :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
```

Current boundaries: `ConnectivityObserver`, `CurrentActivity` (Android only), `FilePicker`, `ImageCache`,
`ImageProcessor`, `NotificationPermission`, `PdfRasterizer`, `Platform`, `PlatformShare`, `PushService`,
`ReceiptFileStore`, `ReceiptUploadScheduler`, `SecureStorage`, `UrlOpener`, plus `data/db/DatabaseBuilder`,
`di/PlatformModule`, and `ui/theme/SystemBars`.

`SecureStorage` is the device-local key/value store and is already registered as a Koin single — use it
rather than introducing a second local KV mechanism.

## ⚠️ A runtime permission is EARNED, never sprung

Both OSes give **exactly one** prompt per install and never re-ask. A prompt fired without context doesn't
just annoy: **a refusal permanently burns the capability**, and only a trip to system settings undoes it.

The rule, in three parts:

1. **Explain first.** The surface must state what the permission buys *before* the OS dialog appears.
2. **Ask on an explicit tap.** Only a deliberate tap ("Turn on notifications") may reach a `request()`, and
   there must be a free "Not now" that leaves the prompt unspent.
3. **Ask at the place of use.** Never from `onCreate`, `didFinishLaunching`, or a screen's first composition,
   and never on a hunch that the user might want it later.

**Notifications are the only runtime permission we ask for.** `NotificationPermission` (Android
`POST_NOTIFICATIONS` on 13+, via a one-shot `activityResultRegistry` launcher off `CurrentActivity`,
mirroring `FilePicker`; iOS `UNUserNotificationCenter`) is requested **only** from the onboarding "Stay in
the loop" step.

It was previously requested from `MainActivity.onCreate` — over the welcome carousel, pre-sign-in, with the
result discarded — while that onboarding step was decorative and asked for nothing. **Do not reintroduce
either half.**

Note that push delivery is still inert (no `FCM_SERVICE_ACCOUNT` secret, and nothing invokes `push-notify`),
so this ask is currently a promise the backend cannot yet keep.

## Everything else is deliberately permission-free — keep it that way

- **Android camera and photos need NO runtime grant.** We use the system Photo Picker
  (`PickMultipleVisualMedia`), SAF (`OpenMultipleDocuments`), and `TakePicture`, which delegates to the
  system camera app. **Do not add `CAMERA` to the manifest** — it would *create* a grant we don't need and
  hand the user a dialog we currently avoid entirely.
- **iOS camera and photos** use the OS prompt whose `NS*UsageDescription` string in `Info.plist` *is* the
  explain-why step. Keep those strings specific and honest.
