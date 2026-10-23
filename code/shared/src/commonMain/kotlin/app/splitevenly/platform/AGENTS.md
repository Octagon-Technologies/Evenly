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

`PlatformShare` shares **text or a file**. `shareFile` writes to the platform cache and shares that
(Android `cacheDir`, which is exactly what the manifest's FileProvider `<cache-path>` exposes — anywhere
else throws `IllegalArgumentException` at share time; iOS `NSTemporaryDirectory`, never Documents, which
is user-visible in Files and iCloud-backed). Used by the group CSV export: sharing a ledger as text
technically works and no spreadsheet app can open it.

`SecureStorage` is the device-local key/value store and is already registered as a Koin single — use it
rather than introducing a second local KV mechanism.

## ⚠️ Image traps that compile clean and corrupt data

**`BitmapFactory` drops EXIF orientation.** It returns raw sensor pixels, so a portrait camera photo
decodes sideways, and re-encoding to JPEG discards the tag that would have corrected it downstream. Read
the orientation with `ExifInterface` and bake the rotation in before scaling, as `ImageProcessor.android`
does. The two below are the Kotlin/Native half of the same lesson.

**Never read a dimension back out of a `CValue` via `useContents` when a local of the same name is in
scope.** Inside `size.useContents { width to height }`, `width`/`height` bind to the **enclosing
function's locals** before the `CGSize` receiver's members. `ImageProcessor.ios` did exactly that, got the
*source* dimensions back, and handed `drawInRect` a rect 2.5x too large — every receipt photo was silently
stored as its own top-left corner. It type-checks, it runs, and only the pixels are wrong. Keep computed
dimensions as plain `Double`s and pass those; don't round-trip them through a struct.

**`UIGraphicsImageRenderer`'s default format inherits the screen's contents scale** (3x on device, 1x in a
Kotlin/Native test binary). A "1600px" target silently produced a 4800px upload on device *and* passed the
test. Pass an explicit `UIGraphicsImageRendererFormat` with `scale = 1.0` so output pixels equal the size
you asked for on both.

Both classes of bug are invisible to a compile and to the eye at thumbnail size, so **pin image output with
a fixture that fails loudly** — `ImageProcessorTest` uses four solid quadrants: a corner crop loses three
colours, and a scale mistake shows up in the decoded pixel dimensions.

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
- **iOS camera is the one exception, and it is gated.** `UIImagePickerController` presented without a check
  shows a **grey, frozen viewfinder** to anyone who refused once: no prompt, no way back. So every camera
  entry point goes through `CameraPermission.status()` first (`ui/navigation/CameraPermissionGate.kt`), which
  routes `NotDetermined` to a primer and `Denied` to a Settings recovery. Android's actual reports
  `NotApplicable` and falls straight through, which is why this is not an `expect`/`actual` UI split.
  `SystemAlertPreview` draws the dialog the primer is about to raise, and its body text **mirrors
  `NSCameraUsageDescription`** in `iosApp/iosApp/Info.plist`. Change both or neither.
