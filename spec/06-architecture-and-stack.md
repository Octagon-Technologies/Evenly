# 06 — Architecture & Stack

> KMP/CMP code layout, dependencies, expect/actual surfaces, build configuration, and clean-architecture boundaries. Provides the structural map that implementing agents fill in.
>
> **This revision reconciles the spec with the actual Android-Studio-generated KMP project** that already lives in `code/`. The generated project uses a `shared` library module + a thin `androidApp` host + an `iosApp` host (the modern KMP template, AGP `com.android.kotlin.multiplatform.library` plugin), package `da.chelimo.sharecost`. The major-directory layout below matches that reality; the internal `core/data/domain/ui/di` layout under the shared module is unchanged from the previous revision.

---

## 0. What already exists (generated baseline)

The `code/` directory is a working Android Studio **Kotlin Multiplatform** project (shared Compose UI). Do **not** re-scaffold it — extend it. Baseline facts the implementer must preserve:

- **Modules:** `:shared` (KMP library, holds shared logic **and** the Compose UI) and `:androidApp` (Android application host). The iOS host is the `iosApp/` Xcode project. `settings.gradle.kts` already declares `include(":androidApp")` and `include(":shared")` and enables `TYPESAFE_PROJECT_ACCESSORS` (use `implementation(projects.shared)`).
- **Package / namespace:** `da.chelimo.sharecost` (app), `da.chelimo.sharecost.shared` (shared library namespace). The iOS framework `baseName = "Shared"` (Swift `import Shared`, entry point `MainViewControllerKt.MainViewController()`).
- **Shared module Android target** uses the new `androidLibrary { … }` DSL from `com.android.kotlin.multiplatform.library` — there is **no `AndroidManifest.xml` in `shared`**. Android app manifest, permissions, deep-link filters, and `google-services.json` live in `:androidApp`.
- **iOS targets:** `iosArm64` + `iosSimulatorArm64`, static framework. (`iosX64` not built.)
- **Compose UI is shared:** `App()` is in `shared/commonMain`; both hosts render it (`MainActivity.setContent { App() }`, `ContentView` → `MainViewController()`).

> Rationale for the major-directory change vs. the prior `composeApp/` proposal: the generated template already separates the **shared library** from the **two thin application hosts** (`androidApp`, `iosApp`). That is cleaner than folding the Android application config into the UI module — `shared` is a pure library, and the two hosts are symmetric shells. We keep the generated names (`shared`, `androidApp`, `iosApp`) rather than renaming to `composeApp`.

## 1. Repository layout

This is a **docs + code monorepo**: the prose spec (`spec/`) and the Gradle project (`code/`) are siblings, with server-side artifacts (`supabase/`) and scripts (`tools/`) also at the repo root.

```
ShareCost/                              ← git repo root
├── App_Overview.md
├── spec/                               ← this directory (00–09)
├── code/                               ← the Gradle project root (open THIS in Android Studio)
│   ├── settings.gradle.kts             ← include(":shared"), include(":androidApp")
│   ├── build.gradle.kts                ← root: plugins declared `apply false`
│   ├── gradle.properties
│   ├── gradle/
│   │   ├── libs.versions.toml          ← THE single source of truth for versions (see §2, §6)
│   │   └── wrapper/                     ← gradle-wrapper.properties pins Gradle exactly
│   ├── shared/                         ← KMP library: shared business logic + Compose UI
│   │   ├── build.gradle.kts            ← kotlinMultiplatform + androidMultiplatformLibrary + compose
│   │   └── src/
│   │       ├── commonMain/
│   │       │   ├── kotlin/da/chelimo/sharecost/
│   │       │   │   ├── core/           ← Money, ID value classes, AppError, AppResult, UuidV7, Logger, Clock
│   │       │   │   ├── data/           ← Room schema/DAOs, Supabase wrappers, sync engine, repo impls
│   │       │   │   │   ├── db/
│   │       │   │   │   ├── remote/     ← supabase-kt client wrappers + @Serializable DTOs
│   │       │   │   │   ├── sync/       ← mutation queue + pull workers
│   │       │   │   │   └── repository/ ← repository implementations
│   │       │   │   ├── domain/         ← pure Kotlin: use cases, repository interfaces, split/balance math
│   │       │   │   │   ├── model/
│   │       │   │   │   ├── repository/ ← interfaces only (no Room, no Supabase)
│   │       │   │   │   └── usecase/
│   │       │   │   │       ├── expense/  settlement/  fx/  retro/  identity/
│   │       │   │   ├── ui/             ← Compose screens + components
│   │       │   │   │   ├── theme/      ← Color.kt, Theme.kt, Type.kt, Shape.kt, ExtendedColors.kt (§4)
│   │       │   │   │   ├── components/ ← shared composables (MoneyText, ExpenseRow, …)
│   │       │   │   │   ├── screen/     ← auth/ home/ group/ expense/ settle/ reconcile/ settings/
│   │       │   │   │   ├── navigation/ ← NavHost, Route sealed hierarchy, deep-link parsing
│   │       │   │   │   └── viewmodel/  ← per-screen state holders (MVI-lite)
│   │       │   │   ├── platform/       ← expect declarations (SecureStorage, PushService, …)
│   │       │   │   └── di/             ← Koin modules
│   │       │   └── composeResources/   ← strings (xml/json), drawables, fonts
│   │       ├── androidMain/kotlin/da/chelimo/sharecost/platform/  ← Android actuals
│   │       ├── iosMain/kotlin/da/chelimo/sharecost/platform/      ← iOS actuals
│   │       ├── commonTest/             ← unit + pure-logic tests
│   │       ├── androidHostTest/        ← JVM-hosted Android unit tests
│   │       └── iosTest/                ← iOS unit tests
│   ├── androidApp/                     ← Android application host (thin)
│   │   ├── build.gradle.kts            ← com.android.application (+ google-services, crashlytics plugins)
│   │   └── src/main/
│   │       ├── AndroidManifest.xml     ← permissions, deep-link intent-filters, WorkManager, FCM service
│   │       ├── kotlin/da/chelimo/sharecost/MainActivity.kt
│   │       ├── google-services.json    ← Firebase config (gitignored / per-env)
│   │       └── res/
│   └── iosApp/                         ← iOS application host (Xcode project)
│       ├── iosApp/
│       │   ├── iOSApp.swift  ContentView.swift
│       │   ├── Info.plist              ← deep links, BGTaskSchedulerPermittedIdentifiers, APNs
│       │   └── GoogleService-Info.plist
│       └── Configuration/Config.xcconfig
├── supabase/                           ← server-side (deployed via Supabase CLI from CI)
│   ├── migrations/                     ← SQL migrations in numeric order
│   ├── functions/                      ← Deno Edge Functions
│   │   ├── dispatch_push/  refresh_fx_rates/  notify_admin_of_conflicts/  export_group/
│   └── seed/                           ← currency table seed, default category seed
└── tools/                              ← scripts (fx_baked regeneration, setup.sh, CI helpers)
```

