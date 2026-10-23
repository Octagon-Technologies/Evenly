# Split by items: receipt to completed assignments

**Status:** implemented 2026-09-30 (see §9)  
**Mockup:** `design/bill-claim-receipt-to-complete-flow.html` (frames 1–7B); revised permanent QR screen: `design/bill-share-link-auto-qr-redesign.svg`; partial review (frame 7) superseded by `design/bill-claim-partial-review-redesign.html`
**Scope:** itemized expenses inside an existing group, on Android and iOS

## 1. Outcome and boundaries

A creator can capture or enter a receipt, check its items before saving, then choose how the group assigns them: on one phone or by sharing a bill link. Members can continue an unfinished bill from their group home. Both paths use the same saved items and claims and meet at a review of each person's total.

This revises the group itemized flow. It does not create a one-time expense outside a group, alter settlement rules, change the web guest's claim UI, or replace `splitBill`'s money allocation. The amount on the expense remains the whole receipt total while claims are incomplete; owed shares remain derived only from assigned items and bill extras.

The mockup is a product-state reference. Use the shipped `EvenlyTheme` tokens and `Ev*` components for Compose, including dark mode; do not hard-code the mockup's CSS values. The existing `SplitApproachChooser` (divide one amount versus itemize) remains the entry question. The new choice comes **after** an itemized expense has been saved and asks how people will pick its items.

## 2. Screen and transition contract

