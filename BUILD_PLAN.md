# ShareCost MVP Build Plan

> One row = one test→implement→audit cycle.
> Status: ✅ done | 🔄 in progress | ⬜ not started
>
> MVP scope: Auth (Google + Apple), groups, EVEN + BY_EXACT splits, itemized
> restaurant bill, bilateral balances, individual-expense settlement + payment-app
> deep link. Everything else is v1.1+.

---

## Layer 1 — core/
Pure Kotlin. No dependencies. Easiest to test.

| # | Status | What | Spec | Key files |
|---|---|---|---|---|
| C-1 | ✅ | Money + ID types + UuidV7 | 00 §6, 03 §1.1, 06 §5.8 | `core/Money.kt`, `core/id/Ids.kt`, `core/UuidV7.kt` |
| C-2 | ✅ | Split allocator (largest-remainder, D-28) | 03 §1.2 | `core/Allocator.kt` |

---

## Layer 2 — domain/
Pure Kotlin. Calls allocate(). No Room, no Supabase.
This is where the business rules live. All testable without any platform.

| # | Status | What | Spec | Key files |
|---|---|---|---|---|
| D-1 | ✅ | Itemized split (tax + tip modes) | 03 §1.4, AC-M2-016/017/018 | `domain/expense/ItemizedAllocator.kt` |
| D-2 | ✅ | Bilateral balances (never simplified) | 03 §2.2, AC-M2-040/041/042 | `domain/balance/BilateralBalance.kt` |
| D-3 | ✅ | Admin transfer rule | 03 §7.5, AC-M1-015/016 | `domain/group/AdminTransfer.kt` |
| D-4 | ✅ | Apply settlement (same-currency) | 03 §4.1/4.3.1, AC-M3-003/004/005 | `domain/settlement/Settlement.kt` |
| D-5 | ✅ | Payment-app deep link builder | 03 §5.1/5.2, AC-M3-030/031 | `domain/settlement/DeepLinkBuilder.kt` |

---

## Layer 3 — data/ (Room entities + DAOs)
First Android-specific layer. This is where you learn Room KMP.
Each cycle = one entity group + its DAO + an in-memory Room test.

| # | Status | What | Spec | Key files |
|---|---|---|---|---|
| R-1 | ✅ | User entity + DAO (+ Room/KSP wiring, DB scaffold) | 02 §3.2, AC-M1-020/021 | `data/db/entity/UserEntity.kt`, `dao/UserDao.kt`, `ShareCostDatabase.kt` |
| R-2 | ✅ | Group + Member entities + DAOs | 02 §3.4/3.5 | `data/db/entity/GroupEntity.kt`, `MemberEntity.kt` |
| R-3 | ✅ | Expense + Share entities + DAOs (+ status recompute) | 02 §3.7/3.8, §6/§7.5 | `data/db/entity/ExpenseEntity.kt`, `ShareEntity.kt`, `ExpenseStatus.kt` |
| R-4 | ✅ | Settlement + Allocation entities + DAOs | 02 §3.9 | `data/db/entity/SettlementEntity.kt`, `SettlementAllocationEntity.kt` |
| R-5 | ✅ | FX rate entity + DAO (baked snapshot) | 02 §3.14, §7, 03 §6.1/6.2 | `data/db/entity/FxRateEntity.kt`, `FxBakedEntity.kt` |

> **Deferred to later cycles (not blocking MVP data layer):** `categories`/`subcategories` (§3.6),
> `payment_app_handles` (§3.3, needed before U-7 settle), `receipts`/`comments`/`history_events`,
> and the Room-only sync tables `pending_mutations` + `sync_state` (§7) which land with S-1.
> DB tests run on the iOS simulator (Android *unit* tests have no `Context` for Room).

---

## Layer 4 — data/ (Repository implementations)
Connects domain interfaces → Room DAOs + Supabase.
Each cycle = one repository. These bring in Supabase for the first time.

| # | Status | What | Spec | Key files |
|---|---|---|---|---|
| P-1 | ✅ | GroupRepository impl | 06 §3.1, 04 §2 | `data/repository/GroupRepositoryImpl.kt` |
| P-2 | ✅ | ExpenseRepository impl | 06 §3.1, 04 §2 | `data/repository/ExpenseRepositoryImpl.kt` |
| P-3 | ✅ | SettlementRepository impl | 06 §3.1 | `data/repository/SettlementRepositoryImpl.kt` |
| P-4 | ✅ | FxRepository impl (Frankfurter fetch + Room cache) | 03 §6.1/6.2, 04 §5 | `data/repository/FxRepositoryImpl.kt` |

> **Scope of this layer as built (local-first; Supabase push deferred to S-1).** Repository
> *interfaces* live in `domain/repository/` (read = `Flow` off Room, write = `suspend → AppResult`,
> per 06 §3.1); impls in `data/repository/` are **local-first**: reads stream from Room, writes land
> in Room immediately (04 §6.2 steps 1–4) with a UUIDv7 id + stamped timestamps. The architecture
> (04 §6.1) puts Supabase RPC calls in the **sync worker**, not the repos — so enqueue + RPC + remote
> reconcile (steps 5–7) land with **S-1** alongside `pending_mutations`/`sync_state`. The one real
> network call in this layer is **FxRepository → Frankfurter** (04 §5; public, no auth), injected
> behind `FxRateFetcher` so it is testable without the wire. All four repos are verified by
> `iosSimulatorArm64Test` (28 new tests, in-memory Room + fakes). `join_group_by_token` resolves the
> local cache only for now (server join is S-1).

---

## Layer 5 — platform/ (expect/actual)
The Android/iOS boundary. Each cycle = one `expect` declaration + both actuals (Android + iOS).

