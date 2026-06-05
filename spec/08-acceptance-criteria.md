# 08 — Acceptance Criteria

> Testable behaviors that gate each milestone. Implementing agents MUST treat each numbered AC as a separate, automatable test. Format: `Given / When / Then`. AC ids are stable; they MUST be referenced from PR descriptions and from automated test names.
>
> A milestone is "done" iff every AC under it passes on Android and iOS, with no regressions in prior milestones.

---

## M1 — Identity & groups

### Auth

**AC-M1-001** — Google sign-in
- **Given** a fresh install
- **When** the user taps "Continue with Google" and completes OAuth
- **Then** a `users` row exists with their Google email; the app lands on onboarding

**AC-M1-002** — Apple sign-in is available
- **Given** a fresh install on iOS
- **Then** "Continue with Apple" is shown above the other OAuth options

**AC-M1-003** — Email magic-link
- **Given** a fresh install
- **When** the user enters an email and taps "Send magic link" → opens the link from the email
- **Then** the app completes auth and routes to onboarding

**AC-M1-004** — Sign out wipes local state
- **Given** a signed-in user with at least one cached group
- **When** they sign out
- **Then** Room is empty; SecureStorage no longer contains tokens; the FCM token is unregistered server-side

**AC-M1-005** — Account deletion
- **Given** a user who is admin of one group and member of another
- **When** they confirm "Delete account"
- **Then** their `users.deleted_self_at` is set; admin auto-transfers to the longest-tenured member; their `payment_app_handles` and `device_push_tokens` are deleted

### Onboarding

**AC-M1-006** — Onboarding can be completed with no payment app
- **Given** a new account
- **When** the user skips the payment-handle step
- **Then** they land on Home; their `payment_app_handles` row is empty; the warning text is shown

**AC-M1-007** — Telemetry consent step (default ON, opt-out, OQ-07)
- **Given** onboarding
- **Then** an "anonymous analytics & crash data" toggle is shown, defaulted ON, with copy stating no PII is collected
- **And** if the user turns it OFF, Firebase Analytics and Crashlytics collection are disabled (`setCollectionEnabled(false)`); the same toggle is reachable later at Profile → Privacy

**AC-M1-008** — Base currency is explicit and drives display (OQ-04, D-27)
- **Given** onboarding
- **When** the user sets base currency to `GBP`
- **Then** `users.base_currency = 'GBP'`; it is editable later in Profile → Base currency
- **And** when that user creates a new group without choosing a currency, the group's `base_currency` defaults to `GBP` (the creator's), not `USD`

### Groups: lifecycle

**AC-M1-010** — Create group
- **When** the user creates a group with name "Trip" and emoji "🏖"
- **Then** a `groups` row exists with `admin_user_id = creator`; the creator is an active member; default categories are seeded

**AC-M1-011** — Invite token is generated and persistent
- **When** a group is created
- **Then** `groups.invite_token` is set and is URL-safe ≥ 22 chars

**AC-M1-012** — Rotate invite token (admin only)
- **Given** a non-admin member
- **When** they attempt `rotate_invite_token` via direct RPC
- **Then** the call returns error code `NOT_ADMIN` (admin-only rotation is settled per D-12 and OQ-01; the spec is internally consistent on this — `01 §7`, `03 §7.1`, `04 §2.3`, `05 §9`)
- **And** when the admin rotates, the previous token stops resolving and `invite_token_rotated_at` is stamped

**AC-M1-013** — Join via valid invite token
- **Given** an invite token for an existing group
- **When** a different signed-in user calls `join_group_by_token(token)`
- **Then** they become an active member; placeholder candidates list is returned (possibly empty)

**AC-M1-014** — Join is idempotent
- **Given** a user who is already an active member
- **When** they call `join_group_by_token(token)` again
- **Then** no duplicate `members` row is created; the response indicates "already joined"

**AC-M1-015** — Leaving auto-transfers admin
- **Given** group with members [A (admin, joined day 1), B (joined day 2), C (joined day 3)]
- **When** A leaves
- **Then** `groups.admin_user_id = B`; B's `is_admin = true`; A's `members.status = 'LEFT'`