| Step | Screen and action | Result |
| --- | --- | --- |
| 1 | `Restaurant bill` editor: title, participants, payer, scan sources directly on the card, or manual item entry. | A staged draft; no expense yet. |
| 2 | `Scanning your receipt` sheet: page count, indeterminate progress, Cancel. | Success opens receipt review; failure offers retry, repick, or manual entry. Cancel keeps the editor usable. |
| 3 | `Review the receipt`: attached receipt opens the in-app viewer; item rows open editors; missing items and extras can be corrected. `Save & continue` validates and saves. | Creates one itemized expense, its ordered items and participant roster, and attaches the staged receipt. No assignment is required to save. Manual entry reaches the same save boundary. |
| 4 | `Finish splitting` chooser: saved bill title, total, item/people counts, and current assignment progress. | `Assign on this phone` opens the accordion. `Show the QR code` opens bill-link sharing. This screen is shown again whenever a saved bill is reopened; returning visitors see explicit *continue* copy and their current progress. Neither choice erases or restarts claims. |
| 5A | `Assign the items`: one expanded item at a time; first unfinished item initially opens, others are collapsed. Participant chips assign a simple item to one or more people; `Everyone` selects the full bill roster. Multi-quantity items use the existing per-serving sheet. | Writes through `BillRepository` and updates progress and totals from the observed bill. The phone holder may assign only their own items and leave the rest to group members. |
| 6A | Completed and unfinished accordion sections. | During this visit, newly completed items append to the completed section in **completion order**. A just-completed open card stays where it is until another card opens. Editing an item already in the completed section keeps its place while it remains complete. |
| 5B | `Let friends pick their items`: a bill-specific QR created automatically on entry, a share action, and scope reassurance. The QR shimmers while the permanent link is loading; success reads `QR ready`. | Every active group member sees the same permanent bill link on every device. The link remains valid until revoked or the bill/group is deleted. Phone assignment remains available from the chooser, not this screen. |
| 6B | `Claim progress`: people, their done/waiting state, remaining items with an `Assign it` action, and a route back to the QR. | Polls/observes current state. `Finish later` keeps the bill accessible to members. |
| 7 | `Review assignments`: progress, per-person totals (each person's items listed under their name, plus a `Not assigned` row so totals add up to the bill), and `See all N items in receipt order`; tapping an item or person returns to the editor. | With unresolved lines, show a **partial review**: a `Still needs someone` card listing each unresolved item with its own `Assign` button, and a footer headed **“Done here? Your picks are saved.”** with two exits, `I'll pick more later` and `I've picked all mine`. When every line is correctly resolved, show the completion review and `Finish splitting`. All exit actions open expense details; Back from details returns to group home. |

The chooser's explanation must make shared responsibility explicit. Suggested copy for its phone option: **“Assign yours now, or choose for anyone. Others in this group can pick their items later.”** The unfinished state must repeat that promise next to its exit buttons (**“Others in this group can pick the rest later.”**), so a creator does not believe they are required to fill out the whole bill alone. Do not imply that finishing later pays or settles the expense.

All saved-bill entry points—newly saved bill, unresolved bill on group home, and `Claim items` on expense details—lead to the continuation chooser. A member sees the saved bill and current claims before choosing a path. Keep the bill link distinct from the permanent group invite.

## 3. Assignment ordering and state

An item is **complete** only when its `ItemStatus` is `RESOLVED`: all physical units are assigned and none is overclaimed. One selected person on a multi-quantity line is not enough if units remain. `OVERCLAIMED` needs correction and stays in the unfinished section. Progress counts complete **items**, not people, servings, claimed dollars, or `done_at` markers. Counts and the review gate derive from the current `BillView.reconcile`, never from local chip selection alone.

The accordion tracks one active item ID. Tapping another header closes the active card and opens the tapped one. A successful assignment updates its chips, totals, and progress immediately after the repository reports/observes it, but does not move the active card under the user's finger. On opening a different card, the previous complete item joins the completed section at the bottom. Existing completed cards retain their relative positions when opened, expanded, collapsed, or edited without losing completion. Unfinished items retain receipt order. A card's receipt number remains visible as a reference after it moves. Keep this in-memory ordering state through a push to partial review and Back to assignment; discard it only when the person exits the bill flow. A later opening of the bill creates a fresh session.

Ordering is keyed by stable `expense_items.id`, never label or current list index. Newly added items get their receipt order from `sort_order`. The completion sequence is **screen-session state only**: when a bill is reopened, partition its current items into completed and unfinished, each initially in receipt order. From that point, newly completed items append at the bottom of the completed section. If a completed item becomes unfinished, remove it from the completed section; when it becomes complete again, append it as a new completion. Editing a completed item without making it incomplete leaves its current position unchanged. No completion-order column, migration, or cross-device ordering guarantee is needed.

Deleting an item removes it from both sections, with existing soft-delete behavior. A remote claim or edit updates completion counts without stealing focus or unexpectedly scrolling the active card; after the active card changes, the sections reconcile with the latest bill. Progress uses true current status, while a newly completed active card can remain visually anchored in its prior position until focus moves. Section badges count cards actually displayed in each section during that brief anchored state, so their labels do not claim the card has already moved.

## 4. Incomplete work, sharing, and finishing

`Review assignments` opens a **partial review** even when items remain unresolved. It shows the current derived totals, a `Not assigned` row carrying the unassigned remainder so the visible person totals are never mistaken for the full bill total, and one `Still needs someone` card listing the unresolved items. Remaining work is shown once: no separate remainder banner, no duplicate remaining list. Each unresolved row has an `Assign` button that opens that item's editor; there is no separate `Continue assigning` button. Every item assignment stays reachable in receipt order through `See all N items in receipt order`; tapping any assignment returns to that item's editor. The full-completion hero and `Finish splitting` appear only when every line is resolved. On the QR path, use **“Everyone has picked”** only when all participants have also marked themselves done; if the lines are assigned but some people have not marked done, use the truthful **“All items are assigned”** message and still allow the phone holder to finish their part.

The partial review offers **both** exit choices while other items remain unassigned. They sit in a footer headed **“Done here? Your picks are saved.”** so both read as leaving the page, and each label describes the state of the viewer's own picks rather than whether they stay. Do not use `Not yet` or a bare `Finish later`: both read as "keep me on this page".

- **`I'll pick more later`** (secondary) saves nothing new, leaves the current person's `done_at` unset, and means they may continue picking later.
- **`I've picked all mine`** (primary) calls `BillRepository.markDone` for the current person only, even if others have not picked or there are unassigned items. It means the current person is done choosing their own items; it does not assert that all items are assigned. It stays valid for a viewer with no items on the bill.

Both choices preserve the saved expense and successful claims, and group members keep an unresolved-bill entry on home while any item remains unassigned. Both exits use the same expense-details → group-home Back path as full completion. A failed done-marker write stays on review with an error and retry. No global completion flag is introduced.

Members of the bill's group can open the unresolved bill from home and pick items on their own phone. The app must distinguish the creator's unfinished setup from an individual member's need to claim. The existing `bill_participants.done_at` is a per-person nudge-silencer, not a bill lock. A member who has marked done can still return and edit until normal bill/link permissions disallow it. The bill remains unresolved while any line needs someone **or** any participant has not marked done.

The web path uses a permanent, per-expense link and the existing browser claim flow. Opening the QR screen creates the link if needed and recovers the same token for any active group member through a membership-checked server RPC. An offline failure stays visible and offers retry; the user can return to the chooser for phone assignment. The mobile app's claim-progress screen must show real current participants, their `done_at` state, and unassigned line identities; the current screen only lists outstanding people and a money remainder. The QR screen retains the scope reassurance and revoke control.

`Finish splitting` on a fully resolved review marks the phone holder done via `BillRepository.markDone`, then navigates to this bill's expense detail with the intermediate flow removed from the back stack. Back from detail lands on the originating group home, even if the flow was opened from expense details; do not leave a duplicate detail route underneath. Failure to save the done marker is shown with a retry; navigation must not report success first. Marking this participant done does not mark other participants done or settle the expense.

| State | Required behavior |
| --- | --- |
| Empty draft | Keep the scan sources and manual `Add item` action visible. Saving explains the missing title, people, or named item on tap. |
| Saved bill still loading | Paint the page and show a shaped loading state, then current bill data; never render a blank route. |
| Scan blocked, failed, or cancelled | Show a truthful retry/manual path. Preserve already staged receipt bytes unless the user explicitly removes them. |
| Camera permission denied | Use the existing `CameraPermissionGate`; Photos, file/PDF, and manual entry remain usable. |
| Offline assignment | Apply only writes that the repository can save locally; show pending sync. A write that requires the join RPC reports failure and leaves the affected chip unselected. |
| Offline QR/link action | Explain that the bill link needs a connection, allow retry, and keep Back to the chooser available for phone assignment. Do not show a QR for an unconfirmed token. |
| Bill edited or removed on another device | Reconcile by stable item ID, preserve the active item if it still exists, and explain if it was removed. Never use stale indices to assign a different line. |
| No remaining item, but participants not done | Show accurate item completion and people progress separately. Do not claim all people are done based only on assigned items. |

## 5. Acceptance criteria

1. Given a new scanned receipt, when OCR succeeds, then the user can compare it in-app, correct items, quantities, prices and extras, add a missing item, and save only after confirmation. No expense exists before `Save & continue` succeeds.
2. Given manual entry or a cancelled/failed scan, when the user enters a valid bill manually, then the same saved-bill chooser is reachable without scanning.
3. Given a saved bill with existing claims, when any member reopens it, then the chooser says they are continuing, shows current progress, and either method resumes the same bill without clearing assignments.
4. Given an unassigned simple item, when a chip is selected, then the assignment is saved for that person and the derived totals update. Multiple selected people split the same line; `Everyone` selects the current roster.
5. Given a multi-quantity item, when only some servings have assignees, then it remains unfinished. Once every serving is assigned without overclaim, it becomes complete.
6. Given completed items A and C, when unfinished B is assigned while open, then B remains open in place. When another item opens, B moves to the bottom of the completed section: A, C, B. Editing C while it remains complete leaves A, C, B unchanged.
7. Given unassigned or overclaimed items, when `Review assignments` is tapped, then a partial review shows current totals, the unassigned remainder as a `Not assigned` row, the exact missing items each with an `Assign` action, and the footer “Done here? Your picks are saved.” with `I'll pick more later` and `I've picked all mine`. A complete-only success message is not shown.
8. Given the creator assigns only their own items and leaves, when another group member opens the bill from home, then they can see the prior assignments and add theirs. The creator's partial work remains saved.
9. Given a saved bill, when any active group member opens the QR path, then the screen creates or recovers the same permanent QR automatically, shares the existing browser claim flow, and claim progress updates without losing the chooser's phone-assignment path.
10. Given every line is `RESOLVED`, when the review screen opens, then per-person totals and line assignees match the domain allocator. When `Finish splitting` succeeds, only the current person's done marker changes, expense detail opens, and Back returns to group home.
11. Given a repository or link write fails, when the user acts, then the screen reports that the action did not land and provides a retry. No false completion or silently changed chip is shown.
12. Given a partial review, when `I'll pick more later` is tapped, then the person's done marker remains unset and their assignment progress persists. When `I've picked all mine` is tapped, only that person's done marker is set, even if the bill remains unresolved. Either exit opens one expense-detail destination over group home.
13. Given a QR bill with all lines assigned but one participant not marked done, when review opens, then it says `All items are assigned` rather than `Everyone has picked`.

## 6. Code map and implementation plan

| Area | Current behavior | Work needed |
| --- | --- | --- |
| `ui/screen/expense/AddExpenseScreen.kt`, `bill/ItemizedExpenseBody.kt` | Split-type chooser, restaurant editor, direct scan sources, inline editable items and extras, `Save & assign items`. | Give scanned OCR its own review step before creation; make the save action `Save & continue`; keep manual entry and receipt viewer connected. |
| `ui/navigation/LedgerRoutes.kt`, `ui/navigation/BillRoutes.kt` | Stage scan files, call OCR, attach receipts, create/edit bill, map `BillView` to claim UI. | Preserve draft through review; route newly saved bills to the method chooser; map current progress and ordering metadata. Keep route wrappers DI-facing and screens callback-only. |
| `ui/navigation/Route.kt`, `EvenlyNavHost.kt` | `AddExpense` creation jumps to `ClaimBill`; expense detail and unresolved rows also jump there. | Add/repurpose typed routes for chooser, phone assignment, partial/full review and progress; ensure every saved-bill entry reaches the chooser and final navigation has the requested Back behavior. Preserve in-memory accordion order while review is pushed. Use string nav args on Native. |
| `ui/screen/bill/BillClaimScreen.kt`, `BillWebClaimEntry.kt` | One long screen combines totals, web doors, guide, all expanded cards, and Done. | Extract new accordion and chooser/review surfaces into separate files; keep the serving sheet, participant chips, add-person path, web edit history, and link/progress doors reachable. The current 598-line file must not grow. |
| `ui/screen/bill/BillShareLinkScreen.kt`, `BillClaimProgressScreen.kt`, `ui/navigation/WebClaimRoutes.kt` | QR creation is automatic and permanent; progress lists people without a line-by-line `Assign it` route. | Match revised frame 5B and frame 6B, keep the link table closed behind membership-checked RPCs, show all people and remaining items, and wire `Assign it` to the relevant item. |
| `ui/screen/group/UnresolvedBills.kt`, `GroupTabRoutes.kt`, `ExpenseDetailScreen.kt` | Unresolved bills already appear on home; detail has a claim-items door. | Copy and routing must communicate “pick yours, others can pick theirs” and distinguish your own done state from overall bill progress. |
| `domain/expense/Bill.kt`, `BillSplit.kt`, `domain/repository/BillRepository.kt` | `BillView` has ordered items, `ItemStatus`, derived tabs and per-person done markers. | Add a pure accordion-order reducer/presenter; change allocator only if a separately verified money defect is found. |
| `data/repository/BillRepositoryImpl.kt`, Room `expense_items`/claim/share/participant entities | Claims and servings are local-first; receipt order and per-person done status are stored. | Reuse persisted claims and done markers. Completion order stays in the screen session, so the synced schema is unchanged. |
| `design/system/dist/catalog.js` | Catalog says assignment-first and records the older screen flow. | On implementation, update the screen/route/flow entries and run `node design/system/scripts/check-design-system.mjs`. |

## 7. Proof plan

| Criterion | Proof |
| --- | --- |
| Receipt review and save boundary (1–2) | Screen state tests for OCR success/failure/manual paths plus an iOS simulator walkthrough; repository test proves no bill before save and receipt attachment on save. |
| Chooser resumes existing work (3, 8) | Navigation/state test entering from create, group home, and expense detail; reopen after partial assignment and verify item IDs, claims and totals unchanged. |
| Assignment, money and multi-serving status (4–5, 10) | Existing `BillSplitTest`/`BillRepositoryTest` plus focused regression vectors for exact derived totals and `RESOLVED`/`UNCLAIMED`/`OVERCLAIMED`. If money rules change, update the TypeScript port and shared vectors in the same implementation commit. |
| Completion order (6) | Pure common-test reducer tests: A/C completed, assign B, keep B anchored while open, open D => A/C/B; edit C complete => same order; unassign/recomplete => B appends again; re-open the bill => current completed/unfinished groups each reset to receipt order; remote status update does not steal focus. Run on Android host and iOS simulator. |
| Partial review and exit choices (7–8, 12–13) | UI state/navigation test and a cold iOS simulator walk: no false success, visible handoff copy, both exit actions, their distinct done-marker effects, accurate QR completion copy, and a member resuming from group home. |
| QR and progress (9, 11) | Verify automatic create/recovery, same-token reuse across two active group members, revoked/offline states, and a manual QR scan to the current web bill with a second-device claim observed on progress. |
| Final Back stack and done state (10) | Navigation test with a real group entry, then on-device Back from expense details; repository assertion that only current participant's `done_at` changed. |

Implementation green gate: `:shared:compileAndroidMain`, `:shared:compileKotlinIosSimulatorArm64`, `:shared:linkReleaseFrameworkIosArm64`, `:androidApp:assembleDebug`, `:shared:testAndroidHostTest`, and `:shared:iosSimulatorArm64Test` from `code/`; then launch the iOS simulator and leave the app running. Before calling changed UI done, walk it with `ux-firsttimer` in build-time mode and clear P0/P1. The existing design file is the pre-Compose mockup.

## 8. Proposed slices

1. Receipt review and saved-bill chooser, including resumption from all entry points.
2. Phone accordion, explicit completion order and regression tests.
3. Partial review/finish later and group-member handoff.
4. QR/progress update and shared final review; Back-stack and done-marker verification.

Each implementation slice should be verified on both platforms before its feature commit.

## 9. Completion report

- **Built:** receipt review (`ReceiptReviewScreen`), chooser (`BillChooserScreen`, `Route.FinishBill`), accordion
  (`BillAssignScreen`/`BillAssignCard`, ordering in `domain/expense/AssignOrder.kt`), review (`BillReviewScreen`,
  `Route.BillReview`), progress and share updates, home and expense-detail entry copy. `BillClaimScreen.kt` removed.
- **Multi-serving lines** open the existing `ServingsSheet` from the card; the per-serving rows in frame 6A are not
  drawn inline (owner's choice).
- **Proof:** `AssignOrderTest` (12) and `BillProgressTest` (3) on JVM and Native; iOS simulator walk of manual entry →
  chooser → accordion ordering (A, C then B) → partial review → "I'll pick more later" → details → group home →
  resume → servings → complete review → Finish splitting → details → group home, plus QR and progress screens.
- **Not exercised on device:** live link creation and a second-device claim (both need a server write), and the
  OCR success path (no receipt image on the simulator); the review step is wired to the same scan result.
