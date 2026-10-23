# Tester feedback round — October 2026 (coordination)

**Status:** planned, not started
**Base branch:** `feat/split-by-items-flow` (all worktrees branch from it and merge back into it)
**Planning:** Opus (these briefs). **Implementation:** Sonnet, one session per worktree.

Ten pieces of owner/tester feedback, scoped into tasks and grouped into five worktrees. A worktree
holds tasks that touch the same files, so the five can run in parallel and merge back with no or
trivial conflicts. Each worktree has its own brief; this file is the map, not the contract.

## Worktree map

| Worktree / branch | Tasks | Brief | Mock first? |
| --- | --- | --- | --- |
| `fb/auth-onboarding` | T1 email link + code on sign-up · T6 clearer payment-details editing · T8 onboarding explains the handle | [auth-onboarding-polish.md](auth-onboarding-polish.md) | T6, T8 copy only |
| `fb/group-surface` | T2 visible members + add member on the group page · T9 drop "New link" · T10 drop conflict reminders | [group-members-and-sharing.md](group-members-and-sharing.md) | T2 yes |
| `fb/loading-states` | T3 loading vs genuinely empty (home, group, post-join) | [loading-vs-empty-states.md](loading-vs-empty-states.md) | skeleton only |
| `fb/bill-edit` | T5a auto-save on back + "Changes saved" · T5b discoverable Scan · T5c show existing receipt, Add vs Replace re-scan | [bill-edit-autosave-and-scan.md](bill-edit-autosave-and-scan.md) | yes |
| `fb/expense-detail` | T4 claim QR that actually opens the web picker · T7 comments + receipt in their own card | [expense-detail-and-claim-qr.md](expense-detail-and-claim-qr.md) | T7 yes |

## Known file overlaps (the only places merges can touch)

- `GroupExpensesTab.kt` — `fb/group-surface` edits the top bar + `InviteSheet` (~L160–175, ~L354–440);
  `fb/loading-states` edits only the `ExpensesState.Loading` branch (~L253). Keep to those hunks.
- `GroupExpensesMapping.kt` — owned by `fb/loading-states` only.
- `ExpenseDetailScreen.kt` — owned by `fb/expense-detail` only.
- `EvenlyNavHost.kt` — any worktree adding a route appends; do not reorder existing entries.

## Merge order

1. `fb/group-surface` (smallest, mostly deletions) → 2. `fb/auth-onboarding` → 3. `fb/loading-states`
→ 4. `fb/expense-detail` → 5. `fb/bill-edit`. Re-run the §5 compile pair after each merge.

## Rules every implementer follows

- Read root `AGENTS.md` and the nested `AGENTS.md` for every tree you touch before editing.
- Mock any new/changed screen into `design/` and stop for owner sign-off before writing Compose (AGENTS.md §7).
- Green = both targets (AGENTS.md §4.1/§5). Run `ux-firsttimer` before calling a UI task done.
- One commit per task (T-number in the message scope body). Never commit to `feat/split-by-items-flow` directly.
- Anything outward-facing (Vercel deploy, Supabase dashboard/template change, edge-function deploy)
  is **proposed, not performed** — write the exact step in the brief's completion report for the owner.
- Fill in §7 Completion report of your brief. Out-of-scope discoveries go there, not into the diff.
