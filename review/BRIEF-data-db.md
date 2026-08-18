# Review brief — `data/db/` + `data/upload/`

You are performing a **first-ever base review** of Evenly's Room layer and its correspondence to the
Postgres schema. This is the largest scope of the four sessions but the least arguable: most of it is
checkable correspondence rather than judgment. Your output is a findings file, not a fix.

Read `AGENTS.md`, `code/shared/src/commonMain/kotlin/app/splitevenly/data/AGENTS.md`, and
`supabase/AGENTS.md` before you start.

---

## 0. Rules

1. **Do not fix anything.** No edits to `code/`. No commits. Parallel sessions are running.
2. **Do not review outside your scope** (§1).
3. **Verify before asserting.** Every claim comes from a command you ran. This brief is heavy on
   correspondence checks — every one of them must be an actual comparison, never a recollection.
4. Write findings to **`review/findings-data-db.md`** and nowhere else.
5. **Nothing destructive against the live Supabase project.** Read-only queries only. No `DROP`, no
   `TRUNCATE`, no `DELETE`, no migration, no schema apply. If you need to test schema behaviour, ask
   the owner for a branch.

---

## 1. Scope

**In:**
- `code/shared/src/commonMain/kotlin/app/splitevenly/data/db/**` — 73 files, ~4,327 lines
  (entities, DAOs, converters, the database class, migrations)
- `code/shared/src/commonMain/kotlin/app/splitevenly/data/upload/**` — 3 files, ~406 lines

**Reference, not scope:** `supabase/schema.sql` (129KB). You read it constantly to check agreement.
A disagreement is a finding, and per `AGENTS.md` §1 a schema/entity disagreement is a **P0** — stop
and report it prominently rather than folding it in with the rest.

**Out:** `data/repository/`, `data/remote/`, `data/auth/`, `data/claim/`, `domain/`, `ui/`.

---

## 2. Already known — do not report these

Read `ARCH_SECURITY_REVIEW.md` and `SECURITY_FIX_HANDOFF.md` first. In or near your scope:

| # | What | Status |
| --- | --- | --- |
| 28 | `computeExpenseStatus` referenced but does not exist; `ExpenseDao.updateStatus` has zero callers | Open, trivial |
| 29 | `schema.sql` never drops the superseded `item_shares_item_user_active_uidx` | Open, downgraded to cleanup |
| 16 | `jsonb_populate_record` inserts explicit NULLs for absent columns | Open, server-side |
| 23 | `expense_edit_conflicts` is a dead table still carried in sync | Open, partly intentional |

Do not re-report them. **Do** report adjacent instances — another dead DAO method, another index that
exists in one place and not the other — naming which known finding you are extending.

Known and not findings: **destructive Room migration** (tracked P0; the app is pre-production and this
is a deliberate accepted risk) and **permissive RLS**.

Note the item-share uniqueness key changed to `(item_id, user_id, portion_id)` and now allows multiple
slices per line, while some docs still describe the old `(item_id, user_id)` "at most one" rule. Docs
lagging is finding #28's subject; **code or schema** still assuming the old key is a new finding.

---

## 3. Method — correspondence first, then behaviour

Start with the mechanical sweep. It is the highest-yield work in this brief and it is the reason this
scope exists as its own session.

### The correspondence sweep (do this first, exhaustively)

For **every** Room `@Entity`, compare against its table in `supabase/schema.sql`:

- **Every column present on both sides.** The full-row upsert sends every field, so a column the
  server lacks breaks **all** sync for that table (`AGENTS.md` §4.4). This is a whole-table outage,
  not a row-level bug — rank accordingly.
- Type agreement, including how `bigint` epoch millis, booleans, and nullable columns map.
- Nullability agreement. A column nullable on one side and not the other is a write that fails only
  for the rows that happen to omit it.
