# One-time splits (quick splits) + two-party payment edits

**Status:** Part B (B1-B4) built and its migration applied 2026-11-21; `web-claim` not redeployed (see §7). Part A not started.
**Owner agent/session:** unassigned
**Mockup:** `design/one-time-splits-mockup.html` (screens 1–6 quick split, 7–11 payment edits)

This brief carries two features, because the second came out of designing the first and applies to
groups too. They ship as separate slices (§6), and Part B can ship first.

---

## 1. Behavior brief

### Part A · Quick splits

**Problem.** The only way to split anything is to create a group first. A user who splits a dinner or
an Uber with different people each time either skips the app or ends up with a long list of groups
that each hold a single expense. Creating a group asks them to decide "these people are a recurring
thing" just to settle one bill.

**User outcome.** From Home, a user can split one bill (by items, evenly, or by amounts) with people
they name on the spot, share one link, and track who has paid, without ever creating or seeing a group.

**Shape (decided).**
- Under the hood a quick split is a **hidden group**: `groups.kind in ('group','quick')`, holding one
  expense. Members, typed-in names (placeholders), web claim, settlements, merge and history are
  reused unchanged. There is no second data model. (Because it is a group, converting one into a normal
  group later would just flip `kind`, but no conversion or suggestion is in scope.)
- Flow: **+ New → Split a bill → add the bill (scan or type) → type names → share link** (mockup 1–4).
  The split method (by items / evenly / amounts) is picked on the people step.
- People are **names only** in v1. Each friend taps their own name on the link (mockup 5), which
  claims the typed-in placeholder rather than creating a duplicate.
- Home is **one list**, no tabs or new sections. A quick split is a card with avatars and "2 of 3
  paid". A day after it is fully settled it moves to History (mockup 6). Home does not suggest
  turning repeat splits into a group (removed 2026-11-19 to keep Home quiet).

**Phasing.**
- **v1:** everything above.
- **v2:** "From contacts" uses the system contact picker (no contacts permission on either OS). It
  fills in the name and stores the number on the placeholder so the user can text the link from their
  own Messages app.
- **v3 (not scheduled):** verified phone numbers, so a contact who already has the app gets a push
  instead of a text. Needs SMS OTP and number matching; we store no phone numbers today.

### Part B · Two-party payment edits (groups and quick splits)

**Problem.** A recorded payment is effectively permanent. Someone who typed $15 instead of $5, or a
creditor who never received the money, has no clear way to fix it. Payments recorded through "Settle
up" (covering several bills) cannot be edited at all, and history doesn't show a correction as a
correction.

**User outcome.** For any payment "Alice paid Bob", Alice or Bob (and only they) can change the amount
or remove it at any time. The change applies immediately, the other person is notified, and history
shows who changed it, from what, to what.

**Rules (decided).**
- **Who:** only the two parties to the payment (the one paying back and the one owed). Not the group
  admin, not other members. Enforced server-side, not only hidden in the UI.
- **When it applies:** immediately. There is no pending or accept state.
- **Notify:** the other party gets a short push: title "Bob edited your $15.00 payment", body
  "Groceries: your Nov 19 payment changed from $15.00 to $5.00." It names the bill, the payment's date,
  and the old and new amounts, and nothing else: no remaining balance and no "you owe".
- **Keep payment screens minimal.** No balance follow-ups ("Alice will still owe you…"), no
  "we'll tell them" lines, and no permission explanations on the edit sheets. Too much information is a
  known problem for users; the history entry is the record.
- **Settle-up payments (several bills):** the user edits the **total** only. The per-bill "Now covers"
  list is a read-only preview; there is no per-bill editing in this sheet. If the user needs exact
  control, editing the payment from one bill's screen changes only that bill.
  - **Reducing** cuts from the **newest** covered bill first, then the next newest. Each bill can only
    go down from what this payment had put on it, so money never moves onto a bill the payment didn't
    originally cover.
  - **Increasing** fills the **oldest unpaid** bill first, the same rule `applySettlement` uses for a
    new payment, capped at what is still owed.
