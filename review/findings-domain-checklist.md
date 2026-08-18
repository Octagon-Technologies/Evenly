# `domain/` findings — fix checklist

Working tracker for `review/findings-domain.md` (D1–D8). Grouped by the defect they actually share, not
by severity: fixing one member of a group usually moves the others, and they want the same test.

Every box is checked only once the fix is **verified** — a test that fails before it and passes after,
run on both JVM and Native (`AGENTS.md` §4.1), plus a recorded vector where the rule is one the TS port
also implements (`domain/AGENTS.md`).

---

## Group A — invalid money reaches the engine and comes back out as a negative tab (D1, D4)

Both hand someone a share `< 0`; `ShareDao`'s `remaining_subunits > 0` filters then erase the credit and
the payer reads as owed *more* than the bill. Same downstream erasure, two different producers, one
boundary to enforce them at.

- [x] **D1** (P0) — a discount larger than the item subtotal produces a negative per-person share
- [x] **D4** (P1) — a negative line total leaks a subunit in `splitEven` and hands claimants negative shares

## Group B — "is this bill finished?" is asked of money instead of units (D2, D5)

`fullyClaimed` is derived from `fullSubtotal > 0 && claimedSum >= fullSubtotal`, and a line's status is
derived without looking at whether its units are claimable at all. Both make a bill lie about being
finished, in opposite directions.

- [x] **D2** (P0) — on an all-zero-priced bill, item-less participants' EVEN tip slices are computed and dropped
- [x] **D5** (P2) — a priced line with quantity 0 reads RESOLVED and is charged to nobody

## Group C — determinism (D3)

- [x] **D3** (P1) — shared-portion iteration order decides who pays the odd cent, so two devices derive different money

## Group D — settlement (D6)

- [x] **D6** (P3) — `allocateSameCurrency`'s stated invariant is false when a share's remaining is negative

## Group E — dead code and input trust (D7, D8)

Neither is a money defect today; both are a claim the code makes that is not true.

- [x] **D7** (P3) — `itemizedShares` is a second split engine with no production caller
- [x] **D8** (P3) — the payee's payment handle is inserted verbatim into a deep link that also carries the amount

---

## Verification ledger

Every test below was written first and observed to FAIL on the pre-fix engine, except D6 (whose defect is
the KDoc, so the tests pin the contract the code already kept) and the two regression guards
(`freeZeroQuantityLine_staysResolved`, `realHandleShapesStillRideVerbatim`), which passed throughout and
are there to catch an over-broad fix.

| # | Guard | Where |
| --- | --- | --- |
| D1 | `discountExceedsSubtotal_noNegativeShare`; `createBill_rejectsADiscountBiggerThanTheItems_evenWhenATipKeepsTheTotalPositive` | `BillSplitTest`, `BillRepositoryTest`, vector `discountExceedsSubtotal_clampedToTheItemsItRidesOn` |
| D2 | `compedBill_evenTipStillSplitsAcrossParticipants`, `compedBill_withDiscount_conservesAndStaysNonNegative` | `BillSplitTest`, vectors `compedBill_evenTipStillSplitsAcrossParticipants` + `compedBill_discountHasNothingToRideOn` |
| D3 | `portions_orderIndependent_oddCent`, `portions_orderIndependent_acrossMemberSets` (all 6 / all 2 orderings); TS `shared portions produce the same money in any row order` | `BillSplitTest`, `web/test/vectors.test.ts`, vector `portions_oddCentFollowsThePortionId_notRowOrder` (recorded from the REVERSED order) |
| D4 | `negativeLineTotal_isNeutralizedNotMisSplit` | `BillSplitTest`, vector `negativeLineTotal_costsZeroAndStaysExact` |
| D5 | `pricedZeroQuantityLine_isNotResolved` + `freeZeroQuantityLine_staysResolved` | `BillSplitTest`, vector `pricedZeroQuantityLine_isNotResolved` |
| D6 | `negativeRemaining_isNotPaidDown_andDoesNotInflateThePayment`, `negativeRemaining_paymentStillCappedAtWhatIsOwed` | `ApplySettlementTest` |
| D7 | removal — no engine, no test, no vectors, no port, no dangling doc | `git` |
| D8 | `venmoHandleCannotOpenASecondQueryParameter`, `payPalHandleCannotOpenASecondPathSegment`, `realHandleShapesStillRideVerbatim` | `DeepLinkBuilderTest` |

**Verified green** (in a detached worktree, because a concurrent session had `data/remote/supabase/**`
mid-edit and red in the shared tree):

```
:shared:testAndroidHostTest      142 tests, 0 failures   (JVM — includes the vectors in verify mode)
:shared:iosSimulatorArm64Test    319 tests, 0 failures   (Kotlin/Native — AGENTS.md §4.1)
cd web && npm test                45 tests, 0 failures   (the TS half of the same vectors)
cd web && npx tsc --noEmit       clean
```

ktlint and detekt were run before/after against a HEAD-pristine tree: the change set adds **no** new
finding to either, and removes one `MaxLineLength` from `BillSplit.kt`.

## Deliberately not done

- **`toEnginePortions` takes a portion's quantity from `rows.first()`**
  (`data/repository/BillMaterializer.kt`). If a portion's rows ever disagreed on the denormalised
  `quantity`, row order would pick the winner — the same *shape* as D3 but a different question (a
  column disagreeing with itself, in `data/`, which the review scoped out). `setPortion` writes all of a
  portion's rows together, so they only disagree mid-sync and converge. Flagged, not changed.
- **Rejecting a query-syntax handle when it is SAVED** (D8's second half). The escape closes the
  defect on its own, and `PaymentHandlesRoute` (`ui/navigation/WiredScreens.kt:615`) discards the result
  of `updatePaymentHandles` and pops the screen — so a `Validation` error there would be a silent dead
  end, which `AGENTS.md` §7 forbids. Doing it properly needs a validation surface in
  `PaymentHandlesScreen`, which is a UI change this pass did not ask for.
