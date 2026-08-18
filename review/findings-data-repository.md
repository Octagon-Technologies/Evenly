# Findings — `data/repository/` + `data/claim/`

**Base review, 2026-08-15.** Working tree on `fix/security-handoff`. All line references were read from
the tree as it stands, not copied from a tracker.

## Summary

I read all 15 files in `data/repository/` and both in `data/claim/` — the four largest
(`BillRepositoryImpl.kt` 833, `ExpenseRepositoryImpl.kt` 491, `GroupRepositoryImpl.kt` 453,
`BillPendingEdits.kt` 285) line by line, the rest at least once — and enumerated every method that
performs more than one write (table in §Atomicity sweep below: **31 write methods, 20 of them
multi-step, 6 of those atomic**). I confirmed call paths into `data/db/dao/`, `data/remote/supabase/`
and the `ui/navigation/` Route wrappers to settle whether a partial state self-heals, and read
`supabase/schema.sql`'s `claim_placeholder` to settle whether a half-applied claim is recoverable. I
did **not** re-report #21 (already fixed in this tree — `validate` takes `extras` at
`BillRepositoryImpl.kt:795` and `BillRepositoryTest` pins it), #17, or #20; three findings below
explicitly extend #21 and #17 into paths they do not cover. Nothing under `code/` was edited.

**Fix R1 first.** It needs no crash, no race and no concurrency: an ordinary edit of an expense's
currency in the expense editor silently re-denominates every payment already recorded against it, so a
$25 payment reads as €25 paid. It is one `validate()` clause away from being closed, it is the only
finding here that is wrong money on the happy path, and every other finding on the list requires an
interruption or a second actor to bite. R2 is the one to fix second — it is unrecoverable and its
trigger (background the app right after confirming a name claim) is the moment the OS is *most* likely
to kill the process.

Counts: **2 P0, 5 P1, 5 P2, 3 P3** — 15 findings, 13 CONFIRMED, 2 PLAUSIBLE.

---

### R1. `editExpense` lets an expense's currency change with settlement allocations already applied to its shares, and the derived `remaining` subtracts those allocations without regard to currency

- **Severity:** P0 (money wrong)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/ExpenseRepositoryImpl.kt:477` (`validate`), applied at `:277-297`
- **Guarantee broken:** Validation completeness — "entity invariants asserted anywhere are enforced on **every** write path". This is the #21 shape (a validator that sees a subset of what the writer uses) in a different file: `validate(title, amountSubunits, shares)` never receives `currency`, yet `currency` is written at `:282` and is the field that decides what the stored allocations mean.
- **Failing sequence:**
  1. Group has an expense "Dinner" for `5000` subunits, currency `USD`. Bob's share is `2500`.
  2. Bob pays. `SettlementRepositoryImpl.applySettlement` writes a `settlement_allocations` row for Bob's share: `applied_amount_subunits = 2500`, `applied_currency = "USD"`. Bob's derived remaining is 0.
  3. The payer reopens the expense editor (`LedgerRoutes.kt:495`, `editing = true`), taps the currency chip, picks `EUR` (`AddExpenseScreen.kt:389` — `CurrencySheet` is offered unconditionally, edit mode included) and saves. `submit.currency` reaches `EditExpense.currency` (`LedgerRoutes.kt:522`).
  4. `validate` checks title / `amountSubunits > 0` / `Σ shares == amount`. All pass. `editExpense` writes `currency = "EUR"`, bumps `splitVersion` (`:271`, `:294`), and `replaceWithShares` preserves Bob's share **id** — deliberately, so allocations stay linked (`ShareMerge.kt:16-23`).
- **Resulting state:** Bob's share is now `2500` EUR subunits, and `ShareDao.kt:53` derives
  `remaining = share_owed_subunits - COALESCE((SELECT SUM(sa.applied_amount_subunits) … WHERE sa.share_id = s.id AND st.deleted_at IS NULL), 0)`
  — **no currency predicate**. Bob's €25 debt reads as fully paid off a $25 payment. `observeBalances` nets it at 0, `observeOutstandingItems` drops it (`> 0` filter), and `SettlementRepositoryImpl.writeSettlement:103` (`.filter { it.currency == input.paymentCurrency }`) will not even let Bob pay the difference, because there is no outstanding EUR row to allocate against. The app does not recover on next launch — the state is stable and syncs; a later re-edit back to USD restores it, but nothing signals that anything went wrong.
- **Why it survives refutation:** I checked for a guard at the call site — `LedgerRoutes.kt:495-532` passes `submit.currency` through with no check, and the editor gates only on `submit.shares.isNotEmpty()`. I checked whether the editor hides the currency picker when payments exist — `AddExpenseScreen.kt:133-134` comments "Currency is editable (F2)" and `:389` renders `CurrencySheet` with no `editing` guard; the same route already loads `settlements.observePaymentsForExpense` at `LedgerRoutes.kt:602`, so the information to gate on is *present and unused*. I checked whether `remaining` filters by currency — it does not, in any of the five derivations in `ShareDao.kt` (`:53, :75, :102, :126, :148, :178`). I checked `ExpenseRepositoryTest` and `SettlementRepositoryTest` for a currency-change case — there is none.
- **Suggested fix:** Widen `validate` to take the existing expense (or at least "does this expense have applied allocations") and reject a currency change when any non-voided allocation points at one of its shares — the exact same widening #21 needed. Rejecting is the safe default; if the owner wants the change to be *possible*, it has to re-denominate or void the allocations, which is a product decision, not a repository one. **Needs an owner decision on which.** Whichever way it goes, the invariant "an allocation's currency equals its share's expense currency" belongs in a test.

---

### R2. `PlaceholderClaimCoordinator.commit` stamps the server-side "first claim wins" record before the local merge, so an interruption between them retires the placeholder server-side while its money stays attached to it, with no path back

- **Severity:** P0 (data lost — a person's whole expense history is stranded under a name nobody can claim)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/claim/PlaceholderClaimCoordinator.kt:188-206`
- **Guarantee broken:** Atomicity across a boundary that cannot hold a transaction, plus Interruption ("no operation may leave a partial state"). The #15 pattern with the teardown on the *server*: `gateway.claim(...)` at `:191` is the teardown, `groups.reconcilePlaceholder(...)` at `:200` is the rebuild.
- **Failing sequence:**
  1. Sam taps "That's me — Dave" on the identity card. `confirm()` (`:112`) schedules the commit; nothing is written yet (deliberate, and correct).
  2. Sam immediately backgrounds the app. The `AppForeground` watcher at `:124-127` fires `flush()` — by design, "the commit is flushed early … when the app is backgrounded".
  3. `commit()` calls `gateway.claim(group, dave, sam, now)`. The RPC (`supabase/schema.sql:1130-1190`) sets `members.placeholder_claim_completed_at = p_now`, `placeholder_claimed_by = sam`, `status = 'LEFT'`, `left_at = p_now`, bumps `row_version`, and returns `won: true`.
  4. The OS kills the backgrounded process before line `:200` runs. `PlaceholderMergeDao.mergePlaceholder` never executes.
