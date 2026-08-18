# Review brief — `data/remote/` + `data/auth/`

You are performing a **first-ever base review** of Evenly's sync and session code. This is the
highest-risk code in the repository: everything here can lose or leak user data, and most of its
failure modes are silent. Your output is a findings file, not a fix.

Read `AGENTS.md` and `code/shared/src/commonMain/kotlin/app/splitevenly/data/AGENTS.md` before you
start. `data/AGENTS.md` is long and it binds — its data-safety rules are the specification you are
reviewing against.

---

## 0. Rules

1. **Do not fix anything.** No edits to `code/`. No commits. Parallel sessions are running.
2. **Do not review outside your scope** (§1).
3. **Verify before asserting.** Every line reference, count, or behavioural claim comes from a command
   you ran. Never assert a hazard you have not confirmed in this tree.
4. Write findings to **`review/findings-data-sync.md`** and nowhere else.
5. **Never run anything destructive against the live Supabase project.** Read-only queries are fine.
   No `DROP`, no `TRUNCATE`, no `DELETE`, no migration. If you want to test schema behaviour, ask the
   owner for a branch rather than touching the live project.

---

## 1. Scope

**In:**
- `code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/**` — 19 files, ~1,771 lines
- `code/shared/src/commonMain/kotlin/app/splitevenly/data/auth/**` — 2 files, ~466 lines
- The `androidMain` / `iosMain` `actual`s for anything in the above (3 files, ~64 lines)

**Out:** `data/repository/`, `data/db/`, `data/upload/`, `data/claim/`, `domain/`, `ui/`. Other
sessions own those. You may *read* them to confirm a call path; do not report findings there.

`supabase/schema.sql` is **reference, not scope** — you read it to check the client agrees with the
server, and a disagreement is a finding *against the client code you own*.

---

## 2. Already known — do not report these

Read `ARCH_SECURITY_REVIEW.md` and `SECURITY_FIX_HANDOFF.md` first. Five open findings are in your
scope and are already triaged:

| # | What | Where |
| --- | --- | --- |
| 22 | `selectIn` builds one URL with every id; dies at ~800-1000 expenses | `SyncEngine.kt:436` |
| 24 | `signOut()` clears no Room state; account A pushes under account B | `SupabaseAuthSession.kt:235` |
| 25 | Every failure maps to `Network.Unreachable` | `SyncEngine.kt:443` |
| 23 | `expense_edit_conflicts` still in `SYNC_TABLES` (partly intentional) | `SyncManager.kt:164` |
| 19 | Client clocks unbounded in `merge_expense` (server-side, but client feeds it) | `schema.sql:880,986` |

Do not re-report them. **Do** report anything adjacent they miss, naming which one you are extending.

Also not findings, all known and tracked: permissive RLS app-wide, destructive Room migration, and
the deliberate one-table realtime doorbell (`group_activity` only — adding app tables back is
forbidden by `AGENTS.md` §4.3, so *proposing* it is itself an error).

---

## 3. Method — the properties sync must satisfy

Do not read files hunting for "bugs." Sync correctness is a set of properties; find inputs or
interleavings that break one.

### No lost writes

- A local write made while offline survives: app backgrounded, process death, sign-out/in, a failed
  push, a failed pull that lands between the write and its push.
- The dirty-tracking state machine (`row_sync_state`, `expense_sync_state`) cannot mark a row clean
  before the server has durably accepted it.
- A push that fails partway cannot leave some rows marked clean and others dirty with no retry.

### Convergence

- Two devices applying the same set of operations in different orders reach the same state.
- A pull that arrives mid-push cannot clobber the in-flight local write.
- Full-row upsert semantics: **a column the server lacks breaks all sync for that table**
  (`AGENTS.md` §4.4). Verify every entity the client upserts has every column present server-side in
  `supabase/schema.sql`. This is mechanical and worth doing exhaustively — it is a whole-table outage,
  not a row bug.

### Deletion safety

