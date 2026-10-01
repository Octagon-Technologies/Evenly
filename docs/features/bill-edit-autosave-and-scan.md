# Bill edit: auto-save + discoverable, additive Scan (T5a–c)

**Status:** not started
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

**Done (commit 9fc278f).** Pure logic in `ui/screen/bill/BillEditLogic.kt` with `BillEditLogicTest`
(passes): `isComplete`/`incompleteItemCount`/`savableItems`/`canAutoSave`/`incompleteItemsNotice` (T5a) and
`mergeScan` Add/Replace with extras as `ExtraSuggestion`s, never summed (T5c). Both compiles green.
Treated a half-filled row (name or price missing) as invalid; fully blank rows drop silently.

**Blocked on owner sign-off:** the mock `design/bill-edit-autosave-scan-mockup.html` (6 frames: saved pill,
Back with incomplete row, empty-bill Scan card, labelled Scan + receipt thumbnails, Add/Replace sheet,
post-Add extras suggestions). No Compose written. Remaining after sign-off: back-arrow + debounced
auto-save + pill + remove Save button (T5a), Scan card/labelled entry (T5b), receipt thumbnails + choice
sheet + suggestions wiring (T5c), `ux-firsttimer`, iOS sim run.

**Open decisions for the owner / scout items not yet done:** (1) mock removes the top-bar Scan in favour of
the card/pill, confirm; (2) how Save enters `BillReviewEditsScreen` for claimed items and the new-bill
create-on-first-valid-save wiring live in the route wrapper, not yet scouted; (3) multi-page receipt
support (`attachedReceipts` is a list, so likely yes, schema unconfirmed); (4) design-system catalog entry
and `check-design-system.mjs` to run with the UI commit.