Notes:

- **Platform actuals live in `shared/src/{androidMain,iosMain}`**, not in the app hosts. The app hosts hold only application-shell concerns: manifest/Info.plist, signing, entitlements, deep-link registration, Firebase config files, and the OS entry point.
- The `iosApp/` Xcode project links the `Shared` framework produced by the `:shared` iOS targets. All UI is Compose in `shared/commonMain`.
- Server-side artifacts in `supabase/` are versioned alongside the client so a schema change and the client code that uses it land in the same PR.
- `composeResources/` is the generated-`Res` resource root (strings, drawables, fonts). It replaces Android `res/values/strings.xml` for shared UI.

## 2. Dependencies

> **All versions are declared in `code/gradle/libs.versions.toml` and nowhere else.** The versions below are the verified, mutually-compatible set as of the baseline (see §6 for the full pinned catalog and the compatibility policy). **No `+` / open-ended ranges** anywhere.

### 2.1 Core libraries (KMP, in `:shared`)

| Library | Artifact | Version | Notes |
|---|---|---|---|
| Room (KMP) | `androidx.room:room-runtime`, `room-compiler` (KSP) | 2.8.4 | Local DB; iOS via the bundled SQLite driver. Needs KSP2. |
| SQLite bundled driver | `androidx.sqlite:sqlite-bundled` | 2.6.2 | Co-released with Room 2.8.4; provides the iOS/native SQLite. |
| supabase-kt | `io.github.jan-tennert.supabase:bom` → `auth-kt`, `postgrest-kt`, `realtime-kt`, `storage-kt`, `compose-auth` | BOM 3.6.0 | Module names in 3.x: `auth-kt` (was `gotrue-kt`). `compose-auth` provides native Google/Apple sign-in (§5.5). |
| Ktor client | `ktor-client-core` + `ktor-client-okhttp` (android), `ktor-client-darwin` (ios) + `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json` | 3.4.3 | **Pinned to exactly what supabase-kt 3.6.0 was built against** — this is the single most constraining third-party pin. |
| kotlinx-coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | Matches supabase-kt's tested set. |
| kotlinx-serialization | `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | JSON for DTOs/RPCs. Plugin version = Kotlin version (§6). |
| kotlinx-datetime | `org.jetbrains.kotlinx:kotlinx-datetime` | 0.7.1 | `Instant`, `LocalDate`, time zones. Matches supabase-kt's pin. |
| Koin | `io.insert-koin:koin-core`, `koin-compose`, `koin-compose-viewmodel`, `koin-android` | 4.2.1 | DI. |
| Navigation (CMP) | `org.jetbrains.androidx.navigation:navigation-compose` | 2.9.2 | **Compose Navigation Multiplatform — replaces Voyager.** Type-safe `@Serializable` routes. Version is pinned by Compose MP 1.11.0 (§6). |
| Coil 3 | `io.coil-kt.coil3:coil-compose`, `coil-network-ktor3` | 3.4.0 | Async image loading (KMP); Ktor 3 network layer. |
| Kermit (logging) | `co.touchlab:kermit` (+ `kermit-crashlytics`) | 2.1.0 | KMP logging (§9). |
| DataStore (prefs) | `androidx.datastore:datastore-preferences` | 1.2.1 | KMP (iOS supported since 1.1.0). Non-secret preferences (§2.2, §5.2). |

**UUIDv7 — zero libraries.** IDs are Postgres `uuid` columns populated client-side with **UUIDv7** (`02 §1`). We use **no third-party UUID library** — neither `benasher44:uuid` (archived) nor `kulid`. See the box below, then §5.8.

> **Correction on `kulid`.** `com.github.guepardoapps:kulid` generates **ULID**s (26-char Crockford base32, e.g. `01ARZ3NDEKTSV4RRFFQ69G5FAV`), **not** UUIDv7. A ULID is **not** a valid value for a Postgres `uuid` column (which requires the canonical 36-char hex-and-dash form). The previous spec's claim that "kulid implements v7" was wrong. The right tool is the Kotlin standard library's `kotlin.uuid.Uuid`: on **Kotlin ≥ 2.4** it ships `Uuid.generateV7()` (RFC 9562-compliant, monotonic, CSPRNG-backed); on the pinned **Kotlin 2.3.21** baseline we generate v7 in ~30 lines of `commonMain` over the stable `Uuid.fromLongs(...)` primitive. Either way the dependency count is **zero**, which is exactly the "don't carry two libraries" outcome you wanted — pushed further to none. See §5.8 for the generator and the upgrade note.

### 2.2 Android-only (in `:shared/androidMain` and `:androidApp`)

- **Firebase** via `com.google.firebase:firebase-bom` (pin latest stable — see §6.3): `firebase-messaging` (FCM), `firebase-analytics`, `firebase-crashlytics`. Gradle plugins `com.google.gms.google-services` and `com.google.firebase.crashlytics` are applied in **`:androidApp`** (where `google-services.json` lives); the SDK calls live behind facades in `shared/androidMain` (§5.10). **Advertising-ID off** — configure Analytics with ad-ID collection disabled so the "no ad ID, ever" promise (`07 §9`) holds.
- `androidx.activity:activity-compose` (1.13.0) — host activity for CMP (already in the template).
- **Native Google sign-in:** `androidx.credentials:credentials`, `androidx.credentials:credentials-play-services-auth`, and `com.google.android.libraries.identity.googleid:googleid` — Credential Manager + Google Identity, used by supabase-kt `compose-auth` (§5.5). **This replaces the browser for Google sign-in.** (Pin latest stable — see §6.3.)
- `androidx.work:work-runtime-ktx` — **WorkManager**, the Android side of the `BackgroundScheduler` abstraction (§5.12) for deferrable sync / FX refresh. (Pin latest stable — see §6.3.)
- `androidx.browser:browser` — **Custom Tabs, needed only for the residual web-OAuth flows** (Facebook on all platforms; Apple on Android). See the box below. (Pin latest stable — see §6.3.)
- **DROPPED: `androidx.security:security-crypto`.** Jetpack Security Crypto (`EncryptedSharedPreferences`) was **deprecated** (since `1.1.0-alpha07`, 2025-04; deprecation carried through `1.1.0` stable). Google's guidance is to **use the Android Keystore directly**. Secrets (session/refresh tokens) go through the `SecureStorage` actual (§5.2) backed by Keystore-wrapped encryption — not EncryptedSharedPreferences.

> **Answering "don't you also need SharedPreferences as a dependency?"** No. `android.content.SharedPreferences` is part of the **Android framework** (the platform SDK) — it never needed a Gradle dependency. `EncryptedSharedPreferences` was only a *decorator* that the (now-deprecated) `security-crypto` artifact added on top of the framework interface. Since we're dropping that artifact anyway, the question is moot.

> **Answering "why are we shifting toward DataStore?"** DataStore is the modern replacement for SharedPreferences because it fixes concrete problems: it is **async (coroutines + `Flow`)** so reads/writes never block the main thread → **no ANRs from disk I/O**; writes are **transactional/atomic** (`updateData` is a single read-modify-write); it is **type-safe**; and it **signals errors** (e.g. `CorruptionException`) instead of failing silently. Crucially for us, `androidx.datastore:datastore-preferences` is **multiplatform** (iOS supported since 1.1.0, built over okio + `NSDocumentDirectory`), so the **same preferences code runs on both platforms** — SharedPreferences cannot. **There is no official "encrypted DataStore."** So the pattern is: **small secrets → `SecureStorage` (Keystore/Keychain) behind expect/actual; everything non-secret → DataStore Preferences in `commonMain`.** (If a non-secret store ever needs at-rest encryption, encrypt the values with a Keystore/Keychain-held key before writing — there is no first-party encrypted store to lean on.)

> **Answering "why a browser for auth, and is pushing to a browser tab a good idea?"** The auth methods (`04 §1`, `05 §1`) are Google, Apple, Facebook, and email. Most of these need **no browser**:
> - **Google → native, both platforms.** Android uses **Credential Manager + Google Identity** to get an ID token → Supabase `signInWithIdToken`; iOS uses the Google Sign-In SDK the same way. supabase-kt's `compose-auth` wires this up (`googleNativeLogin(...)`, `rememberSignInWithGoogle()`).
> - **Apple → native on iOS** (`Sign in with Apple` → ID token → `signInWithIdToken`). On **Android** there is no native Apple SDK, so Apple-on-Android **must** use the web OAuth flow.
> - **Facebook → web OAuth (PKCE).** Supabase's `signInWithIdToken` does not reliably parse Facebook ID tokens (GoTrue issue #1522 open), so Facebook uses the authorization-code + PKCE web flow.
> - **Email → use OTP code, not magic-link.** Configure the email template to send a **6-digit code** (`signInWithOtp` → `verifyOtp`), which the user types **in-app — no browser.** (Magic-*link* would bounce through a browser; we avoid it.)
>
> So the browser is needed **only** for Facebook and Apple-on-Android. And it is a **Custom Tab**, not a jump to the standalone browser app: a Custom Tab is an **in-app browser tab** that shares the system browser's cookie jar (existing sessions carry over) and returns to the app via deep link. It is the correct, native-feeling tool when a web OAuth flow is unavoidable. **Decision: keep `androidx.browser` while we ship Facebook and/or Apple-on-Android; if both are ever dropped from scope, the dependency can be removed entirely.** Prefer native flows (Google everywhere, Apple on iOS) and email OTP to minimize browser use.

### 2.3 iOS-only (in `:shared/iosMain` and `iosApp/`)

- **Firebase iOS SDK** via Swift Package Manager from `iosApp/` (FCM, Analytics, Crashlytics) — consent-gated, ad-ID disabled, mirroring Android.
- Native APIs through `expect/actual` (§5): **Keychain** (secure storage), **`UNUserNotificationCenter`** + APNs (push), **`BGTaskScheduler`** (background, §5.12), **`AuthenticationServices`** (`ASAuthorizationController` for native Sign in with Apple; `ASWebAuthenticationSession` for residual web OAuth), **`UIDocumentPickerViewController`** / **`PHPickerViewController`** (file/image picking), **`ImageIO`** / **`PDFKit`** (compression/thumbnailing).

### 2.4 Server-side (`supabase/`)

| Component | Purpose |
|---|---|
| Supabase Postgres 15+ | Database. |
| Supabase Auth (GoTrue) | Identity. |
| Supabase Storage | Receipts, exports. |
| Supabase Realtime | Change subscriptions. |
| Supabase Edge Functions (Deno) | `dispatch_push`, `refresh_fx_rates`, `notify_admin_of_conflicts`, `export_group`. |
| `pg_net` extension | Async HTTP from triggers (push fanout). |
| `pg_cron` extension | Daily scheduled jobs (FX refresh, conflict reminders). |

## 3. Module / layer boundaries (clean architecture)

All layers live in `:shared` as Kotlin packages (not separate Gradle modules) — the boundaries are enforced by package discipline + Koin wiring, not module walls. Promote a layer to its own Gradle module only if build times demand it.

```
┌─────────────────────────────────────────────────────────────────┐
│  ui (Compose screens + viewmodels)                              │
│      │ depends on ▼                                             │
│  domain (use cases + repository interfaces; pure Kotlin)         │
│      │ implemented by ▼ (DI provides impls)                     │
│  data (Room, supabase-kt, Ktor, sync engine, repository impls)  │
│      │ depends on ▼                                             │
│  core (Money, IDs, AppError, AppResult, UuidV7, Logger)         │
└─────────────────────────────────────────────────────────────────┘
```

Rules:

- `domain` has **no** Android, iOS, Room, Supabase, or Ktor imports. Pure Kotlin. Side effects abstracted behind repository interfaces it owns.
- `ui` depends on `domain` (use cases) and emits state via `StateFlow`. It **never** touches `data` directly.
- `data` implements the repository interfaces declared in `domain`. It owns Room, supabase-kt, and the sync engine.
- `core` is the shared bedrock everything may depend on (`Money`, `AppResult`, `AppError`, ID value classes, `Currency`, `UuidV7`, `Logger`).

### 3.1 Repository interfaces (in `domain/repository/`)

`AuthRepository`, `UserRepository`, `GroupRepository`, `ExpenseRepository`, `DraftRepository` (D-24, OQ-13), `SettlementRepository`, `RefundRepository`, `ConflictRepository`, `CommentRepository`, `CategoryRepository`, `ReceiptRepository` (D-22/D-26), `FxRepository`, `NotificationRepository`, `HistoryRepository`, `AnalyticsRepository` (D-23/D-25).

Each has a write surface (suspend funs returning `AppResult<T>` — §9) and a read surface (`Flow<…>` from Room).

### 3.2 Use cases (in `domain/usecase/`)

One use case per business rule in `03-business-rules.md`. Examples: `AddExpenseUseCase`, `EditExpenseUseCase`, `DeleteExpenseUseCase`, `ComputeSplitUseCase` (the pure `allocate` from `03 §1.2`), `ComputeItemizedSharesUseCase` (`03 §1.4`, D-21), `SaveDraftUseCase` / `ConvertDraftToExpenseUseCase` / `DiscardDraftUseCase` (`03 §3.5`, D-24), `ProcessReceiptUseCase` (`03 §15.1`, D-22), `ApplySettlementUseCase`, `VoidSettlementUseCase`, `BuildBilateralBalancesUseCase`, `AddRetroactiveMemberUseCase`, `ResolveConflictUseCase`, `GenerateAutoRefundUseCase`, `ClaimPlaceholdersUseCase`, `RotateInviteTokenUseCase`, `ExportGroupUseCase`.

Use cases are stateless objects with one `operator fun invoke(...)`. Easy to unit-test with fake repositories — no mocking framework needed.

### 3.3 ViewModels / state holders (in `ui/viewmodel/`)

One state holder per screen, subclassing the multiplatform `androidx.lifecycle.ViewModel` (provided by `koin-compose-viewmodel`). MVI-lite: a `StateFlow<UiState>` exposed to the screen; intents handled via function calls. No external state-machine library.

```kotlin
class ExpenseDetailViewModel(
    private val expenseRepo: ExpenseRepository,
    private val applySettlement: ApplySettlementUseCase,
    private val expenseId: ExpenseId,
) : ViewModel() {
    val state: StateFlow<UiState> = /* … */
    fun onSettleClicked(/* … */) { /* … */ }
}
```

## 4. UI: Compose Multiplatform — theming (Material 3)

> Reworked to follow the **recommended Android practice** (per the official "Material Design 3 in Compose" and "Custom design systems in Compose" guides). The previous "bespoke `Tokens` object that replaces Material" approach is **not** how theming is conventionally done. The convention is: **Material 3 `MaterialTheme` is the foundation** (color scheme, typography, shapes), and brand values Material doesn't model are added as a **small `@Immutable` extension exposed through a `CompositionLocal`** — not a parallel token system.

### 4.1 Theme structure

Standard Compose file layout in `ui/theme/`:

- **`Color.kt`** — raw brand `Color(0xFF…)` constants for light and dark.
- **`Type.kt`** — a `Typography` mapping the brand type ramp onto the M3 type scale (`displayLarge … labelSmall`).
- **`Shape.kt`** — a `Shapes` (small/medium/large corner radii).
- **`Theme.kt`** — `lightColorScheme()` / `darkColorScheme()` and the `ShareCostTheme` wrapper.
- **`ExtendedColors.kt`** — the brand-semantic colors Material lacks (§4.3).

```kotlin
private val DarkColors = darkColorScheme(
    primary = BrandGreen,            // "settled" / selection / positive accent
    onPrimary = …, primaryContainer = …, onPrimaryContainer = …,
    error = DangerRed, onError = …,
    background = …, surface = …, surfaceContainer = …, surfaceVariant = …,
    onSurface = …, onSurfaceVariant = …, outline = …, /* … full role set … */
)
private val LightColors = lightColorScheme( /* … */ )