| # | Status | What | Android API | Spec | Key files |
|---|---|---|---|---|---|
| E-1 | ✅ | SecureStorage | AndroidKeyStore AES/GCM + SharedPreferences (no security-crypto) | 06 §5.2 | `platform/SecureStorage.kt` (+ `.android`/`.ios`) |
| E-2 | ✅ | ConnectivityObserver | ConnectivityManager.NetworkCallback → Flow | 06 §5.7 | `platform/ConnectivityObserver.kt` |
| E-3 | ✅ | UrlOpener | Intent(ACTION_VIEW), ActivityNotFoundException | 06 §5.6 | `platform/UrlOpener.kt` |
| E-4 | ✅ | PushService | Firebase Messaging, POST_NOTIFICATIONS | 06 §5.4 | `platform/PushService.kt` (+ `PushBus`) |
| E-5 | ✅ | FilePicker | ActivityResultContracts.OpenDocument | 06 §5.1 | `platform/FilePicker.kt` |

> **Scope as built.** All five `expect` surfaces live in `commonMain/.../platform/` with **both** actuals.
> **E-1** rolls the deprecated-`EncryptedSharedPreferences` wrapper by hand: an `AndroidKeyStore` AES-256-GCM
> key encrypts to a private `SharedPreferences` (Android); a `kSecClassGenericPassword` Keychain item,
> `AfterFirstUnlockThisDeviceOnly` (iOS). iOS Keychain failures **degrade, not crash** (token read failure ⇒
> re-auth). **E-2** seeds current status then emits on change (`VALIDATED` capability / `NWPathMonitor`
> `satisfied`). **E-4** is FCM both sides: Android reads the token off the SDK; iOS reads a token bridged
> from the Swift `MessagingDelegate` ([IosPushTokenHolder]); both re-expose a shared `PushBus` that the
> host notification entry points feed. **E-5** uses the foreground-Activity tracker (`installActivityTracking`,
> wired in `ShareCostApplication`) on Android and `UIDocumentPickerViewController` on iOS. DI: all five are
> bound in `platformModule()` per platform. Manifest gains `INTERNET` / `ACCESS_NETWORK_STATE` /
> `POST_NOTIFICATIONS`. **Verified:** both targets compile (`compileAndroidMain`, iOS sim) + `:androidApp:assembleDebug`;
> the iOS Keychain actual has a 7-test round-trip suite (`iosSimulatorArm64Test`, 115 total green) gated on a
> functional probe since a bare simulator test binary has no Keychain entitlement.
>
> **Deferred (host wiring, lands with the screens/push feature that needs it):** the Android
> `FirebaseMessagingService` + iOS `AppDelegate` plumbing that *feeds* `PushBus`; `Info.plist`
> `LSApplicationQueriesSchemes` for `venmo://`/`cashapp://` (U-7); the richer `PHPickerViewController` image
> path (the document picker already reaches Photos). **Not in this layer:** `WebAuthSession` (§5.5),
> `ImageProcessor` (§5.9), `Analytics`/`CrashReporter` (§5.10), `BackgroundScheduler` (§5.12) — they arrive
> with the auth, receipt, and sync cycles that use them.

---

## Layer 6 — ui/ (ViewModels + Screens)
One ViewModel + Screen pair per feature. Compose Multiplatform.
This is where you learn Compose theming, MVI state, and navigation.

| # | Status | What | Spec | Learn |
|---|---|---|---|---|
| U-0 | ⬜ | ShareCostTheme + design tokens | 06 §4.1/4.2 | MaterialTheme, CompositionLocal, dark mode |
| U-1 | ⬜ | Auth screen (Google + Apple sign-in) | 05 §1, AC-M1-001/002 | OAuth flow, Supabase Auth |
| U-2 | ⬜ | Home screen (group list) | 05 §2 | LazyColumn, StateFlow→collectAsState |
| U-3 | ⬜ | Group home (expense list, date headers) | 05 §3, AC-M2-060/061 | Sticky headers, tab navigation |
| U-4 | ⬜ | Add expense form (EVEN + BY_EXACT) | 05 §5, AC-M2-001/005 | Form state, validation |
| U-5 | ⬜ | Itemized split screen | 05 §5.2, AC-M2-016/017 | Complex form state |
| U-6 | ⬜ | Balances tab | 05 §6, AC-M2-040 | Derived state from Flow |
| U-7 | ⬜ | Settle sheet + deep link flow | 05 §8, AC-M3-001/030/031 | UrlOpener expect/actual |

---

## Sync (offline-first) — wires through all layers
Do this AFTER the repository layer is solid. One cycle, but it's big.

| # | Status | What | Spec | Learn |
|---|---|---|---|---|
| S-1 | ⬜ | Mutation queue + sync engine | 04 §6, D-15 | WorkManager, LWW conflict resolution |

---

## MVP done when:
- [ ] User can sign in with Google
- [ ] User can create/join a group
- [ ] User can add an expense (even split or itemized)
- [ ] Balances tab shows correct bilateral pairs
- [ ] User can tap "Settle" → opens Venmo/CashApp pre-filled
- [ ] Offline entry syncs on reconnect
- [ ] Passes all AC-M1, AC-M2, AC-M3 tests marked above

---

## What each layer teaches you

| Layer | The Android skill |
|---|---|
| core + domain | Pure Kotlin, value classes, sealed interfaces, Result types |
| data/Room | Room KMP, DAO patterns, Flow from DB, migrations |
| data/Supabase | REST + Realtime, RPCs, auth tokens, offline queue |
| platform/expect-actual | The Android/iOS boundary — the most KMP-specific skill |
| ui/Compose | MaterialTheme, MVI state, Navigation, CompositionLocal |
| sync | WorkManager, LWW, offline-first architecture |