- **Resulting state:** Server-side, Dave the placeholder is retired and stamped as claimed by Sam. Locally, every `shares`, `expenses.payer_user_id`, `settlements`, `settlement_allocations`, `item_claims`, `item_shares` and `bill_participants` row still points at Dave's user id. On the next launch the pull adopts the server `members` row (its `updated_at` is `greatest(updated_at, p_now)`, so `keepNewer` accepts it), and `UserDao.observePlaceholdersInGroup` — which filters `status='ACTIVE' AND placeholder_claim_completed_at IS NULL`, per `data/AGENTS.md` — stops listing Dave. **Dave is now unpickable in both the Reconcile picker and the Join-sheet identity picker, so the merge can never be retried from the UI.** The coordinator holds no durable record (`pending` is a private field, `:98`), so there is nothing to resume. Sam's balances never receive Dave's history; Dave's debts sit in the ledger under a LEFT member. Recovery requires SQL.
- **Why it survives refutation:** The class carries a long load-bearing comment (`:54-78`) that *does* address process death — "If the process dies inside the window nothing was written and the card returns on the next launch" — but that covers only the pre-flush window, not the gap between the RPC and the merge. `schema.sql:1127-1129` explicitly justifies the server-first ordering ("a loser then reverses rows no other client has pulled yet"), so reordering is not available, and per the brief's own rule, reordering would not be a fix anyway. I checked whether the RPC's idempotency saves it — `schema.sql:1185-1186` does make a retry by the same claimer return `won: true`, so a retry *would* work, but there is no surviving affordance to retry from, because step 3 already removed Dave from every picker. I checked `PlaceholderClaimCoordinatorTest` — it covers undo, supersession, flush-on-background and the lost/failed statuses, but no test interposes a failure between the gateway call and the merge. Secondary: `commit()` catches only around `gateway.claim` (`:190-196`); a throw from `reconcilePlaceholder` escapes `scope.launch` at `:155-158`, is swallowed by the `SupervisorJob`, and leaves `status` stuck on `Confirming` with no error shown — the same end state via a non-crash route.
- **Suggested fix:** Park the claim durably *before* calling the RPC, in the shape `ProPurchaseCoordinator` already uses for exactly this failure (`ProPurchaseCoordinator.kt:44-55`: write the pending record, then attempt, then clear on confirmation) — a `SecureStorage` or local-only Room row holding `(groupId, placeholderUserId, claimerUserId)`, cleared only once `mergePlaceholder` returns. Retry it on launch alongside `retryPendingActivation()`. Also wrap `reconcilePlaceholder` in the existing `try/catch` so a merge failure surfaces as `Failed` instead of a stuck `Confirming`. **Needs an owner decision** on where the resume runs (launch vs. next group open).

---

### R3. `BillPendingEdits.undo` stamps the log row UNDONE before restoring the line, and the stamp is first-undo-wins — so an interruption between the two makes the undo permanently unrepeatable

- **Severity:** P1 (user-visible wrong state, unrecoverable)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/BillPendingEdits.kt:80-83`
- **Guarantee broken:** Atomicity + Idempotency. This is the closest structural match to #15 in the tree: a claim-the-work step, then the work, in two writes, invoked from a navigation-scoped coroutine.
- **Failing sequence:**
  1. A web guest reprices "Margherita pizza" from `3600` to `4000`. `apply_web_bill_edit` writes the item and an `APPLIED` `pending_item_edits` row server-side; the app pulls both.
  2. The payer opens Review edits and taps Undo. `WebClaimRoutes.kt:145-154` launches `bills.undoPendingEdit(editId, me)` on `rememberCoroutineScope()` (`WebClaimRoutes.kt:194`) — a **composition-scoped** scope, cancelled when the screen leaves composition.
  3. `undo` reaches `:80`: `pendingItemEditDao.markUndone(...)` returns 1. The row is now `decision = 'UNDONE'`, `row_version + 1`, and will push to the server as such.
  4. The payer taps back (or the process dies) before `restore(expense, edit, now)` at `:82` completes. The item is still `4000`; `expenses.amount_subunits` still carries the guest's total; `split_version` is not advanced.
  5. The payer notices and taps Undo again. `markUndone` returns **0** (the `WHERE … decision = 'APPLIED'` guard), and `:80` returns `AppResult.Ok(Unit)` — a silent no-op.
- **Resulting state:** The bill permanently bills at the price the payer rejected, while every device's Review-edits screen renders the change as undone (`WebClaimRoutes.kt:139-140` shows `undoneByName`). No launch, pull, or re-tap repairs it: the log row is the only thing gating the restore and it is already spent. `BillRepositoryTest`/`BillPendingEditTest` never re-derive from the log, and `BillMaterializer.rematerializeGroups` re-derives shares from *items*, so it faithfully re-derives the wrong price.
- **Why it survives refutation:** The first-undo-wins design is deliberate and documented (`:77-79`, `PendingItemEditDao.markUndone`'s doc, `data/AGENTS.md`: "First-undo-wins is the conditional `UPDATE` … Losing the race is an `Ok`"). That reasoning is sound for *two people tapping at once* — but it assumes the winner completes. `BillPendingEditTest.undoingTwice_changesNothingTheSecondTime` (`:267-287`) asserts exactly the no-op behaviour that makes this unrecoverable, and it asserts it only after a *successful* first undo; it is not wrong, it just does not cover the interrupted first undo. There is no `@Transaction` wrapper anywhere on this path — `PendingItemEditDao` has no transactional method at all, and `restore` writes through four separate DAOs.
- **Suggested fix:** Move the stamp and the restore into one DAO `@Transaction` (the shape `ItemShareDao.setServings` already uses for #15) — the conditional `UPDATE` keeps its first-undo-wins semantics inside the transaction, and a rollback returns the row to `APPLIED` so the retry works. If a single transaction across five DAOs is awkward, the alternative is to make the restore idempotent and drive it off `decision = 'UNDONE' AND the line does not yet match previous_*`, but that reintroduces a read-then-write; the transaction is cleaner.

---

### R4. `editBill` takes a participant off a bill, then their claims, then their portion memberships, in three separate writes — an interruption leaves them off the bill and still paying for their dishes

- **Severity:** P1 (money wrong for a person who is not on the bill)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/BillRepositoryImpl.kt:349-358`
- **Guarantee broken:** Atomicity. This is the #15 pattern, and `data/AGENTS.md` states the requirement in so many words: "Taking someone off a bill must take their claims with them … `editBill` soft-deletes their claims and portion memberships **in the same write**." The code performs three writes.
- **Failing sequence:**
  1. A four-person bill; Mary is a participant with a solo claim on the salad and a membership in a shared portion of the pizza.
  2. The payer opens the bill editor, deselects Mary, taps Save. `BillRoutes.kt:334` launches `bills.editBill(...)` on `rememberCoroutineScope()` (`BillRoutes.kt:111`).
  3. `:351` runs `billParticipantDao.softDeleteByIds(...)` — Mary's roster row is tombstoned.
  4. The payer taps back, or the process is killed. `:356` (`itemClaimDao.softDeleteByExpenseAndUsers`) and `:357` (`itemShareDao.softDeleteByExpenseAndUsers`) never run, and neither does `materializeShares` at `:360`.