@Composable
fun ShareCostTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),   // default theme is DARK (05 header) — see §4.4
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val extended = if (darkTheme) ExtendedDark else ExtendedLight
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ShareCostTypography,
            shapes = ShareCostShapes,
            content = content,
        )
    }
}
```

Components read theme values the **idiomatic** way: `MaterialTheme.colorScheme.primary`, `MaterialTheme.typography.bodyLarge`, `MaterialTheme.shapes.medium`.

### 4.2 Token → Material-role mapping (the "theming tokens" from `05 §16`)

The informative token names in `05 §16` map onto Material roles + extensions (this is the canonical, complete mapping `05 §16`/`07 §3.1` point to):

| `05 §16` token | Material 3 role / extension |
|---|---|
| `bg.primary` / `bg.secondary` / `bg.elevated` | `background` / `surfaceContainer` / `surfaceContainerHigh` |
| `text.primary` / `text.secondary` / `text.muted` | `onSurface` / `onSurfaceVariant` / `ExtendedColors.textMuted` |
| `text.inverse` | `inverseOnSurface` |
| `accent.primary` (brand green, "settled"/selection — e.g. `#37D39A`, `05 §14`) | `primary` |
| `accent.warning` (amber, "approaching cap") | `ExtendedColors.warning` |
| `accent.danger` (red, errors) | `error` |
| `border.default` / `border.focus` | `outline` / `outlineVariant` (focus also drawn as a 2dp ring) |

