# Bill edit: auto-save + discoverable, additive Scan (T5a–c)

**Status:** T5a/T5c logic done; UI blocked on mock sign-off
**Worktree / branch:** `fb/bill-edit` (from `feat/split-by-items-flow`)
**Mockup:** `design/bill-edit-autosave-scan-mockup.html` — required before Compose (top bar, saved
pill, Scan placement, re-scan Add/Replace choice).

Main file: `code/shared/src/commonMain/kotlin/app/splitevenly/ui/screen/bill/BillEditScreen.kt`.
Read `ui/AGENTS.md`, `data/AGENTS.md`, and `docs/features/split-by-items-flow.md` (this screen's
parent brief) first.

---

## T5a · Back saves; "Changes saved" confirms

**Problem.** The editor has an X top-left and "Save bill" at the very bottom (L406). Editing then
tapping X looks like it discards changes; users don't scroll to Save.

**Owner decisions (locked).**
- Replace X with a **back arrow that saves**. Remove the bottom "Save bill" button.
- **Save valid edits only:** debounced auto-save (~600–800 ms after the last change) of the current
  state *when it is valid*. Invalid rows (blank label, zero price) stay local with an inline hint and
  are not written. Back saves everything valid and leaves; if something invalid is left, Back shows
  a one-line inline notice ("1 item has no name. Fix it or remove it.") with "Leave anyway" which
  drops only the invalid rows. Never a silent loss.
- Edits that touch an item someone already claimed go through the existing review-edits flow
  (`ui/screen/bill/BillReviewEditsScreen.kt`) — auto-save must not bypass it. Planner to confirm in
  scout how that flow is entered today from Save and keep it on the same trigger.
- **"Changes saved" pill:** small, muted (ink2 on surface-2), directly under the top bar, appears on
  each successful save, fades after 3 s, reappears on the next save. Copy: "Changes saved".
  Not a snackbar; must not shift layout (overlay).

**Acceptance criteria.**
- Edit a price, wait 1 s → pill appears; re-open the bill → the price persisted.
- Edit, immediately tap Back → change persisted, no pill needed.
- Blank-name row + Back → inline notice; nothing silently lost.
- Offline: save writes locally (Room) as today and the pill still shows (sync is separate).
- A new bill (not yet persisted) — define in scout whether it's created on first valid change or
  stays a draft until Back; recommend: created on first valid save.

---

## T5b · Scan is easy to find

**Problem.** Scan is a small control at L285 the owner didn't notice.

**Acceptance criteria.**
- Empty bill: a prominent "Scan a receipt" card at the top of the items section (camera icon,
  one-line subtext), above the manual "Add item" row.
- Bill with items: a persistent, labelled "Scan" entry near the items header (not an unlabelled icon).
- While scanning, the existing `ScanUiState.Working` progress stays visible in place.

---

## T5c · Scan page shows the existing receipt; re-scan can Add or Replace

**Problem.** After scanning once and coming back to edit, the scan surface doesn't show the receipt
already attached, and a re-scan always **replaces** the items (L252–257: `LaunchedEffect(scanned)`
sets `items = s.items`). A user who missed part of a long receipt wants to append.

**Owner decision (locked): ask each time.** When a scan completes and the bill already has items:
a small choice — **"Add to current items"** / **"Replace all items"** (default focus on Add). With no
existing items, no prompt.

**Acceptance criteria.**
- The scan surface shows the already-attached receipt page(s) as thumbnails (reuse
  `ReceiptViewerScreen` / `StagedReceiptStrip` patterns) with the current item count.
- Add: new items are appended, the new page is attached alongside the old (multi-page receipt),
  extras (tax/tip/fees) from the second scan are **not** auto-summed — show them as a suggested
  diff the user confirms, since a second partial photo usually repeats the totals block.
- Replace: today's behaviour, but the old page is kept as an attachment unless the user removes it.
- The reconciliation banner (L210–211, L298) re-evaluates against the combined pages.

**Constraints.** Multi-page attachments: confirm in scout whether the receipt model already supports
>1 page (`scanned pages` viewer at L199 suggests yes). If a schema change is needed, it goes to
`supabase/schema.sql` first (AGENTS.md §4.4) — flag before doing it.

---

## Test plan

| Criterion | Proof |
| --- | --- |
| Validity filter for auto-save, append/replace merge of items + extras | Unit tests in `commonTest` (pure functions extracted from the screen) |
| Debounce + pill timing | iOS sim run |
| Claimed-item edit routes to review | iOS sim, two accounts |
| UX | `ux-firsttimer` as Diego (dinner claimer) and the payer |
| Both targets | AGENTS.md §5 compile pair + both test tasks |

## 7. Completion report

**Status: T5a, T5b, T5c implemented. Commits on `fb/bill-edit`:** logic `9fc278f`, mock docs, T5a `f7efb8a`,
T5b and T5c (see `git log`). Each code commit was green on `:shared:compileAndroidMain` and
`:shared:compileKotlinIosSimulatorArm64`; `BillEditLogicTest` passes (`:shared:testAndroidHostTest`).

**What shipped**
- T5a: back arrow saves (also system back via the new `ui-backhandler` dependency), no Save button, debounced
  (700 ms) auto-save of valid state, incomplete rows stay local with a hint, Back warns ("Leave anyway" drops
  only those rows). A half-retyped saved row is written as its last saved version, never dropped, so claims
  are not tombstoned mid-edit.