- **Resulting state:** `bill_participants` says Mary is off the bill; `item_claims` and `item_shares` still hold her salad claim and her pizza slice. Because a bill's owed amounts derive from claims and not from the roster (`BillMaterializer.materialize:48-83` reads items/claims/item-shares, never participants), Mary keeps a `shares` row and keeps owing. The next pull makes it worse, not better: `SyncEngine.kt:289` calls `rematerializeGroups`, which faithfully re-derives Mary's debt from her still-live claims. She does not appear on the bill's roster, so no screen offers a way to remove her claims. Permanent until someone re-runs the full edit. A narrower window (dying between `:356` and `:357`) leaves her solo claims gone and her shared slices live — same class, smaller amount.
- **Why it survives refutation:** I checked `BillRepositoryTest.takingSomeoneOffTheBill_clearsTheirClaimsAndTheirMoney` (`:249`) and `…_leavesTheRestOfASharedSlice` (`:271`) — both assert the *completed* behaviour and pass; neither can observe a partial one. I checked for a transactional DAO covering this: `BillParticipantDao`, `ItemClaimDao` and `ItemShareDao` expose only single-table methods, and the only `@Transaction` in this area is `ItemShareDao.setServings` (the #15 fix), which covers a different operation. I checked whether the pull heals it — it does the opposite, per the `rematerializeGroups` gate above. The whole of `editBill` (`:226-363`) is eleven writes across six DAOs with no transaction; this is the sub-sequence where a partial state costs money rather than merely looking odd.
- **Suggested fix:** One `@Transaction` DAO method taking the removal set — participant ids, expense id, user ids — mirroring `setServings`. Widening the surrounding coroutine scope is not a fix here (it does not survive process death) and the existing `setServings` comment already says so.

---

### R5. `BillPendingEdits.restore` recomputes and stores the bill's total with no validation, so undoing a guest's edit can drive the expense negative

- **Severity:** P1 (money wrong; the bill silently leaves the balances)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/BillPendingEdits.kt:171-181`, helper at `:188-191`
- **Guarantee broken:** Validation completeness — **explicitly extending #21**. #21's fix widened `BillRepositoryImpl.validate` to take `extras` and reject a non-positive total (`BillRepositoryImpl.kt:795-805`, pinned by `BillRepositoryTest:310-378`). `BillPendingEdits.restore` writes `amountSubunits` through a *duplicate* of the same `total()` helper and calls no validator at all, so the invariant #21 established is enforced on the create and edit paths and not on the undo path.
- **Failing sequence:**
  1. Payer creates a bill: Pizza `4000`, Beer `3000`, Dessert `3000` (Σ `10000`), no extras. Total `10000`.
  2. A web guest reprices Pizza `4000 → 14000`. `apply_web_bill_edit` applies it server-side and logs a `REPRICE` row with `previous_line_total_subunits = 4000`. The bill totals `20000`.
  3. Payer edits the bill and enters a `13000` discount. `validate` computes `20000 - 13000 = 7000 > 0` and accepts.
  4. Payer opens Review edits and undoes the guest's reprice. `restore` hits the `REPRICE` branch at `:155-167`, writes Pizza back to `4000`, then at `:172` computes `total(fresh, expense.toExtras()) = 10000 - 13000 = -3000` and at `:180` writes it.
- **Resulting state:** `expenses.amount_subunits = -3000`, `split_version` advanced, pushed through `merge_expense` and adopted as canonical on every device. `splitBill`'s negative-proportional discount drives individual owed amounts negative, so the shares fall out of every `remaining > 0` outstanding filter — the exact consequence `BillRepositoryImpl.kt:791-793` documents for #21: "the bill simply vanished from the balances instead of failing". The app does not recover on next launch; the only exit is another `editBill`, which *will* reject the same numbers, so the user is left with a bill that cannot be re-saved as it stands.
- **Why it survives refutation:** I checked whether the undo path routes through `BillRepositoryImpl.validate` — it does not; `undoPendingEdit` (`BillRepositoryImpl.kt:715-717`) delegates straight to `pendingEdits.undo`, and `BillPendingEdits` has no validator, only its own copy of `total()` at `:188` (see R13). I checked whether a discount larger than the post-undo subtotal is reachable — it requires only that the discount was entered while the guest's larger line was live, which is the normal order of events on a shared bill (the guest edits the menu during the meal, the payer enters the receipt's discount afterwards). I checked `BillPendingEditTest` — nine tests, all on bills with no discount; `amountOf(bill)` is asserted but never against a negative case. I checked whether `merge_expense` rejects a negative amount server-side — `schema.sql` has no such check.
- **Suggested fix:** Have `restore` compute the total, run the same `<= 0` rejection, and return a validation error before writing — which means the total rule needs one home both files can call (R13). The undo would then fail loudly with "this would make the bill negative — adjust the discount first", which is the honest answer.

---

### R6. `editSettlement` voids the old payment before re-recording, and the re-record has two `Err` returns that leave the void standing — so a *rejected* edit destroys the payment, without any crash

- **Severity:** P1 (a recorded payment disappears; the debt reappears)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/SettlementRepositoryImpl.kt:206-219`, returns at `:113` and `:158`
- **Guarantee broken:** Atomicity. **Explicitly extending #17**, which is about a *crash* between the void and the re-record. This is the same window reached with no crash at all: `writeSettlement` can return `Err` on its own, and `editSettlement` returns that `Err` straight through at `:221` with the void already committed and nothing restoring it.
- **Failing sequence:**
  1. Bob owes Ann `2500` on one expense; Ann records Bob's `2500` payment. One settlement, one allocation.
  2. Ann taps Edit payment and changes it to `2400`. `LedgerRoutes.kt:713` launches `settlements.editSettlement(...)`.
  3. The pre-check at `:200` passes: `2400 <= outstandingNow(0) + thisApplied(2500)`.
  4. `:206` — `settlementDao.voidSettlement(...)` commits. Bob owes `2500` again.
  5. A background pull (`SyncManager`'s 60s tick, or the `group_activity` doorbell) lands Bob's own device's settlement for the same expense, recorded a minute earlier offline. Bob's share now derives to `remaining = 0`.
  6. `writeSettlement` re-reads `outstanding` and either fails `:111` (`2400 > totalOutstanding`) → `Err`, or passes and then `settlementDao.applySettlement`'s in-transaction re-check (`SettlementDao.kt:158-159`, `a.appliedAmountSubunits > derivedRemainingForShare(...)`) returns false → `:158` `Err`.
- **Resulting state:** `editSettlement` returns a `Validation(amount, OutOfRange)`. `LedgerRoutes.kt:713` discards the result entirely (`scope.launch { settlements.editSettlement(...) }` — no `when`, no notice), so **the user is not even told**. Ann's `2500` payment is soft-deleted with no replacement, Bob's debt is back, and the only trace is the tombstoned settlement. No launch or sync restores it; Ann must notice and re-record by hand.
- **Why it survives refutation:** The comment at `:193-194` is load-bearing and asserts precisely the opposite — "Guarding here — *before* voiding — means a rejected edit never destroys the existing payment". That guard covers only the ceiling check at `:200`; it does not cover either `Err` inside `writeSettlement`, both of which run *after* the void. `SettlementDao.applySettlement`'s own doc (`:151`) says "Returns false (nothing written) when the guard trips" — true of that transaction, and exactly why the void is left orphaned. I checked `SettlementRepositoryTest` for an edit-rejection case — it covers over-allocation on `applySettlement` and the edit ceiling, but no test drives `writeSettlement` to `Err` *from inside* `editSettlement`. This is distinct from #17: #17's fix (one `replaceSettlement` `@Transaction`) would close this too, which is worth noting on the #17 ticket, but the current code fails here with no interruption whatsoever.
- **Suggested fix:** The #17 fix — a single `SettlementDao.replaceSettlement(...)` `@Transaction` combining void + apply — closes both. Until then, at minimum the `Err` branches must un-void. Separately, `LedgerRoutes.kt:713-714` swallowing the result is a silent dead end under `AGENTS.md` §7 and should surface the failure; that half is a `ui/` change and out of my scope, noted only because it is what makes this invisible.

---

### R7. `resolveConflict` writes a hand-computed split onto an ITEMIZED bill, which `rematerializeGroups` erases at the next pull — the user's decision silently evaporates and the conflict card is already gone

- **Severity:** P1 (user-visible wrong state, no retry affordance)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/GroupRepositoryImpl.kt:410-449`; created by `:394-405`
- **Guarantee broken:** Domain boundary / Idempotency — a derived materialization is overwritten by a hand-written value that the deriving process then reverts. Neither method knows that a bill's `shares` are not user input.
- **Failing sequence:**
  1. A group already has itemized bills (`split_mode = "ITEMIZED"`).
  2. A new member is added. `GroupSettingsRoute.kt:180` calls `addMemberToPastExpenses`, which loops `expenseDao.getActiveByGroup` with **no `splitMode` filter** (`:374`). Each itemized bill fails `e.splitMode == "EVEN"` at `:379`, so `:394` writes a `conflicts` row for it — a conflict card per bill.
  3. The user opens the Conflicts tab, taps Include on one, and enters a share (`GroupTabRoutes.kt:534`).
  4. `resolveConflict` reallocates every existing participant's share proportionally via `allocate` (`:434-437`), bumps `splitVersion` (`:443`), writes through `replaceWithShares`, and closes the conflict (`:447`).
  5. The device syncs. `SyncEngine.kt:287-289`: any pull carrying a fresh expense, item, claim, item-share or participant in *any* synced group sets `billSourcesChanged` and runs `billMaterializer.rematerializeGroups(groupIds, now)`.
  6. `BillMaterializer.materialize` re-derives owed purely from items + claims + item-shares + extras, upserts the derived amounts over the hand-written ones, and tombstones every share whose user is not in the derived set (`BillMaterializer.kt:80-82`) — including the newly-added member, who has no claims.
- **Resulting state:** The added member's share is tombstoned, every other participant's share is restored to its pre-resolution value, and the `conflicts` row is `resolved` — so the card is gone and there is nothing to tap again. The user explicitly chose an amount and it is now nowhere. Convergent (the derived value is correct as a *bill*), so the app is not corrupt; it is simply that a deliberate user decision was accepted and discarded. The correct action for a bill would have been a `bill_participants` row, which nothing on this path writes.
- **Why it survives refutation:** I confirmed `rematerializeGroups` runs on the pull path and is not restricted to the bills that changed — it sweeps every ACTIVE itemized expense in every group id it was handed (`BillMaterializer.kt:89-95`), gated only on *some* bill source having changed. I confirmed `addMemberToPastExpenses` has no split-mode filter (`:374-406`). I checked `ConflictFlowTest` and `GroupRepositoryTest` — the conflict flow is tested on non-itemized expenses only, so no test asserts the wrong behaviour and none catches this. `data/AGENTS.md` states the rule this violates ("Shares for an itemized expense are a **local derived materialization**"), which is why I am confident the derived side is the correct one and the conflict path is the defect.
- **Suggested fix:** Skip `split_mode = "ITEMIZED"` expenses in `addMemberToPastExpenses`, so no conflict is raised for a bill in the first place; if adding a member to past *bills* is wanted, the write is a `bill_participants` row, not a `shares` reallocation. Defensively, `resolveConflict` should refuse an itemized expense rather than write shares that a sweep will erase. **Needs an owner decision** on whether "add member to past expenses" should touch bills at all.

---

### R8. `createBill` writes the expense, its items, its participants and its shares as four unrelated statements, so an interruption leaves a bill with a full total and no lines

- **Severity:** P2 (bounded — no money is wrong, but the row is not a state any user action produces)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/BillRepositoryImpl.kt:214-218`
- **Guarantee broken:** Atomicity.
- **Failing sequence:** The user finishes the bill editor and taps Save; `BillRoutes.kt` launches `createBill` on `rememberCoroutineScope()` (`:111`). `:214` `expenseDao.upsert(expense)` commits with `amountSubunits` = the whole bill total. The user swipes back immediately, cancelling the scope, before `:215` `expenseItemDao.upsertAll(items)`.
- **Resulting state:** An ACTIVE `ITEMIZED` expense with, say, `amount_subunits = 8400`, zero `expense_items`, zero `bill_participants`, zero `shares`. It pushes to the server and reaches every device. `observeUnresolvedBills` skips it (`:741`, `if (its.isEmpty()) return@mapNotNull null`), so it never appears as needing attention; the group's expense list shows an $84 bill nobody owes anything on. Partially recoverable — the user can reopen the editor and add lines — but nothing prompts them to, and `validate` will refuse to save until they add at least one line. The same window one statement later leaves items but no participants, which is a *legal* state (the roster is optional) and therefore invisible.
- **Why it survives refutation:** `ExpenseRepositoryImpl.addExpense` shows the intended shape — `expenseDao.insertWithShares` is `@Transaction` (`ExpenseDao.kt:72-76`) — so a transactional create is idiomatic here and simply absent on the bill path. No comment anywhere claims the sequence is deliberate. `BillRepositoryTest.newBill_hasNoSharesUntilClaimed` asserts the completed state.
- **Suggested fix:** One `@Transaction` DAO method covering expense + items + participants; leave `materializeShares` outside it, which is already correct and documented as self-healing at `:650-653`. `recordHistory` may stay outside — it is an activity feed, not money.

---

### R9. `addPlaceholder` writes the `users` row and the `members` row separately, so an interruption leaves a placeholder user with no membership — invisible everywhere and never cleaned up

- **Severity:** P2 (bounded)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/GroupRepositoryImpl.kt:79-105`
- **Guarantee broken:** Atomicity. `data/AGENTS.md` defines the pair as one thing: "A placeholder is a `users` row … **plus** a `members` row — `addPlaceholder` creates both."
- **Failing sequence:** In the add-expense editor the user types a name and taps Add (`LedgerRoutes.kt:504` — `scope.launch { groups.addPlaceholder(...) }`, composition-scoped). `:79` `userDao.upsert(UserEntity(isPlaceholder = true, …))` commits; the user backs out of the sheet, cancelling the scope, before `:94` `memberDao.upsert(...)`.
- **Resulting state:** A `users` row with `is_placeholder = 1` and `placeholder_group_id` set, and no `members` row. It pushes to the server. Every read that matters JOINs `members` — `observeActiveMembersWithUser`, `UserDao.observePlaceholdersInGroup` — so the person appears in no roster, no reconcile picker and no join-sheet identity picker, and no UI offers a way to delete them. Harmless to money (nothing references them), permanent as a row. Note the method returns `Member(...).asOk()` at `:107` regardless, so a caller that survived would believe the member exists.
- **Why it survives refutation:** `GroupDao.createGroupWithAdmin` (`GroupDao.kt:95-99`) is the same group+member pair done correctly with `@Transaction`, so the pattern exists and was not applied here. `GroupRepositoryTest` covers placeholder creation and reconciliation end-to-end but cannot observe a partial write. No comment claims the split is deliberate.
- **Suggested fix:** A `@Transaction` DAO method writing both rows, mirroring `createGroupWithAdmin`.

---

### R10. A second `assignRemainder` with a different member set is a silent no-op that reports success

- **Severity:** P2 (silent dead end — `AGENTS.md` §7)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/BillPendingEdits.kt:226-256` (`BillRemainder.assign`)
- **Guarantee broken:** Idempotency — "re-running with the same inputs converges" holds, but re-running with *different* inputs neither converges on the new inputs nor reports that it did nothing.
- **Failing sequence:**
  1. Three units of a line are unclaimed. The payer taps "Split between outstanding" on the claim-progress screen (`WebClaimRoutes.kt:270`). `assign` computes `left = 3`, writes one `item_shares` portion under the deterministic id `"<item>__remainder"` for those members, materializes, returns `Ok`.
  2. The payer reconsiders and taps "Split across everyone" (`WebClaimRoutes.kt:271`), a different member set.
  3. `assign` recomputes `portionUnits` at `:230-232`, which now includes the remainder portion's quantity, so `left = item.quantity - soloUnits - portionUnits = 0` and `:234` `continue`s for every line. The loop writes nothing. `:256` returns `Ok`.
- **Resulting state:** The remainder is still assigned to the first set. `WebClaimRoutes.kt:261` only shows a notice on `Err`, so the second tap produces no writes, no error and no visible change — the classic dead button. Recoverable in the sense that nothing is wrong, but the user cannot change their mind through this control.
- **Why it survives refutation:** The deterministic-id convergence at `:236-238` is deliberate and documented ("running this twice converges instead of double-billing") and is correct for a *repeat* of the same call — the comment simply does not consider a different target set. `assign` only ever upserts; it has no path that drops former members of the `__remainder` portion, unlike `setPortion`, which does exactly that at `BillRepositoryImpl.kt:537-538`. No test covers a second `assignRemainder` with different members.
- **Suggested fix:** Give `assign` the same "drop this portion's former members who aren't in the target set" step `setPortion` already has, keyed on the `"<item>__remainder"` portion id, and recompute `left` excluding that portion so a re-assignment recomputes rather than reading zero. Failing that, return a validation error when there is nothing left to assign, so the button can say why.

---

### R11. The parked pass activation is a single un-scoped key, so a stuck charge in one group hijacks every other group's pass sheet and blocks buying there

- **Severity:** P2 (bounded — money is never lost, but the wrong group is activated and the right one cannot be bought)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/ProPurchaseCoordinator.kt:61-71, 87-92` (`PENDING_KEY` is one constant, `:95`)
- **Guarantee broken:** Idempotency / interruption recovery — the durable record exists (which is the good part) but carries no identity the caller can match against.
- **Failing sequence:**
  1. In group A the user buys a pass. The store charges; `activate` fails (offline). `"A|txn123"` is parked; the sheet shows `Charged`.
  2. The user opens the pass sheet in group **B**. `ProRoutes.kt:283-284` runs `hasPendingActivation()` — true, because the key is global — and calls `retryPendingActivation()`, which reads `"A|txn123"` and activates **group A**.
  3. If still offline, the retry fails and `phase = PassSheetPhase.Charged`, which replaces the primary button with "Turn on Pro" (`PassSheet.kt:162`). The Buy action is no longer reachable.
  4. If the retry succeeds, it returns `true`, so `ProRoutes.kt:439` dismisses the sheet as though group B is now Pro. Group B is still free.
- **Resulting state:** Either the user cannot buy a pass for the group they are actually in until an unrelated group's activation clears, or a sheet opened in group B silently activates group A and closes claiming success. Self-clearing once the network returns and the parked activation lands, so nothing is permanently wrong — but the buyer's model of what they bought is wrong for the duration.
- **Why it survives refutation:** I checked whether a second purchase can overwrite the parked key and orphan the first charge — it cannot, because `Charged` removes the Buy button, so the worse version of this is not reachable. I checked the spec — `PRO_PASS_SPEC.md` describes `(store, store_txn_id)` idempotency on the server side and says nothing about multiple concurrent pending activations, so a single slot is an undocumented assumption rather than a stated one. The class comment (`:24-34`) explains the park thoroughly and correctly and simply never considers two groups.
- **Suggested fix:** Key the pending record by group (`pro_pending_pass_activation_<groupId>`, or one JSON list), and give `hasPendingActivation` / `retryPendingActivation` a `groupId` parameter so a sheet only ever retries and reports on its own group. Retrying the others on launch is fine and desirable.

---

### R12. `addMemberToPastExpenses` is a per-expense loop with no overall atomicity and no report, so a cancellation part-way leaves some past expenses re-split and some not, silently

- **Severity:** P2 (bounded — converges on a re-run, but nothing re-runs it)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/GroupRepositoryImpl.kt:368-408`
- **Guarantee broken:** Interruption ("no operation depends on the caller's coroutine scope surviving").
- **Failing sequence:** A member is added in group settings; `GroupSettingsRoute.kt:180` launches `addMemberToPastExpenses` on the route's scope. The loop iterates every active expense; each `replaceWithShares` is individually atomic (`ExpenseDao.kt:84`). The user navigates away after eight of twenty expenses, cancelling the scope mid-loop.
- **Resulting state:** Eight expenses now include the new member with everyone's share reduced and `splitVersion` bumped; twelve do not. Both halves are internally consistent and push cleanly, so nothing looks broken — the group's history is simply split at an arbitrary point. Re-running would converge (`:376` skips expenses that already contain the member, `:394` guards duplicate conflict rows), but the only trigger is adding the member again, which is not possible once they are in. `AppResult.Ok(Unit)` at `:407` is never reached on the cancelled path, and the call site discards the result anyway.
- **Why it survives refutation:** I confirmed the re-run guards make the operation genuinely idempotent, which is why this is P2 and not higher — but idempotency only helps if something re-runs it, and nothing does. `data/AGENTS.md` and `AGENTS.md` §7 both treat "silently half-applied and unreported" as a defect regardless of recoverability. No test covers a cancelled loop.
- **Suggested fix:** Either wrap the whole sweep in one transaction (it is bounded by the group's expense count) or run it from a scope that outlives navigation and record progress so a resumed run finishes it — and surface a result the settings screen can act on.

---

### R13. The bill's total is money arithmetic implemented twice in `data/` and nowhere in `domain/`

- **Severity:** P3 (cleanup — no current defect; the drift risk is the point)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/BillRepositoryImpl.kt:811-814` and `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/BillPendingEdits.kt:188-191`
- **Guarantee broken:** Domain boundary — "Money arithmetic belongs in `domain/`. A second implementation is a second thing to get wrong, and it will drift."
- **Failing sequence:** Not a runtime failure today; the two copies currently agree, including `otherChargesSubunits`, which `domain/AGENTS.md` records as added on 2026-08-08 and which therefore had to be added to both by hand. The failure is the next such change: a rule change made in one copy leaves `createBill`/`editBill` validating against one definition of "the bill total" and `undoPendingEdit` storing another, and nothing compares them. `BillPendingEdits.kt:187` even names the coupling in a comment — "same rule as `BillRepositoryImpl`" — which is the documentation form of the problem.
- **Resulting state:** n/a.
- **Why it survives refutation:** I checked `domain/expense/` for an existing bill-total function to delegate to: there is none. `BillSplit.kt:105`'s `TabBreakdown.totalSubunits` is a *per-person* total and omits `gratuitySubunits`/`otherChargesSubunits` (they are folded into `taxSubunits` for the breakdown), so it is not the same quantity and cannot be reused as-is. So this is not "the helper exists and was duplicated"; it is "the rule has no home in the layer that owns money". Note the TS port in `web/src/lib/money/` makes this arithmetic's authority question live — `domain/AGENTS.md` names Kotlin as the authority, and right now the Kotlin authority is two private functions in the persistence layer.
- **Suggested fix:** One `billTotalSubunits(lines, extras)` in `domain/expense/`, called by `validate`, `createBill`, `editBill` and `BillPendingEdits.restore`. That is also the precondition for R5's fix.

---

### R14. FX conversion rounds money per share inside the repository, in floating point

- **Severity:** P3 (cleanup / display-only drift)
- **Verdict:** PLAUSIBLE
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/ExpenseRepositoryImpl.kt:202-206`, used at `:130-137`
- **Guarantee broken:** Domain boundary — "domain results that get rounded, truncated, or re-derived on the way to storage" (here, on the way to the screen). `domain/AGENTS.md`: "Money is integer subunits, never floating point … If you are writing a division that produces money, you are almost certainly meant to call `allocate`."
- **Failing sequence:** A group with base `USD` holds fifteen EUR shares of `333` subunits each. `observeBalances` calls `convertToBase` per share, each doing `(subunits * rate).roundToLong()` independently, then nets the rounded values through `buildBilateralBalances`. Rounding fifteen times and summing differs from summing then rounding once by up to a few subunits, in a direction that depends on the rate.
- **Resulting state:** The displayed balance can be off by a small number of subunits from the same figure computed any other way. Nothing is stored, so nothing is corrupted; it is a number on a screen that will not reconcile with a hand check.
- **Why it survives refutation:** `FxResult.rate` is a `Double` by design, so floating point is unavoidable *somewhere*; and this is a read path, not a write, so no stored money is affected. That is why I have marked it PLAUSIBLE rather than CONFIRMED — it may well be an accepted trade. What is not arguable is the placement: the rounding rule for converted money is a domain decision living in a repository, with no test pinning it. `FxRepositoryImpl` correctly keeps itself to rates and dates and does no money arithmetic, which makes this the one place in the layer that does.
- **Suggested fix:** Move the conversion to a small pure function in `domain/fx/` (`convertSubunits(subunits, rate)`), and convert the *netted* per-pair figure rather than each share, so rounding happens once per displayed number. Pin it with a test. **Needs an owner call** on whether the drift is worth the change at all.

---

### R15. An undo advances the split version but leaves `splitUpdatedBy` pointing at whoever last edited the split

- **Severity:** P3 (cleanup — attribution)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/BillPendingEdits.kt:173-179`
- **Guarantee broken:** None of §3 directly; it is a correctness-of-record issue adjacent to Concurrency, since `split_updated_by` is what the superseded-edit notice uses to decide whose change lost.
- **Failing sequence:** Mary edits the bill (`editBill` sets `splitUpdatedBy = mary`, `BillRepositoryImpl.kt:316`). A guest reprices a line. Sam undoes it. `restore` at `:178` writes `splitUpdatedBy = expense.splitUpdatedBy` — Mary — while bumping `splitVersion` at `:177`. `undo` receives `undoneBy` and `restore` is never given it.
- **Resulting state:** `expenses.split_updated_by` credits Mary for a Zone-2 edit she did not make. Pushed through `merge_expense` as the actor of the winning split. If a concurrent split edit is superseded, the one-sided "your change was superseded" attribution names the wrong person. Cosmetic in most flows.
- **Why it survives refutation:** The method's own doc (`:86-93`) is explicit that "an undo is a Zone-2 edit and has to lose to, or beat, a concurrent split edit on the same causal rules as any other" — so the version bump is deliberate and correct, and carrying the *previous* author with it is the inconsistency, not the bump. `undoneBy` is available at the call site (`:82`) and simply not threaded through. `BillPendingEditTest` asserts `splitVersion` advanced but never `splitUpdatedBy`.
- **Suggested fix:** Pass `undoneBy` into `restore` and stamp it. One parameter.

---

## Atomicity sweep — every multi-step write method in scope

"Atomic" means every write the method performs is inside one `@Transaction` / `db.withTransaction`.
"Money-atomic" means the writes that can produce wrong money are, while a documented self-healing or
non-financial write (activity log, share re-derivation) sits outside.

| File | Method | Writes | Atomic? | Note |
| --- | --- | --- | --- | --- |
| `BillRepositoryImpl.kt` | `createBill` `:152` | expense; items; participants; shares (materialize); history | **No** | R8 |
| | `editBill` `:226` | expense; item upserts; item tombstones; claim tombstones; item-share tombstones; participant upserts; participant tombstones; claim tombstones by user; item-share tombstones by user; shares; history | **No** | R4 — 11 writes, 6 DAOs |
| | `setClaim` `:365` | claim upsert *or* tombstone; item-share tombstone; shares | **No** | Shares self-heal via pull |
| | `setShareMember` `:398` | item-share upsert *or* tombstone; claim tombstone; shares | **No** | Same |
| | `joinItem` `:438` | RPC; item-share tombstones; item-share upserts; claim tombstones; shares | **No** | Server is canonical; a partial mirror re-heals on the next join, not automatically |
| | `setPortion` `:512` | item-share tombstones (dropped); per-target upsert / RPC / claim tombstone; shares | **No** | Network call *inside* the write loop; cancellation skips `materializeShares` entirely |
| | `setServings` `:619` | `ItemShareDao.setServings` (**@Transaction**); shares | **Money-atomic** | The #15 fix; `materializeShares` outside is documented self-healing `:650` |
| | `setParticipant` `:661` | one | n/a | |
| | `markDone` `:682` | one | n/a | |
| | `BillMaterializer.materialize` | share tombstones; share upserts | **No** | Self-healing by construction; re-runs on every pull |
| `BillPendingEdits.kt` | `undo` → `restore` `:67` | log stamp; item upsert/tombstone; claim tombstones; item-share tombstones; expense; shares | **No** | R3 — and the log stamp is consumed first |
| | `BillRemainder.assign` `:210` | N × item-share upsert; shares | **No** | Converges on identical re-run (R10 covers the non-identical case) |
| `ExpenseRepositoryImpl.kt` | `addExpense` `:208` | `insertWithShares` (**@Transaction**); history | **Money-atomic** | |
| | `editExpense` `:250` | `replaceWithShares` (**@Transaction**); history | **Money-atomic** | |
| | `deleteExpense` `:303` | expense tombstone; history | **Money-atomic** | |
| | `resolveEditConflict` `:373` | `replaceWithShares` (**@Transaction**); conflict resolve | **No** | Two transactions. Dead path — nothing writes `expense_edit_conflicts` (`data/AGENTS.md:118`); not reported |
| | `dismissSupersededNotice` `:84` | one | n/a | Device-local |
| `GroupRepositoryImpl.kt` | `addPlaceholder` `:74` | user; member | **No** | R9 |
| | `createGroup` `:151` | `createGroupWithAdmin` (**@Transaction**) | **Yes** | |
| | `joinByToken` `:185` | member upsert; `reconcilePlaceholder` (own **@Transaction**) | **No** | Two transactions; a partial leaves the user joined and unmerged, which the Reconcile screen can still fix |
| | `leaveGroup` `:223` | `applyLeave` (**@Transaction**) | **Yes** | Covers leaver + admin handoff + group row |
| | `renameGroup` / `setArchived` / `removeMember` / `rotateInviteToken` | one each | n/a | |
| | `reconcilePlaceholder` `:293` | `mergePlaceholder` (**@Transaction**) | **Yes** | Nine tables in one transaction |
| | `answerNotMe` `:320` | one `upsertAll` | **Yes** | |
| | `addMemberToPastExpenses` `:368` | loop × (`replaceWithShares` **@Transaction** \| conflict upsert) | **No** across the loop | R12 |
| | `resolveConflict` `:410` | `replaceWithShares` (**@Transaction**); conflict resolve | **No** | Two transactions, but a retry converges (`:428` closes it if the member is already a participant) — refuted as a finding; see R7 for the separate itemized problem |
| `SettlementRepositoryImpl.kt` | `applySettlement` `:57` | `applySettlement` (**@Transaction**, with an in-txn over-allocation re-check); N history rows | **Money-atomic** | |
| | `editSettlement` `:172` | `voidSettlement` (**@Transaction**); `writeSettlement` (**@Transaction**); history | **No** | Known #17 — and R6 for the non-crash path |
| | `voidSettlement` `:240` | one **@Transaction** | **Yes** | |
| `CategoryRepositoryImpl.kt` | `addCategory` `:37` | `materialize` (upsertAll); upsert | **No** | Converges: `materialize` is a guarded no-op once rows exist |
| | `renameCategory` / `updateCategoryStyle` → `mutateExisting` `:90` | `materialize`; upsert | **No** | Same |
| | `deleteCategory` `:79` | `materialize`; soft delete | **No** | Same |
| `ActivityRepositoryImpl.kt` | `postComment` `:54` | comment; history | **Money-atomic** | History only |
| | `addReceipt` `:82` | Storage upload; receipt row; history | **No** | Order is deliberate (`data/AGENTS.md`: the row is created only once the bytes land); a partial leaks a Storage object, covered by prod Rule 10 |
| | `deleteReceipt` `:119` | receipt tombstone; Storage delete | **No** | Documented best-effort `:122` |
| | `deleteComment` `:73` | one | n/a | |
| `FxRepositoryImpl.kt` | `refreshIfStale` `:64` | one `upsertRates` | **Yes** | |
| | `currencies` `:89` | one `upsertAll` | **Yes** | |
| `ProfileRepositoryImpl.kt` | all six updates | one each | **Yes** | |
| `ProRepositoryImpl.kt` | `refresh` `:82` | one upsert | **Yes** | No other write path exists, by design |
| `WebBillLinkRepositoryImpl.kt` | `create` `:42` | RPC; token to `SecureStorage`; RPC status | **No** | Deliberately not local-first; store-before-report is documented `:45-46` |
| | `revoke` `:57` | RPC; token removal; RPC status | **No** | Same |
| `ProPurchaseCoordinator.kt` | `buyPass` `:44` | store purchase; park key; activate; clear key | **No** | Deliberate — the park *is* the durability mechanism. R11 is about its scoping, not its ordering |
| `PlaceholderClaimCoordinator.kt` | `commit` `:188` | `claim_placeholder` RPC; `reconcilePlaceholder` (**@Transaction**) | **No** | R2 |
| `IdentityPromptSnooze.kt` | `snooze` / `markFinished` | in-memory only | n/a | Deliberately unpersisted |

**Totals:** 31 write methods; 20 perform more than one write; 6 are fully atomic; 6 more are
money-atomic with a documented non-financial write outside; 8 are not atomic in a way that matters
(R2, R3, R4, R6, R8, R9, R12, and `setPortion`).

---

## Guarantees that came back clean

- **Deletion safety — clean.** No hard delete is reachable from anything in scope. `grep -rn "DELETE FROM" data/db/dao/` returns exactly three, all outside this layer's call paths and all already accounted for in `data/AGENTS.md`: `ReceiptUploadDao.kt:45` and `ExpenseSyncStateDao.kt:21` (device-local, no user data) and `UserDao.kt:29` (the known Rule 11 prod-gate item, reached only from `StubAuthSession`). Every removal in scope is a `deleted_at`/`deleted_by` stamp or a `members.status = 'LEFT'`, and every tombstone is written to a table whose `allForSync()` carries it. `ShareDao.getByExpense` filters `deleted_at IS NULL`, so `mergeShares` cannot accidentally revive a tombstone by copying it — I checked this specifically because `ExpenseRepositoryImpl.kt:269` re-filters the same list, which reads like a hedge against the opposite. Rule 9 ("never wipe local state ahead of the server") holds: no error branch in scope clears local rows.
- **Idempotency of the deterministic-id writes — clean.** `claimId` / `participantId` / `shareId` (`BillRepositoryImpl.kt:113-115`), the `"<expenseId>__<userId>"` share ids in `BillMaterializer`, the `"<groupId>__<key>"` category ids, the `"<group>__<placeholder>__<answerer>"` claim-answer ids, and the `"<item>__remainder"` / `"<item>__slot<n>"` portion ids all make a repeated write converge on one row rather than accumulate. Tombstone revival via the same id is used deliberately and is tested (`BillRepositoryTest.puttingSomeoneBackOnTheBill_restoresThem`). The one gap is R10, which is a different-inputs case, not a repeat.
- **Concurrency between local actors — clean apart from what is reported.** `PlaceholderClaimCoordinator` serialises confirm/undo/flush behind a `Mutex` and guards stale publishes with `latestSeq` (`:106`, `:184-186`); `PendingItemEditDao.markUndone` is a conditional `UPDATE`, not a read-then-write; `SettlementDao.applySettlement` re-checks derived remaining *inside* the transaction; `ExpenseDao.overwriteFromServerIfUnchanged` guards adoption on `row_version`. Two rapid taps on the claim writes converge on the same deterministic row. The loser of a `BillPendingEdits` undo race is correctly treated as a success. R6 is the one place a background sync landing mid-operation produces a state neither actor intended.
- **Interruption — clean apart from what is reported.** No method in scope widens a scope as a substitute for atomicity, and the two places that need to outlive the caller do it correctly and say why: `PlaceholderClaimCoordinator.flushDetached` (`:167-169`) and its app-lifetime `SupervisorJob` scope. The only scope-lifetime comment I found asserts the *right* thing — `ItemShareDao.setServings`'s doc records that the nav-scoped sequence was the #15 bug and that a transaction, not a wider scope, was the fix.
- **Domain boundary — mostly clean.** `BillRepositoryImpl` and `BillMaterializer` persist `splitBill`'s output verbatim, with no re-rounding: `materialize` writes `owedByUser` values unchanged (`BillMaterializer.kt:62-79`). `GroupRepositoryImpl` calls `allocate` for both re-splits (`:382`, `:434`) rather than dividing by hand. `SettlementRepositoryImpl` calls `allocateSameCurrency` and stores its output unchanged, and correctly stores no `remaining` anywhere. `perUnitSubunits` is used only for the vestigial `unit_price_subunits` column, with `line_total_subunits` kept as truth on every path including undo (`BillPendingEdits.kt:152-154`). The exceptions are R13 (the bill total) and R14 (FX rounding).

## Not covered

- I could not settle R5 or R14 by execution. A throwaway test for either needs a Room-backed
  `iosSimulatorArm64Test` source set, which means adding a file under `code/` — forbidden by §0.1. Both
  are settled by reading instead, and R5's arithmetic is fully worked in its failing sequence.
- I did not query the live Supabase project. Nothing in these findings depends on production data;
  `schema.sql` was sufficient to settle `claim_placeholder`'s behaviour for R2.
- I read `data/remote/supabase/SyncEngine.kt`, `SyncManager.kt`, `data/db/dao/*` and
  `ui/navigation/*` only far enough to confirm call paths and recovery behaviour, and report no
  findings in them. Two things I noticed there and deliberately left alone, for whoever owns those
  trees: `LedgerRoutes.kt:713-714` discards `editSettlement`'s and `voidSettlement`'s results entirely,
  which is what makes R6 invisible to the user; and `ShareDao`'s derived-remaining subqueries carry no
  currency predicate, which is the mechanism behind R1 but a schema/DAO decision rather than a
  repository one.