All roles are defined for **both** dark and light schemes; **default is dark**. **Dynamic color is intentionally OFF** — this is a brand app where color carries domain meaning (green = settled/positive, red = owed), so wallpaper-derived palettes would break that semantics; dynamic color is also Android-12-only and unavailable on iOS. (If ever enabled, guard `dynamicLightColorScheme/dynamicDarkColorScheme` behind an `expect` returning `null` on iOS.)

### 4.3 Extended (brand-semantic) colors

Material has no role for "money you're owed" vs "money you owe." Add them as an immutable extension behind a `CompositionLocal`, per the official custom-design-systems guide:

```kotlin
@Immutable
data class ExtendedColors(
    val positive: Color,     // you are owed / credit (green family)
    val negative: Color,     // you owe / debit (red-orange family)
    val warning: Color,      // approaching storage cap, soft alerts (amber)
    val textMuted: Color,    // tertiary text below onSurfaceVariant
    val reconcileOutline: Color, // explicit green selection outline (05 §14, 2dp)
)

val LocalExtendedColors = staticCompositionLocalOf<ExtendedColors> { error("No ExtendedColors") }

object ShareCostTheme {                    // usage: ShareCostTheme.colors.positive
    val colors: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
}
```

`07 §3.1` contrast checks run over **every** `ColorScheme` pair **and** every `ExtendedColors` pair, for both themes, failing CI below WCAG AA. Color is never the sole state indicator (reconcile uses `reconcileOutline` **and** a checkmark — `05 §14`).

