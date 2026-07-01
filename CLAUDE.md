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
- **Mock UI before you build it.** For any new screen or non-trivial UI change, produce a faithful
  visual mockup for the owner to react to *before* writing Compose. The owner iterates on the mock
  (naming, controls, density, flow) and only then do you implement — this has repeatedly surfaced
  simplifications that would have been costly to discover in code. Design decisions get made in the
  mock, not the PR. (For big features, also discuss the data/logic model to agreement first.)

---

## Architecture conventions that bite if ignored

- **Local-first.** Reads stream from Room; writes land in Room first; the `SyncEngine`/`SyncManager`
  carry them to Supabase. UI is wired via **Route wrappers** in `ui/navigation/` (screens stay DI-free,
  taking plain callbacks so `@Preview` works; the wrapper `koinInject`s repos and binds callbacks).
- **Tabs are screen *state*, not routes.** The app-root nav (`ui/navigation/MainShell.kt`, rendered at
  `Route.Home`) and the in-group nav (`GroupHomeScreen`) both keep their tabs as a `remember`ed enum
  inside a single destination — so Back leaves the section instead of cycling tabs, and there's no enum
  nav-arg to crash the Native NavHost. `MainShell` is the root bottom nav (**Groups** + **Settings**;
  Settings *is* `ProfileScreen`); opening a group is a full-screen push *over* the shell. There is no
  standalone `Route.Profile`. Reach the profile via the Settings tab, not a Home avatar.
