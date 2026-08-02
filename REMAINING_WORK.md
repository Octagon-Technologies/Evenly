# Evenly — Remaining Work Handoff (E1 · F · C3)

> Written by the previous agent for a fresh Claude Code agent. It contains everything already decided
> or discovered in the prior session, plus **"DISCOVER"** pointers for what you should confirm yourself
> before coding. Read `CLAUDE.md` first (build/commit/data-safety rules — they bind). The app is in
> **development** (destructive Room migration + permissive RLS are OK; keep deletes soft).

Branch: `feat/expense-versioning-conflicts`. The post-testing UX overhaul is mostly done and committed
(front door, editor legibility, and the **v4 portions assign screen** are all merged and verified on an
emulator). What's left are three planned tracks the owner deferred: **E1 (dark mode)**, **F (retire the
Conflicts tab via a real merge, not naive LWW)**, and **C3 (edit-bill placement)**.

Owner's working style (important): **mock the UI before writing Compose** for any non-trivial screen —
produce a faithful visual mockup (an HTML Artifact) and get sign-off first. For big data/logic changes,
agree the model in writing first. Commit per coherent feature, only when **both** platforms are green.

---

## Build / test / run (verified recipe)

`gradlew` lives in `code/`, not the repo root. From anywhere, use `-p`:

```bash
CODE=/Users/DaChelimo/Documents/TechWork/Evenly/code
$CODE/gradlew -p $CODE :shared:compileAndroidMain              # Android/JVM + Room KSP
$CODE/gradlew -p $CODE :shared:compileKotlinIosSimulatorArm64  # Kotlin/Native — catches iOS-only breakage
$CODE/gradlew -p $CODE :shared:testAndroidHostTest :shared:iosSimulatorArm64Test  # both test suites
$CODE/gradlew -p $CODE :androidApp:assembleDebug               # full APK (manifest merge, Firebase)
```

Run on the already-set-up emulator (AVD `Pixel_10_Pro`) — the owner had `emulator-5554` running:
```bash
export ANDROID_HOME=~/Library/Android/sdk; export PATH="$PATH:$ANDROID_HOME/platform-tools"
adb devices                                      # confirm a device is attached (boot one if not)
adb install -r $CODE/androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n app.splitevenly/.MainActivity
adb exec-out screencap -p > /tmp/shot.png        # then Read the PNG to see the UI
```
The emulator screen is 1280×2856; a Read'd screenshot displays scaled — multiply displayed coords by
~1.43 for `adb shell input tap X Y`. Android-green ≠ iOS-green: always compile Native too. **Run the app
on the sim after UI changes and leave it up for the owner** (repo rule). Verify dark mode on a *physical*
device, not just the emulator.

Supabase project ref: `wfpfgbipjmkysalfmyub`. `supabase/schema.sql` is canonical; apply additive,
idempotent migrations (Supabase MCP `apply_migration`, or dashboard). **A new column on a synced entity
must be added server-side FIRST** (the full-row upsert sends every field, so a missing server column
breaks all sync for that table). Room schema is at **v16** (bump + `exportSchema` on any entity change;
schemas live in `code/shared/schemas/`).

---

## Track C3 — "Edit bill" placement (smallest; do this first to warm up)