### 4.4 Component library (`ui/components/`)

Shared, opinionated composables built on the theme: `MoneyText(amountSubunits, currency, style)`, `RemainingMoneyText(remaining, owed, currency)`, `MemberAvatar(user)`, `MemberChip(user, selected, onToggle)`, `CategoryChip(category, subcategory)`, `ExpenseRow(expense, share?)`, `DayHeader(date, totalForDay?)`, `EmptyState(icon, title, body, cta?)`, `SectionCard(title, content)`, `SkeletonRow()`, `SettleSheet(...)`, `ReconcileCard(placeholder, selected, onToggle)`.

### 4.5 Navigation (Compose Navigation Multiplatform)

`org.jetbrains.androidx.navigation:navigation-compose` (2.9.2). **Type-safe routes via `@Serializable`** (no string routes, no Voyager):

```kotlin
sealed interface Route {
    @Serializable data object Home : Route
    @Serializable data class GroupHome(val groupId: String) : Route
    @Serializable data class ExpenseDetail(val groupId: String, val expenseId: String) : Route
    @Serializable data class AddExpense(val groupId: String, val draftId: String? = null) : Route
    @Serializable data class Reconcile(val groupId: String) : Route
    // …
}
```

`NavHost` + `composable<Route.ExpenseDetail> { … }` with `toRoute<Route.ExpenseDetail>()` for typed args. Deep links registered:

- `sharecost://join?t=<token>`
- `sharecost://expense?g=<group_id>&e=<expense_id>`
- `https://sharecost.app/j/<token>` (universal / app link)

Configured on Android in `:androidApp` `AndroidManifest.xml` (`<intent-filter>` + `<nav-deep-link>` via `deepLinks`) and on iOS via `iosApp/Info.plist` + `apple-app-site-association`.

### 4.6 Screenshot export for Trip Overview

CMP renders the Overview offscreen via `androidx.compose.ui.graphics.Picture` (Android) and `UIGraphicsImageRenderer` (iOS) behind an `expect class Screenshot` producing PNG `ByteArray`, handed to the platform share sheet (another expect/actual).

## 5. Platform abstractions (expect/actual)

`expect` declarations live in `shared/commonMain/.../platform/`; actuals in `androidMain`/`iosMain`.

### 5.1 `expect class FilePicker` — photo/PDF/file picker → `(uri, mime, bytes)`.
- Android: `ActivityResultContracts.OpenDocument`. iOS: `UIDocumentPickerViewController` (PDFs), `PHPickerViewController` (images).

### 5.2 `expect class SecureStorage` — read/write small secrets (session/refresh tokens).
- **Android: Android Keystore-backed.** Generate/keep an AES key in the **Android Keystore** (`AndroidKeyStore` provider, `AES/GCM/NoPadding`) and store the ciphertext in a plain `SharedPreferences`/DataStore entry — i.e. roll the small wrapper the deprecated `EncryptedSharedPreferences` used to provide. **Do not** add `androidx.security:security-crypto` (deprecated, §2.2).
- **iOS: Keychain** (`kSecClassGenericPassword`) via a `Foundation` bridge.
- Tokens are **never** stored in plain SharedPreferences/UserDefaults/DataStore (`07 §2.1`).

### 5.3 `expect class Clipboard` — write text. Android `ClipboardManager`; iOS `UIPasteboard.general`.

### 5.4 `expect class PushService` — register token, surface delivery callbacks.
- Android: Firebase Messaging (`token`, `onNewToken`, `onMessageReceived`). iOS: APNs via `UNUserNotificationCenter` + Firebase Messaging iOS SDK. **Decision: Firebase Messaging on both** for v1; iOS forwards `didRegisterForRemoteNotificationsWithDeviceToken` to FCM.

### 5.5 Auth surfaces — native first, web only as fallback.
- **`composeAuth` (supabase-kt)** provides native **Google** (Credential Manager on Android; Google Sign-In SDK on iOS) and native **Apple** (iOS) → `signInWithIdToken`. No browser.
- **`expect class WebAuthSession`** — launches a web OAuth flow **only** for Facebook (all platforms) and Apple-on-Android. Android: **Custom Tab** (`androidx.browser`); iOS: `ASWebAuthenticationSession`. Returns via the `sharecost://` deep-link callback.
- **Email: OTP code** (`signInWithOtp` → `verifyOtp`) entered in-app — no platform surface needed.