- Default agreement — and where the client relies on a server default, note that
  `jsonb_populate_record` (finding #16) may be writing an explicit NULL instead.
- Primary keys, unique indexes, and foreign keys present on both sides. An index on one side only
  means a constraint violation that appears on one path and not the other.

**Produce a table of this sweep in your findings file, covering every entity, even the clean ones.**
That table is the durable artifact — it is what lets a future session check a diff instead of
re-deriving all of this.

### Converters and serialization

- Every custom `TypeConverter` round-trips: `decode(encode(x)) == x` for all values including nulls,
  empty collections, and unusual strings.
- Enum persistence: what happens on an unknown value arriving from a newer client. Silent data loss or
  a crash are both findings.
- Timestamps: one unit and one epoch everywhere, matching what the server stores. A millis/seconds
  mismatch is a silent, permanent corruption.
- `@Serializable` entity field names match the Postgres column names exactly — a mismatch surfaces as
  a sync failure that finding #25 will report to the user as "offline."

### DAOs

- Queries with a `WHERE` clause that omits the soft-delete filter, returning tombstoned rows as live.
- `@Transaction` present on any DAO method performing multiple statements or returning a relation.
- Raw `@Query` string correctness — table and column names are unchecked strings; verify they exist.
- Dead methods with zero callers (finding #28 has one; look for more).
- Any hard `DELETE`. Soft-delete is a non-negotiable (`AGENTS.md` §4.5); a hard delete reachable from
  anywhere is a P0.

### Migrations

- The migration path is destructive today and that is known and accepted. What is **not** covered by
  that acceptance: whether the entity definitions are consistent with each other and with
  `schema.sql` *right now*, and whether anything assumes a migration that does not exist.

### KMP parity

Per `AGENTS.md` §4.1, Android-green is not green. `RoomDatabase.clearAllTables()` is the documented
example of an API that resolves on JVM and fails on Kotlin/Native. Sweep `commonMain` for others.

### `data/upload/`

`ReceiptUploadManager.kt` (313 lines) drives a resumable outbox with progress written into Room.
Check: a killed process resumes rather than duplicating or stranding; the status state machine has no
unreachable or terminal-but-not-final state; a failed upload is distinguishable from a pending one;
progress writes cannot thrash the database.

---

## 4. Adversarial self-check — mandatory

Before writing a finding, try to refute it. For correspondence findings specifically: confirm you are
comparing against the **live** definition, not a superseded one — `schema.sql` is 129KB and contains
history, deprecated objects, and comments describing past states. Finding #29 exists precisely because
a stale index reference confused this once already.

Survived → `CONFIRMED`. Still suspected, not disproved → `PLAUSIBLE`, labelled. Refuted → omit.

Where a read-only query against the live project settles it, prefer that over reading the file.

---

## 5. Output contract

Write **`review/findings-data-db.md`**. Lead with the correspondence table from §3, then findings:

```markdown
### B<n>. <one-sentence statement of the defect>

- **Severity:** P0 (schema/entity disagreement, hard delete, whole-table sync outage) | P1 (silent corruption) | P2 (bounded) | P3 (cleanup)
- **Verdict:** CONFIRMED | PLAUSIBLE
- **Where:** `path/to/File.kt:123` and/or `supabase/schema.sql:456`
- **Check that failed:** <which one from §3>
- **Concrete trigger:** <the specific row, value, or column that breaks it — not "a mismatch">
- **Blast radius:** <one row / one table's entire sync / the whole database>
- **Why it survives refutation:** <including how you confirmed you compared the live definition>
- **Suggested fix:** <shape, not a patch. Note if it needs a server-side migration, which per
  AGENTS.md §4.4 must go server-side first>
```

Order by severity. Open with a two-paragraph summary: what you covered, and the one thing you would
fix first.

State explicitly which checks came back clean, and list every entity you verified as correspondent.

---

## 6. Done when

- The correspondence sweep covers **every** entity, and the table exists in the findings file.
- Every check in §3 has a verdict.
- Every finding survived §4.
- `review/findings-data-db.md` exists and follows §5.
- You have **not** edited any file under `code/` and have run nothing destructive against Supabase.

Report the summary and counts by severity. If the sweep is incomplete, say exactly which entities were
not covered — a partial sweep honestly reported is useful; one presented as complete is worse than
none, because the next session will trust it.
