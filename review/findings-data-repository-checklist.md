# `data/repository/` + `data/claim/` findings — fix checklist

Working tracker for `review/findings-data-repository.md` (R1–R15). Grouped by the **defect they share**,
not by severity: within a group the fixes touch the same code and want the same test, and closing one
often moves the next.

A box is checked only once the fix is **verified** — a test written first, observed to FAIL against the old
code, and passing after; run on both JVM and Native (`AGENTS.md` §4.1). Every fix was additionally
**mutation-checked**: the fix backed out in a throwaway worktree, to confirm the intended test is the one
that catches it. The table at the bottom names both.

**Status: 15 / 15 closed.** Green on Android + iOS; 440 Native and 232 JVM tests pass, 33 of them new.

---

## Group A — a money value is written without the rule that guards it (R13 → R1, R5)

Three faces of "the validator sees less than the writer does". R13 is the precondition: the bill-total
rule had no home in `domain/`, so `BillPendingEdits` carried a copy and then never called it (R5). R1 is
the same shape one file over — `validate` never sees `currency`, the field that decides what an expense's
stored settlement allocations *mean*.

- [x] **R13** (P3) — the bill total is money arithmetic implemented twice in `data/` and nowhere in
      `domain/`. **Fix:** `domain/expense/BillTotal.kt` (`billTotalSubunits`, `billTotalProblem`), called by
      `BillRepositoryImpl.validate`, `createBill`, `editBill` and `BillPendingEdits`. The TS port needs no
      change: `tabTotal` there is the *per-person* breakdown, a different quantity, exactly as the finding
      said.
- [x] **R1** (P0) — an expense's currency can change with allocations already applied to its shares.
      **Fix:** `editExpense` refuses the change while any non-voided allocation points at one of its shares
      (`ShareDao.appliedAllocationCount`). **Owner decision taken:** *reject*, the finding's safe default;
      re-denominating or voiding somebody else's recorded payment is a product decision, not a repository
      one. Voiding the payment frees the currency again. The editor now says why rather than silently
      un-sticking Save.
- [x] **R5** (P1) — `BillPendingEdits.restore` recomputes and stores the total with no validation, so an
      undo can drive the expense negative. **Fix:** the plan is computed and validated against
      `billTotalProblem` *before* anything is written. Only a **newly** illegal total is refused, so a bill
      that arrived broken can still have its cosmetic changes taken back.

## Group B — a multi-step write an interruption can cut in half (R3, R4, R8, R9, R12)