### 5.6 `expect class UrlOpener` — open an arbitrary external URL.
- Android: `Intent(ACTION_VIEW)` + `try/catch ActivityNotFoundException`. iOS: `UIApplication.shared.open(...)` with the boolean completion for fallback detection.

### 5.7 `expect class ConnectivityObserver` — `Flow<Online | Offline>`.
- Android: `ConnectivityManager.NetworkCallback`. iOS: `NWPathMonitor`.

### 5.8 UUIDv7 generation — pure `commonMain`, zero dependencies.
- IDs are generated in common code as `kotlin.uuid.Uuid`; `.toString()` yields the canonical 36-char value Postgres `uuid` requires. **No expect/actual needed.**
- On the **Kotlin 2.3.21** baseline, `core/UuidV7.kt` is a small RFC 9562 generator over the stable `Uuid.fromLongs(msb, lsb)` primitive: 48-bit Unix-ms timestamp, `version = 7`, `variant = 0b10`, CSPRNG fill, with an intra-millisecond monotonic counter (`rand_a`). Add property-based tests for **monotonicity within a millisecond** and **uniqueness across milliseconds**.
- **Upgrade note:** once the whole dependency set (notably supabase-kt) officially supports **Kotlin ≥ 2.4**, replace the body with the stdlib `Uuid.generateV7()` (it does all of the above correctly) and delete the hand-rolled bits. Both paths require `@OptIn(ExperimentalUuidApi::class)`.

### 5.9 `expect class ImageProcessor` — compress + thumbnail (D-22, OQ-11).
- One entry: raw picked bytes + mime → `(compressedBytes, compressedMime, thumbBytes, width, height)`.
- Android: `BitmapFactory` (`inSampleSize`) → JPEG q80, long-edge ≤ 2048; thumb via second downscale. PDFs: `PdfRenderer` page 1 → bitmap thumbnail; PDF passed through unmodified.
- iOS: `ImageIO` (`CGImageSource`/`CGImageDestination`) downscale + JPEG re-encode; `PDFKit` (`PDFDocument` page 1 → image) for the PDF thumbnail.

### 5.10 `expect class Analytics` + `expect class CrashReporter` — Firebase facades (D-23, OQ-08).
- `Analytics.logEvent(name, params)`, `Analytics.setCollectionEnabled(Boolean)`; `CrashReporter.recordNonFatal(throwable)`, `.log(msg)`, `.setCollectionEnabled(Boolean)`.
- Android: Firebase Analytics + Crashlytics. iOS: Firebase Analytics + Crashlytics (iOS SDK). The **Kermit `kermit-crashlytics` writer** (§9) routes structured logs into Crashlytics breadcrumbs.
- Both honor the privacy consent toggle (`05 §1.3`, `§10`): consent off → collection disabled, nothing sent. Payloads **never** include names, emails, amounts, expense titles, or handles (`07 §4`). Ad-ID collection disabled.

### 5.11 `expect class Biometrics?` — **NOT in v1**. Listed for awareness.

### 5.12 `expect class BackgroundScheduler` — deferrable background work (iOS WorkManager-equivalent).
> **Answering "what's the iOS equivalent of WorkManager?"** Apple's **BackgroundTasks** framework. Register identifiers with **`BGTaskScheduler`** and declare them in `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`. Two task types: **`BGAppRefreshTask`** (short, ~30 s, periodic refresh) and **`BGProcessingTask`** (longer maintenance, with `requiresNetworkConnectivity` / `requiresExternalPower` so it can wait for charging). The catch: iOS decides **when (and whether)** tasks run based on battery/usage — it is **best-effort**, unlike WorkManager's persistent, guaranteed, constraint-aware execution that survives process death and reboot.
- Define one `expect class BackgroundScheduler` for deferrable jobs (sync flush, FX refresh). **Android actual → WorkManager** (`androidx.work`). **iOS actual → `BGTaskScheduler`** (`BGAppRefreshTask` for light periodic sync, `BGProcessingTask` for heavier/charging-time work).
- **Because iOS background execution is best-effort, anything time-critical goes through push (APNs/FCM, §5.4), not background tasks** — push wakes the app on demand; background tasks only opportunistically catch up.

## 6. Build targets, toolchain & the version-compatibility policy

### 6.1 Pinned toolchain (exact — no `+`)

| | Value | Constraint |
|---|---|---|
| Kotlin | **2.3.21** | Anchor (set by the template; matches supabase-kt 3.6.0's build). |
| Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) | **2.3.21** | **= Kotlin version, exactly.** |
| kotlinx-serialization plugin (`org.jetbrains.kotlin.plugin.serialization`) | **2.3.21** | **= Kotlin version, exactly.** |
| KSP (`com.google.devtools.ksp`) | **2.3.9** | KSP2; minor tracks Kotlin minor (2.3.x). KSP1 is incompatible with Kotlin ≥ 2.3. |
| Compose Multiplatform | **1.11.0** | Pins `navigation-compose` 2.9.2, `lifecycle` 2.11.0-beta01, `material3` 1.11.0-alpha07 — take these from CM's notes, don't hand-pick. |
| AGP (`com.android.application`, `com.android.kotlin.multiplatform.library`) | **9.0.1** | Requires Gradle ≥ 9.1.0 and JDK 17+; requires compileSdk/targetSdk 36. |
| Gradle (wrapper) | **9.1.0** | |
| Java toolchain | **17** | AGP 9.x build JVM minimum. |
| Android | `minSdk = 24`, `compileSdk = 36`, `targetSdk = 36` | minSdk 24 = Android 7.0 (template default; keeps Credential Manager + modern crypto ergonomic). |
| iOS | min **iOS 16**; targets `iosArm64`, `iosSimulatorArm64` | `iosX64` not built. |

### 6.2 The full version catalog (`gradle/libs.versions.toml [versions]`)