- **Confirm step:** the existing "We opened Venmo. Did the payment go through?" sheet gets an
  **editable amount** (pre-filled with the full share), so a partial payment is recorded in one step.
  Saving it counts as paid; nobody has to confirm it later.
- **Web guests** (quick-split friends with no account) can correct their own payment from the same
  link in the same browser. The person owed can always correct it from the app.

### Non-goals
- **Billing / Pro.** Quick splits ignore `group_passes` and any group limit for launch. Tracked as a
  separate follow-up.
- No change to the split engine, balance math, or the itemized claim model.
- No contact upload, no address-book sync, no server-sent SMS (in any phase).
- Part B does not add an approval or dispute flow.
- Existing groups are not converted or migrated into quick splits.

**Platforms and systems affected.** Android, iOS, `supabase/` (column, RLS, RPCs, `web-claim`),
`web/` (name picker, payment edit), `domain/`, `data/`, `ui/`, `platform/` (contact picker, v2 only).
Read `data/AGENTS.md`, `ui/AGENTS.md`, `supabase/AGENTS.md` and `web/AGENTS.md` first.

---

## 2. Acceptance criteria

### Part A
- Given Home, when I tap + New, then "Split a bill" is the first option and "Start a group" the second.
- Given I scan a receipt or type a title and total, add three names and tap "Create & share", then the
  share sheet opens with the bill's link, and nothing called a "group" was shown at any point.
- Given a quick split, then it never appears inside any group's expenses, and no group screen
  (settings, members strip, invite) is reachable from it.
- Given a quick split, then Home shows it as a card in the same list as groups, with how many people
  have paid and what I am owed or owe.
- Given a friend opens the link on the web or in the app, when they tap their name, then they claim
  that typed-in name (no second "Ann" is created) and see their share and the pay buttons.
- Given someone not on the list opens the link, when they tap "I'm not on this list", then they can
  add themselves (existing web-claim behaviour).
- Given every person has paid in full, then the card shows "All settled" and moves to History about
  24 hours later; it stays reachable there.
- Given the link is opened on a phone with the app installed, then it opens in the app; otherwise it
  stays on the web.
- Home's headline balance includes quick splits. (The original brief proposed separate totals; this was
  replaced because the question people ask is "what does Joe owe me overall".)

### Part B
- Given Alice paid Bob $15, then both Alice and Bob can open the payment and choose "Change amount" or
  "I didn't pay this" / "I didn't get this"; Steve sees the row but cannot open an edit.
- Given Steve calls the server directly to edit Alice→Bob, then the write is refused.
- Given Bob changes it to $5, then balances update immediately on both devices, Alice gets the push
  "Bob edited your $15.00 payment" / "Groceries: your Nov 19 payment changed from $15.00 to $5.00"
  (no balance in it), and the history of each affected bill shows "Bob changed Alice's payment
  $15.00 → $5.00" with the time.
- Given a $15 settle-up payment covered Groceries (Nov 12) $9 and Internet (Nov 15) $6, when either
  party changes it to $5, then Internet drops to $0 and Groceries to $5, and no other bill changes.
- Given the same payment, when either party changes it to $17, then the extra $2 goes to the oldest
  bill that still has money owed.
