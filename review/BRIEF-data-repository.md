# Review brief — `data/repository/` + `data/claim/`

You are performing a **first-ever base review** of Evenly's repository layer — where money logic meets
persistence. Domain calculations are pure and testable; this layer is where their results get written,
in sequences that can be interrupted. Your output is a findings file, not a fix.

Read `AGENTS.md`, `code/shared/src/commonMain/kotlin/app/splitevenly/data/AGENTS.md`, and
`code/shared/src/commonMain/kotlin/app/splitevenly/domain/AGENTS.md` before you start.

---

## 0. Rules

1. **Do not fix anything.** No edits to `code/`. No commits. Parallel sessions are running.
2. **Do not review outside your scope** (§1).
3. **Verify before asserting.** Every claim comes from a command you ran.
4. Write findings to **`review/findings-data-repository.md`** and nowhere else.
5. Nothing destructive against the live Supabase project. Read-only queries only.

---

## 1. Scope

**In:**
- `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/**` — 15 files, ~3,479 lines
- `code/shared/src/commonMain/kotlin/app/splitevenly/data/claim/**` — 2 files, ~254 lines

The four largest files are where the risk concentrates: `BillRepositoryImpl.kt` (824),
`ExpenseRepositoryImpl.kt` (491), `GroupRepositoryImpl.kt` (453), `BillPendingEdits.kt` (285). Read
those exhaustively. Read the rest at least once.

**Out:** `data/remote/`, `data/db/`, `data/auth/`, `data/upload/`, `domain/`, `ui/`. Other sessions
own those. Read them to confirm a call path; report no findings there.

Existing tests are in scope as evidence — `BillRepositoryTest`, `ExpenseRepositoryTest`,
`SettlementRepositoryTest`, `GroupRepositoryTest`, `BillPendingEditTest`,
`PlaceholderClaimCoordinatorTest`, `EditConflictResolutionTest`. A test asserting wrong behaviour is
itself a finding.

---

## 2. Already known — do not report these

Read `ARCH_SECURITY_REVIEW.md` and `SECURITY_FIX_HANDOFF.md` first. Three open findings are in your
scope:

| # | What | Where |
| --- | --- | --- |
| 21 | `validate()` never checks the bill total; an oversized discount yields a negative expense | `BillRepositoryImpl.kt:787` |
| 17 | `editSettlement` voids and re-records across two transactions | `SettlementRepositoryImpl.kt:172` |
| 20 | Even-split tip slices dropped (arithmetic is in `domain/`, validation here) | `BillSplit.kt` / `BillRepositoryImpl.kt` |

Do not re-report them. **Do** report adjacent cases they miss — another unvalidated path to a bad
amount, another multi-step write with the same crash window — naming which one you are extending.

Also known and not findings: permissive RLS, destructive Room migration.

Note finding #15 (already fixed, see the tracker) concerned a sequential
zero-claims → delete-portions → write-new-portions flow being interrupted mid-teardown. **That pattern
is your best lead.** Look for other places the same shape survives.

---

## 3. Method — what this layer must guarantee

Do not read hunting for "bugs." This layer's job is to take a correct calculation and persist it
without ever leaving the database in a state that no user action could have produced. Find the
sequences where that breaks.

### Atomicity

- **Every multi-step write is one transaction.** Enumerate every method that performs more than one
  write and check each is inside a single `@Transaction` / `db.withTransaction`. List them; this is a
  mechanical sweep worth doing exhaustively.
- For each non-atomic one, state what a crash *between* the steps leaves behind, and whether the app
  can recover on next launch or the row is permanently wrong.
- Ordering matters even within a fix: for settlements, void-then-write un-pays on crash while
  write-then-void double-counts. Neither is acceptable; only atomicity is. Flag any fix-shaped comment
  that assumes reordering is enough.

### Validation completeness

