# Loading vs genuinely empty (T3)

**Status:** done; owner signed off the T3 mock as-is on 2026-10-01 (blank until loaded, offline/retry copy kept)
**Worktree / branch:** `fb/loading-states` (from `feat/split-by-items-flow`)
**Mockup:** `design/loading-skeletons-mockup.html`. **Owner decision (supersedes the skeleton
design below):** no skeletons, shimmer or "Updating" hint. Most users have no groups or expenses, so a
skeleton would flash and then fall back to "no groups". Show a plain blank page until the first pull
finishes, then content or the genuine empty state. The offline/failed state with retry stays; its copy
is still open for the owner.

Paths below are under `code/shared/src/commonMain/kotlin/app/splitevenly/` unless noted. Read
`data/AGENTS.md` and `ui/AGENTS.md` first.

---

## Problem

1. **After joining a group via link**, the group opened blank ("No expenses yet"); leaving and
   re-entering showed the expense. The data simply hadn't been pulled yet.
2. **On launch / sign-in**, Home shows "No groups" for 3–5 s, then groups appear. Users think they
   lost their data. Same for a group's expenses.

**Root cause (scout).** Both screens derive "empty" from Room alone, and Room answers instantly with
an empty list before the first pull lands:
- `ui/screen/group/GroupExpensesMapping.kt:38–41` — `Loading` only while `group == null`; an
  existing-but-unhydrated group goes straight to `Empty`.
- `ui/screen/home/HomeViewModel.kt` (~L40–57) — `active.isEmpty() && archived.isEmpty()` →
  `HomeUiState.Empty` with no notion of "haven't pulled yet".
- `data/repository/GroupRepositoryImpl.kt:207` `joinByToken` resolves the group remotely and upserts
  the membership, but nothing pulls the group's expenses before the UI navigates in.
- `data/remote/supabase/SyncManager.kt` exposes no "first pull finished" signal; `SyncHealth` tracks
  failures, not completion.

## User outcome

A list area shows "empty" only when we *know* it is empty. Until then it shows a blank page; if the pull
fails or the phone is offline, it says so instead of claiming there's nothing.

## Design (planner's recommendation — implementer may refine, not replace)

- **Data:** add a per-session hydration signal to `SyncManager` (or a small `SyncProgress` next to
  `SyncHealth`): `StateFlow<HydrationState>` = `NotStarted | Pulling | Done | Failed(offline: Boolean)`
  for the signed-in user, set around the first `syncEngine.pull(userId)` after bind. Reset on
  sign-out/user change. In-memory only — no schema change.
- **Post-join:** after `joinByToken` succeeds, trigger an immediate pull (don't wait for the realtime
  doorbell) and expose per-group hydration (`Set<groupId>` being hydrated) so the group screen can
  show skeletons for that group specifically.
- **No persisted "has ever hydrated" flag** (dropped under the blank-until-loaded rule): a persisted
  flag would let a wiped cache claim the empty state before the new pull lands. Offline with nothing
  local shows the offline/retry state instead.
- **UI:** `HomeUiState` and `ExpensesState` gain the distinction:
  empty Room + `Pulling`/`NotStarted` → blank page; empty Room + `Done` → Empty;
  empty Room + `Failed` → "Couldn't load your groups. Pull to retry." with retry. Non-empty Room
  always renders content immediately (offline-first is unchanged), with no loading indicator.

## Acceptance criteria

- Given a fresh sign-in on a new device with 3 groups server-side, Home stays blank, never "No
  groups", until the pull completes, then the 3 groups.
- Given a brand-new account with no groups, Home shows the real empty state as soon as the first pull
  returns (≤ the pull latency, not a fixed timer).
- Given I join a group by link that already has an expense, the group page stays blank, then the
  expense, without leaving and re-entering.
- Given offline on first launch with no local data, the screen shows an offline message with retry,
  not "No groups yet".
- Given local data exists, content renders instantly; no skeleton flash.

## Constraints

- No new table/column; no realtime publication changes (AGENTS.md §4.3). Hydration state is in-memory
  + one local KV flag.
- `GroupExpensesTab.kt`: edit only the `ExpensesState.Loading` branch (~L253). `fb/group-surface`
  owns the top bar and `InviteSheet` in the same file.

