# `expense_edit_conflicts` — the four stranded rows, exported before resolution

**Exported 2026-08-15** from project `wfpfgbipjmkysalfmyub`, finding #23 in `ARCH_SECURITY_REVIEW.md`.

These rows were written by the retired `commit_expense` CAS. Nothing has written the table since
Track F replaced it with `merge_expense`, and `observeEditConflicts` is hard-wired empty, so they were
invisible in-app, unresolvable, and synced to every device forever.

They carry full rejected money payloads, so they were read and judged **before** being marked resolved
rather than blind-updated. This file is that record. All four are in `@sharecost.test` groups — no real
user's money is involved.

## The four rows

| # | id | Group | Expense | Verdict |
| - | -- | ----- | ------- | ------- |
| 1 | `demo-conflict-airbnb-carol` | New York | Airbnb | Seeded demo fixture — discard |
| 2 | `demo-conflict-acme-dave` | Trip to PR | Acme Shopping | Seeded demo fixture — discard |
| 3 | `019efbc3-…:8:0aabe43b-…` | Trip to PR | Acme Shopping | **Real rejected split edit** — see below |
| 4 | `019f2a5a-…:1:0aabe43b-…` | Penn | ACME | Real, but nothing to recover |

Rows 1 and 2 are fixtures: both carry the round `created_at = 1750000000000` (2025-06-15), ids that are
literally `demo-conflict-*`, and hand-written share ids (`rs-bob`, `prs1`). They were seeded to populate
the Conflicts tab that Track F then retired.

### Row 3 — the only one that lost real intent

`Acme Shopping`, Trip to PR, 2026-06-25. Rejected by `0aabe43b` (Alice), base 8 vs server 9.

The expense row is byte-identical to canonical (same title, amount 10000, `PERCENT`, same payer). **The
split is not:**

| Member | Rejected | Canonical (kept) |
| ------ | -------- | ---------------- |
| `0b372ffa` | 2500 (25%) | 4000 (40%) |
| `019efbc2` | 2500 (25%) | 2500 (25%) |
| `f9e22757` | 2500 (25%) | 2500 (25%) |
| `0aabe43b` | 2500 (25%) | 1000 (10%) |

Both total 10000. Someone tried to flatten the bill to an even 25% each and lost the CAS to a 40/25/25/10
split. That edit was never applied and never surfaced to its author.

**Decision: discard.** It is a test group, it is two months old, and the canonical split is the one every
device has been reading since. Re-applying a stale split now would move test money for no benefit. Under
today's `merge_expense` this same collision produces a one-sided "your change was superseded" notice, so
the class of silent loss is already closed.

### Row 4 — nothing to recover

`ACME`, Penn, 2026-07-03, `ITEMIZED`. `rejected_shares` is `[]` and the expense row matches canonical
exactly. `server_actor` equals `rejected_by`: the same device lost to itself, which is the self-supersede
case finding #6's no-op suppression now prevents. An itemized bill's shares are a local derived
materialization re-derived from items/claims on every pull, so an empty rejected share set carries no
information at all.

**Decision: discard.**

## Full payloads

The complete `to_jsonb` export of all four rows, including `rejected_expense` and `rejected_shares`
verbatim, is in [`expense_edit_conflicts-2026-08-15.json`](expense_edit_conflicts-2026-08-15.json)
beside this file. Keep both: once the rows are marked resolved this is the only copy.