- Given any total edit, then the sheet previews the per-bill result (read-only) before saving.
- Given a payment edited from a single bill's screen, then only that bill's portion changes.
- Given the new amount is above what is owed, then Save stays tappable and shows the ceiling on tap
  (existing guard: a correction can't create an overpayment).
- Given the confirm sheet after opening Venmo/Zelle, then the amount is editable and "Yes, I paid"
  records exactly what was entered.
- Given a web guest paid from the link, when they reopen it in the same browser, then they can change
  or remove their own payment, and the person owed is notified.

**States that are not the happy path.**

| State | Expected behavior |
| --- | --- |
| Empty | Quick split with only "You": Create stays live; tapping explains "Add at least one person". |
| Loading | Home cards use the existing skeletons (`loading-vs-empty-states.md`). |
| Error (server rejected) | Payment edit refused (not a party, or over the ceiling): the sheet stays open with the reason; local state is rolled back. |
| Offline / queued | Creating a quick split and editing a payment both work offline and sync later, like every other write. The share link needs the network; the button says so on tap. |
| Permission denied | v2 contact picker returns nothing: stay on the names screen, no error. |
| Stale local vs newer server row | Two parties edit the same payment at once: the later edit wins and both edits appear in history. |

---

## 3. Constraints

- **Server first.** `groups.kind` (`text not null default 'group'`) lands in `schema.sql` before the
  Room entity change (AGENTS.md §4.4). Older clients will see quick splits as ordinary groups. That
  is acceptable, but confirm it before shipping.
- **Per-actor RLS is new.** Today any ACTIVE member can edit or void any settlement
  (`supabase/AGENTS.md`, "Membership gates which group you may write to, not which row"). Part B needs
  a settlements policy (or a definer RPC) that allows updates only where `auth.uid()` is
  `from_user_id` or `to_user_id`. A typed-in name can't sign in, so for a placeholder party only the
  real party (or the web guest bound to that placeholder through `web-claim`) can edit.
- **History.** An edit today is void + re-record, and the history row says neither. Part B writes an
  `expense_history` row per affected expense with type `payment_edited` and old/new/actor in `detail`.
- **Push is not live.** `push-notify` is inert until `FCM_SERVICE_ACCOUNT` is set, and the iOS host
  SDK is deferred. Until that's done, "notify" means the history entry plus an in-app activity badge.
  Name this gap in the completion report; don't claim the push works.
- **Realtime.** No new tables in the publication; the `group_activity` doorbell covers quick splits
  because they are groups.
- **Soft delete only.** Removing a payment voids it (`deleted_at`), as today.
- **Privacy.** v2 stores a phone number on a placeholder `users` row, which is new PII. It needs a
  column, RLS review (readable by co-members only, like names), and a mention in the privacy policy.
- **Rollback.** `kind` is additive. Part B's policy can be reverted to the membership policy.

---

## 4. Test plan

| Criterion | Proof | Where |
| --- | --- | --- |
| Quick split excluded from group lists, included in Home total | Unit test on the Home projection | `commonTest` |
| Reduce cuts newest-first capped per bill; increase fills oldest unpaid; per-bill edit touches one bill | Unit tests on `SettlementRepositoryImpl.editSettlement` | `commonTest` |
| Only the two parties can edit | SQL check: as a third member, update/void Alice→Bob affects 0 rows | `supabase/` |
| History row on edit | Repository test asserts one `payment_edited` row per affected expense | `commonTest` |
| Web guest edit | `web-claim` function test for the new action, including a guest bound to a different name | `supabase/functions/web-claim` |
| Flow and copy | iOS sim walk of mockup 1–6 and 7–10; `ux-firsttimer` as Sam and Diego | Manual |

Green means both targets (AGENTS.md §4.1), plus `linkReleaseFrameworkIosArm64` before release.

---

## 5. Scout findings (2026-11-19)

- **Nothing was implemented before.** The only prior artifact is this file's first draft (commit
  19bc91d). No branch carries quick-split code.
- **Everything is group-scoped:** `expenses.group_id`, `members`, placeholders
  (`users.placeholder_group_id`), web guests (`create_web_placeholder`, `web_sessions`),
  `web_bill_links`, settlements and allocations. That is why the hidden group is the cheap path.
- **Payment editing exists, partially:** `SettlementRepository.editSettlement` (void + re-record,
  single-expense only, with an overpayment ceiling) and `voidSettlement`, reached from the Payments
  card in `ui/screen/expense/ExpenseDetailScreen.kt` (`actionPayment`). There is **no party check**:
  any member can tap any payment.