- Soft-delete only. No hard delete, no `clearAllTables()`, no `TRUNCATE` reachable from any path here.
- Tombstones actually reach the server. A tombstone that fails to sync means the deletion resurrects
  on the next pull — verify the failure path, not just the happy one.
- `data/AGENTS.md` Rule 9: never wipe local state ahead of the server. Check every path that clears
  anything, including error branches.

### Identity and session

- No path can push data authored under session A using session B's credentials (finding #24 is one
  instance; look for others — token refresh, session restore, a stale client captured in a closure).
- Session restore on cold start cannot transiently expose or push another account's rows.
- Auth state changes are observed, not polled into a race.

### Failure reporting

- A permanent failure is distinguishable from a transient one (this is finding #25's subject; report
  *additional* places the same conflation happens, e.g. in the RevenueCat or upload paths).
- No `catch (e: Exception)` that swallows `CancellationException` — that breaks structured concurrency
  and turns a cancelled scope into a silent success.

### Concurrency

- Coroutine scope lifetimes: work that must survive navigation is not tied to a composable's scope.
  Note that widening a scope does **not** survive process death — a fix that only widens is not a fix.
- Shared mutable state across the sync loop, realtime doorbell, and foreground gating.
- The realtime doorbell path (`SyncManager`): a burst of doorbell messages cannot stampede into
  overlapping syncs.

### KMP parity

Per `AGENTS.md` §4.1, Android-green is not green. Flag any JVM-only API reachable from `commonMain`.
`RoomDatabase.clearAllTables()` is the known example; there are others.

---

## 4. Adversarial self-check — mandatory

Before writing a finding, try to refute it. Check whether a caller guards it, a test covers it, or a
comment documents it as deliberate. This codebase has a lot of load-bearing comments — the
`requestAccountDeletion()` block in `SupabaseAuthSession.kt` is the worked example, encoding two
non-obvious constraints that make the "obvious" fix wrong.

Survived refutation → `CONFIRMED`. Still suspected, not disproved → `PLAUSIBLE`, labelled, with what
you could not rule out. Refuted → does not appear.

For anything you can settle by execution rather than argument, do that instead: a read-only query
against the live project, or a `commonTest` you write in the scratchpad (not in `code/`) and run.

---

## 5. Output contract

Write **`review/findings-data-sync.md`**. For each finding:

```markdown
### S<n>. <one-sentence statement of the defect>

- **Severity:** P0 (data loss / leak across accounts) | P1 (silent divergence or wedge) | P2 (bounded) | P3 (cleanup)
- **Verdict:** CONFIRMED | PLAUSIBLE
- **Where:** `path/to/File.kt:123`
- **Property broken:** <which one from §3>
- **Failing sequence:** <a concrete ordered interleaving. "Device A writes offline → B deletes the
  same expense → A comes online and pushes before pulling" — not "a race condition">
- **Consequence:** <what the user loses or sees, and whether it is recoverable>
- **Why it survives refutation:** <what you checked that did not save it>
- **Suggested fix:** <shape, not a patch. Note if it needs an owner decision>
```

Order by severity, worst first. Open with a two-paragraph summary: what you covered, and the one
thing you would fix first.

State explicitly which properties in §3 came back **clean**. Silence reads as "not looked at."

Call out separately any finding that is **unrecoverable once it happens** (data loss, cross-account
leak). Those are a different class from bugs that produce a bad screen, and the owner triages them
differently.

---

## 6. Done when

- Every file in scope has been read at least once.
- The full-row-upsert column check (§3, Convergence) has been done exhaustively against
  `supabase/schema.sql`, and you can state which tables you verified.
- Every property in §3 has a verdict.
- Every finding survived §4.
- `review/findings-data-sync.md` exists and follows §5.
- You have **not** edited any file under `code/` and have run nothing destructive against Supabase.

Report the summary and counts by severity. Name any file or property you did not get to — an honest
gap is useful, a silent one is not.
