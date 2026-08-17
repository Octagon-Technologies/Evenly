# Review brief — `domain/`

You are performing a **first-ever base review** of Evenly's domain layer. Nobody has reviewed this
code as a whole before. Your output is a findings file, not a fix.

Read `AGENTS.md` and `code/shared/src/commonMain/kotlin/app/splitevenly/domain/AGENTS.md` before you
start. They bind.

---

## 0. Rules

1. **Do not fix anything.** No edits to `code/`. No commits. Fixes are a separate pass; a fix applied
   here collides with parallel sessions and skips triage.
2. **Do not review outside your scope** (§1). Other sessions own the rest.
3. **Verify before asserting.** Every claim about a line, a count, or a behaviour must come from a
   command you actually ran. A confidently wrong finding is worse than no finding.
4. Write findings to **`review/findings-domain.md`** and nowhere else.

---

## 1. Scope

**In:** every `.kt` under `code/shared/src/commonMain/kotlin/app/splitevenly/domain/` — 46 files,
~2,851 lines. Small enough to read **every file at least once**. Do that; do not sample.

**Out:** `data/`, `ui/`, `platform/`, `supabase/`, anything under `code/iosApp/`. You may *read*
outside `domain/` to confirm how something is called, but do not review it or report findings there.

Existing tests are in scope as evidence: `commonTest`, `androidHostTest`, `iosTest`. A test that
asserts wrong behaviour is itself a finding.

---

## 2. Already known — do not report these

Budget spent rediscovering these is budget wasted. Read both files first:

- **`ARCH_SECURITY_REVIEW.md`** — 15 fixed, 14 open. The open ones are already triaged.
- **`SECURITY_FIX_HANDOFF.md`** — the current triage. Two domain findings live there:
  - **#20** even-split tip slices of non-claiming participants dropped in `BillSplit.kt`
  - **#21** a bill can total zero or negative (validation lives in `data/`, the arithmetic in `domain/`)

Do not re-report those two. **Do** report anything *adjacent* that they miss — a different path to a
non-conserving split, a different way a negative amount is produced. Say explicitly which known
finding you are extending.

Also not findings: permissive RLS, destructive Room migration (both known P0s, both outside this
layer anyway).

---

## 3. Method — invariants, not vibes

Do **not** read files looking for "bugs." That produces generic slop. This layer is pure logic with no
I/O, which means its correctness is expressible as **invariants that must hold for all inputs**. Your
job is to find inputs where one breaks.

For each invariant below: find the code responsible, read it, then actively try to construct an input
that violates it. Where you can, prove the violation with a runnable test rather than by argument.

### Money conservation

- The sum of every participant's share **exactly equals** the total, in subunits, with no rounding
  leak. Check `expense/BillSplit.kt`, `expense/ItemizedAllocator.kt`,
  `settlement/Settlement.kt:allocateSameCurrency`.
- Remainder distribution (the odd cent) is **deterministic** and does not depend on map iteration
  order, participant insertion order, or claim order.
- Conservation holds under every combination of tax, gratuity, other charges, tip (both
  `TipSplitMode` values) and discount — not just one at a time. The interaction is where this breaks.
- `UNCLAIMED_BUCKET` in `BillSplit.kt` is a sentinel that is computed then dropped. Confirm nothing
  else can leak it, and that dropping it cannot take real money with it.

### Positivity and range

- No path produces a negative share, a negative total, or a negative balance where the type implies
  otherwise.
- Long overflow at extreme-but-reachable values (a large group, a large bill, a currency with 0
  decimal places where subunits == units).
- Zero-weight and empty-collection paths: zero participants, one participant, zero-quantity lines,
  a bill where every line is claimed by nobody.

### Balances and settlement

- Bilateral balances are **antisymmetric**: what A owes B is exactly the negation of what B owes A.
  See `balance/BilateralBalance.kt`.
- The sum of all balances in a group is **zero**.
- A settlement can never pay more than is owed, and applying settlements cannot produce an
  overpayment the model claims is impossible. See `balance/Overpayment.kt`,
  `settlement/Settlement.kt`.
- Currency is never mixed inside one sum, and an FX rate is applied exactly once. See `fx/`.

### Pro entitlement logic

`domain/pro/` is new (this branch) and has never been exercised against a real purchase.

- Expiry boundary: `expires_at == now` must read as **expired**, not active.
- A revoked pass is ignored on every route.
- When multiple sources grant Pro, the latest expiry wins and reports the right `source`.
- `ScanMeter` cannot be driven negative, past its cap, or reset by a clock moving backwards.

Cross-check these against `PRO_PASS_SPEC.md` §13 — the spec is authority, and a divergence is a
finding.

### Determinism and purity

- No wall-clock or randomness inside a calculation whose output is persisted or compared across
  devices. A function that returns a different answer on two devices for the same input is a sync bug
  waiting to happen.
- No hidden dependence on locale, string ordering, or platform collation.

---

## 4. Adversarial self-check — mandatory

Before a finding goes in the file, **try to refute it**. Re-read the code assuming you are wrong.
Check whether a caller already guards the input, whether a test already covers it, whether a comment
explains it as deliberate.

`BillSplit.kt` lines ~248-250 are the worked example: a comment there explains that non-claimers
deliberately do not pick up their tip slice until they claim. Behaviour that looks wrong may be
specified. **Read the comments before calling something a bug.**

Findings that survive refutation are `CONFIRMED`. Findings you still believe but could not fully
disprove are `PLAUSIBLE` — report them, labelled, and say what you could not rule out. Findings you
refuted do not appear at all.

---

## 5. Output contract

Write **`review/findings-domain.md`**. Nothing else. For each finding:

```markdown
### D<n>. <one-sentence statement of the defect>

- **Severity:** P0 (money is wrong / data is lost) | P1 (user-visible wrong behaviour) | P2 (real but bounded) | P3 (cleanup)
- **Verdict:** CONFIRMED | PLAUSIBLE
- **Where:** `path/to/File.kt:123`
- **Invariant broken:** <which one from §3>
- **Failing input:** <concrete values. "A 3-person bill, $10.00 subtotal, 7% tax, EVEN tip of $1.00,
  where Bob claims nothing" — not "certain inputs">
- **Actual vs expected:** <what it computes, what it should compute>
- **Why it survives refutation:** <what you checked that did not save it>
- **Suggested fix:** <shape, not a patch>
- **Vector:** <the test-vector case that would catch this forever, or "n/a">
```

Order the file by severity, worst first. Open with a two-paragraph summary: what you covered, and the
single thing you would fix first.

If a whole invariant class came back clean, **say so explicitly** in a short "checked and clean"
section. Silence is indistinguishable from not having looked, and the owner needs to know which is
which.

---

## 6. Turn findings into permanent guards

This layer already has vector infrastructure: `test-vectors/bill-split.json`, run in CI by
`.github/workflows/money-vectors.yml`. `test-vectors/README.md` documents the format and the record
command.

For every P0/P1 money finding, propose the **vector case** that would catch it forever, in the
`Vector:` field. Do not add them yourself — proposing is in scope, writing is the fix pass.

Prefer a vector over prose wherever the finding is a money invariant. A markdown checkbox decays; a
vector does not.

---

## 7. Done when

- Every file in `domain/` has been read at least once.
- Every invariant in §3 has a verdict — a finding, or a line in "checked and clean."
- Every finding survived §4.
- `review/findings-domain.md` exists and follows §5.
- You have **not** edited any file under `code/`.

Report back with the summary and the count by severity. If you ran out of room before finishing, say
exactly which invariants and files were not covered — an honest gap is useful, a silent one is not.
