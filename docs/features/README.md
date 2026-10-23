# Feature briefs

One `.md` per feature: what it must do, what it must not touch, how each behavior is proven, and
what actually shipped. Start from [`_TEMPLATE.md`](_TEMPLATE.md).

**Why this exists.** `spec/` describes the app as a whole and `design/` is the visual source of
truth; neither is the place for the behavior contract of a single piece of work. This directory is.
Precedence is unchanged: the nearest `AGENTS.md` > root `AGENTS.md` > `design/` > a brief here.

**Conventions**

- Filename is the kebab-case feature slug: `recurring-expenses.md`, not `RECURRING_EXPENSES.md`.
- The brief is written and agreed **before** implementation, and updated in place as it changes.
  Corrections replace the wrong sentence; they are not appended below it.
- Index the feature in the table below in the same commit that creates its file.
- Feature docs written before this directory existed still live at the repo root as
  `*_SPEC.md` / `*_PLAN.md`. They migrate here when next substantially edited — not in a bulk move.

| Feature | Status | Brief |
| --- | --- | --- |
| Split by items flow | Ready for implementation | [split-by-items-flow.md](split-by-items-flow.md) |
| Tester feedback Oct 2026 (coordination) | Planned | [feedback-round-oct-2026.md](feedback-round-oct-2026.md) |
| Auth + onboarding polish (T1, T6, T8) | Planned | [auth-onboarding-polish.md](auth-onboarding-polish.md) |
| Group members + sharing cleanup (T2, T9, T10) | Planned | [group-members-and-sharing.md](group-members-and-sharing.md) |
| Loading vs genuinely empty (T3) | Planned | [loading-vs-empty-states.md](loading-vs-empty-states.md) |
| Bill edit auto-save + Scan (T5a–c) | Planned | [bill-edit-autosave-and-scan.md](bill-edit-autosave-and-scan.md) |
| Expense detail: claim QR + cards (T4, T7) | Planned | [expense-detail-and-claim-qr.md](expense-detail-and-claim-qr.md) |