- Saved indicator (owner change: a card, not a pill): "Changes saved..." expands, holds 2.5 s, collapses.
  **Choice:** latched at the moment of save. Scrolled to the top it is in flow under the top bar and pushes
  content down; scrolled away it is an overlay pinned to the viewport top, same expand/hold/collapse.
- T5b: Scan a receipt card when the bill has no items; labelled Scan pill beside Items otherwise; top-bar Scan
  removed. Progress sheet unchanged.
- T5c: saved receipt pages show as thumbnails with the item count (PDFs download and rasterize); a scan on a
  bill with items asks Add to current items or Replace all items; Add appends and offers differing tax/tip as
  "Use" suggestions, never summed; earlier pages stay attached either way. Pending uploads are de-duplicated
  against saved receipts.

**Decisions taken on the open questions (flag if wrong)**
- Review-edits: scouted. The editor never routed into `BillReviewEditsScreen`; it is entered from the assign
  screen. `editBill` bumps the split version as before, so auto-save uses the same path as the old Save.
  Nothing bypassed.
- New bill: created on the first valid save (implemented in `BillEditRoute`). Note `BillEditScreen` is only
  reachable today for existing bills (`Route.SplitBill` is navigated to from detail/assign); creation goes
  through AddExpense. A created-in-session bill's Back goes to FinishBill (old Save destination).
- Multi-page: `attachedReceipts` is a list and `receipts` is per-expense; no schema change needed.

**Verification**
- iOS sim (`run-ios-sim.sh`): opened an existing bill; back arrow, saved-receipt thumbnail + "3 items on this
  bill.", labelled Scan pill, no Save button, and the in-flow "Changes saved..." card were all seen. Not
  exercised: pinned (scrolled) card, Add/Replace sheet and suggestions (needs a real scan with the OCR backend),
  claimed-item flow with two accounts.
- **Residue to clean:** during the sim run the first item of the owner's dev bill "dinner" (group Food Truck)
  was auto-saved as "Chicken Tikka Masala!?&#8;&#8;" (typing test; I could not send backspace). Please retype.
- `ux-firsttimer` (build-time, Diego and the payer), no P0/P1. P2: dismissing the Add/Replace sheet silently
  drops the scan's items (the page stays attached); the shared unverified-receipt notice still says "before
  saving"; Replace has no confirm (old page is kept and it is an explicit choice).
- Catalog: `design/system/dist/` does not exist in this repo state, so `check-design-system.mjs` fails with
  ENOENT and no catalog entry could be added. Not run successfully.

**Environment notes:** the worktree lacks gitignored `GoogleService-Info.plist` and `local.properties`; I
copied them (read-only) from the main checkout so the iOS build runs. Both stay ignored. Nothing outward-facing
was done. Auto-save risk: a row removed or reordered within ~tens of ms of a save keeps a null id and could
duplicate on the next save (id hand-back matches by index and label).

### Review fixes (Opus, 2026-10-01)
- A rejected `editBill` (validation `Err`) was ignored and still showed "Changes saved..."; the save now returns
  null on `Err`, so no card and the editor retries on the next change.
- Every debounced save wrote its own "edited" row to `expense_history` (append-only, synced). `recordHistory`
  now coalesces an EDITED into the previous CREATED/EDITED by the same actor within 10 minutes
  (`HistoryEventDao.latestForExpense`, no schema change). iOS test `BillHistoryCoalesceTest`.
- Dismissing the Add/Replace sheet silently dropped the scanned items (the P2 above). Dismiss now takes Add, the
  non-destructive default.
- Double-tapping Back while the final save ran could pop twice; Back now latches.
- `SCAN_RESULT_EDITED` fired on every Back, reporting an unsaved scan as "all items removed"; it now fires only
  when something was saved.
- Back on a named bill with no complete item left silently without saving; it now warns ("Add at least one item
  or this bill won't be saved."). The notice clears itself once the problem is fixed.
- The "could duplicate" auto-save risk above does not hold: `editBill` tombstones any line whose id is not
  sent, so a row that misses its id hand-back is recreated once and the orphan is removed, never doubled.

### Owner review changes (2026-10-01)
- **Scan leads the page.** Until anything is scanned or typed, the Scan card sits above Name in the bill editor
  and above Title in Add expense's "Restaurant bill" (the real new-bill path; the editor is only reached for
  existing bills). After that it drops back to the Scan pill (editor) or the items section (Add expense).
- **Receipts live under the name** in both screens.
- **No inline "Scanning page 2" notice**: the progress sheet at the bottom is the only scan progress (mock only;
  the code never had the inline notice).
- **Suggested name from the scan.** Owner chose server-side over an on-device model (Gemma / Apple Foundation
  Models would need a large download or iOS 26+, and would still wait for the server's read of the receipt).
  `extract-receipt` now also returns `merchant_name`, `venue_kind` and `printed_time` (optional on the wire;
  `contract.test.ts`). `domain/receipt/ReceiptTitle.kt` turns them into "Dinner at Tavolo", "Lunch at...",
  "Coffee at...", "Drinks at...", "Evening shopping at Westgate", using the printed time or else the phone's
  clock (`ReceiptTitleTest`). It only fills an empty name, never a typed one.
- **Owner step (proposed, not performed):** deploy the function so the app gets the new fields:
  `supabase functions deploy extract-receipt --project-ref wfpfgbipjmkysalfmyub`. Until then scans work exactly
  as before, just without a suggested name. No schema change.