## Test plan

| Criterion | Proof |
| --- | --- |
| State derivation (empty × hydration matrix) | Unit tests on `GroupExpensesMapping` and the Home state reducer in `commonTest` |
| Post-join pull | Unit test with a fake gateway: join → pull invoked → hydrating cleared |
| Real flow | iOS sim: sign in on a wiped sim; join-by-link with a second account; airplane-mode first launch |
| Both targets | AGENTS.md §5 compile pair + both test tasks |

## 7. Completion report

**Status: implemented, both compiles green, unit tests green. Not yet run on a simulator and
`ux-firsttimer` not yet run (see "Still owed").**

Decision: blank page until the first pull finishes (owner sign-off), no skeletons/shimmer/hint.

What changed (under `code/shared/src/commonMain/kotlin/app/splitevenly/`):
- `data/remote/supabase/HydrationTracker.kt` (new): in-memory per-user `HydrationState`
  (`NotStarted | Pulling | Done | Failed(offline)`), a pending-group set, and `ListReadiness`
  (`Ready | Waiting | Failed`). `Done` is sticky for a session unless a joined group is still pending.
  `retry(userId)` re-runs `syncNow`.
- `SyncEngine`: owns a tracker; `pull` reports start/finish; `syncNow` reports a failed push (otherwise a
  first launch offline would wait forever, since a failed push skips the pull).
- `GroupRepositoryImpl.joinByToken`: marks the group pending and runs `syncNow` before returning, so the
  group opens populated. Optional ctor dep, null in tests (`DataModule` passes it).
- `UiModule`: tracker is a Koin single; the offline stub build binds `assumeHydrated = true` so it never
  waits on a pull that cannot happen.
- Home: `homeState(...)` pure reducer; `HomeUiState.Failed`; `Loading` is now a blank page. Local rows
  always win, so no flash when data exists.
- Group: `buildGroupExpenses(..., readiness)`; `ExpensesState.Failed`; `Loading` branch is blank.
- `commonTest/.../HydrationTrackerTest.kt`: readiness matrix, retry, account isolation, joined-group
  pending/clear, Home and group state derivation.

Deviations and flags:
- **No persisted "ever hydrated" flag** (brief suggested one). It conflicts with "never show empty before
  hydration completes": after a cache wipe it would claim empty before the new pull. Offline with nothing
  local shows the offline/retry state instead.
- **Per-group pending set** replaces the brief's "Set of groups being hydrated" signal; same purpose.
- `GroupExpensesTab.kt` hunks beyond the `Loading` branch (flag for merge with `fb/group-surface`): the
  `ExpensesState` enum gains `Failed` (L85), one `onRetry` param after `state`, and a `Failed` branch
  directly under the `Loading` branch. No top bar or `InviteSheet` lines touched.
- `GroupTabRoutes.kt` (route wrapper) reads readiness from the tracker and wires retry.
- **Offline copy still open for the owner.** Current copy kept: Home "Couldn't load your groups" /
  "You seem to be offline. Your groups are safe and will show up once you're connected." (non-network
  failures say "Something went wrong on our side. Your groups are safe."); group "Couldn't load
  expenses" / "Check your connection and try again. Nothing was lost."; button "Try again".
- `EvSkeletonRow` is now unused by Home and group (still used by `ExpenseDetailScreen`, which belongs to
  `fb/expense-detail`).
- No post-join repo-level test: `GroupRepositoryImpl` needs a Room fixture. The pending/clear behavior is
  covered on the tracker.

Still owed: `:shared:iosSimulatorArm64Test`, an iOS-sim run (wiped sim sign-in, join-by-link with a
second account, airplane-mode first launch), and `ux-firsttimer`.

### Review fixes (Opus, 2026-10-01)
- `Done` survived sign-out, so the same account signing back in (cache wiped) saw "No groups" until the pull
  landed: the exact bug T3 fixes. Sign-out's wipe now calls `HydrationTracker.forget(userId)`.
- Any successful pull cleared every pending joined group, including a pull that began before the join and so
  could not carry its rows. Only groups pending when the pull *started* are cleared now. Tests for both.