```toml
[versions]
kotlin = "2.3.21"
agp = "9.0.1"                          # com.android.* plugins
ksp = "2.3.9"
composeMultiplatform = "1.11.0"
material3 = "1.11.0-alpha07"           # org.jetbrains.compose.material3 (pinned by CM 1.11.0)
androidx-lifecycle = "2.11.0-beta01"   # org.jetbrains.androidx.lifecycle (pinned by CM 1.11.0)
navigationCompose = "2.9.2"            # org.jetbrains.androidx.navigation (pinned by CM 1.11.0)
android-minSdk = "24"
android-compileSdk = "36"
android-targetSdk = "36"

supabase = "3.6.0"                      # BOM
ktor = "3.4.3"                          # EXACT match to supabase 3.6.0
coroutines = "1.10.2"
serializationJson = "1.11.0"
datetime = "0.7.1"
koin = "4.2.1"
coil = "3.4.0"
room = "2.8.4"
sqlite = "2.6.2"
kermit = "2.1.0"
datastore = "1.2.1"
androidx-activity = "1.13.0"

# --- Android-only support libs: pin the EXACT latest stable from Google Maven at
# implementation time (see §6.3); these placeholders are the known-good floor, NOT a range.
firebaseBom = "<pin latest stable>"    # com.google.firebase:firebase-bom
work = "<pin latest stable>"           # androidx.work:work-runtime-ktx
browser = "<pin latest stable>"        # androidx.browser:browser
credentials = "<pin latest stable>"    # androidx.credentials:*
googleid = "<pin latest stable>"       # com.google.android.libraries.identity.googleid:googleid
```

### 6.3 ⚠️ Version & compatibility policy — READ BEFORE ADDING ANY DEPENDENCY

> Incompatible versions are the **single most frequent cause of broken KMP builds.** The implementing session MUST follow this process **every time** it adds or bumps a dependency, and verify a clean `./gradlew build` after each change rather than batching.

1. **Anchor on Kotlin.** Kotlin (**2.3.21**) is the root of the tree; everything compiler-coupled derives from it. **Never let a third-party library silently force a Kotlin change.**
2. **Lockstep-to-Kotlin plugins must equal the Kotlin version string exactly:** `kotlin.plugin.compose` and `kotlin.plugin.serialization` → **2.3.21**.
3. **Derive KSP from Kotlin:** latest KSP whose minor matches the Kotlin minor → **2.3.9** (KSP2 mandatory at Kotlin ≥ 2.3).
4. **Let Compose MP pin its satellites:** `navigation-compose`, `lifecycle`, `material3` come **from CM 1.11.0's release notes** — do not pick them independently.
5. **Move AGP ↔ Gradle ↔ JDK as a block:** AGP 9.0.1 ⇒ Gradle ≥ 9.1.0 ⇒ JDK 17+ ⇒ compileSdk/targetSdk 36.
6. **For every third-party lib, read its *own* `gradle/libs.versions.toml` at the release tag** (not its prose docs) to find its required Kotlin and Ktor. supabase-kt 3.6.0 → Kotlin 2.3.21 (✓ anchor) and **Ktor 3.4.3** (the hard pin that fixes our Ktor version). Room 2.8.4 → needs KSP2 (✓) and `sqlite-bundled` 2.6.2.
7. **Reconcile shared transitive deps (Ktor, coroutines, datetime, serialization)** to the highest version every consumer accepts, biasing to the API-sensitive consumer's tested version: **Ktor 3.4.3 and datetime 0.7.1 match supabase**; serialization 1.11.0 and coroutines 1.10.2 are safe.
8. **Pick the latest *stable* — reject pre-release where a stable exists.** As of the baseline these were newer but **rejected**: Room 3.0 (alpha), `navigation-compose` 2.10 (alpha), Coil 3.5 (beta), `sqlite` 2.7 (alpha), Kotlin 2.4 / Ktor 3.5 (not yet what supabase-kt is built against).
9. **For the §6.2 "pin latest stable" support libs** (firebase-bom, work, browser, credentials, googleid): look up the current latest **stable** on Google Maven, write the **exact** value into the catalog (never a `+`), then re-run the build.

**Known tight couplings (must move together):** Kotlin ↔ KSP · Kotlin ↔ compose-compiler plugin · Kotlin ↔ serialization plugin · Compose MP ↔ {nav, lifecycle, material3} · supabase-kt ↔ Ktor · AGP ↔ Gradle ↔ JDK · Room ↔ {KSP, sqlite-bundled}.

> **Worked example of the discipline (UUIDv7).** Kotlin **2.4** adds a genuinely useful stdlib feature (`Uuid.generateV7()`). It is *tempting* to bump the anchor to get it. **Don't — yet.** Our heaviest dependency, supabase-kt 3.6.0, is built against Kotlin 2.3.21; bumping Kotlin ahead of it risks a broken build for a feature we can replicate in ~30 lines (§5.8). Stay on 2.3.21, ship the tiny generator, and upgrade the anchor only once supabase-kt (and Compose MP) declare Kotlin 2.4 support. **This is exactly the per-dependency compatibility check this policy is about.**

## 7. CI/CD outline (informative)

- **CI on PR:** `./gradlew build test` (JVM `commonTest` + `androidHostTest`), `./gradlew iosSimulatorArm64Test` on a macOS runner, `detekt` + `ktlint`, and the contrast-check + RLS smoke suites (`07 §2.2`, `§3.1`).
- **Deploy `supabase/`:** `supabase db push` on merge to `main` (approval-gated); `supabase functions deploy <name>` for Edge Functions.
- **App release:** Android — GitHub Actions builds the AAB → Play internal testing. iOS — Xcode Cloud or GH Actions + `fastlane match`.
- **Versioning:** semver in `androidApp/build.gradle.kts`; `versionCode` derived from `versionName` (`1.2.3` → 10203).

## 8. Local dev setup (informative)

- Required: Android Studio (latest stable), Xcode 15+, JDK 17, Supabase CLI.
- `tools/setup.sh`: install Supabase CLI → `supabase start` (local Postgres + Auth + Storage) → apply `supabase/migrations/*.sql` → seed `currencies` + `seed_default_categories`.
- `.env.local` template: `SUPABASE_URL`, `SUPABASE_ANON_KEY`. The app reads them from a generated Kotlin object (`BuildConfig`-equivalent produced by a Gradle task) — secrets are not committed.

## 9. Logging & error model (`core`)

### 9.1 Logging — Kermit

