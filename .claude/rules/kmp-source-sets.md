---
paths:
  - "code/shared/src/androidMain/**"
  - "code/shared/src/iosMain/**"
  - "code/androidApp/**"
---

# You are editing a platform-specific source set

Rules for this code live in the **owning layer's** briefing in `commonMain`, not in a source-set file —
nesting alone would not have loaded it, which is why this rule exists.

| Editing                                              | Read                                                                          |
| ---------------------------------------------------- | ----------------------------------------------------------------------------- |
| `platform/*.android.kt`, `platform/*.ios.kt`         | `code/shared/src/commonMain/kotlin/app/splitevenly/platform/AGENTS.md`   |
| `data/db/DatabaseBuilder.*`, `data/remote/**`        | `code/shared/src/commonMain/kotlin/app/splitevenly/data/AGENTS.md`       |
| `ui/theme/SystemBars.*`                              | `code/shared/src/commonMain/kotlin/app/splitevenly/ui/AGENTS.md`         |
| `di/PlatformModule.*`, `di/KoinAndroid.kt`           | the layer whose dependency you are binding                                    |
| Anything under `code/androidApp/`                    | root `AGENTS.md` §5, plus `code/FIREBASE_SETUP.md` for Firebase wiring        |

Two rules that bite most often here:

- **Both `actual`s must exist and compile.** Adding an `expect` with only one `actual` breaks the Native
  build, and the Android build will not catch it. Run
  `./gradlew :shared:compileKotlinIosSimulatorArm64` from `code/`.
- **A runtime permission is EARNED, never sprung.** Never call a `request()` from `onCreate`,
  `didFinishLaunching`, or a first composition. See `platform/AGENTS.md` for the full rule — getting this
  wrong permanently burns the capability on that install.
