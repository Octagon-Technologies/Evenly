# CLAUDE.md — how to work in the ShareCost repo

This file is auto-loaded every session. It's the canonical, version-controlled record of how to build,
verify, and commit here. Keep it current: when a convention changes, edit this file in the same commit.

---

## Commit rules (READ FIRST)

**Commit per feature, and only when the build is green.** The point: lock in the verified ~80% so a
failure in the last 10% never threatens work that already passed.

1. **Verify before every commit.** Run the build + tests for *both* platforms (see "Verify" below) and
   confirm green. Never commit on a red build. If you can't verify, say so — don't commit blind.
2. **One coherent feature per commit.** Finish a feature (e.g. "notification prefs"), verify, commit,
   then start the next. Don't batch unrelated features into one commit. Use a selective `git add <paths>`
   if unrelated new files are already in the tree.
3. **Branch, don't commit to `main`.** Work happens on a feature branch (e.g. `feat/...`). If you're on
   `main`, branch first.
4. **Conventional messages** with a scope: `feat(notifications): …`, `fix(sync): …`, `docs(ios): …`,
   `refactor(...)`. Body: *what changed + why*, and a "Green on Android + iOS; tests pass" line when true.
5. **Every commit ends with the Claude `Co-Authored-By:` trailer** (the harness gives the exact line for
   the session's model).
6. **Never commit secrets.** `local.properties` is gitignored. `code/androidApp/google-services.json` is
   intentionally tracked (client config). `SupabaseConfig.kt` ships the *anon* key (public, RLS-gated —
   safe). Do NOT commit service-account JSON, APNs `.p8`/`.p12`, or `GoogleService-Info.plist` with private
   data. Sanity-check `git diff --cached --name-only` before committing.
7. **Don't silently defer.** If something can't be finished, name what's blocked and why (missing
   credential, needs a paid account, out of client scope). Don't dress up caution as a hard constraint.
8. **Keep this file in sync — enforced.** When a commit changes a build/test/commit/architecture
   convention, update `CLAUDE.md` *in that same commit*. A project `PreToolUse` hook
   (`.claude/settings.json`) reminds you at `git commit` time whenever `CLAUDE.md` isn't part of the
   staged change; it's a non-blocking nudge, so ignore it when nothing convention-level changed.

---

## Build & verify

`gradlew` lives in **`code/`**, not the repo root. From `code/`:

| Goal | Command |
|---|---|
| Compile shared (Android target: commonMain + androidMain + Room KSP) | `./gradlew :shared:compileAndroidMain` |
| Compile shared (iOS / Kotlin-Native — **catches Native-only breakage**) | `./gradlew :shared:compileKotlinIosSimulatorArm64` |
| Build the Android app (APK, manifest merge, Firebase) | `./gradlew :androidApp:assembleDebug` |
| JVM unit tests (commonTest + androidHostTest) | `./gradlew :shared:testAndroidHostTest` |
| Native unit tests (commonTest + iosTest — MoneyLoop, ConflictFlow, etc.) | `./gradlew :shared:iosSimulatorArm64Test` |

**Always compile iOS too** before declaring green — several APIs exist on Android Room/JVM but not on
Kotlin/Native (e.g. `RoomDatabase.clearAllTables()` resolves on Android, fails on Native). Android-green
≠ iOS-green.

JDK 17. Toolchain anchor: **Kotlin 2.3.21** (pinned by supabase-kt 3.6.0 / Ktor 3.4.3). minSdk 24,
compile/target 36, iOS 16, Compose MP 1.11.0, Room 2.8.4.

---

## Project shape

- **Modules:** `:shared` (KMP — shared logic *and* Compose UI) + `:androidApp` (thin Android host) +
  `iosApp/` (Xcode host). The modern `com.android.kotlin.multiplatform.library` plugin → `:shared` has
  **no AndroidManifest** (Android manifest/Firebase live in `:androidApp`).
- **Package:** `da.chelimo.sharecost` (not `com.sharecost`). iOS framework `baseName = "Shared"`.
- **Layers** under `commonMain/.../`: `core / data / domain / ui / platform / di`; actuals in
  `androidMain` / `iosMain`.
- **Docs map:** `App_Overview.md` (product), `code/BUILD_PLAN.md` (initial build), `FINISH_PLAN.md`
  (the F1–F8 finish plan + status), `supabase/SETUP.md`, `code/iosApp/PUSH_SETUP.md`,
  `code/FIREBASE_SETUP.md`. The Claude-Design export in `design/` is the **visual source of truth**
  (blue/light; intentionally overrides the spec's dark/green — don't "fix" the UI back toward the spec).

---

## Architecture conventions that bite if ignored

- **Local-first.** Reads stream from Room; writes land in Room first; the `SyncEngine`/`SyncManager`
  carry them to Supabase. UI is wired via **Route wrappers** in `ui/navigation/` (screens stay DI-free,
  taking plain callbacks so `@Preview` works; the wrapper `koinInject`s repos and binds callbacks).
- **Synced Room entities double as wire DTOs:** snake_case `@ColumnInfo` names mirror the Postgres
  columns 1:1, the entity is `@Serializable`, and the client uses a snake_case `JsonNamingStrategy`. **No
  Room foreign keys** (rows sync in dependency-arbitrary order).
- **Adding a column to a synced entity REQUIRES adding it server-side first** (additive `alter table …
  add column if not exists … default …`). The full-row `upsert` sends every field, so a column missing
  on the server breaks ALL sync for that table. Apply the migration before/with the entity change.
- **Room schema bump = destructive migration** (pre-release: `fallbackToDestructiveMigration`). Bumping
  the version drops + recreates local tables; the server rehydrates. Fine for now; revisit before GA.
  Schemas are exported to `code/shared/schemas/` (commit them).
- **Optional-ctor-dep pattern for testability:** repos take new collaborators as nullable ctor params
  defaulted to `null` (e.g. `fxRepository`, `historyEventDao`, `remoteGroups`, `syncManager`). Production
  DI passes real instances; unit tests pass nothing → legacy behaviour, no test churn.
- **Supabase plugins** bind only when `SupabaseConfig.isConfigured` (real creds): Auth + Postgrest +
  Storage + Realtime. Otherwise `StubAuthSession` keeps the app fully offline-usable.

## Supabase

- Project ref **`wfpfgbipjmkysalfmyub`** ("ShareCost"). `supabase/schema.sql` is the canonical schema;
  apply additive/idempotent migrations (dashboard SQL editor or the Supabase MCP `apply_migration`).
- RLS is currently **permissive** (`to authenticated using (true)`) for testing — tighten to
  membership-scoped before real multi-user (sketch is in `schema.sql`).
- Receipts live in a public Storage bucket `receipts`. Push targets `device_tokens`; the `push-notify`
  edge function is deployed but inert until the `FCM_SERVICE_ACCOUNT` secret is set.

## iOS / Kotlin-Native gotchas

- A type-safe nav `Route` arg that's an **enum crashes the NavHost on Native** — use `String` (the tab
  arg does). `kotlinx-datetime` `Clock` moved to `kotlin.time.Clock`. Use **`KoinPlatform`**, not
  `GlobalContext`, from iOS host code (Native has no `GlobalContext`). Coil 3 needs the Ktor network
  fetcher registered explicitly (done in `App.kt`) — no ServiceLoader on Native.