- Every repository entry point that accepts user input validates it **before** anything is written.
- Validation sees **all** the inputs that affect the outcome. Finding #21 is the archetype:
  `validate(title, lines)` never receives `extras`, so the discount that makes the total negative is
  invisible to it. Look for the same shape elsewhere — a validator that takes a subset of what the
  writer uses.
- Entity invariants asserted anywhere (amounts positive, quantities positive, shares summing to the
  total) are enforced on **every** write path, including edit and merge paths, not just create.

### Interruption and lifecycle

- No repository operation depends on the caller's coroutine scope surviving. Navigating away mid-write
  must not leave a partial state.
- Widening a scope is not a fix — it does not survive process death. Flag any code or comment relying
  on it.
- Anything that tears down before rebuilding (claims, portions, shares, allocations) is a candidate
  for the #15 pattern: the transient empty state is visible to other devices if it can be pulled.

### Idempotency

- A retried write does not double-apply. Settlements, claims, and merges are the risky ones.
- Re-running a merge or a claim with the same inputs converges rather than accumulating.

### Concurrency between local actors

- Two rapid taps, or a background sync landing mid-edit, cannot interleave into a state neither
  intended.
- `BillPendingEdits` and `data/claim/` coordinate optimistic local state against arriving server
  state — check what happens when they disagree, and whether the loser is recoverable.

### Deletion safety

- Soft-delete only; no hard delete reachable from here. Tombstone written, and written in the same
  transaction as whatever else the operation changes.
- `data/AGENTS.md` Rule 9: never wipe local state ahead of the server, including in error branches.

### Domain boundary

- Money arithmetic belongs in `domain/`. Flag any arithmetic re-implemented here — a second
  implementation is a second thing to get wrong, and it will drift.
- Conversely, flag domain results that get rounded, truncated, or re-derived on the way to storage.

---

## 4. Adversarial self-check — mandatory

Before writing a finding, try to refute it. Check for a guard at the call site, a test that covers it,
or a comment documenting it as deliberate. This codebase carries load-bearing comments; the
`requestAccountDeletion()` block in `data/auth/SupabaseAuthSession.kt` is the worked example, encoding
constraints that make the obvious fix wrong.

Survived → `CONFIRMED`. Still suspected, not disproved → `PLAUSIBLE`, labelled. Refuted → omit.

Where you can settle it by execution, do: write a throwaway test in the scratchpad directory (**not**
in `code/`) and run it.

---

## 5. Output contract

Write **`review/findings-data-repository.md`**. For each finding:

```markdown
### R<n>. <one-sentence statement of the defect>

- **Severity:** P0 (money wrong / data lost) | P1 (user-visible wrong state) | P2 (bounded) | P3 (cleanup)
- **Verdict:** CONFIRMED | PLAUSIBLE
- **Where:** `path/to/File.kt:123`
- **Guarantee broken:** <which one from §3>
- **Failing sequence:** <concrete and ordered. "User edits a bill's discount to exceed the subtotal,
  taps save, app is killed after the expense row is written but before shares are rebuilt" — not
  "there is a race">
- **Resulting state:** <what is in the DB afterwards, and whether the app recovers on next launch>
- **Why it survives refutation:** <what you checked that did not save it>
- **Suggested fix:** <shape, not a patch. Note if it needs an owner decision>
```

Order by severity, worst first. Open with a two-paragraph summary: what you covered, and the one thing
you would fix first.

Include a **table of every multi-step write method** you enumerated under §3 Atomicity, with a
transactional yes/no. That table is valuable on its own even where the answer is "yes" — it is the
artifact that lets the next reviewer skip this work.

State explicitly which guarantees came back clean.

---

## 6. Done when

- The four largest files are read exhaustively; every other file in scope read at least once.
- The atomicity sweep is complete and the table exists.
- Every guarantee in §3 has a verdict.
- Every finding survived §4.
- `review/findings-data-repository.md` exists and follows §5.
- You have **not** edited any file under `code/`.

Report the summary and counts by severity. Name anything you did not get to.