**AC-M1-016** — Abandonment leaves group readable
- **Given** a group with only member A
- **When** A leaves
- **Then** `admin_user_id` is NULL; A can still read the group's history from their "Left groups" section

### Members & placeholders

**AC-M1-020** — Add placeholder participant
- **Given** a group with member A
- **When** A creates an expense and types "Tyler" as a participant not in the member list
- **Then** a `users` row with `is_placeholder = true, display_name = 'Tyler'` is created; a `members` row joins them to the group

**AC-M1-021** — Placeholder name uniqueness (case-insensitive)
- **Given** a placeholder "Tyler" exists in group G
- **When** any member tries to add a placeholder "tyler" or "TYLER"
- **Then** RPC returns `PLACEHOLDER_NAME_TAKEN`

**AC-M1-022** — Placeholder cannot be admin
- **Given** any group
- **Then** no placeholder user_id appears as `groups.admin_user_id`; attempting to set it raises an error

### Placeholder claim & merge (reconcile flow)

**AC-M1-030** — Reconcile screen lists all placeholders
- **Given** a group with placeholders P1 (used on E1, E2), P2 (used on E3)
- **When** Tyler joins via invite link and reaches the reconcile screen
- **Then** both P1 and P2 are shown; each shows a chip strip with the expenses they're on (amount + currency + truncated title)

**AC-M1-031** — Reconcile selection visual contract
- **Given** the reconcile screen
- **When** the user taps a placeholder card to select
- **Then** the card displays a clear green outline (2dp, `accent.primary` token); tapping again removes the outline (UI test verifies the rendered border)

**AC-M1-032** — Confirmation modal shows expense list with placeholder name
- **Given** the user has selected P1 and P2 and tapped "These are me (2)"
- **Then** the confirmation modal lists every expense (E1, E2, E3) with the placeholder name used per expense ("Listed as 'Ty'")

**AC-M1-033** — `claim_placeholders` rewrites all references atomically
- **Given** Tyler confirms claim of P1 and P2
- **Then** all `shares.user_id`, `expenses.payer_user_id`, and `settlements.from/to_user_id` references to P1/P2 become Tyler's `user_id`; P1, P2 rows are deleted; `history_events` of type `PARTICIPANT_MERGED` are appended to each affected expense
- **And** the operation is atomic (a failure mid-way rolls back all rewrites)

**AC-M1-034** — Happy path: zero placeholders skips modal
- **Given** a group with no placeholders
- **When** a new user joins via invite link
- **Then** the reconcile screen is skipped and the user lands on Group home directly

**AC-M1-035** — Re-entry into reconcile
- **Given** a user who joined via invite and selected zero placeholders
- **When** they later navigate to Group settings → Members
- **Then** a "Find me in past entries" affordance is visible until they perform their first successful run (which sets `placeholder_claim_completed_at`)

### Archive

**AC-M1-040** — Archive hides group from Home
- **Given** a user in group G
- **When** they archive G
- **Then** G no longer appears in Home; G appears in Profile → Archived groups

**AC-M1-041** — Archive silences notifications (D-11)
- **Given** an archived group G for user A
- **When** another member adds an expense involving A
- **Then** no push notification fires to A (dispatch_push consults archived state)