Every one is the #15 pattern: writes that are one fact, issued from a navigation-scoped coroutine, with no
transaction. The fix is the same shape each time — one `@Transaction` DAO method, never a wider coroutine
scope (`ItemShareDao.setServings`' own comment already records why).

- [x] **R3** (P1) — `undo` stamps the log UNDONE before restoring the line, and the stamp is
      first-undo-wins. **Fix:** `PendingItemEditDao.undoAndRestore` does the stamp and the restore in one
      transaction, and the conditional `UPDATE` runs first so losing the race writes nothing at all.
- [x] **R4** (P1) — `editBill` takes a participant off a bill, then their claims, then their portions, in
      three writes. **Fix:** `BillWriteDao.applyBillEdit`. Scope widened past the finding's minimum: the
      whole edit (expense, lines, removed lines, roster) is one transaction, so it can no longer leave an
      expense whose stored total disagrees with the lines under it either.
- [x] **R8** (P2) — `createBill` writes expense / items / participants as unrelated statements.
      **Fix:** `BillWriteDao.createBill`. `materializeShares` and `recordHistory` stay outside, which is
      already correct and documented.
- [x] **R9** (P2) — `addPlaceholder` writes the `users` row and the `members` row separately.
      **Fix:** `UserDao.createPlaceholder`, mirroring `GroupDao.createGroupWithAdmin`.
- [x] **R12** (P2) — `addMemberToPastExpenses` is a per-expense loop with no overall atomicity and no
      report. **Fix:** every row is computed first, then written by one
      `ExpenseDao.applyRetroactiveMember` transaction, inside `withContext(NonCancellable)` so leaving the
      settings screen mid-sweep cannot abandon it. All or none, either way.

## Group C — durable recovery across process death, and the identity of what is parked (R2, R11)

Both are about a record that has to survive the process. R2 had none where it needed one; R11 had one that
carried no identity, so the wrong group answered for it.

- [x] **R2** (P0) — `commit` stamps the server-side "first claim wins" record before the local merge, with
      no path back. **Fix:** a `PendingClaimStore` record written *before* the RPC and cleared only once
      `reconcilePlaceholder` returns, plus `resumePending()`. A lost guard clears the park (so a resume
      never merges a name this device lost); an unreachable guard keeps it, because "the RPC committed and
      the reply was lost" strands the name identically. The merge is now wrapped, so a throw reports
      `Failed` instead of sticking on `Confirming`. **Owner decision taken:** the resume runs when a group
      screen opens, silently — it needs no new app-lifetime plumbing, and a toast about a name the user
      stopped thinking about last session is worse than none.
- [x] **R11** (P2) — the parked pass activation is a single un-scoped key. **Fix:** records are keyed by
      group (one `groupId|txnId` per line, so an existing single record decodes with no migration), and
      `hasPendingActivation`/`retryPendingActivation` take a `groupId`. Other groups' charges are retried
      in the background whenever a pass sheet opens, and never reported on a sheet that does not own them.

## Group D — a deliberate write the deriving process erases, and one it silently skips (R7, R10)

- [x] **R7** (P1) — `resolveConflict` writes a hand-computed split onto an ITEMIZED bill, which
      `rematerializeGroups` erases at the next pull. **Fix:** the sweep skips itemized bills, so no card is
      raised; `resolveConflict` also refuses one, for cards parked before this. **Owner decision taken:**
      "add member to past expenses" does not touch bills at all — adding someone to a past bill means a
      `bill_participants` row and a claim, which is a different feature, not this one done badly.
- [x] **R10** (P2) — a second `assignRemainder` with a different member set is a silent no-op that reports
      success. **Fix:** the leftover is recomputed *excluding* this line's own remainder slice, and former
      members not in the new set are dropped, the same step `setPortion` already had.

## Group E — the record is right but what it says about itself is wrong (R14, R15)

- [x] **R15** (P3) — an undo advances the split version but leaves `splitUpdatedBy` on the previous editor.
      **Fix:** `undoneBy` is threaded into the plan and stamped.
- [x] **R14** (P3) — FX conversion rounds money per share inside the repository, in floating point.
      **Fix:** `domain/fx/convertSubunits`, called **once per displayed figure**: `observeBalances` nets each
      pair in its own currency first and converts the net. **Owner decision taken:** worth the change. The
      finding marked it PLAUSIBLE on the grounds that the drift may be an accepted trade, but it is seven
      subunits on fifteen shares and its sign follows the rate, so no reader can reconcile it by hand.

## Already closed in the tree (R6)

- [x] **R6** (P1) — `editSettlement` voids before re-recording and two `Err` returns leave the void
      standing. **Already fixed** by `1ec791a`'s single `SettlementDao.replaceSettlement` transaction, which
      rolls the void back with the refusal. Verified rather than re-fixed: a new repository test drives the
      *non-crash* path the finding describes (a pulled payment landing between the pre-flight ceiling check
      and the write), which #17's own test did not cover, and it fails if the refusal stops throwing.

---

## Verification ledger

Every test below was written first and observed to FAIL, except R6's (whose fix already existed) and the
three regression guards that are there to catch an over-broad fix
(`editExpense_changesCurrencyFreelyWhileNobodyHasPaid`,
`undoingSomethingThatMovesNoMoney_worksEvenOnAnAlreadyBrokenBill`,
`assignRemainder_reassigning_leavesAFullyClaimedLineAlone`).

The **mutation** column names what was backed out, in a throwaway worktree, to confirm the guard catches it.

| # | Guard | Where | Mutation caught |
| --- | --- | --- | --- |
| R1 | `editExpense_cannotRedenominateAnExpenseSomebodyHasAlreadyPaidAgainst` (+ 3 companions) | `ExpenseRepositoryTest` | guard deleted |
| R2 | `aWonClaimWhoseMergeNeverRan_isFinishedByTheResume`, `losingTheGuard_clearsTheParkSoTheResumeNeverMergesANameSomebodyElseOwns`, `anUnreachableGuard_leavesTheClaimParkedForTheResumeToAskAgain`, `aMergeThatThrows_saysSoInsteadOfSittingOnConfirming` | `PlaceholderClaimCoordinatorTest` | `store.park` removed |
| R3 | `anUndoThatWasRefused_leavesTheChangeStillUndoable` | `BillPendingEditTest` | refusal removed (stamp spent) |
| R4 | `editBill_takesAPersonAndTheirClaimsOffInOneTransaction` | `BillRepositoryTest` | removal moved back out of the transaction |
| R5 | `undoingAChangeThatWouldDriveTheBillNegative_isRefusedRatherThanStored` | `BillPendingEditTest` | `billTotalProblem` check removed |
| R6 | `editSettlement_refusedByAPaymentThatLandedMidEdit_keepsTheOriginalPayment` | `SettlementRepositoryTest` | `SettlementReplaceRefused` throw → `return false` |
| R7 | `retroAdd_itemizedBill_raisesNoConflictBecauseTheAnswerWouldBeErasedAtTheNextPull`, `resolveConflict_onAnItemizedBill_isRefusedRatherThanWritingASplitThePullWillErase` | `ConflictFlowTest` | ITEMIZED skip disabled |
| R8 | `createBill_writesTheExpenseItsLinesAndItsRosterInOneTransaction` | `BillRepositoryTest` | back to three loose writes |
| R9 | `addPlaceholder_writesTheUserAndTheMembershipInOneTransaction` | `GroupRepositoryTest` | back to two loose writes |
| R10 | `assignRemainder_withADifferentSetTheSecondTime_reassignsInsteadOfSilentlyKeepingTheFirst`, `assignRemainder_replacingTheSetEntirely_takesTheFormerMembersOffTheLeftover` | `BillPendingEditTest` | remainder slice counted as spoken for again |
| R11 | `ParkedActivationsTest` (5 cases, incl. the legacy single-record decode) | `commonTest` | `txnFor` ignores the group |
| R12 | `retroAdd_sweepsEveryExpenseInOneTransaction` | `ConflictFlowTest` | back to a per-expense write |
| R13 | R5's and #21's guards now run through the one `domain` rule; both callers share it | `BillTotal.kt` | covered by R5's mutation |
| R14 | `observeBalances_convertsTheNettedFigureRatherThanEachShare`, `observeBalances_leavesAPairWithNoRateInItsOwnCurrency`, `ConvertTest` (3) | `ExpenseRepositoryTest`, `ConvertTest` | convert-per-share restored |
| R15 | `undoing_creditsTheUndoerWithTheSplitItJustChanged` | `BillPendingEditTest` | previous author carried forward |

**Verified green** (in a detached worktree, because a concurrent session had `data/db/**`,
`data/upload/**` and a whole feedback feature mid-edit and red in the shared tree — same reason
`findings-domain-checklist.md` gives):

```
:shared:iosSimulatorArm64Test    440 tests, 0 failures   (Kotlin/Native — AGENTS.md §4.1)
:shared:testAndroidHostTest      232 tests, 0 failures   (JVM, includes the money vectors)
:shared:compileAndroidMain       green (shared tree)
:shared:compileKotlinIosSimulatorArm64  green (shared tree)
code/iosApp/run-ios-sim.sh       built, installed, launched, left running
```

detekt reports **zero** findings in every file this change touches. Eight grandfathered findings on
`AddExpenseScreen` and `BillRepositoryImpl` had their baseline IDs shift (detekt keys on the signature, and
both gained a parameter); those eight entries were re-pointed 1:1 in
`.claude/config/detekt-baseline.xml` — no new debt was grandfathered. ktlint reports the same
`standard:kdoc` / `function-signature` shapes as the untouched files beside them, which `--format` handles
at commit time.

## Deliberately not done

- **`ShareDao`'s derived-remaining subqueries still carry no currency predicate.** This is the *mechanism*
  behind R1, and the finding scoped it out ("a schema/DAO decision rather than a repository one"). The
  repository refusal closes every path inside the app; what it does not close is a currency change arriving
  through `merge_expense` from a client that lacks the guard. Adding `sa.applied_currency = e.currency` to
  the six subqueries would close that too, and `applied_currency` is `not null` on both sides so it is
  safe — but it also desynchronises them from `SettlementDao.derivedRemainingForShare`, which cannot take
  the same predicate without an `INNER JOIN expenses` that would break for a share whose expense has not
  synced yet (there are no FKs; rows arrive in dependency-arbitrary order). Doing it properly means
  changing eight read queries that drive every balance in the app. **Flagged for whoever owns `data/db/`,
  not smuggled in here.**
- **R12 survives navigation but not process death.** `NonCancellable` plus one transaction makes the sweep
  all-or-none and immune to the reported cause (leaving the settings screen). A kill mid-transaction rolls
  it back cleanly, but nothing re-runs it, and the finding's second option — a durable progress record —
  needs an app-lifetime scope that `ui/` does not have today. The corrupting half of the finding (history
  split at an arbitrary point) is gone; the "silently did not happen" half is reduced, not eliminated.
- **A missing `@Transaction` annotation is not test-observable.** The Group B tests pin that the repository
  routes its writes through the one transactional method — which is the regression that actually happens —
  and the annotation itself is the atomicity. Removing just the annotation passes every test. Same limit
  `ItemShareDao.setServings` has lived with since #15; worth knowing before trusting a green suite here.
- **`setPortion` still makes a network call inside its write loop** (the atomicity sweep's last "No" row).
  It is not one of R1–R15 and was left alone.
- **`resolveEditConflict` can still write a currency** (`ExpenseRepositoryImpl`). It is a dead path —
  nothing writes `expense_edit_conflicts` any more, per `data/AGENTS.md` — and is due for deletion with the
  rest of that machinery rather than a guard.
