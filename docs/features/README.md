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