`co.touchlab:kermit:2.1.0` (KMP-first, ~1k★, actively maintained by Touchlab, small modular core; chosen over the now-dormant Napier). It already provides per-platform writers (Logcat on Android, `OSLog` on iOS) and a Crashlytics writer.

```kotlin
// core/log/Log.kt — thin app-owned facade over Kermit so call sites never import a vendor type
object Log {
    private val logger = Logger.withTag("ShareCost")          // co.touchlab.kermit.Logger
    fun d(msg: String, t: Throwable? = null) = logger.d(t) { msg }
    fun w(msg: String, t: Throwable? = null) = logger.w(t) { msg }
    fun e(msg: String, t: Throwable? = null) = logger.e(t) { msg }
}
```

- Wire **`kermit-crashlytics`** as a `LogWriter` so warnings/errors become Crashlytics breadcrumbs — **gated by the privacy-consent toggle** (`05 §1.3`, §5.10). Consent off ⇒ no Crashlytics writer attached.
- **Sensitive data (tokens, emails, amounts, names, handles) MUST NOT be logged** (`07 §4`).

### 9.2 Error model — typed errors as first-class values

> Reworked. The previous `typealias AppResult<T> = kotlin.Result<T>` was self-contradictory: it defined a rich `AppError` hierarchy but then threw it inside `kotlin.Result`, which only carries a `Throwable` — so callers lost the ability to exhaustively `when` over error categories, and the "domain returns errors, not exceptions" rule was undermined. We replace it with an explicit result type so **errors are values, handling is exhaustive, and the UI can react per category** (retry vs. re-login vs. highlight a field).

**`AppError` — a sealed hierarchy carrying exactly what the UI needs to render and decide recoverability:**

```kotlin
// core/error/AppError.kt
sealed interface AppError {
    /** Connectivity/transport failure — recoverable by retry. */
    data class Network(val kind: Kind, val cause: Throwable? = null) : AppError {
        enum class Kind { Offline, Timeout, Tls, Unreachable }
    }
    /** Backend returned an error (HTTP 4xx/5xx, PostgREST/Postgres). `code` enables i18n mapping. */
    data class Backend(val status: Int?, val code: String?, val detail: String?) : AppError
    /** Local, pre-flight validation — drives per-field UI highlighting. */
    data class Validation(val fieldErrors: Map<Field, Reason>) : AppError
    /** Optimistic-concurrency / sync conflict (02 §sync). UI offers merge/refresh. */
    data class Conflict(val entity: String, val serverVersion: Long?) : AppError
    /** Auth/session — UI routes to (re-)login. */
    data object SessionExpired : AppError
    data object NotAuthorized : AppError
    /** Truly unexpected — log + generic message; never swallowed. */
    data class Unexpected(val cause: Throwable) : AppError
}
```

**`AppResult<T>` — an explicit, exhaustive result type (no `Throwable` boxing):**

```kotlin
// core/error/AppResult.kt
sealed interface AppResult<out T> {
    data class Ok<out T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(f: (T) -> R): AppResult<R> =
    when (this) { is AppResult.Ok -> AppResult.Ok(f(value)); is AppResult.Err -> this }

inline fun <T, R> AppResult<T>.flatMap(f: (T) -> AppResult<R>): AppResult<R> =
    when (this) { is AppResult.Ok -> f(value); is AppResult.Err -> this }

inline fun <T> AppResult<T>.getOrElse(f: (AppError) -> @UnsafeVariance T): T =
    when (this) { is AppResult.Ok -> value; is AppResult.Err -> f(error) }

inline fun <T, R> AppResult<T>.fold(onOk: (T) -> R, onErr: (AppError) -> R): R =
    when (this) { is AppResult.Ok -> onOk(value); is AppResult.Err -> onErr(error) }
```

**How it flows through the layers — and why that helps:**

1. **`data` is the only place exceptions exist.** Repository impls wrap Ktor/supabase/Room calls and **translate exceptions into `AppError`** at the boundary (`UnknownHostException`/`SocketTimeout` → `Network`; PostgREST error body → `Backend(status, code, detail)`; Room unique-constraint → `Conflict`; 401 → `SessionExpired`). Everything below the UI returns `AppResult<T>` — **no exception crosses a `domain` boundary** (`10`).
2. **`domain` use cases compose with `map`/`flatMap`** and stay pure — a failed step short-circuits without `try/catch` noise.
3. **`ui` exhaustively maps `AppError` → (localized message + action).** Because it's a sealed type, the compiler **forces** the ViewModel to handle every category, and each category dictates behaviour: `Network` → retry affordance; `Validation` → highlight the offending `Field`s inline; `SessionExpired` → navigate to auth; `Conflict` → offer refresh/merge; `Backend.code` → look up a specific `composeResources` string with a generic fallback; `Unexpected` → generic message **and** `CrashReporter.recordNonFatal` (§5.10).

This is strictly better than `kotlin.Result` here: errors are **enumerable** (exhaustive `when`, no stringly-typed guessing), **serializable-friendly**, carry **structured fields** (per-field validation, HTTP status, conflict version), and keep the throw/catch surface confined to one layer. We deliberately avoid pulling in Arrow's `Either` — this hand-rolled pair is ~40 lines, has zero dependencies, and is tailored to the app's error categories.

## 10. Coding conventions

- Strict null-safety; `!!` is banned in `domain` and `core`; allowed sparingly in `ui` only when wrapping platform interop.
- All fallible operations return `AppResult<T>` (§9.2) — **no exceptions across `domain`/`core` boundaries**.
- Public APIs use Kotlin nullability (no `Optional`).
- `data class` for records; **`value class` for ID wrappers** (`UserId`, `GroupId`, `ExpenseId`, …) wrapping the UUIDv7 string (§5.8).
- `@Serializable` on all DTOs in `data/remote/` and on all `Route`s (§4.5).
- Naming: use cases `-UseCase`, repositories `-Repository`, screens `-Screen`, composables are nouns (`ExpenseRow`), ViewModels `-ViewModel`.

---

**Read next:** [`07-non-functional.md`](07-non-functional.md).