- **The confirm step exists:** `ui/screen/settle/SettleSingleSheet.kt` ("We opened … Did the payment
  go through?") and `DeepLinkConfirmSheet.kt`. Neither lets you change the amount.
- **Home today** (`ui/screen/home/HomeScreen.kt`): "Active groups" section, "Create or Join Group"
  CTA, `NewGroupSheet`.
- **Open question for implementation:** whether the web name picker reuses the `share` action
  (names entered by the payer) or needs a list endpoint. Check `web-claim`'s `/bill` response first.

---

## 6. Slices

Part B first: it is smaller, fixes a live complaint, and Part A's payment screens depend on it.

- [x] B1 — Server: party-only settlement policy/RPC, `payment_edited` history rows.
- [x] B2 — App: party gating on the payment sheet; edit a settle-up total with re-spread preview.
- [x] B3 — Confirm sheet with editable amount.
- [x] B4 — `web-claim` action + web UI for a guest correcting their own payment.
- [ ] A1 — `groups.kind` server + Room; hidden from group lists; Home card + combined total.
- [ ] A2 — + New chooser, Split a bill screen, names step, create & share.
- [ ] A3 — Web/app link opens on the name picker; claiming a typed-in name.
- [ ] A4 — Auto-move to History once fully settled.
- [ ] A5 (v2) — System contact picker + text the link.

---

## 7. Completion report

### Part B (B1-B4), 2026-11-21

**Built.**
- **B1 server** (`supabase/schema.sql`, end of file): `settlements.original_settlement_id`;
  `settlement_party_guard` (trigger) refuses amount/currency/void changes and edit replacements unless the
  caller is a party; `settlement_allocation_party_guard`; `settlement_chain_last_wins` (two concurrent edits
  leave one live payment, arrival order); `record_web_payment` / `edit_web_payment` (service-role only).
  A trigger rather than a policy: see `supabase/AGENTS.md` ("Membership gates…").
- **B1/B2 app**: pure rule `domain/settlement/PaymentEdit.kt` (`respreadPayment`: cut newest-first, increase
  oldest-unpaid, single-bill scope); `SettlementRepository.editSettlement(…, scopeExpenseId)`,
  `previewSettlementEdit`, `voidSettlement(…, actor)`, `observeExpensePayments`. Non-parties get
  `AppError.NotAuthorized`. History: one `SETTLEMENT_EDITED` row per affected bill
  (`edit:<oldTotal>:<newTotal>:<payer>:<newOnThisBill>`) and `SETTLEMENT_REMOVED` (`removed:<amount>:<payer>`).
- **B2 UI**: `ui/screen/settle/PaymentSheets.kt` (mockups 8, 9). Bill screen: settle-up payments now
  appear with this bill's portion; only parties get the chevron; edits there touch only that bill.
  Settle-with-a-person screen: new "Payments" list; editing there changes the total with the read-only
  "Now covers" preview.
- **B3**: `DeepLinkConfirmSheet` has an editable amount ("You paid Bob $X of $Y", "Yes, I paid").
  `SettleSingleSheet` already had the field; its confirm button now reads "Yes, I paid".
- **B4**: `web-claim` actions `payments` / `pay` / `payment`; `web/src/screens/MyPayment.svelte` (mockup 11).

**Deviations from the brief, on purpose.**
- History type is the existing `SETTLEMENT_EDITED` wire name, not `payment_edited`: renaming a synced enum
  would hide every existing row on old clients. `SETTLEMENT_REMOVED` is new; old clients drop unknown types.
- Web guests could not record a payment at all before this, so B4 includes "I paid {payer}" on the bill
  (one bill only, never a balance). `web/AGENTS.md` notes the exception.

**Not done / not verified.**
- Migration applied to the live project 2026-11-21 (`payment_edits_party_guard`).
  `supabase/tests/payment_edits.sql` passed in full against it, and `get_advisors` flags none of the new
  functions (none is executable by `anon` or `authenticated`).
- `web-claim` is not redeployed. No Deno on this machine, so the function was not type-checked.
- **Push is not live** (`FCM_SERVICE_ACCOUNT` unset, iOS host SDK deferred). "The other person is
  notified" is the history row only. No in-app activity badge was added.
- A refused settlements push still wedges that table's batch on the device (pre-existing behaviour for
  any RLS refusal). The app checks party-ness first, so a legitimate client should not hit it.