**Problem (owner's words):** unsure "Edit bill" makes sense where it is; and edit-bill should let you
**add a person** too.

**What's known:**
- The assign screen (`ui/screen/bill/BillClaimScreen.kt`) has an **"Edit bill"** secondary button in its
  bottom bar; the route `BillClaimRoute` (in `ui/navigation/BillRoutes.kt`) wires `onEditBill` →
  navigates to `Route.SplitBill(groupId, expenseId)` (the itemized editor).
- The itemized editor is `AddExpenseScreen` in edit mode (reached via `Route.SplitBill` with an
  expenseId) — it already shows the participant chips with an **"Add"** chip that calls `onAddPlaceholder`
  (creates a placeholder member). So **add-person in the editor likely already works** — DISCOVER: open
  edit-bill and confirm the Add chip is present and functional; if not, thread `onAddPlaceholder` through.
- **DISCOVER:** where else "Edit bill" is/should be reachable — the **expense detail** screen
  (`Route.ExpenseDetail`, likely `ui/screen/expense/`). Owner implied it belongs on the expense, not
  buried. Grep `Route.SplitBill(` and `onEditBill` for all entry points.

**Direction:** make "Edit bill" an obvious, well-placed action on the expense/claim surface; ensure
add-person is available there. Small — a quick mockup is optional. Verify the edit round-trips (edit a
bill's items, save, re-derives shares in place without wiping claims/allocations — that invariant is in
`BillRepositoryImpl.editBill`/`materializeShares`).

---

## Track E1 — Dark mode (targeted contrast pass, NOT a re-theme)

**Problem (owner diagnosis):** in dark mode the **blue/primary can't be seen well** against the dark
background, and cards barely separate from the page. Keep the blue/light brand identity — this is a
**targeted retune**, not a redesign.

**Scope (agreed):** retune **dark-mode background + on-background** and the **primary (blue) + on-primary**
so the accent reads clearly; strengthen **page↔surface separation** and **borders** so cards are distinct.

**Files / current dark tokens (confirm exact line numbers — file may have shifted):**
- `code/shared/src/commonMain/kotlin/app/splitevenly/ui/theme/Color.kt` — dark palette (~lines 51–79):
  `EvPageDark #0B0F17`, `EvSurfaceDark #141A24` (too close to page → cards vanish), `EvInkDark #E7ECF3`,
  `EvBlueDark #5B8DEF` (the accent the owner can't see — likely needs lifting/saturating), `EvBorderDark
  #232C3A` (too subtle), `EvBorderStrongDark #334052`.
- `code/shared/src/commonMain/kotlin/app/splitevenly/ui/theme/ExtendedColors.kt` — `ExtendedDark`
  (~94–125) maps the brand tokens; also has `blueTint`/`blueTint2`/`onAccent` used all over the new UI.
- `Theme.kt` (M3 dark ColorScheme wiring), `SystemBars.kt` (status-bar icon contrast follows the theme),
  `components/EvBars.kt` (`StatusBarScrim` paints `page` behind the status bar).

**Approach:** (1) **Mock it first** — produce a dark-mode Artifact showing the retuned palette on real
screens (Groups list, an expense, the assign screen) for owner sign-off; the owner iterates on the mock.
(2) Then adjust the dark tokens: lift the blue's luminance/saturation so it pops on `#0B0F17`; increase
page↔surface separation (e.g. lighten surface or darken page) and strengthen dark borders so cards read.
(3) Verify contrast (WCAG AA for text; visible separation for cards) and **eyeball on a physical device**.
Check the owe/owed amber/green semantic colors still read in dark (memory: that palette is deliberate).
The design source of truth is `design/` (blue/light; the mock/build overrides the spec's dark/green — do
not "fix" back toward the spec).

---

## Track F — Retire the Conflicts tab via FIELD-LEVEL MERGE (design-heavy; gate on a model doc)

This is the big one. **Owner leans last-write-wins but correctly fears it** (a device offline for weeks
then syncing a stale edit could clobber many newer edits). We agreed: **eliminate the user-facing
"Conflicts" concept, but NOT via naive timestamp LWW.** Write a **model doc + get owner sign-off before
coding** (per the CLAUDE.md design gate). The full "When Two Edits Collide" rationale is in Notion.

### The agreed design
1. **Field-level merge for independent scalars.** `title`, `category`, `notes`, `payer` each carry their
   own last-edited timestamp and merge independently — so two people editing *different* fields both
   survive (the "name + category coexist" case). Applies on **push** (server merge) AND **pull** (extend
   the existing row-level `keepNewer` guard to field-level for expenses).
2. **Coupled "split unit" with a causal version.** `amount` + `split_mode` + `participant_set` +
   per-person `shares` move as ONE atomic value guarded by a `split_version` (causal, NOT wall-clock). A
   split edit made against an older `split_version` than the server holds is *causally stale* → the server
   keeps its advanced split; the stale split edit is **logged, not applied**. (This is the narrowed, good
   half of today's CAS — it's what stops an offline-for-weeks device from eating 15 newer edits.)
3. **No bilateral "you both edited this" cards; at most a one-sided, dismissible notice.** Delete the
   `expense_edit_conflicts` parking + the edit-conflict half of `GroupConflictsTab`. A superseded split
   edit produces an **append-only audit record** (recoverable — aligns with the future prod audit-log
   rule) and optionally a one-sided "your change was superseded while you were away — review?" nudge to
   that one person. Never a two-sided conflict card.
4. **Late-join member decision** (the OTHER half of the Conflicts tab — a member added after a non-even
   expense): reframe as a soft inline **"Add [new member] to past expenses?"** prompt, not a "conflict."
5. **Scope boundary:** start field-merge with **expenses** (the collision hotspot). Other synced tables
   keep the existing row-level `keepNewer` LWW (claims are per-user-partitioned; settlements append-mostly;
   groups/members rarely collide). Revisit only if a second hotspot appears.

### What exists today (so you replace, not duplicate)
- **CAS RPC:** `commit_expense(p_expense, p_shares, p_base_version, p_actor)` in `supabase/schema.sql` —
  compare-and-swaps on `row_version`; first writer wins, loser **parked** in `expense_edit_conflicts`;
  suppresses a no-op when a stale payload is materially identical; stamps `expenses.last_editor`.
- **Client push:** `SyncEngine.pushExpenses` (`data/remote/supabase/SyncEngine.kt`, ~lines 204–228) routes
  each dirty expense (local `row_version` ≠ device-local `expense_sync_state.synced_version`) through
  `commit_expense`; handles `created`/`committed`/`noop`/`conflict` (on noop/conflict it reverts local
  cache to canonical via `ExpenseDao.overwriteFromServer`). `expense_sync_state` is device-local (the
  per-expense base_version tracker) — NOT synced.
- **Client pull:** `SyncEngine.pull()` already applies a client-side `keepNewer` last-write-wins guard
  (keyed on `updated_at`) to every synced table carrying `updated_at` (row-level today).
- **Conflict UI:** `ui/screen/group/GroupConflictsTab.kt` renders BOTH kinds — edit-collisions (a
  field-level diff, `EditConflictUi`) and late-join member decisions (`ConflictUi`). Wired into
  `GroupHomeScreen.kt` (~51–71): the tab only shows when `conflictCount > 0`.
- **Repo:** `ExpenseRepositoryImpl.observeEditConflicts` (~227–277) and `resolveEditConflict` (~279–320+);
  domain models `domain/expense/ExpenseEditConflict.kt` and `domain/group/Conflict.kt`; entities
  `ExpenseEditConflictEntity`, `ConflictEntity`.

### Build order (suggested)
1. **Model doc** (`data/logic`): per-field timestamp columns on `expenses` (server-additive FIRST, then
   entity, then Room bump), a `split_version` column, the server **merge RPC** that replaces
   `commit_expense` (field-level merge + causal split guard + append-only superseded-edit log), and the
   client push/pull field-merge. **Get owner sign-off.**
2. **Schema/migration** (additive) + entity fields + Room bump + `schema.sql`.
3. **Server merge RPC** + a superseded-edits audit table (append-only).
4. **Client:** rewrite `pushExpenses` to the merge RPC; extend pull `keepNewer` to field-level for
   expenses; the one-sided "superseded" nudge.
5. **Remove** the edit-conflict UI (`GroupConflictsTab` edit half) + the `expense_edit_conflicts` parking;
   reframe late-join as the inline prompt. Update `GroupHomeScreen` tab wiring.
6. **Tests:** the two scenarios the owner raised — (a) offline-for-weeks device syncing a stale edit must
   NOT clobber intervening edits; (b) name/category/participants/split edited by different people — name
   & category coexist, but participants+split (the coupled unit) resolve to one causal winner.
7. **CLAUDE.md:** the "Expenses sync through an optimistic-concurrency RPC" + conflicts paragraphs must be
   rewritten to describe the merge model in the SAME commit that changes behavior.

---

## Discovery checklist (do before coding each track)
- **C3:** `grep -rn "onEditBill\|Route.SplitBill(\|ExpenseDetail" code/shared/.../ui` — map every
  edit-bill entry point; confirm add-person works in the editor.
- **E1:** read `ui/theme/Color.kt`, `ExtendedColors.kt`, `Theme.kt` in full; enumerate every dark token
  the new screens use (`blueTint`, `blueTint2`, `onAccent`, `warning`, `warningTint`, `borderStrong`).
- **F:** read `SyncEngine.kt` (push + pull), `ExpenseRepositoryImpl` (the conflict methods), the
  `commit_expense` RPC in `schema.sql`, and `GroupConflictsTab` + `GroupHomeScreen` — you're replacing all
  of it. Read the Notion "When Two Edits Collide" article if accessible.

Also see the memory file `evenly-post-testing-overhaul.md` (auto-loaded) for the decision history and
the commit list of everything already done.