**AC-M1-042** — Other members unaffected
- **Given** A archived G; B did not
- **When** any expense is added in G
- **Then** B still receives the push (subject to B's notification prefs)

**AC-M1-043** — Unarchive on deep-link entry
- **Given** A archived G
- **When** A navigates to G via a universal link (e.g., from a forwarded email)
- **Then** G unarchives automatically; pushes resume

**AC-M1-044** — One-time auto-unarchive tooltip (OQ-02)
- **Given** A has never seen the auto-unarchive tooltip
- **When** an auto-unarchive happens (per AC-M1-043, or by opening an archived row)
- **Then** a dismissible tooltip explains the group was unarchived because A opened a link to it
- **And** the tooltip never shows again for A (one-shot local flag), even on subsequent auto-unarchives of other groups

---

## M2 — Expenses & balances

### Add expense

**AC-M2-001** — Create EVEN split
- **Given** group [A, B, C], A creates expense $30 EVEN
- **Then** three `shares` rows of $10 each are created; `SUM(share_owed_subunits) == amount_subunits`

**AC-M2-002** — Largest-remainder rounding (cent-perfect)
- **Given** $10 EVEN among 3
- **Then** shares are [$3.34, $3.33, $3.33] in member-join-date order; sum == $10.00

**AC-M2-003** — BY_SHARE split
- **Given** expense $100 with weights {A:1, B:2, C:2}
- **Then** shares are [A:$20, B:$40, C:$40]

**AC-M2-004** — BY_PERCENTAGE 4-decimal precision
- **Given** $100 split 33.3333%, 33.3334%, 33.3333%
- **Then** shares are within 1 cent of expected; sum == $100.00

**AC-M2-005** — BY_EXACT rejects mismatched total
- **Given** $100 with exact shares summing to $99.99
- **Then** Save is disabled; UI shows "Off by $0.01" delta

**AC-M2-006** — Smart defaults: last-used payer
- **Given** user A previously created an expense with B as payer
- **When** A opens "Add expense" again
- **Then** B is pre-selected as payer

**AC-M2-007** — Outside-the-group payer
- **Given** A creates an expense paid by "Tyler's mom"
- **Then** `payer_outside_name = 'Tyler\'s mom'`, `payer_user_id IS NULL`; the expense doesn't appear in any member's "paid by me" total

**AC-M2-008** — Calculator on amount field
- **Given** Add expense form
- **Then** the amount field supports `+ − × ÷ = .` operations inline

**AC-M2-009** — Receipt attachment within limits
- **Given** an expense with 9 attached receipts
- **When** user tries to attach a 10th
- **Then** upload succeeds; an 11th is rejected with `RECEIPT_COUNT_EXCEEDED`

**AC-M2-010** — 30 MB receipt cap
- **Given** a 30.1 MB file
- **When** user attempts upload
- **Then** the request_receipt_upload RPC returns `VALIDATION` with field byte_size

**AC-M2-011** — 500 MB soft-cap warning
- **Given** group storage at 499 MB
- **When** a member uploads a 5 MB receipt
- **Then** the RPC response includes `warn: 'STORAGE_CAP_WARN'`; admin receives a banner on next session

**AC-M2-012** — 1 GB hard cap
- **Given** group storage at 999 MB
- **When** any member attempts a 5 MB upload
- **Then** RPC returns `STORAGE_CAP_EXCEEDED`

**AC-M2-013** — Receipt deletion is author-only (OQ-06, D-26)
- **Given** receipt R uploaded by member A on expense E
- **When** member B (not the uploader) calls `delete_receipt(R)`
- **Then** the RPC returns `NOT_AUTHORIZED` and R is unchanged
- **And** when A calls `delete_receipt(R)`, R is soft-deleted and `storage_bytes_used` decreases by `byte_size + thumb_byte_size`

**AC-M2-014** — Receipts are compressed before upload (OQ-11, D-22)
- **Given** a 12 MB original photo
- **When** the user attaches it
- **Then** the uploaded full image is re-encoded at quality ≥ 0.80 (long edge ≤ 2048 px) and `byte_size` reflects the smaller compressed size
- **And** a separate thumbnail object (≤ 320 px) is uploaded and `thumb_byte_size` is recorded

**AC-M2-015** — Lazy fetch + 14-day cache (OQ-11, D-22)
- **Given** an expense with 3 receipts the user has never opened
- **When** the expense detail loads
- **Then** only thumbnails are fetched (not full images); the full image is fetched only on tap
- **And** a cached receipt image older than 14 days is evicted and re-fetched on next view

**AC-M2-016** — Itemized tip, proportional (OQ-05, D-21)
- **Given** an itemized expense: subtotals A:$60, B:$40; `tax_subunits = $0`; `tip_subunits = $20`; `tip_split_mode = PROPORTIONAL`
- **Then** shares are A:$72, B:$48; `SUM(shares) == amount_subunits == $120`

**AC-M2-017** — Itemized tip, even (OQ-05, D-21)
- **Given** the same subtotals A:$60, B:$40; `tip_subunits = $20`; `tip_split_mode = EVEN`
- **Then** shares are A:$70, B:$50; `SUM(shares) == $120`

**AC-M2-018** — Tax + tip combined
- **Given** subtotals A:$60, B:$40; `tax_subunits = $10` (proportional); `tip_subunits = $15` (EVEN)
- **Then** A = 60 + 6 + 7.50 = $73.50, B = 40 + 4 + 7.50 = $51.50; `SUM == amount_subunits == $125.00`

### Drafts (server-synced, OQ-13, D-24)

**AC-M2-070** — Auto-save creates a synced draft
- **Given** the Add-expense form, user types a title and amount
- **Then** a `drafts` row is created/updated via `save_draft`; it appears on the user's other device after sync

**AC-M2-071** — Receipt-first draft
- **Given** the Add-expense form with no other fields filled
- **When** the user attaches a receipt
- **Then** a draft exists and the receipt row has `draft_id` set, `expense_id` NULL; it is visible only to the draft owner

**AC-M2-072** — Convert draft to expense re-points receipts
- **Given** a draft with id D and one attached receipt
- **When** the user saves a valid expense
- **Then** an expense with `id = D` is created; the receipt now has `expense_id = D`, `draft_id` NULL; the draft is soft-deleted

**AC-M2-073** — Discard draft cascades
- **Given** a draft with an attached receipt
- **When** the user discards it (`delete_draft`)
- **Then** the draft and its receipt are soft-deleted; `storage_bytes_used` is reclaimed

**AC-M2-074** — Drafts are private
- **Given** member A has a draft in group G
- **When** member B syncs G
- **Then** B never receives A's draft or its receipts

### Edit / delete

**AC-M2-020** — Edit increases amount; shares recompute
- **Given** $30 EVEN among 3 → each $10; nobody has paid
- **When** A edits amount to $60
- **Then** shares become $20 each; `remaining_subunits` = $20

**AC-M2-021** — Edit lowering amount triggers auto-refund when a share is already paid
- **Given** $30 EVEN among 3; B paid his full $10
- **When** A edits amount to $15 (each now owes $5)
- **Then** auto-refund expense is generated paying B back $5; history event `AUTO_REFUND_GENERATED` is appended

**AC-M2-022** — Soft delete preserves history
- **Given** expense E with history events
- **When** A deletes E
- **Then** `E.deleted_at` is set; `history_events` for E are unchanged; E no longer appears in any list; balances exclude E

### Refunds

**AC-M2-030** — Manual refund preserves original
- **Given** expense E ($30 EVEN among 3, paid by A)
- **When** B "adds refund" for $10 from A → B
- **Then** a new expense R with `kind = 'REFUND', refund_of_expense_id = E.id, payer = B, participant = A` is created; E is unchanged

**AC-M2-031** — Refund of a refund is rejected
- **Given** refund R
- **When** any user tries to refund R
- **Then** RPC returns `REFUND_OF_REFUND`

### Balances

**AC-M2-040** — Bilateral balance computation
- **Given** A paid $30 EVEN among A,B,C; nobody settled
- **Then** balance shows "B owes A $10, C owes A $10" in the relevant currency

**AC-M2-041** — Balances never simplified across triangles
- **Given** A owes B $10; B owes C $10; C owes A $10
- **Then** the Balances tab shows all three debts — not "zero net" or "simplified to empty"

**AC-M2-042** — Multi-currency stored per currency
- **Given** an expense in USD and another in EUR among the same pair
- **Then** the Balances tab shows two rows (one per currency); no auto-conversion at rest

**AC-M2-043** — Per-currency display conversion (read-only)
- **Given** A owes B $20 USD and €15 EUR; user toggles "Show approximate total"
- **Then** an aggregated figure is shown using the current FX cache; the stored shares are unaffected

### FX

**AC-M2-050** — Daily FX refresh
- **Given** the app cold-starts and `fx_rates`'s latest row's date < today UTC
- **Then** the client fetches Frankfurter; on success, new rows are inserted into Room `fx_rates`

**AC-M2-051** — First-launch with no network uses baked snapshot
- **Given** install with no network connectivity
- **Then** FX conversions use `fx_baked.json`; no warning is shown (D-08)

**AC-M2-052** — Staleness > 7 days shows hint
- **Given** the latest fx_rate is 8 days old
- **When** the user views a converted amount on a screen
- **Then** "Rate as of [date]" is rendered as a muted line beneath the converted figure

**AC-M2-053** — Same-currency expense doesn't use FX
- **Given** an expense in USD viewed by a user with base USD
- **Then** no FX rate is consulted; no staleness hint is shown

### Date-grouped list

**AC-M2-060** — Day-headers + sticky
- **Given** group with expenses on May 22 and May 21
- **Then** the list shows two day sections with sticky headers as the user scrolls

**AC-M2-061** — Settled tray within day section
- **Given** day with 3 active and 2 settled expenses
- **Then** Active tab: section shows 3 expenses + "Settled (2)" tray, collapsed
- **And** Settled tab: section shows the tray expanded with the 2 settled expenses

**AC-M2-062** — Sort by amount
- **Given** day with expenses $30, $5, $15
- **When** user toggles sort=Amount
- **Then** within the day, expenses are ordered $30, $15, $5

**AC-M2-063** — Filter by member
- **Given** expenses with payers A, B, C
- **When** user filters to "Member: A"
- **Then** only A's expenses are visible

---

## M3 — Settlement & refunds

### Single-expense settlement

**AC-M3-001** — Open settle sheet from expense detail
- **Given** I'm a participant with `remaining > 0` on expense E
- **When** I tap "Settle this" on my share row
- **Then** a sheet opens preselecting the expense's payer as recipient

**AC-M3-002** — Default amount = full remaining
- **Given** I owe $10 on expense E
- **When** the settle sheet opens
- **Then** the amount field shows $10.00

**AC-M3-003** — Partial settlement reduces remaining
- **Given** I owe $10 on E
- **When** I confirm a $4 settlement
- **Then** my share's `remaining_subunits` becomes $6; `settlement_allocations.applied_amount_subunits = 400`

**AC-M3-004** — Full settlement marks share zero
- **Given** I owe $6 on E
- **When** I confirm a $6 settlement
- **Then** my share's `remaining_subunits = 0`; E's `status` becomes `SETTLED` iff all shares are zero

**AC-M3-005** — Same-currency invariant
- **Given** E is in USD
- **When** I settle
- **Then** every `settlement_allocation.applied_currency = 'USD'`; `fx_rate_used IS NULL`

### Multi-expense settlement (same currency)

**AC-M3-010** — Settle person flow opens with all unsettled shares preselected
- **Given** I owe B $5 on E1, $10 on E2 in USD
- **When** I tap the debt row in Balances
- **Then** the picker shows E1, E2 both selected by default; total $15

**AC-M3-011** — Partial multi-expense pays oldest first
- **Given** E1 ($5, older) and E2 ($10) in USD; I enter $7
- **Then** E1 receives $5 (full); E2 receives $2 (partial); E1's remaining = 0; E2's remaining = $8

**AC-M3-012** — Cannot save with zero selected expenses
- **Given** user deselects all expenses in the multi-expense picker
- **Then** the "Continue" button is disabled

### Cross-currency settlement

**AC-M3-020** — Single foreign-currency expense, paid in USD
- **Given** I owe £10 on E; current rate USD→GBP = 0.80
- **When** I open settle and amount shows $12.50 (10 / 0.80)
- **Then** confirming creates an allocation with `applied_amount_subunits = 1000 (GBP), applied_currency = 'GBP', fx_rate_used = 0.80, fx_rate_date = today`

**AC-M3-021** — Mixed-currency multi-expense defaults to USD (D-05)
- **Given** I owe £10 (E1) and €5 (E2) to B; I open Balances → settle B
- **Then** the payment currency selector is fixed to USD in v1

**AC-M3-022** — Rounding wiggle absorbed by last allocation
- **Given** the FX math leaves a 1-cent gap after all allocations
- **Then** the last allocation's `applied_amount_subunits` is bumped by 1 subunit to close the gap; the sum of payment-currency values equals the user-entered payment amount

### Payment-app deep links

**AC-M3-030** — Venmo deep link constructed correctly
- **Given** recipient handle "andrew-c", amount $12.34, expense "Dinner"
- **When** the Venmo button is tapped
- **Then** a URL `venmo://paycharge?txn=pay&recipients=andrew-c&amount=12.34&note=...` is launched

**AC-M3-031** — Fallback to clipboard on missing app
- **Given** the platform launcher rejects the deep link (e.g., Venmo not installed)
- **Then** clipboard contains "andrew-c  12.34 USD"; toast shows "Copied — paste in your payment app"

**AC-M3-032** — Confirm sheet appears after deep link
- **Given** user tapped "Open in Venmo"
- **When** the app returns to foreground
- **Then** the "Did the transfer go through?" sheet appears within 1.5s

**AC-M3-033** — "Not yet" discards draft
- **Given** the confirm sheet is shown
- **When** user taps "Not yet"
- **Then** no `settlements` row is created; no `settlement_allocations` rows exist; the expense's `remaining_subunits` is unchanged

**AC-M3-034** — Zelle: no deep link attempted
- **Given** recipient has only a Zelle handle
- **Then** the settle sheet shows "Mark paid manually" as the only option; deep-link button is not present

### Void settlement

**AC-M3-040** — Void reverses allocations
- **Given** A settled $5 against E1
- **When** A voids the settlement
- **Then** E1's `remaining_subunits` is restored; `settlements.deleted_at` is set; history event `SETTLEMENT_VOIDED` is appended to E1

---

## M4 — Retroactive adds & conflicts

### Retro add — equal splits (silent)

**AC-M4-001** — Equal-split retro add silently recomputes
- **Given** group [A, B, C]; E1: $30 EVEN paid by A; nobody settled
- **When** Tyler is added with "Add to all past expenses"
- **Then** E1's shares become $7.50 each across [A, B, C, Tyler]; history event `RETRO_RESPLIT` with `decision='EQUAL_AUTO'` is appended

**AC-M4-002** — Retro add when a member has settled triggers auto-refund
- **Given** E1: $30 EVEN; B paid his full $10
- **When** Tyler is retro-added → each now owes $7.50
- **Then** an auto-refund expense is generated from A to B for $2.50; B's old share remains owed $0 (he had paid $10 of the new $7.50 = he overpaid by $2.50)

**AC-M4-003** — Retro add with "Future only" affects no past expense
- **Given** existing expenses
- **When** Tyler is added with "Future only"
- **Then** no past expense's shares change; no conflict row is created

### Retro add — non-equal splits (conflicts)

**AC-M4-010** — Non-equal-split expense generates a conflict
- **Given** E1: $100 BY_SHARE {A:1, B:2, C:2}, paid by A
- **When** Tyler is retro-added with "Add to all past"
- **Then** a `conflicts` row exists for (E1, Tyler); E1's shares are unchanged until resolved

**AC-M4-011** — Conflicts tab shows badge
- **Given** ≥ 1 unresolved conflict in group G
- **Then** the bottom nav shows a Conflicts tab with a numeric badge

**AC-M4-012** — Resolve INCLUDE requires explicit share
- **Given** the conflict sheet for (E1, Tyler)
- **When** the user opens it
- **Then** Tyler's share field is empty (NOT pre-filled); resolution cannot be confirmed until the user enters a positive value

**AC-M4-013** — Resolve INCLUDE redistributes existing shares
- **Given** E1: $100 with [A:$20, B:$40, C:$40]
- **When** Tyler is included with share $10
- **Then** total is $100: A:$18, B:$36, C:$36, Tyler:$10 (proportionally reduced by $90/$100 = 0.9 multiplier on existing)

**AC-M4-014** — Resolve INCLUDE triggers auto-refund when needed
- **Given** B in E1 paid all $40; Tyler is included with share $10 (B now owes $36)
- **Then** auto-refund from A to B for $4 is generated; history event noted

**AC-M4-015** — Resolve DISMISS removes Tyler from E1
- **Given** the conflict sheet for (E1, Tyler)
- **When** user taps "Skip Tyler"
- **Then** conflict.resolution = 'DISMISS'; no share row for Tyler on E1; E1's shares unchanged

**AC-M4-016** — Any active member can resolve a conflict
- **Given** A triggered the retro add; B (any other member) opens the Conflicts tab
- **When** B resolves the conflict
- **Then** the resolution proceeds normally (not restricted to A)

### Conflict reminders

**AC-M4-020** — Default cadence: weekly
- **Given** a new group
- **Then** `reminder_cadence = 'WEEKLY'`

**AC-M4-021** — Admin can change cadence
- **Given** I'm the admin of G
- **When** I set cadence to "Daily"
- **Then** `groups.reminder_cadence = 'DAILY'`

**AC-M4-022** — Reminder fires only when conflicts exist
- **Given** group has zero unresolved conflicts
- **Then** the `notify_admin_of_conflicts` daemon does not enqueue a push

**AC-M4-023** — Reminder respects archived state of admin (D-11)
- **Given** the admin has archived the group
- **Then** no conflict reminder push is sent to them; the conflict remains until they unarchive and address it

**AC-M4-024** — Reminder respects admin's notification preferences
- **Given** admin has `conflict_reminder = false`
- **Then** even with conflicts present, no push is sent

### History log

**AC-M4-030** — Every mutation appends a history event
- **Given** an expense E
- **When** any RPC mutates E (create, edit, settle, refund, conflict, comment, merge, delete)
- **Then** at least one new `history_events` row is appended

**AC-M4-031** — History is append-only (RLS enforcement)
- **Given** any user tries `UPDATE history_events` or `DELETE history_events`
- **Then** the operation is denied by RLS

**AC-M4-032** — History UI is collapsible
- **Given** expense detail with history events
- **Then** the History section is collapsed by default; tapping expands

### Removing a retroactively-added member

**AC-M4-040** — Remove reverses silent recomputes
- **Given** Tyler was retro-added (equal split case) to E1, causing each share to drop to $7.50
- **When** Tyler is removed from the group
- **Then** E1's shares revert to $10 each across [A, B, C]

**AC-M4-041** — Remove deletes generated auto-refunds
- **Given** an auto-refund expense was generated on Tyler's retro-add
- **When** Tyler is removed
- **Then** the auto-refund expense (`is_auto_refund = true`) is deleted; affected balances revert

---

## M5 — Polish & operational

### Comments

**AC-M5-001** — Comments visible to participants only
- **Given** expense E with payer A and participants [A, B, C]
- **When** D (group member not on E) opens E's detail
- **Then** the Comments section is not rendered; the comment count is hidden; unread indicator is hidden

**AC-M5-002** — Add a comment
- **Given** I am a participant on E
- **When** I post "Was this the right total?"
- **Then** a `comments` row is created with my `author_user_id`; other participants see an unread indicator on E's row

**AC-M5-003** — Resolve and reopen
- **Given** a thread with one comment
- **When** any participant taps "Resolve thread"
- **Then** the thread collapses to a "Resolved (Andrew, 2m ago)" chip; tapping the chip expands and a "Reopen" affordance restores `is_resolved = false`

**AC-M5-004** — Unread indicator clears on view
- **Given** the unread indicator is shown for me on E
- **When** I open E's comment thread
- **Then** `comment_read_state.last_read_at` is set to now; indicator disappears

### Notifications

**AC-M5-010** — Push registers on cold start
- **Given** I sign in for the first time and grant notification permission
- **Then** `device_push_tokens` contains my FCM token tied to my `app_install_id`

**AC-M5-011** — Expense-added push
- **Given** I am a participant on E (newly created by A)
- **Then** I receive a push titled "[A] added '[Title]' for $[amount]"; tap navigates to E's detail

**AC-M5-012** — Settlement-received push
- **Given** B settles $5 with me
- **Then** I receive a push titled "[B] paid you $5"; tap navigates to Balances tab

**AC-M5-013** — Per-channel mute
- **Given** I disabled `comment_added`
- **When** another member comments on an expense I'm on
- **Then** no push fires for that channel

### Exports

**AC-M5-020** — CSV export
- **Given** I export group G as CSV
- **Then** a signed URL is returned; the artifact contains expenses, shares, and settlements with header rows

**AC-M5-021** — JSON export round-trips
- **Given** the JSON export of a group
- **Then** parsing it yields all expenses, shares, settlements, members, comments, history visible to the requester per RLS

**AC-M5-022** — PDF export renders correctly
- **Given** I export group G as PDF
- **Then** the artifact opens; cover page shows group name + total per currency; expense list is grouped by day; final page shows balances

**AC-M5-023** — Export is free and unrestricted
- **Given** any group size
- **When** the user exports
- **Then** no paywall, no row limit; rate limit is 1 export per minute per user

### Accessibility

**AC-M5-030** — Color contrast meets WCAG AA
- **When** the design tokens are evaluated in CI
- **Then** every `(text color, background color)` pair used by the app meets WCAG 2.1 AA contrast ratios

**AC-M5-031** — VoiceOver / TalkBack on reconcile cards
- **Given** the reconcile screen
- **When** screen reader is on
- **Then** each card announces "[Name], [N] expenses, [selected | not selected], double tap to toggle"

**AC-M5-032** — Tap target sizes ≥ 44dp
- **When** UI is audited
- **Then** every tappable affordance is at least 44×44 dp; verified by a UI test that asserts on `Modifier.testSizeMatchesMinimumTouchTarget()`

### Search & filter

**AC-M5-040** — Search across titles, members, categories
- **Given** group with expense "Dinner at Joe's" and category "Food → Restaurant"
- **When** the user searches "Joe"
- **Then** "Dinner at Joe's" appears in results

### Storage cap UX

**AC-M5-050** — Soft cap warning banner appears for admin
- **Given** group crosses 500 MB
- **Then** on the admin's next session, a top banner reads "Approaching storage cap — export or delete old receipts"; non-admins see no banner

### Privacy & analytics (OQ-07/OQ-08)

**AC-M5-060** — Analytics/crash collection respects consent
- **Given** the user turned the privacy toggle OFF (onboarding or Profile → Privacy)
- **Then** Firebase Analytics and Crashlytics report nothing (collection disabled at runtime); turning it back ON re-enables collection

**AC-M5-061** — No PII or advertising ID leaves the device
- **When** analytics events and crash reports are inspected
- **Then** no display names, emails, amounts, expense titles, or payment handles appear; no advertising identifier is collected

### Group settings

**AC-M5-070** — Base-currency change is display-only (OQ-12)
- **Given** a group with expenses in USD and EUR
- **When** the admin changes the group `base_currency`
- **Then** no expense is re-denominated; only display rollups change; the settings screen shows the "display totals only" footnote

---

## Cross-milestone invariants (always asserted)

Implementing agents MUST also include a "data integrity" test suite that asserts the invariants in `02 §9` after every mutation in every E2E scenario:

**AC-INV-001** — `SUM(shares.share_owed_subunits for E) == expenses.amount_subunits` for every non-deleted E
**AC-INV-002** — `SUM(applied_amount_subunits against share S) == (share_owed_subunits - remaining_subunits)` for every S
**AC-INV-003** — `expenses.status == 'SETTLED'` iff `SUM(shares.remaining_subunits for E) == 0`
**AC-INV-004** — For every (group, currency), the bilateral balance ledger sums to zero
**AC-INV-005** — `groups.admin_user_id IS NULL` iff the group has zero active members
**AC-INV-006** — No placeholder user_id is referenced by `payment_app_handles`, `device_push_tokens`, or `groups.admin_user_id`
**AC-INV-007** — `history_events` row count for any expense never decreases between two reads
**AC-INV-008** — Refund chain depth ≤ 1 (no refund-of-a-refund)
**AC-INV-009** — Every `receipts` row references exactly one parent: `(expense_id IS NOT NULL) XOR (draft_id IS NOT NULL)`
**AC-INV-010** — `groups.storage_bytes_used == SUM(byte_size + thumb_byte_size)` over non-deleted receipts
**AC-INV-011** — The reserved system user is never a `members` row nor a `groups.admin_user_id`
**AC-INV-012** — For an itemized expense, `SUM(shares.share_owed_subunits) == amount_subunits == SUM(subtotals) + tax_subunits + tip_subunits`

---

**Read next:** [`09-open-questions.md`](09-open-questions.md).
