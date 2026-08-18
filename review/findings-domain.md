# Findings — `domain/` (first base review)

**Scope covered.** All 46 `.kt` files under `code/shared/src/commonMain/kotlin/app/splitevenly/domain/`
(2,866 lines) were read once each, plus `core/Allocator.kt` (the shared primitive `domain/` calls) and the
domain tests in `commonTest` (`BillSplitTest`, `ItemizedAllocatorTest`, `ApplySettlementTest`,
`ProStatusTest`, `ScanMeterTest`, `ProFunnelTest`, `AdminTransferTest`) as evidence. Where a claim is about
arithmetic, it was proved rather than argued: `allocate`, `splitEven`, `perUnitSubunits` and `splitBill`
were transcribed statement-by-statement into a runnable model, the transcription was validated by replaying
**all 21 `BillSplitTest` cases** (all pass), and the invariants in §3 of the brief were then attacked with
directed cases and ~250k randomized bills. Every failing input below is a real output of that model, and
every line reference was re-read in the working tree. Note the tree is being edited by a concurrent session
(`BillSplit.kt` was reformatted mid-review); the line numbers are as of this read, the code is unchanged in
substance.

**Fix this first: D1.** A discount that exceeds the item subtotal while the bill total stays positive gives
the claimant a *negative* share. It survives `validate()` (which now checks only that the total is
positive — the in-flight #21 fix), it is conserved so no sum check catches it, and the `remaining > 0`
filters in `ShareDao` then erase the credit — the payer is shown as owed **$6.00 on a $5.00 bill**. It needs
no hostile input, no unusual device state and no concurrency: one voucher bigger than the food, a tip, and a
person who claimed. D2 is the same class through a different gate and should be fixed in the same pass;
both belong in `bill-split.json` before either is touched in code. Counts: **2 × P0, 2 × P1, 1 × P2,
3 × P3**.

---

### D1. A discount larger than the item subtotal produces a negative per-person share, which the balance layer silently erases

- **Severity:** P0
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/domain/expense/BillSplit.kt:105` (`TabBreakdown.totalSubunits`), fed by `:260` (`discountShares`) and `:307` (`owed`)
- **Invariant broken:** Positivity and range — "no path produces a negative share … where the type implies otherwise"
- **Failing input:** One $26.00 line, quantity 1, claimed by Bob. Participants Ada, Bob, Cy. `tipSubunits = 900` (EVEN), `discountSubunits = 3000`, no tax. Bill total = 2600 + 900 − 3000 = **500**, so `validate()` passes.
- **Actual vs expected:** Computes `owedByUser = {Bob: -100, Ada: 300, Cy: 300}` — Bob's tab is **−$1.00**. The parts sum to the bill total (500), so AC-INV-001 and every "shares sum to amount" check pass. It then goes wrong downstream: `ShareDao.observeOutstandingShares` filters `remaining_subunits > 0` (`data/db/dao/ShareDao.kt:106`), so Bob's row is dropped and the payer reads as owed **600 on a 500 bill**. Expected: either the split refuses to hand anyone a negative tab (clamp and redistribute), or the bill is rejected at entry.
- **Why it survives refutation:** (a) It is not #21 — #21 is the *bill total* going ≤ 0, and the in-flight `validate(title, lines, extras)` fix at `BillRepositoryImpl.kt:885-899` explicitly permits this bill because the tip carries the total above zero. (b) It is not caught by conservation: I swept ~30k randomized fully-resolved bills across every extras combination and found **zero** sum violations but **11 negative-share bills**, all of this shape. (c) No caller guards it: `BillMaterializer.materialize` (`data/repository/BillMaterializer.kt:62-79`) writes whatever `splitBill` returns with no sign check. (d) `Overpayment` does not cover it — that signal requires two equal payments between a pair, and here no payment exists. (e) My first refutation attempt (share ≤ items whenever discount ≤ subtotal) is sound, and is exactly why the bug needs `discount > subtotal`, which the total check no longer forbids.
- **Suggested fix:** Decide the rule once, at the engine boundary: a bill-level discount may not exceed the item subtotal it is proportional to. Either cap `discountSubunits` at `fullSubtotal` inside `splitBill` (and surface the excess), or reject `discount > Σ line totals` in `validate` alongside the existing total check. Do not clamp per-person after the fact — that breaks conservation, which currently holds.
- **Vector:** `discountExceedsSubtotal_noNegativeShare` — items `[{dinner, 2600, qty 1}]`, claim `{dinner, bob, 1}`, participants `[ada, bob, cy]`, extras `{tip: 900, EVEN, discount: 3000}`; assert every `owedByUser` value `>= 0` and the sum equals 500.

---

### D2. On a bill whose lines are all zero-priced, item-less participants' EVEN tip slices are computed and dropped

- **Severity:** P0
- **Verdict:** CONFIRMED
- **Where:** `BillSplit.kt:285` (`val fullyClaimed = fullSubtotal > 0L && …`) and `:253` (`if (fullSubtotal <= 0L)`)
- **Invariant broken:** Money conservation — a fully resolved bill's shares must sum to `amount_subunits`
- **Failing input:** Two comped lines (`lineTotalSubunits = 0`, quantity 1 each), Alice claims both, participants `[Alice, Bob, Carol]`, `tipSubunits = 3000` (EVEN). Both lines read RESOLVED. `validate()` passes: every line total is `>= 0` (only `< 0` is rejected) and the bill total is 3000.
- **Actual vs expected:** Computes `owedByUser = {Alice: 1000}`; sum **1000** against an `amount_subunits` of **3000**. Bob's and Carol's $10 tip slices are allocated at `:273` and then discarded, because `fullyClaimed` requires `fullSubtotal > 0L`. Expected: 1000 each, summing to 3000. Add a discount to the same bill (`tip = 500, discount = 300`, total 200) and it also goes negative: `{Alice: -133}`, sum −133 on a +200 bill — the `fullSubtotal <= 0L` branch at `:253` splits the discount evenly among *claimants only*, so the one person who claimed absorbs 100% of a discount meant for the whole table.
- **Why it survives refutation:** This is an **extension of #20**, not a re-report of it. The working tree already carries the #20 fix (the `tipOnly` block at `:277-305`) and its vector `evenTip_participantWithNoItems_stillOwesTheirSlice`; both are gated on `fullSubtotal > 0L`, so a zero-subtotal bill falls straight back into the old dropped-slice behaviour. The existing vector `allFreeBill_extrasSplitEvenlyAmongClaimants` pins the same branch but with tax only, tip 0 and no non-claiming participants, so it passes either way. The comment at `:277-284` distinguishes "not yet claimed" from "genuinely item-less" — a comped bill is unambiguously the second case, so this is not the specified behaviour that comment protects.
- **Suggested fix:** Make "fully claimed" a statement about *units*, not money: the bill is fully claimed when every `ItemReconcile` is `RESOLVED`, which is true for a comped bill and false for a half-claimed one. That single change fixes both halves and removes the coupling between "is this bill finished" and "does it cost anything".
- **Vector:** `compedBill_evenTipStillSplitsAcrossParticipants` — items `[{comped-a, 0, 1}, {comped-b, 0, 1}]`, claims both to `a`, participants `[a, b, c]`, extras `{tip: 3000, EVEN}`; assert `owedByUser` sums to 3000 with 1000 each.

---

### D3. Shared-portion iteration order decides who pays the odd cent, so two devices derive different money for the same bill

- **Severity:** P1
- **Verdict:** CONFIRMED
- **Where:** `BillSplit.kt:185` (portions built in caller order) and `:221-225` (portions consume `unitCosts` in that order); contrast the deliberate `sortedBy { it.key.value }` at `:216`
- **Invariant broken:** "Remainder distribution is deterministic and does not depend on map iteration order, participant insertion order, or claim order"; and determinism — "a function that returns a different answer on two devices for the same input"
- **Failing input:** One $10.00 line over 3 units (`lineTotalSubunits = 1000, quantity = 3`, so `splitEven` = `[334, 333, 333]`) sliced per serving: portion `p1` = 1 unit for Alice, `p2` = 1 unit for Bob, `p3` = 1 unit for Carol — exactly what `BillRepository.setServings` writes for "who had what?" on a 3-portion line.
- **Actual vs expected:** Whoever's portion appears first in the list pays 334; the other two pay 333. All 6 permutations were run: `{alice:334,bob:333,carol:333}`, `{bob:334,…}`, `{carol:334,…}`. The order is whatever `ItemShareDao.observeByExpense` / `getByExpense` returns — `SELECT * FROM item_shares WHERE expense_id = ? AND deleted_at IS NULL` (`data/db/dao/ItemShareDao.kt:24,28`) with **no `ORDER BY`** — grouped without sorting by `toEnginePortions` (`data/repository/BillMaterializer.kt:122-125`). Row order differs between a device that authored the portions and a device that pulled them, so `BillMaterializer` writes different `shares` on each. Expected: the same input rows produce the same money everywhere, as `BillMaterializer`'s own doc promises ("every device instead re-derives from the synced source rows … so all devices converge (P0 #3)").
- **Why it survives refutation:** (a) It is not theoretical — 311 of 6,000 randomized bills (≈5%) changed at least one person's total under a portion reshuffle, while shuffling claims, participants and items changed **nothing** (0/6,000 each). (b) `allocate` sorts internally and individual claims are sorted at `:216`, i.e. the codebase already treats order-independence as required here; portions are the one path that was missed. (c) No caller sorts: neither `toSharedPortions` (`BillRepositoryImpl.kt:934-937`) nor `toEnginePortions` orders the groups. (d) The TS port has the identical structure (`web/src/lib/money/billSplit.ts:220-263`), so the vectors cannot catch it — both engines are order-sensitive in the same way and the recorded case fixes one order.
- **Suggested fix:** Sort the effective portions by a stable key before consuming `unitCosts` — `portionId` is the natural one (it is deterministic per slice) with the member list as a tiebreak for legacy rows. Per `domain/AGENTS.md` this must land in `web/src/lib/money/billSplit.ts` in the same commit.
- **Vector:** `portions_orderIndependent_oddCent` — one line `{1000, qty 3}` with three single-unit portions, recorded twice with the portion array in two different orders; both must produce the same `owedByUser`. (A vector alone only pins one order — pair it with a test that feeds a shuffled list.)

---

### D4. A negative line total makes the largest-remainder split leak a subunit and hands claimants negative shares

- **Severity:** P1
- **Verdict:** CONFIRMED
- **Where:** `BillSplit.kt:334-341` (`splitEven`) — `extra` goes negative and `it < extra` is then never true; the same shape in `core/Allocator.kt:22-29` (outside this scope, same defect)
- **Invariant broken:** Money conservation; positivity and range
- **Failing input:** `splitEven(-1000, 3)` → `[-333, -333, -333]`, summing to **−999**, not −1000. End to end: items `[{credit, -500, qty 3}, {mains, 5000, qty 1}]`, one unit of `credit` claimed by each of Alice/Bob/Carol, `mains` by Alice.
- **Actual vs expected:** `owedByUser = {alice: 4834, bob: -166, carol: -166}` summing to **4502** against an `amount_subunits` of **4500** — two subunits materialize out of nothing, and two people hold negative shares that `ShareDao`'s `remaining > 0` filters then erase (same downstream erasure as D1). Expected: either the engine refuses a negative line total, or the split is exact for it.
- **Why it survives refutation:** (a) The app's own editor cannot produce it — `validate` rejects `lineTotal < 0L` (`BillRepositoryImpl.kt:895`) — but the **web guest path is unguarded end to end**: `actionEdit` passes `body.unitPriceSubunits` straight through with no sign check (`supabase/functions/web-claim/index.ts:741`), `apply_web_bill_edit` computes `v_line_total := p_unit_price_subunits * v_quantity` with no clamp (`supabase/schema.sql`, REPRICE branch), and `expense_items.line_total_subunits` carries no `CHECK`. Anyone holding the bill's QR link can therefore write it. (b) This **extends #21** rather than repeating it: #21 is the bill total going negative through `discountSubunits`; this is a *line* total, a different producer, and it breaks conservation as well as positivity. (c) No test covers a negative amount anywhere in `AllocatorTest` or `BillSplitTest`.
- **Suggested fix:** State the precondition and enforce it at the engine boundary — `splitBill` should treat a negative `lineTotalSubunits` as invalid input rather than silently mis-splitting it. (The server-side clamp is a separate fix in `supabase/`, out of this review's scope, and is the one that actually closes the door.)
- **Vector:** `negativeLineTotal_isRejectedOrExact` — a `{-1000, qty 3}` line; assert the per-unit slices sum to the line total exactly (or that the engine rejects the bill), and that no `owedByUser` value is negative.

---

### D5. A priced line with quantity 0 reads RESOLVED and is charged to nobody

- **Severity:** P2
- **Verdict:** PLAUSIBLE
- **Where:** `BillSplit.kt:214` (`splitEven` returns an empty list for `parts <= 0`) and `:230-234` (status)
- **Invariant broken:** Money conservation; zero-weight and empty-collection paths
- **Failing input:** items `[{ghost, 2500, quantity 0}, {beer, 1000, quantity 1}]`, `beer` claimed by Alice.
- **Actual vs expected:** `owedByUser = {alice: 1000}`, both lines `RESOLVED`, `fullyResolved == true` — against an `amount_subunits` of **3500**. $25.00 disappears from a bill the UI reports as finished, and the "N dishes still need someone" nudge never fires. Expected: a priced line nobody can claim must not read as resolved.
- **Why it survives refutation:** The existing vector `zeroQuantityLine_resolvesAndChargesNobody` pins only the **zero-priced** variant (`lineTotalSubunits: 0`), i.e. the divide-by-zero guard, not this one — so the behaviour is not specified, it is untested. What keeps this at PLAUSIBLE is reachability: `validate` rejects `qty <= 0` (`BillRepositoryImpl.kt:895`) and the server clamps with `greatest(coalesce(p_quantity, 1), 1)` on both ADD and REQUANTITY, so no writer I could find produces one today. What I could not rule out: `expense_items.quantity` has **no `CHECK` constraint** (`supabase/schema.sql:229-242`) and rows arrive from sync, so any other client, an older build, or a manual row makes this silently true — and it is the only path in this file where money vanishes while the bill claims to be finished.
- **Suggested fix:** Treat `quantity <= 0` with a non-zero line total as `UNCLAIMED` rather than `RESOLVED`, so the money stays visible and the nudge fires. (A `CHECK (quantity > 0)` server-side is the belt to this braces, in `supabase/`.)
- **Vector:** `pricedZeroQuantityLine_isNotResolved` — the input above; assert the `ghost` line's status is `UNCLAIMED` and `fullyResolved` is false.

---

### D6. `allocateSameCurrency`'s stated invariant is false when a share's remaining is negative

- **Severity:** P3
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/domain/settlement/Settlement.kt:31` (the documented invariant) vs `:49-53` (the code)
- **Invariant broken:** "A settlement can never pay more than is owed"
- **Failing input:** `shares = [ShareBalance("s1", USD, -500), ShareBalance("s2", USD, 1000)]`, `paymentAmountSubunits = 1000`.
- **Actual vs expected:** Returns one allocation of 1000. The doc states `sum(appliedSubunits) == min(payment, sum(remainingSubunits))`, which here is `min(1000, 500) = 500`. The negative share (a credit — the state `Overpayment.kt` exists to describe) is skipped by `if (applied > 0L)` rather than netted, so the payment covers more than the set actually owes.
- **Why it survives refutation:** Every production caller filters first — all three outstanding queries in `ShareDao` end `WHERE remaining_subunits > 0` — so this is latent, which is why it is P3 and not higher. It is still a real defect: the invariant is written as unconditional in the KDoc and `ApplySettlementTest` pins only non-negative inputs (`zeroRemainingShares_produceNoAllocation`, `paymentExceedingTotalRemaining_cappedAtSumRemaining`), so the next caller to pass a raw share set inherits a silent overpayment.
- **Suggested fix:** Either narrow the doc to "assumes every share's remaining is `>= 0`, which callers guarantee", or net negatives into the running total so the stated invariant is true for any input. Pick one; do not leave the KDoc asserting something the function does not do.
- **Vector:** n/a (not a `bill-split.json` shape — a `commonTest` case in `ApplySettlementTest` is the right guard).

---

### D7. `itemizedShares` is a second split engine with no production caller, kept alive only by the vectors, and it throws when every subtotal is zero

- **Severity:** P3
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/domain/expense/ItemizedAllocator.kt:6-13`
- **Invariant broken:** none directly — this is the "two implementations of one rule" hazard `domain/AGENTS.md` warns about, plus an unguarded `require`
- **Failing input:** `itemizedShares(subtotals = [(a, 0), (b, 0)], taxSubunits = 100, tipSubunits = 0, PROPORTIONAL)` → `allocate` hits `require(totalWeight > 0)` and throws `IllegalArgumentException`. `splitBill` handles the same input (its `fullSubtotal <= 0L` branch at `:253` exists precisely for it).
- **Actual vs expected:** A grep of `code/shared/src` finds callers only in `ItemizedAllocatorTest`, `BillSplitVectorsTest` and the TS port's `index.ts` re-export (unused by any `web/src` page). It is nonetheless pinned by 5 cases in `bill-split.json` and a hand-port in `web/src/lib/money/itemizedShares.ts`, so it reads as live, load-bearing money code. It also predates the extras model — no gratuity, no other charges, no discount, no unclaimed bucket — so anyone who reaches for it gets a different answer from `splitBill`.
- **Why it survives refutation:** I looked specifically for a live caller (repositories, UI routes, sync) and there is none; the vectors are what keep it compiling and green, which is the trap — the guard is protecting code the app does not run while implying it does.
- **Suggested fix:** Delete it with its vectors, its test and its TS port in one commit, or add a one-line KDoc saying it is retained only as the vector-pinned reference implementation and is not on any live path. Either is fine; the current silence is not.
- **Vector:** n/a (the fix removes vectors rather than adding one).

---

### D8. The payee's payment handle is inserted verbatim into a deep link whose query string also carries the amount

- **Severity:** P3
- **Verdict:** PLAUSIBLE
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/domain/settlement/DeepLinkBuilder.kt:41-45`
- **Invariant broken:** none from §3 — reported as an input-trust defect adjacent to the money path
- **Failing input:** A member whose Venmo handle is stored as `bob&amount=99.99`. `buildDeepLink(VENMO, handle, 500, …)` yields `venmo://paycharge?txn=pay&recipients=bob&amount=99.99&amount=5.00&note=…`.
- **Actual vs expected:** Which `amount` the Venmo app honours is its parser's choice, not ours; the pre-filled figure can differ from the $5.00 Evenly recorded. The same applies to `?` or `#` in a handle for the Cash App and PayPal path templates. Expected: the handle is escaped, or validated on entry, so it cannot contribute query syntax.
- **Why it survives refutation:** The KDoc says "handles are inserted verbatim per the templates (§5.1)" and the spec's URL templates are normative, so this is a deliberate transcription — that is the strongest argument against reporting it. It survives because "verbatim" was a statement about *template shape*, not a decision to let a user-supplied string extend the query, and nothing else in the chain re-checks: `ProfileRepository.updatePaymentHandles` takes free text, and the payer never sees the URL. The blast radius is small (the payer still confirms in the payment app, and the payee is a group member, not a stranger), which is why it is P3 and PLAUSIBLE rather than higher. I could not rule out a plain typo producing a malformed link with no error shown.
- **Suggested fix:** Percent-encode the handle with the existing `percentEncode` for the query-parameter case (Venmo), and strip/reject query-syntax characters when a handle is saved. Leave the path-segment templates (Cash App, PayPal) alone unless the spec is revisited.
- **Vector:** n/a.

---

## Checked and clean

Everything below was actively attacked and did **not** yield a finding. Listed so the absence of a finding
reads as a result rather than as an omission.

**Money conservation under combined extras.** ~30,000 randomized fully-resolved bills (1–3 lines, 1–4
people, mixed individual claims and multi-member shared portions, every combination of tax / gratuity /
other charges / discount / tip in both `TipSplitMode` values, bills whose total `<= 0` discarded as
`validate`-rejected) produced **zero** cases where `Σ owedByUser != amount_subunits`. The interaction of the
extras is sound; the failures found (D1, D2, D4) are about sign and about which people get rows, not about
the totals drifting.

**`UNCLAIMED_BUCKET` cannot leak or steal.** The sentinel is stripped by the `- UNCLAIMED_BUCKET` at
`BillSplit.kt:255`, and its sentinel id (a NUL byte followed by `unclaimed`, `BillSplit.kt:318`) sorts before every real id, so the worry is the
opposite one: that a *zero-weight* bucket wins a remainder subunit that is then dropped. It cannot — in
largest-remainder the number of entries with a non-zero fractional part always exceeds the remainder count,
so a zero-frac entry never receives one. Verified over 200,000 randomized weight sets: 0 occurrences.
Dropping the bucket therefore never takes real money with it.

**Remainder determinism, other than portions.** Shuffling the claim list, the participant list and the item
list changed no one's money in 6,000 randomized bills each (0/6,000). `allocate` sorts its own weights and
tiebreaks on `UserId.value`; individual claims are sorted at `BillSplit.kt:216`. Shared portions are the
single exception — D3.

**`allocate`'s `require(totalWeight > 0)` is unreachable from `splitBill`.** The weight list at
`BillSplit.kt:255` totals `claimedSum + max(fullSubtotal − claimedSum, 0)`, i.e. `max(fullSubtotal,
claimedSum)`, and the branch is only entered when `fullSubtotal > 0`. The `fullSubtotal <= 0` branch uses
weights of 1 over a list already proven non-empty at `:240`. No divide-by-zero and no `IllegalArgumentException`
on any input I could construct.

**Zero and empty collections.** Zero participants with a tip, one participant, nothing claimed at all,
zero-priced lines, negative claim units, and a bill where every line is claimed by nobody all return without
throwing and with sensible statuses. The one crash I found — `IndexOutOfBoundsException` at `BillSplit.kt:224`
when one portion's quantity exceeds the line while a sibling portion carries a *negative* quantity — needs a
negative `item_shares.quantity`, which `setPortion` refuses (`quantity <= 0` removes the portion) and the
server never writes. Not reported as a finding; noted here because a `CHECK (quantity > 0)` would make it
impossible rather than merely unreached.

**Long overflow.** `allocate` computes `totalSubunits * weight`, which needs a product above 9.22e18 to
overflow. That takes roughly a 3-billion-subunit amount against a 3-billion-subunit weight simultaneously —
about 30 million units in a 2-decimal currency, or 3 billion units in a 0-decimal one like IDR/VND. A
1-billion-IDR bill with 1-billion-IDR weights reaches 1e18, still an order of magnitude below the limit.
Not reachable for any bill a group of friends can enter. `PassTier.expiryIfBought`'s `tier.days * DAY_MILLIS`
is `Int * Long`, no overflow.

**Balances antisymmetry and zero-sum.** `buildBilateralBalances` nets each `(currency, payer, participant)`
direction against its reverse and marks both `handled`, so no pair is emitted twice and `A→B` is exactly
`−(B→A)` by construction; a `net == 0` pair emits nothing, and a self-directed share (payer == participant)
cancels with itself. Balances never cross currencies — the key is a `Triple` including currency. The
`remainingSubunits <= 0` skip at `BilateralBalance.kt:30` looks like it erases credits, but the shares
arriving there are already filtered `> 0` by `ShareDao.observeOutstandingShares`, and the erasure of negative
remainders is the deliberate design that `Overpayment.kt` exists to compensate for (documented in
`data/AGENTS.md`). Refuted as a domain finding — but note D1 and D4 are what *create* the negative shares
that design assumes only arise from double payments.

**Currency mixing and FX.** `allocateSameCurrency` rejects a mixed-currency share set outright;
`buildBilateralBalances` keys per currency; `UnclaimedName` and `ClaimPreview` both carry an explicit
`multiCurrency` / null-currency escape rather than summing. There is **no FX arithmetic in `domain/` at all** —
`fx/` is types only (`FxResult`, `rateOrNull`, `isStale`, a fallback currency list). "A rate is applied
exactly once" is therefore a `data/` question (`ExpenseRepositoryImpl.convertToBase`), out of scope here.

**Pro entitlement (`domain/pro/`), against `PRO_PASS_SPEC.md` §13.** `proStatusOf` filters
`revokedAt == null && expiresAt > now`, so `expires_at == now` reads as **expired** (§13's boundary, pinned by
`ProStatusTest.expiryBoundaryIsStrict`); a revoked pass is ignored on both routes and does not hide a live
one; `maxByOrNull { it.expiresAt }` makes latest-expiry-wins fall out for both routes with the correct
`source`, matching §5.3 and §5.4's stacking rule. `ScanMeter` cannot go negative (`(limit - used)
.coerceAtLeast(0)`), cannot exceed its cap in anything rendered (`ScanQuotaMeter` prints only `remaining` and
`limit`, and guards `limit <= 0`), and has no clock at all, so no clock movement can reset it —
`PassTier.expiryIfBought` uses `maxOf(now, currentExpiresAt ?: now)` and is display-only anyway, with the real
expiry stamped server-side. No divergence from §13 found.

**Determinism and purity.** No wall clock, no randomness and no locale-sensitive operation exists anywhere in
`domain/` — `now` is always a parameter (`proStatusOf`, `WebBillLinkState.isLive`, `expiryIfBought`). The only
case-mapping call is `uppercase()` on hex digits in `percentEncode` (`DeepLinkBuilder.kt:82`), which is
locale-independent in Kotlin and operates on `0-9a-f` regardless. All ordering is by `UserId.value` /
`packageId` string comparison, which is UTF-16 code-unit ordering and identical on JVM and Native — no
collation dependency.

**Also looked at and not reported.** `formatUsd` (`DeepLinkBuilder.kt:58-62`) renders a negative amount as
`"0.-50"`, but `Debt.amountSubunits` is strictly positive by construction and no caller can reach it — noted,
not filed. `PendingBillEdit.proposedLineTotalSubunits` rebuilds a line total from the *rounded* per-unit,
the thing `data/AGENTS.md` warns about — but it matches `apply_web_bill_edit`'s own formula branch for branch,
and the value is only ever rendered as a delta on the "what changed" screen (`ui/navigation/WebClaimRoutes.kt:132`),
never used to restore a line. `PassOffer.bestValueOffer` breaks a cents-per-day tie by list order; with three
tiers at distinct prices this cannot bite today.

## Limits of this review

- Everything under `data/`, `ui/`, `platform/`, `supabase/` and `code/iosApp/` was read only to establish how
  `domain/` is called, and is reported on only where it is the reachability path for a domain defect
  (D1, D3, D4, D5).
- The arithmetic proofs come from a transcription of the Kotlin validated against all 21 `BillSplitTest`
  cases, not from running Kotlin — there is no `kotlinc` on this machine and the brief forbids adding a test
  under `code/`. Every finding is stated with the exact input needed to reproduce it in `BillSplitTest`, and
  D1–D4 should be re-confirmed there as part of the fix pass.
- A concurrent session landed the #20 and #21 fixes as `7a2740b` while this review was running. Every line
  reference above was re-verified against that commit and is current. D2 in particular is a hole *in* that
  #20 fix — the new `fullyClaimed` gate — and should go to whoever owns it.
- The same session is editing `domain/auth/AuthSession.kt` (the #24 sign-out wipe). Its addition is
  declarative only — `signOut(discardUnsynced)` and a `SignOutOutcome` sealed interface, no logic — so it
  carries none of the §3 invariants and is not assessed here.