- **System bars blend via the theme, edge-to-edge, no per-platform color code.** A `StatusBarScrim`
  (in `ScBars.kt`) paints the `page` color behind the status bar and `ScBottomNav(navBarInset = true)`
  paints `page` behind the nav bar, so each chrome owns its own inset (don't wrap whole screens in
  `systemBarsPadding()` when they use the scrim — you'll double-pad). Status-bar *icon* contrast is
  driven from the active theme (light/dark), not the OS setting.
- **Synced Room entities double as wire DTOs:** snake_case `@ColumnInfo` names mirror the Postgres
  columns 1:1, the entity is `@Serializable`, and the client uses a snake_case `JsonNamingStrategy`. **No
  Room foreign keys** (rows sync in dependency-arbitrary order).
- **Expenses sync through an optimistic-concurrency RPC, not a blind upsert.** An expense + its full
  share set are one atomic, versioned unit. `SyncEngine.pushExpenses` routes each *dirty* expense (local
  `row_version` ≠ its device-local `expense_sync_state.synced_version`) through the server
  `commit_expense(p_expense, p_shares, p_base_version, p_actor)` RPC, which compare-and-swaps on
  `row_version`: the first writer to advance `base→base+1` wins; a stale writer's payload is **parked** in
  `expense_edit_conflicts` instead of clobbering. Shares ride *with* the expense (the RPC soft-deletes
  removed ones server-side), so a losing edit's shares never land on the live `shares` table and corrupt a
  split — do **not** reintroduce a blind bulk `expenses`/`shares` push. Soft-deleted expenses are
  tombstones (plain upsert, LWW-safe). Before parking, the RPC **suppresses a no-op**: if a stale-base
  payload is materially identical to canonical (same scalar fields *and* active share split — a stale
  re-push, or two edits that converged), it returns `noop` instead of `conflict`, and the client silently
  reverts local to canonical. This is what kills the bogus "you edited this while you did too" cards with
  identical numbers on both sides — don't remove it. A real conflict records the **winner**:
  `commit_expense` stamps `expenses.last_editor = p_actor` on every write and copies it into
  `expense_edit_conflicts.server_actor`, so the client can attribute the collision to the person who
  actually won (`ExpenseEditConflict.winnerBy`) rather than the misleading `rejected_by` (always the local
  pusher). On `noop`/`conflict` the client reverts its cache to canonical
  (`ExpenseDao.overwriteFromServer`); a parked edit syncs back for a **pick-a-side** resolution
  (`ExpenseRepository.observeEditConflicts` now exposes BOTH full sides — title/amount/split/payer/per-user
  shares — so `GroupConflictsTab` renders a field-level **diff** led by the viewer's own share, not a bare
  total; `resolveEditConflict` unchanged; both kinds of conflict share the Conflicts tab). The whole
  rationale is in the "When Two Edits Collide" article in Notion.
- **Settlement state is DERIVED on read, never stored.** A share's `remaining` is computed as
  `owed − Σ(applied allocations of non-voided settlements)` (the payer's own share is always 0); balance
  and "settled" follow from it (an expense is settled iff every share's derived remaining is 0). So:
  `settlement_allocations` is now a **synced** table (the payment ground truth; carries `group_id` +
  `row_version`), settlements no longer mutate a stored remaining (the `shares.remaining_subunits` column
  is vestigial — read it nowhere), and `expenses.status` only ever stores ACTIVE/DELETED. Because remaining
  derives, **editing a split no longer wipes payments**: `ExpenseDao.replaceWithShares` + `mergeShares`
  preserve each surviving participant's share `id` (matched by `user_id`) so allocations stay linked.
  `shares` has `deleted_at` (removed participants are soft-deleted, Rule 1) + a partial unique index over
  active rows; the old hard-delete `deleteSharesForExpense` is gone. Don't reintroduce a stored remaining
  or a stored SETTLED status — that's the staleness bug this design removes.
- **"Split the bill" expenses derive shares from items + claims, never stored input.** An itemized
  expense (`split_mode = "ITEMIZED"`) keeps its line items in the synced `expense_items` table and
  who-had-what in the synced `item_claims` table; bill-level extras ride on the expense row
  (`tax_subunits`/`gratuity_subunits`/`tip_subunits`/`tip_split_mode`/`discount_subunits`). **Claims are
  partitioned by user** — each device only ever writes its *own* claim — so live multi-device claiming is
  conflict-free and needs no CAS (unlike an expense edit). `BillRepositoryImpl.materializeShares` runs the
  pure `splitBill` engine (`domain/expense/BillSplit.kt`) and writes the resulting `shares` with
  **deterministic ids** (`"<expenseId>__<userId>"`), so every device converges on identical rows and
  editing the menu re-derives *in place* — a price fix never disturbs a recorded claim or its settlement
  allocations. Item cost is per-unit exact (claim 2 of 4 → pay 2×price; unclaimed units stay unassigned
  until Finish; over-claim is **surfaced, not capped**); tax & gratuity split proportionally, tip
  even-by-default (toggleable), discount negative-proportional — all penny-exact via the existing
  largest-remainder `allocate`. Shares for an itemized expense are a **local derived materialization** of
  the synced items/claims (not independently pushed). Receipt OCR is the `extract-receipt` edge function
  (Claude vision → structured draft) reached via the `ReceiptOcr` gateway; it only ever *pre-fills* the
  editable item list (human-verify before any money is computed). Don't reintroduce stored itemized share
  input or a blind items/shares push.
- **Claimed placeholders are retired *everywhere*, not just the roster.** A placeholder is a `users` row
  (`is_placeholder=1` + `placeholder_group_id`) **plus** a `members` row (`addPlaceholder` creates both).
  When it's merged into a real user — reconcile *or* a joiner picking it on the Join sheet — soft-leave it
  **and** stamp `placeholder_claim_completed_at` via `MemberDao.markClaimedByUser` (not plain
  `markLeftByUser`). `UserDao.observePlaceholdersInGroup` (the source for both the Reconcile picker and the
  Join-sheet identity picker) JOINs `members` and filters `status='ACTIVE' AND
  placeholder_claim_completed_at IS NULL`, so a claimed placeholder stops appearing as a pickable identity.
  Don't revert it to a users-only query — that resurrects the merged placeholder. Member display names
  always resolve from the global `users` JOIN (`MemberWithUserRow.displayName`); there is **no per-group
  name copy**, so a Settings rename (`ProfileRepositoryImpl.updateDisplayName`) propagates to every roster
  reactively. (`SyncEngine.pull()` now guards `members`/`users` — and every other synced table carrying
  `updated_at` — with the `keepNewer` last-write-wins filter (Rule 5), so a stale server row can no longer
  re-resurrect a local soft-delete/rename; `conflicts`/`expense_history` are exempt, having no `updated_at`.)
- **Device-local tables stay out of sync.** Not every Room table is a wire-mirror: the receipt-upload
  outbox (`receipt_uploads`, D-22) is local-only — it is *not* `@Serializable`, *not* in `SyncEngine`'s
  table list, and never reaches the server. It tracks in-flight upload state (local file path, progress,
  status); the synced `receipts` row is created only once the bytes land in Storage. Resilient receipt
  upload lives in `data/upload/` (`ReceiptUploadManager` = the shared `ReceiptUploadDriver`) with a
  platform `ReceiptUploadScheduler` actual — Android `WorkManager`, iOS background `URLSession`. Progress
  is written to Room and observed by the UI (no WorkManager↔UI plumbing). See `iosApp/PUSH_SETUP.md`-style
  note: the iOS host `AppDelegate` still needs `handleEventsForBackgroundURLSession` for suspended-app
  completion (session id `da.chelimo.sharecost.receiptUpload`). Likewise `expense_sync_state` (the
  per-expense `base_version` tracker for the `commit_expense` CAS) is device-local — not `@Serializable`,
  not in `SyncEngine`'s table list, and never triggers a push.
- **Receipts are viewed *in-app*, never handed to an external browser.** Tapping a receipt opens the
  full-screen `ReceiptViewerScreen` (`ui/screen/expense/`) — a `HorizontalPager` over all of the expense's
  receipts with a bottom thumbnail filmstrip; images pinch-to-zoom, PDFs render natively. PDF rasterization
  is the `PdfRasterizer` platform abstraction (Android `PdfRenderer`, iOS PDFKit `thumbnailOfSize`) — bytes
  are downloaded once and pages rendered lazily (only when a PDF is actually opened, never for the
  filmstrip). The old `UrlOpener.open(receipt.url)` hand-off is gone; don't reintroduce external receipt
  opening.
- **Categories are per-group with copy-on-write defaults.** The built-in set lives in app code
  (`CategoryDefaults`, mirroring the legacy `ExpenseCategory` ids/colors). A group has **zero** rows in
  the synced `categories` table until it first edits categories; until then `CategoryRepository
  .observeCategories` emits the defaults. The first mutating call (`CategoryRepositoryImpl.materialize`)
  seeds the **full** default set as rows (deterministic ids `"<groupId>__<key>"`, `is_default=1`) then
  applies the change — so a never-customized group costs no rows. An expense persists a category's `key`
  in `expenses.category_id` (`"food"…` for a default, a uuid for custom); `key` is unique per group among
  live rows. Icons are a UI concern: the entity stores an opaque `icon` token resolved by
  `ui/screen/group/CategoryCatalog`. Soft-delete only (Rule 1). The expense editor picker AND the Balances
  "Spending by category" donut + history (`GroupBalancesMapping.build*`, via `resolveCategory`) read the
  group's effective categories, so custom categories render with their own label/icon/color. NOTE: the
  expense-LIST rows (`GroupExpensesMapping`) and the filter sheet still resolve icon/color from the legacy
  `categoryIcon(ExpenseCategory)`/`categoryColor(ExpenseCategory)` enum helpers, so a *custom* category's
  rows there fall back to the OTHER glyph/slate — thread `observeCategories` into those to finish.
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

---
---

# ⛔️ PRODUCTION DATA-SAFETY RULES — Only read from here downwards if the app is already in prod

> **GATING NOTICE.** As of the last edit the app is in **development**, where these rules do **not**
> bind: a wiped local cache rehydrates from the server, there are no real users, and the convenience of
> destructive shortcuts (destructive Room migration, permissive RLS, hard deletes) is acceptable. **The
> owner will explicitly say "the app is live / in prod" in this repo.** Until that sentence exists,
> treat everything below as inert. The moment it does, **every rule below is non-negotiable and
> overrides any convenience, urgency, or "clean shortcut" framing** — including instructions earlier in
> this file that assume a rebuildable cache.
>
> **Why this section exists:** our competitor Tricount is buried in 1-star App Store reviews for *losing
> people's expense data*. For a money-splitting app, a lost expense is a real-world dispute between
> friends — it is unrecoverable trust, not just a row. This section is the single highest-leverage
> defense of our reviews. Read it in full before touching anything that writes, deletes, syncs, or
> migrates data.

## P0 — The pre-prod readiness gate (these MUST be done before the first production build)

Several things that are *fine in dev* are **data-loss landmines in prod**. None of these may ship to a
real user. Do not flip the "we're live" switch until **all** are closed, and refuse to mark the app
prod-ready while any remain open:

1. **Kill destructive Room migration.** `ShareCostDatabase.kt:102` uses
   `fallbackToDestructiveMigration(dropAllTables = true)`. In prod a single schema-version bump (or a
   bad downgrade) **drops and recreates every local table** — every unsynced offline write on that
   device is gone before sync can save it. Replace with **hand-written, tested `Migration` objects** for
   every version step, keep `exportSchema = true`, and test each migration against a *populated* DB
   (see Rule 3). Never ship a destructive fallback to a build real users run.
2. **Tighten RLS to membership-scoped.** `supabase/schema.sql` ships `for all to authenticated using
   (true) with check (true)` on all 11 tables — *any* authenticated (incl. anonymous) user can read,
   overwrite, **or delete every row in the database**. That is a one-account mass-data-loss vector. Apply
   the membership-scoped policy sketch already in `schema.sql` before any non-test user exists. See Rule 6.
3. **Add the audit log + triggers** (Rule 4) — there is none today; `expense_history` logs *events*, not
   *before-images*, so today a bad mutation is unrecoverable from app data alone.
4. **Close the soft-delete gaps** (Rule 1): `users`, `members`, `conflicts`, `device_tokens` still have
   no `deleted_at`. (`shares` now has one — done.)
5. ~~Stop hard-deleting shares on edit~~ **(done)**, and **add concurrency guards to sync** (Rule 5):
   expenses now go through the `commit_expense` compare-and-swap RPC with parked conflicts, and pull keeps
   a locally-dirty expense from being clobbered; the remaining tables are still blind last-write-wins.
6. **Turn on Supabase Point-in-Time Recovery (PITR) + scheduled backups, and rehearse a restore** (Rule 12).

## The rules

### 1. Never hard-delete user data. Soft-delete only.

Every table holding user-generated or financial data deletes by setting `deleted_at` (epoch millis,
matching our `updated_at` convention) and `deleted_by` (actor `user_id`) — never by removing the row.
The synced-entity round-trip already depends on this: tombstones must reach the server, and our
`allForSync()` queries deliberately include soft-deleted rows so the deletion *propagates* instead of a
hard delete silently resurrecting on the next pull.

**Already correct (keep it this way):** `expenses`, `groups`, `settlements`, `comments`, `receipts`
carry `deleted_at`; `members` uses `status = 'LEFT'` + `left_at`. Their `softDelete(...)` DAO methods and
the `deleted_at IS NULL` read filters are the pattern to copy.

**Forbidden — flag before writing, never add silently:**
- `@Delete` or `@Query("DELETE FROM …")` on any user-data DAO.
- A Postgrest `.delete()` call from `commonMain`/client code on a user-data table.
- One hard delete still exists **today** and must be converted before prod:
  - **`UserDao.delete` (`UserDao.kt:29`)** — see Rule 11 (account deletion).
- **Resolved (keep it this way):** the old `ExpenseDao.deleteSharesForExpense` share hard-delete is **gone**.
  `shares` now carries `deleted_at` (+ a partial unique index over active rows); editing an expense
  soft-deletes removed participants via `replaceWithShares(…, removedShareIds, ts)` and `allForSync()`
  ships the tombstones. Don't reintroduce a `DELETE FROM shares` — the conflict-revert path
  (`ExpenseDao.overwriteFromServer`) also tombstones rejected local shares rather than hard-deleting them.

If a table is missing `deleted_at`/`deleted_by` and you're asked to delete from it: **stop and flag it.**
Adding a hard delete as a workaround is itself a rule violation.

### 2. Never DROP, TRUNCATE, or unscoped-DELETE. Ever.

`DROP TABLE`, `TRUNCATE`, and `DELETE` without a `WHERE` are forbidden in any migration, RPC, edge
function, or client query you write — on Postgres *and* in Room. `clearAllTables()` and any "wipe / reset
/ nuke" helper are forbidden against a prod DB. If a task seems to need one of these, there is a safer
path — ask. (Today the schema is clean: zero `DROP`/`TRUNCATE`, the only `DELETE` is the per-user
`delete_my_account()` RPC. Keep it that way.)

### 3. Migrations are additive and backward-compatible — and tested against real data.

This extends the dev-mode migration rule earlier in this file; in prod it is hard law:
- Add columns with a sensible `DEFAULT` so existing rows and older app versions keep working. Remember
  the existing constraint: **a new column on a synced entity must be added server-side *first*** (the
  full-row upsert sends every field).
- **Never rename in one step:** add the new column → backfill → deprecate the old one in a *later*
  migration after no in-the-wild app version reads it.
- **Never drop a column** without a deprecation period (mark it with a comment first) and a real
  audit/recovery trail.
- **Test every migration against a populated copy of prod**, not a fresh schema — both the Postgres
  migration and the Room `Migration`. A migration that's only been run on an empty DB is untested.

### 4. Every financial mutation writes to the audit log.

Any repository method, RPC, trigger, or edge function that creates / edits / soft-deletes an `expense`,
`share`, `settlement`, or `settlement_allocation` must append a before+after record. **We don't have this
table yet — it's a P0.** Build `expense_audit_log` (append-only):

```
expense_audit_log (
  id uuid pk default gen_random_uuid(),
  entity text not null,            -- 'expense' | 'share' | 'settlement' | ...
  entity_id text not null,
  action text not null,            -- 'created' | 'updated' | 'deleted'
  old_data jsonb,                  -- before-image (null on create)
  new_data jsonb,                  -- after-image (null on hard delete — which we don't do)
  changed_by uuid references auth.users(id),
  changed_at timestamptz default now()
)
```

Prefer a **Postgres `AFTER INSERT/UPDATE` trigger** on the financial tables so the before-image is
captured server-side and *cannot* be skipped by a client code path. The audit log is **append-only**: it
is never updated, soft-deleted, hard-deleted, or truncated. If asked to delete from it, refuse. (Our
existing `expense_history` is a *user-facing activity feed* of events, **not** a substitute — it stores
no `old_data`.)

### 5. Sync must not silently clobber data — guard last-write-wins.

This is our biggest *latent* data-loss vector and it is invisible until two devices (or one offline
device) collide. `SyncEngine.pull()` **now applies a client-side last-write-wins guard** (`keepNewer`,
keyed on `updated_at`) to every synced table that carries `updated_at`, so it no longer clobbers a newer
local row. Two gaps remain before prod: (a) `push()` still sends every local row with **no version
check**, so a stale local row can overwrite a newer server row; (b) the pull guard silently keeps the
local winner instead of surfacing a conflict. We carry `row_version` and `updated_at` on every synced
entity — **use them** to close both:

- **Pull must never overwrite a local row that has unsynced local edits, or that is newer than the
  incoming remote row.** The `keepNewer` filter already does the keep-the-winner half (drops a stale
  incoming row by `updated_at`); still TODO is to **surface a real conflict** to the user (we have the
  `conflicts` table + `GroupConflictsTab` UX) rather than silently dropping the losing side.
- **Push must not let a stale local row overwrite a newer server row.** Add a server-side guard — a
  trigger or an `on conflict … where excluded.updated_at > <table>.updated_at` upsert — that **rejects
  an upsert whose `updated_at` is older** than the stored row.
- **The Realtime handler (`SyncManager`) currently ignores the action type and just re-pulls.** That is
  only safe *because we soft-delete*; if any hard delete ever reaches the server, a re-pull won't remove
  it locally. Keep deletes soft (Rule 1) so a pull always carries the tombstone.
- Treat a partially-failed push (e.g. an expense edit that deleted shares locally then lost the network)
  as recoverable: the periodic full-sync re-pushes, but only if the local write is still intact — which
  is the whole point of Rules 1 and 4.

### 6. Never bypass RLS without flagging it loudly.

RLS is the primary authorization layer and the wall between one user's mistake and *everyone's* data.
- Client/`commonMain` code uses the **anon/authenticated** key only (`SupabaseConfig` ships the anon key
  — correct). The **service-role key never appears in shared/client code** — only in edge functions via
  env vars. (`push-notify` correctly uses it server-side, read-only.)
- Reaching for the service-role client is never the default. If a task genuinely needs it, say so
  explicitly in a comment explaining *why RLS can't express the rule*, and keep it read-only or tightly
  scoped.
- Prod RLS must be **membership-scoped** (Rule P0.2), not `using(true)`. A permissive policy means one
  compromised/anonymous token can delete the whole table — exactly the Tricount failure mode.

### 7. Cascade deletes require explicit confirmation, and are soft.

We deliberately have **no FK constraints and no `ON DELETE CASCADE`** (rows sync in dependency-arbitrary
order). Keep it that way: **never add `ON DELETE CASCADE` to a user-data table.** Deleting a parent
(group, expense) cascades to children (expenses, shares, settlements, comments, receipts) **in
application/trigger logic as a soft-delete cascade** — set `deleted_at` on each child. Before writing any
cascade, state in your response: (a) exactly which tables and roughly how many rows are affected, and
(b) that it is a *soft* cascade. If you can't confirm both, don't write it.

### 8. Irreversible operations need a confirmation header with real pre-checks.

Any migration or function that can't be cleanly undone carries a top-of-file comment:
`-- DESTRUCTIVE OPERATION`, what it does, **pre-checks filled with real answers** (which app versions
still read this column? since when unused? is the data preserved in the audit log / a PITR snapshot?),
and the **recovery path** (e.g. "restore from PITR snapshot taken at …"). If you cannot fill the
pre-checks with true statements, **do not write it — ask for the missing facts first.**

### 9. Data-safety logic lives in the repository layer.

`deleted_at`/`deleted_by` stamping, audit-log writes, version/conflict resolution, and tombstone-sync
belong in **`data/repository/*` and the DAO layer**, never in `ui/` Composables or the `ui/navigation/`
Route wrappers. Screens stay DI-free and take plain callbacks (our established pattern); if you find
yourself writing `deleted_at` or conflict logic in a Composable or a Route wrapper, stop and push it
down into the repository.

### 10. Storage (receipts): a soft-deleted row must not silently orphan its file — and never bulk-purge.

Soft-deleting a `receipts` row already best-effort deletes the Storage object
(`ActivityRepositoryImpl.deleteReceipt`). In prod, make that cleanup reliable (reconcile/retry) so we
neither leak files nor delete a file while the row (or another row) still references it. **Never** issue a
bucket-wide or prefix-wide delete against the prod `receipts` bucket, and tighten the currently-permissive
bucket policies alongside Rule 6.

### 11. Account deletion is reversible and never erases others' financial history.

`SupabaseAuthSession.deleteAccount` (`SupabaseAuthSession.kt:136`) calls the server RPC, then
**hard-deletes the local user row even if the RPC failed**, and `StubAuthSession` hard-deletes outright.
For prod:
- Deleting a user must **not** vaporize the expenses/shares/settlements that *other* members still depend
  on to settle up. Soft-delete / anonymize the profile; preserve the financial rows others share.
- Honor a **grace period** (mark `deleted_at`, purge later via a server job) so an accidental or
  rage-tap deletion is recoverable, and so a legal/export request can still be served.
- Don't destroy local state until the server confirms; on RPC failure, surface the error instead of
  leaving the device wiped but the account live.

### 12. Backups are a feature: enable PITR and rehearse restores.

The single most reliable defense against the failure mode we're guarding against. Before prod: enable
**Supabase Point-in-Time Recovery** and scheduled backups, set a retention window, and **actually
perform a test restore** to a scratch project at least once so "restore from PITR" in Rule 8 is a proven
path, not a hope. Re-test after any major schema change.

### General

- **Flag before you act** on anything touching schema, deletes, auth, RLS, or sync — name the change at
  the top of your response. Never silently alter a column, RLS policy, sync rule, or function signature.
- **When in doubt on any data operation, ask — don't assume.** In prod the cost of asking is a message;
  the cost of a wrong guess is unrecoverable user data and the reviews that follow.
- Prefer explicit, readable data-safety code over clever/terse code.
