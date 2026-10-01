# Loading vs genuinely empty (T3)

**Status:** not started
**Worktree / branch:** `fb/loading-states` (from `feat/split-by-items-flow`)
**Mockup:** skeleton rows for Home group cards and group expense rows — small HTML in
`design/loading-skeletons-mockup.html`, owner sign-off before Compose.

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

A list area shows "empty" only when we *know* it is empty. Until then it shows skeletons; if the pull
fails or the phone is offline, it says so instead of claiming there's nothing.

## Design (planner's recommendation — implementer may refine, not replace)

- **Data:** add a per-session hydration signal to `SyncManager` (or a small `SyncProgress` next to
  `SyncHealth`): `StateFlow<HydrationState>` = `NotStarted | Pulling | Done | Failed(offline: Boolean)`
  for the signed-in user, set around the first `syncEngine.pull(userId)` after bind. Reset on
  sign-out/user change. In-memory only — no schema change.
- **Post-join:** after `joinByToken` succeeds, trigger an immediate pull (don't wait for the realtime
  doorbell) and expose per-group hydration (`Set<groupId>` being hydrated) so the group screen can
  show skeletons for that group specifically.
- **Persist "has ever hydrated"** per user in `SecureStorage` (device-local KV, already a Koin single)
  so a returning user offline with a truly empty DB still gets the real empty state, not an endless
  skeleton.
- **UI:** `HomeUiState` and `ExpensesState` gain the distinction:
  empty Room + `Pulling`/`NotStarted` (and never hydrated) → skeleton; empty Room + `Done` → Empty;
  empty Room + `Failed` → "Couldn't load your groups. Pull to retry." with retry. Non-empty Room
  always renders content immediately (offline-first is unchanged), optionally with a thin
  "Updating…" indicator while pulling.

## Acceptance criteria

- Given a fresh sign-in on a new device with 3 groups server-side, Home shows skeletons, never "No
  groups", until the pull completes, then the 3 groups.
- Given a brand-new account with no groups, Home shows the real empty state as soon as the first pull
  returns (≤ the pull latency, not a fixed timer).
- Given I join a group by link that already has an expense, the group page shows skeletons, then the
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

_(fill in at the end)_
