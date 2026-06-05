# 03 — Business Rules

> The deterministic logic of ShareCost. Every rule here is normative; implementing agents MUST follow the pseudocode and invariants exactly. Pseudocode is in Kotlin-flavored form because the shared logic lives in `commonMain`.

---

## 1. Money & rounding

### 1.1 Representation

```kotlin
data class Money(
    val amountSubunits: Long,   // never negative in this app
    val currency: String     // ISO 4217
) {
    init { require(amountSubunits >= 0) }
}
```

`subunitScale(currency)` looks up the currency's scale from the static `currencies` table (USD = 2, JPY = 0, BHD = 3). Display formatting MUST use this; never assume 2.

### 1.2 Splitting an amount across N participants (largest-remainder rounding)

The fundamental allocator used by every split mode:

```kotlin
fun allocate(totalSubunits: Long, weights: List<Pair<MemberId, Long>>): Map<MemberId, Long> {
    require(weights.isNotEmpty())
    val totalWeight = weights.sumOf { it.second }
    require(totalWeight > 0)

    // First pass: integer floor division.
    val raw = weights.map { (m, w) -> m to (totalSubunits * w / totalWeight) }
    var assigned = raw.sumOf { it.second }
    val remainder = totalSubunits - assigned

    // Largest-remainder: distribute the leftover cents to the members with the
    // largest fractional remainders, ties broken by deterministic member-join order.
    val fracs = weights.zip(raw).map { (w, r) ->
        val frac = (totalSubunits * w.second).rem(totalWeight)
        Triple(w.first, r.second, frac)
    }.sortedWith(
        compareByDescending<Triple<MemberId, Long, Long>> { it.third }
            .thenBy { joinedAt(it.first) }
            .thenBy { it.first.toString() }     // final tiebreaker on ID
    )

    val out = mutableMapOf<MemberId, Long>()
    fracs.forEachIndexed { i, (m, base, _) ->
        out[m] = base + (if (i < remainder) 1L else 0L)
    }
    return out
}
```

This is the **only** allocator the app uses. Every split mode reduces to a `weights` vector and a call to `allocate`.

### 1.3 Per-split-mode weight vectors

Given expense amount `A` (in subunits) and an ordered participant list:

| Split mode | Weights | Validation |
|---|---|---|
| `EVEN` | `[1, 1, ..., 1]` (one per participant) | participants.size >= 1 |
| `BY_SHARE` | `share_units` per participant (positive integers) | every share_units >= 1; sum > 0 |
| `BY_PERCENTAGE` | `round(percentage * 10000)` per participant (scaled to integer basis points) | sum of percentages == 100.0000 ± 0.0001 (4 decimal places UI) |
| `BY_EXACT` | participant-provided exact subunit amounts | sum == A; no remainder distribution needed (`weights` not used; values applied directly) |

For `BY_PERCENTAGE`: convert percentages to weights by multiplying by 10000 and rounding to nearest integer, then call `allocate` with `total = A`. The "scale by 10000" ensures 4-decimal-place precision (e.g., 33.3333%) survives the integer pipeline.

For `BY_EXACT`: skip `allocate`; use the entered amounts directly. The UI MUST display a live "off by X" delta until it is exactly zero (see `05 §5.2`).

### 1.4 Tax & tip rows (itemized split)

When `expenses.has_tax_row = true` (the itemized restaurant flow):

1. Each participant enters their pre-tax, pre-tip subtotal as `BY_EXACT`.
2. **Tax** (`tax_subunits`) is distributed **proportionally** to each participant's subtotal using `allocate(total = tax_subunits, weights = subtotals)`.
3. **Tip** (`tip_subunits`) is distributed according to `tip_split_mode` (D-21, OQ-05):
   - `PROPORTIONAL` → `allocate(total = tip_subunits, weights = subtotals)` (same weighting as tax).
   - `EVEN` → `allocate(total = tip_subunits, weights = [1, 1, …])` (one weight per participant; largest-remainder cents fall in member-join order).
4. Each participant's final `share_owed_subunits = subtotal + tax_share + tip_share`.

The expense `amount_subunits` therefore equals `SUM(subtotals) + tax_subunits + tip_subunits`. The UI displays subtotal, tax allocation, tip allocation, and final share on each row, with a tip-split-mode toggle (`05 §5.2`).

```kotlin
fun itemizedShares(
    subtotals: List<Pair<MemberId, Long>>,   // BY_EXACT subtotals
    taxSubunits: Long,
    tipSubunits: Long,
    tipSplitMode: TipSplitMode                // PROPORTIONAL | EVEN
): Map<MemberId, Long> {
    val taxShare = allocate(taxSubunits, subtotals)                       // always proportional
    val tipWeights = when (tipSplitMode) {
        TipSplitMode.PROPORTIONAL -> subtotals
        TipSplitMode.EVEN         -> subtotals.map { it.first to 1L }
    }
    val tipShare = if (tipSubunits > 0) allocate(tipSubunits, tipWeights) else emptyMap()
    return subtotals.associate { (m, sub) ->
        m to (sub + (taxShare[m] ?: 0) + (tipShare[m] ?: 0))
    }
}
```

When `has_tax_row = false`, both `tax_subunits` and `tip_subunits` are 0 (enforced by `02 §3.7` CHECK) and this path is not used.

## 2. Balance computation

### 2.1 Per-share remaining (the ledger)

Every `share` carries its own `remaining_subunits`. The group's net balances are computed entirely from these. There is no separate "balances" table.

### 2.2 Bilateral balances (per currency, never simplified)

For a group, the pairwise debt of A → B in currency `C` is:

```
debt(A, B, C) = SUM over expenses E in C
                  WHERE payer(E) = B AND share(E, A).remaining > 0
                  of share(E, A).remaining
              - SUM over expenses E in C
                  WHERE payer(E) = A AND share(E, B).remaining > 0
                  of share(E, B).remaining
```

If positive: A owes B that amount in C. If negative: B owes A `-debt(A, B, C)`. Display the larger side as the principal, the smaller side as zero. **Never collapse across currencies. Never net across triangles.**

For each `(group, currency)` pair, render an N×N matrix of debts and walk it pair-by-pair for the "Balances" tab.

### 2.3 Group total / per-member spend (Trip Overview)

Independent of remaining balances; uses original `share_owed_subunits`:

- **Group total** in currency C: `SUM(amount_subunits)` of non-deleted, non-refund expenses with `currency = C`.
- **Member share** (what M owes overall) in C: `SUM(share_owed_subunits)` of M's non-deleted shares in expenses with that currency.
- **Member paid** in C: `SUM(amount_subunits)` of expenses where M is the payer in C.
- **Resulting balance** = `Member paid - Member share`.

Per-day rollups: bucket by `expense_date`.

## 3. Expense lifecycle

### 3.1 Create

Inputs: `title`, `amountSubunits`, `currency`, `expenseDate` (default = today, local), `payer` (member or outsider string), `participants` (set of member IDs), `splitMode`, split inputs (per mode), optional `categoryId`/`subcategoryId`, optional `notes`, optional receipts (uploaded separately, attached by ID before save).

Procedure:

1. Validate per `1.3`.
2. Compute `shareOwed = allocate(...)` (or use direct inputs for `BY_EXACT`).
3. Insert `expenses` row; insert `shares` rows with `remaining_subunits = share_owed_subunits`.
4. Insert `history_events` `CREATED`.
5. Enqueue push notifications to all participants except the actor (subject to their prefs).

The whole thing runs in a single Supabase RPC `add_expense(payload jsonb) returns expense_id uuid`.

### 3.2 Edit

Any active member can edit any expense in their group.

Mutable fields: `title`, `notes`, `expenseDate`, `amountSubunits`, `currency`, `payer`, `participants`, `splitMode`, split inputs, `categoryId`/`subcategoryId`, `hasTaxRow`, `taxSubunits`, `tipSubunits`, `tipSplitMode`, receipts.

Editing recomputes all `shares` for the expense. **Settlement allocations against the old shares are preserved if and only if the participant stays on the expense and their new `share_owed_subunits` is >= the sum of allocations applied to their old share.** Concretely:

```
for each existing share S (by user U):
    appliedAgainstS = SUM(settlement_allocations.applied_amount_subunits WHERE share_id = S.id)
    newShareOwed   = new allocator output[U] (or 0 if U dropped)
    if newShareOwed >= appliedAgainstS:
        S.share_owed_subunits  = newShareOwed
        S.remaining_subunits   = newShareOwed - appliedAgainstS
    else:
        // Edit lowers an already-overpaid share; generate an auto-refund per §4.4
        emit auto-refund of (appliedAgainstS - newShareOwed) from payer to U
        S.share_owed_subunits  = newShareOwed
        S.remaining_subunits   = 0
```

Drops: if U is removed from the participants, their share row is deleted, and any settlement allocations against it stay linked but the share row is hard-deleted only if there were no allocations; otherwise the share row is retained with `share_owed_subunits = appliedAgainstS, remaining_subunits = 0` (it appears in history but not in active math).

Adds: a new participant gets a fresh share row with `remaining_subunits = share_owed_subunits`.

Emit `history_events` `EDITED` with a diff payload.

### 3.3 Delete (soft)

Stamp `deleted_at`. All shares become inert. Append `history_events` `DELETED`.

If the expense had settlement allocations against it, the allocations stay (so the settlement total reconstructs). The deleted expense disappears from balance math and from list views.

### 3.4 Refund (manual)

User taps "Add refund" on expense E:

1. Open a refund form pre-filled with E's participants, split mode, and split inputs, BUT with the recipient = E's payer and the participants playing the role of payers (one-to-one).
2. The user may edit participants and switch to `BY_EXACT`.
3. On submit, insert a new `expenses` row with `kind = 'REFUND'`, `refund_of_expense_id = E.id`, `payer_user_id = <recipient from the form>`, and shares per the new split.
4. The original E is unchanged.
5. Append `history_events` to E: `REFUND_LINKED` with the new expense id.

The refund's shares are independent of E's shares. The bilateral balance computation handles this naturally: a refund where Bob pays Alice $5 is just another expense where Bob is the payer and Alice is the participant; it shows in the ledger and offsets Alice's prior debt to Bob.

### 3.5 Drafts (D-24, OQ-13)

A **draft** is a half-filled Add-Expense form persisted server-side and synced across the owner's devices. Drafts exist so a user can capture a receipt or a partial expense now and finish the split later (the "receipt-first" workflow), without losing work across app restarts, reinstalls, or device switches.

**Ownership & visibility.** A draft belongs to exactly one user in exactly one group and is never visible to other members (`02 §3.17`, `§5.2a`).

**Create / auto-save.**
1. Opening "Add expense" with no draft creates an in-memory form. The first meaningful edit (any field touched, or a receipt attached) materializes a `drafts` row via `save_draft` with a client-generated UUIDv7 `id`.
2. Subsequent edits debounce (~2 s idle, or on backgrounding) into `save_draft` calls that overwrite `payload_json`. Writes flow through the local mutation queue (`04 §6`), so auto-save works offline.
3. `payload_json` is schemaless and is **never validated** while saving — a draft may be arbitrarily incomplete.

**Receipt-first.** A receipt may be attached to a draft (`receipts.draft_id`). This is the only way to hold a receipt without a committed expense. Such receipts are private to the draft owner until convert.

**Convert to expense.** When the user taps Save and the form passes validation (`05 §5.3`):
1. The client calls `add_expense` with the draft's `id` reused as the new `expense_id` (so optimistic identity and any draft-attached receipts line up).
2. Inside the `add_expense` transaction, the server re-points every `receipts` row with `draft_id = id` to `expense_id = id` (and clears `draft_id`), then soft-deletes the `drafts` row.
3. Standard `CREATED` history event and notifications fire as in `§3.1`.

**Discard.** `delete_draft(draft_id)` soft-deletes the draft and cascade-soft-deletes any draft-attached receipts (whose Storage objects are reclaimed against the group cap). Discarding requires an explicit user action or successful convert — drafts are never auto-purged on the server in v1.

**No conflict semantics.** Drafts use plain LWW (`04 §6.4`) on `row_version`; because a draft is single-owner, cross-device edits simply keep the most recent save. There is no STALE_ROW dialog for drafts — the newer save wins silently.

## 4. Settlement

### 4.1 Single-expense settlement (the headline feature)

From the expense detail screen, "Settle this" opens a form:

1. Preselects the user's role: if I am a participant with `remaining > 0`, I am the payer; the expense's payer is the recipient.
2. Amount field: defaults to my full remaining; editable down to any positive value <= remaining.
3. Payment app picker: defaults to recipient's primary app; lists all their handles.
4. Note (optional).
5. Submit:
   - Construct deep link (`§5.1`).
   - Open via platform URL opener.
   - On return, show "Did the transfer go through?" sheet.
6. On "Yes": call `apply_settlement` RPC with one allocation row pointing at my share and `applied_amount_subunits = amount`.

### 4.2 Settling a person (whole bilateral balance)

From the Balances tab, tap a debt pair "A → B in $C":

1. Show the list of expenses contributing to A's debt to B in $C, sorted by `expense_date` ascending.
2. User picks which expenses to settle (multi-select). The picker MUST be present; a settlement cannot be saved with zero selected expenses.
3. Sum of selected `remaining` becomes the default amount; user can lower it.
4. If amount < default, the allocator (`§4.3.1` below) distributes the payment across the selected expenses **in the order shown** (oldest first), filling each remaining and overflowing to the next.
5. From here, same as `§4.1` step 5–6 — open deep link, confirm, write settlement.

### 4.3 Multi-expense settlement: allocation algorithm

Given a payment of `paymentAmountSubunits` in `paymentCurrency`, paying down a list of selected shares `S = [s1, s2, ...]` in some order:

#### 4.3.1 Same-currency case

If every share has `currency == paymentCurrency`:

```kotlin
var remaining = paymentAmountSubunits
val allocations = mutableListOf<Allocation>()
for (s in S) {
    if (remaining <= 0) break
    val applied = min(remaining, s.remainingSubunits)
    if (applied > 0) {
        allocations += Allocation(shareId = s.id, appliedSubunits = applied,
                                  appliedCurrency = paymentCurrency,
                                  fxRate = null, fxRateDate = null)
        remaining -= applied
    }
}
// Invariant: remaining == 0 (else the UI prevented over-allocation).
```

#### 4.3.2 Different-currency case (cross-FX settlement)

If the share's currency differs from `paymentCurrency`, convert at the moment of settlement using the rate the user sees in the UI at submit time:

```
For each share s in S, in the order shown:
    if remaining <= 0: break
    rate_s = fxRate(paymentCurrency -> s.currency, date = today)
    requiredInPayment = ceilToSubunits(s.remainingSubunits / rate_s, paymentCurrency)
        // ceil so the user can fully clear an expense without an off-by-one
    apply_payment   = min(remaining, requiredInPayment)
    apply_share     = if (apply_payment == requiredInPayment) s.remainingSubunits
                      else floorToSubunits(apply_payment * rate_s, s.currency)
    record Allocation(share_id = s.id, applied_subunits = apply_share,
                      applied_currency = s.currency, fxRate = rate_s, fxRateDate = today)
    remaining -= apply_payment
```

Notes:

- The rate used MUST be stamped onto the allocation row so post-hoc display is consistent (FX rates may change after the fact).
- The 1-subunit rounding wiggle (from `floor` vs `ceil`) is absorbed by the **last** allocation: after the loop, if `remaining > 0` (because of cumulative ceil), append a top-up to the last allocation that closes the gap.
- If `remaining < 0` after the loop, the algorithm has over-allocated; this MUST NOT happen if the loop is correct — assert and surface a test failure.

#### 4.3.3 Multi-expense across mixed currencies (D-05)

If the user picks multiple expenses spanning multiple currencies:

1. The payment currency in the form is **fixed to USD** in v1.
2. The user enters one USD amount.
3. The allocator above runs with `paymentCurrency = USD`, converting per share as it goes.
4. The deep link is constructed in USD (most payment apps accept USD; the only US-payment-app target in v1).

In v2 this defaults to the user's home currency. Implementing agents MUST keep the `paymentCurrency` parameter on the RPC so v2 is a UI-only change.

### 4.4 Voiding a settlement

If the user marked a settlement as paid in error, "Void settlement" reverses the allocations:

1. Append `applied_amount_subunits` back to each `shares.remaining_subunits`.
2. Stamp `settlements.deleted_at = now`.
3. Append `history_events` `SETTLEMENT_VOIDED` to each affected expense.

Constraint: voiding a settlement can never cause any `remaining_subunits > share_owed_subunits`. This is automatic since voiding only adds back what was originally subtracted.

## 5. Payment-app deep links

### 5.1 URL templates (best known as of v1)

| App | URL template | Notes |
|---|---|---|
| **Venmo** | `venmo://paycharge?txn=pay&recipients=<handle>&amount=<usd>&note=<encoded>` | Handle is the Venmo username, no `@`. USD only. |
| **Cash App** | `https://cash.app/$<handle>/<usd>` | Handle includes the `$`. Universal link; opens app if installed. |
| **Zelle** | None reliable. v1: fall back to "Copy email/phone" + open the recipient's bank's Zelle URL not attempted. Show clipboard fallback always. | Mark `deep_link_attempted = false`. |
| **PayPal** | `https://paypal.me/<handle>/<usd>USD` | Universal link; opens app if installed. |

All amounts in deep links are in USD with two decimals (`<usd>` = `amountSubunits / 100` formatted as `"#.00"`). Non-USD amounts MUST first be converted (the deep link from a non-USD share goes out at the converted USD value; the in-app form is responsible for stamping the FX onto the settlement row, not the link).

Note encoding: short string, URL-encoded, format: `"ShareCost: <group> · <expense title or N expenses>"`.

### 5.2 Fallback path (D-13)

1. Attempt to open the deep link URL via `Intent.ACTION_VIEW` (Android) or `UIApplication.shared.open` (iOS).
2. If the launcher rejects (Android: `ActivityNotFoundException`; iOS: `canOpenURL` returned false earlier or `open` completion handler returns `false`):
   - Write to clipboard: `<handle>  <amount> <currency>` (literal two-space separator).
   - Show non-blocking toast: "Copied — paste in your payment app."
   - Set `settlements.deep_link_attempted = true, deep_link_succeeded = false`.
3. Regardless of deep-link outcome, after the form is dismissed, show the "Did the transfer go through?" confirm sheet.

### 5.3 "Did the transfer go through?" confirm sheet

Triggered after deep link or fallback, on app-foreground or after a 1s delay (whichever comes first). Contents:

- Heading: "Settled $X with @handle?"
- Primary: "Yes — mark paid" → calls `apply_settlement` RPC.
- Secondary: "Not yet" → discards the in-memory settlement draft. **No partial state is persisted server-side until the user confirms.**

The settlement is NOT created on the server until the user taps "Yes." The deep link can be opened, the user can pay or not pay, and ShareCost only writes the row when the user confirms.

## 6. Multi-currency & FX

### 6.1 Rate fetching

- **Source**: Frankfurter (`https://api.frankfurter.app/latest?base=USD`).
- **Cadence**: once per day, triggered by the app on cold start if `last_fetch_at < today UTC`.
- **Storage**: insert/upsert into local Room `fx_rates`. Server-side, a daily Edge Function `refresh_fx_rates` mirrors the snapshot to Postgres `fx_rates` (consumed only by the Trip Overview export and by analytics; clients fetch their own).
- **Network failure**: do not surface an error; the chain in `§6.2` handles it.

### 6.2 Lookup chain

```kotlin
fun fxRate(from: String, to: String, asOf: LocalDate = today()): FxResult {
    if (from == to) return FxResult.same()

    // All rates stored as USD -> X; cross-pair via USD.
    val usdToFrom = ratePerUsd(from, asOf)
    val usdToTo   = ratePerUsd(to, asOf)
    if (usdToFrom != null && usdToTo != null) {
        val rate = usdToTo / usdToFrom
        val stale = staleness(asOf, asOf - rateDateActuallyUsed)
        return FxResult.fresh(rate, snapshotDate, stale)
    }
    // Fallback to baked snapshot:
    val bakedFrom = bakedRatePerUsd(from)
    val bakedTo   = bakedRatePerUsd(to)
    if (bakedFrom != null && bakedTo != null) {
        return FxResult.baked(bakedTo / bakedFrom, bakedSnapshotDate)
    }
    return FxResult.unavailable()
}

fun ratePerUsd(currency: String, asOf: LocalDate): Double? {
    // Most-recent fx_rates row with rate_date <= asOf.
    return roomDao.latestOnOrBefore(currency, asOf)?.ratePerUsd
}
```

### 6.3 Staleness signal (D-07)

`staleness > 7 days` is the trigger.

Computed as: `today - max(rate_date used for both legs)`. If > 7 days, display directly below the converted figure a muted line "Rate as of <YYYY-MM-DD>". The hint applies to all visible converted figures on the screen, not per-line — a single banner under the converted area is preferable.

UI placement specified in `05 §6.4`.

### 6.4 First-launch failure (D-08)

If on cold launch:

1. Live cache is empty.
2. Network fetch fails or times out (5s).
3. Baked snapshot is the only source.

Then: proceed silently. Do not warn the user. The 7-day staleness banner will still surface if/when the snapshot is older than 7 days; that is the only surface.

## 7. Identity flows

### 7.1 OAuth & magic-link sign-in

- Sign-in providers: Google, Apple, Facebook, Email magic link. Apple is App Store-mandatory once others are listed; ship Apple from day 1.
- On first sign-in, Supabase creates an `auth.users` row; a trigger mirrors `(id, email)` into `users` with `is_placeholder = false` and the OAuth-provided display name.
- The user lands on onboarding (`05 §1`).

### 7.2 Adding a placeholder participant

From the expense form's "Participants" picker, the user can type a name not in the member list. On submit:

1. Call `add_placeholder_participant(group_id, display_name)` RPC.
2. RPC validates `display_name` is unique within the group's placeholders (case-insensitive).
3. RPC inserts `users` row with `is_placeholder = true, placeholder_group_id = group_id`.
4. RPC inserts `members` row with `is_admin = false, status = 'ACTIVE'`.
5. Returns the new `user_id`.

The user can rename a placeholder later from the member list; renaming is allowed only by an account-holding member (placeholders themselves can't act).

### 7.3 Placeholder claim & merge

Full UX in `01 §3.2`. The RPC `claim_placeholders(group_id, placeholder_ids[])` runs atomically:

```sql
BEGIN;
  -- Real user must be an active member of the group (or being added now).
  INSERT INTO members(group_id, user_id, status, joined_at) VALUES (...)
    ON CONFLICT (group_id, user_id) DO NOTHING;

  FOR each placeholder_id IN placeholder_ids LOOP
    UPDATE shares    SET user_id = real_user_id WHERE user_id = placeholder_id;
    UPDATE expenses  SET payer_user_id = real_user_id WHERE payer_user_id = placeholder_id;
    UPDATE settlements SET from_user_id = real_user_id WHERE from_user_id = placeholder_id;
    UPDATE settlements SET to_user_id   = real_user_id WHERE to_user_id   = placeholder_id;
    UPDATE settlement_allocations -- no user_id on this table; skip
      ...
    -- Append history events for every affected expense:
    INSERT INTO history_events (group_id, expense_id, event_type, payload)
    SELECT group_id, expense_id, 'PARTICIPANT_MERGED',
           jsonb_build_object('from', placeholder_id, 'fromName', <name>, 'to', real_user_id)
    FROM expenses WHERE id IN (... affected ...);
    DELETE FROM members WHERE user_id = placeholder_id AND group_id = ?;
    DELETE FROM users   WHERE id = placeholder_id AND is_placeholder = true;
  END LOOP;

  UPDATE members SET placeholder_claim_completed_at = now()
    WHERE user_id = real_user_id AND group_id = ?;
COMMIT;
```

After commit, server broadcasts the affected rows via Realtime so other members see the merge live.

### 7.4 Renaming a real user

User edits `users.display_name`. All reads JOIN `users` so the change propagates automatically. No per-expense rewrite. The history log is unaffected (it stores `display_name_at_time` only for events where the name was relevant — see `02 §3.12`).

### 7.5 Group abandonment

When the last active member leaves:

1. `groups.admin_user_id = NULL`.
2. No reminder pushes fire (the daemon checks for `admin_user_id IS NOT NULL` before enqueuing).
3. The group remains readable to any left member who still has a `members` row with `status = 'LEFT'`.
4. No further mutations are possible by non-members; left members are not entitled to mutate.

## 8. Retroactive member addition

The headline differentiator (App_Overview §6).

### 8.1 Trigger

A user adds a new member M (account or placeholder) and chooses on the dialog: "Add to all past expenses" or "Future expenses only."

If "Future only": stop here. M is added with no retro effect.

If "All past": the server iterates every non-deleted, non-refund expense E in the group:

```
for each expense E in group, ordered by created_at:
    if M was already a participant of E: skip
    if E.split_mode == 'EVEN':
        retroEqualSplit(E, M)
    else:
        createConflict(E, M, triggeredBy = caller)
```

### 8.2 retroEqualSplit (silent recompute)

```
oldShare = E.amount_subunits / oldParticipantCount   (with allocate rounding)
newShare = allocate(E.amount_subunits, weights = list(N+1 ones))[forEach]
for each existing participant P:
    appliedAgainstP = SUM(allocations for P's share)
    newShareP       = newShare[P]
    if newShareP >= appliedAgainstP:
        update P.share_owed_subunits = newShareP
        update P.remaining_subunits  = newShareP - appliedAgainstP
    else:
        // newShareP < appliedAgainstP -> auto-refund
        generateAutoRefund(E, recipient = P, amount = appliedAgainstP - newShareP)
        update P.share_owed_subunits = newShareP
        update P.remaining_subunits  = 0

insert share row for M with share_owed_subunits = newShare[M], remaining_subunits = newShare[M]
append history_events RETRO_RESPLIT(decision = 'EQUAL_AUTO')
```

### 8.3 createConflict

```
insert into conflicts (expense_id, added_user_id, triggered_by_user_id)
emit reminder check (admin schedule daemon)
```

No share row is created yet for M; it's created at resolution.

### 8.4 Resolution

Any group member can open a `conflict` row and choose:

**INCLUDE** — the resolver enters a new share for M (the system does NOT pre-fill a suggestion). The new share is in the same currency as the expense and bounded `[0, amount_subunits]`. On submit:

```
expense.split_mode does NOT change (still BY_SHARE / BY_PERCENTAGE / BY_EXACT)
new shares row for M with share_owed_subunits = entered, remaining_subunits = entered
for each existing participant P:
    proportionally reduce P.share_owed_subunits so SUM == amount_subunits
    (allocate over current weights minus M's slice)
    if reduction lowers P.share_owed_subunits below appliedAgainstP -> auto-refund (same as §8.2)
conflict.resolved_at = now, .resolution = 'INCLUDE'
append history_events CONFLICT_RESOLVED(decision='INCLUDE', new_share_subunits=...)
```

**DISMISS** — M is removed from this conflict's expense permanently (no share row). The conflict is closed with `resolution = 'DISMISS'`.

```
conflict.resolved_at = now, .resolution = 'DISMISS'
append history_events CONFLICT_RESOLVED(decision='DISMISS')
```

### 8.5 Conflict reminders

A daemon (Supabase scheduled Edge Function `notify_admin_of_conflicts`, runs every 6 hours) checks for groups with `EXISTS (SELECT 1 FROM conflicts WHERE group_id = G AND resolved_at IS NULL)` AND `admin_user_id IS NOT NULL` AND (`last_conflict_reminder_at IS NULL` OR `last_conflict_reminder_at < now() - cadence_interval(reminder_cadence)`). For each match: send a push to `groups.admin_user_id` (subject to their `notification_preferences.conflict_reminder` and the admin's archived state, `§14.3`), then stamp `groups.last_conflict_reminder_at = now()` to dedup. `cadence_interval` maps `DAILY → 1 day`, `EVERY_THREE_DAYS → 3 days`, `WEEKLY → 7 days`.

`reminder_cadence` is one of `DAILY`, `EVERY_THREE_DAYS`, `WEEKLY`. Default `WEEKLY`. Admin-editable from group settings.

### 8.6 Removing a retroactively-added member

If a member added via "Add to all past expenses" is later removed from the group:

1. For each expense where the auto-refund flow fired on add: reverse the auto-refund (delete the auto-refund expense — it's marked `is_auto_refund = true` so it's identifiable).
2. For each expense whose split was silently recomputed (equal-split case): recompute the split without M and rewrite shares.
3. For each expense where M's share was set via conflict resolution: drop M's share, redistribute over remaining participants per original split mode. If this would push any existing share below its `appliedAgainstP`, do the symmetric auto-refund (now from M to that participant — but M is leaving, so the refund is a debt the group sees as M owing the original payer that participant overpaid through; in practice this case is rare and is logged as `RETRO_RESPLIT` again).
4. Append `history_events` for each affected expense.

This is the only operation in the app that reverses an auto-refund. Manual refunds (`§3.4`) are never reversed automatically.

### 8.7 Auto-refund generation (called by §3.2, §8.2, §8.4, §8.6)

```kotlin
fun generateAutoRefund(
    sourceExpense: Expense,
    recipient: UserId,      // who overpaid
    overpaidSubunits: Long
) {
    val refund = Expense(
        id = uuidv7(),
        groupId = sourceExpense.groupId,
        kind = "REFUND",
        title = "Auto-refund for \"${sourceExpense.title}\"",
        amountSubunits = overpaidSubunits,
        currency = sourceExpense.currency,
        expenseDate = today(),
        payerUserId = sourceExpense.payerUserId,     // original payer refunds the overpayer
        payerOutsideName = sourceExpense.payerOutsideName,
        participants = listOf(recipient),
        splitMode = "BY_EXACT",
        shares = listOf(Share(recipient, overpaidSubunits, overpaidSubunits)),
        refundOfExpenseId = sourceExpense.id,
        isAutoRefund = true,
        createdBy = systemUserId
    )
    insertExpenseAndShares(refund)
    appendHistoryEvent(sourceExpense.id, AUTO_REFUND_GENERATED, payload = {
        refundExpenseId: refund.id,
        toUserId: recipient,
        amountSubunits: overpaidSubunits,
        currency: sourceExpense.currency,
        reason: "RETRO_RESPLIT_SETTLED_OVERPAY"
    })
}
```

The "systemUserId" is a deterministic UUID reserved for system-authored events (`00000000-0000-0000-0000-000000000000`). It is seeded once at deploy (`02 §3.2`, "Reserved system user") with `is_placeholder = false` and the reserved email `system@sharecost.invalid` so it satisfies the `users_real_has_email` CHECK. It never appears as a `members` row, never as `groups.admin_user_id`, and is excluded from every member-facing query (member lists, pickers, balances, push recipients). RLS allows the `add_expense` / retro RPCs running SECURITY DEFINER to write rows with this `created_by` and `payer_user_id`.

Note: the auto-refund's `payer_user_id` is the **original payer** (who refunds the overpayer), not the system user; the system user is only the `created_by` actor. The `actor_user_id` on the `AUTO_REFUND_GENERATED` history event is NULL (system event) per `02 §3.12`.

## 9. History log

Append-only (`02 §3.12`). Every mutation MUST emit at least one event.

### 9.1 Display

On the expense detail screen, a collapsible "History" section lists events newest-first. Each row:

- Avatar of `actor_user_id` (or system icon if NULL).
- Single-line summary derived from `event_type` + `payload`. Examples:
  - `EDITED`: "Andrew edited amount from $42.00 to $48.00."
  - `RETRO_RESPLIT`: "Tyler was added and silently included (equal split)."
  - `AUTO_REFUND_GENERATED`: "System generated a $4 refund to Tyler (he had already settled)."
  - `PARTICIPANT_MERGED`: "Tyler was identified — past entries labeled 'Ty' are now Tyler."
- Timestamp.

### 9.2 Tamper resistance

Server-side RLS denies UPDATE and DELETE on `history_events` to all roles except service. Local clients store the events but never authoritatively; on conflict, the server is the source of truth.

## 10. Comments

### 10.1 Visibility

A `comment` on expense E is visible iff the viewer is:

- The expense's payer (`payer_user_id`), OR
- A participant of the expense (has a `shares` row).

Non-participants of E MUST NOT see the comment, the comment count, or the unread indicator.

RLS:

```sql
CREATE POLICY comments_select ON comments FOR SELECT USING (
  EXISTS (
    SELECT 1 FROM expenses e
    WHERE e.id = comments.expense_id
      AND e.deleted_at IS NULL
      AND (e.payer_user_id = auth.uid()
           OR EXISTS (SELECT 1 FROM shares s WHERE s.expense_id = e.id AND s.user_id = auth.uid()))
  )
);
```

### 10.2 Resolve

Any participant can resolve a thread (`is_resolved = true`). Resolved threads collapse into a single chip "Resolved (Andrew, 3 days ago)" tappable to expand. Re-opening is allowed; just flip the flag back.

### 10.3 Unread indicator

Computed locally from `comments.created_at` vs. `comment_read_state.last_read_at`. On opening an expense's thread, the client upserts `comment_read_state` with `last_read_at = max(comments.created_at)`.

## 11. Categories

### 11.1 Seed

On group creation, `seed_default_categories(group_id)` populates the list from `02 §3.6`.

### 11.2 Editing

Any active member can add, rename, or delete a category or subcategory. Deletion:

- Reassigns every affected expense's `category_id` (or `subcategory_id`) to the group's "Other" category (creating it if it doesn't exist).
- Soft-deletes the category row.
- Appends a single `history_events` to every affected expense: `EDITED` with `{ changes: { categoryId: [old, new] } }`. (Bulk inside one transaction.)

### 11.3 Per-subcategory spending breakdown

On the Balances tab, a "Spending by category" card aggregates `SUM(amount_subunits) GROUP BY (currency, category_id, subcategory_id)` over a user-selected date range (default: last 30 days; presets: this month, last month, this trip, custom). Render two-level: category total, expandable to subcategory rows. Per currency — multi-currency groups show one card per currency.

## 12. Trip overview

Single screen. Sections:

1. **Group total** per currency.
2. **Spend by day** — bar chart of per-day totals; tap a bar to filter the expense list below to that day.
3. **Spend by member** — bar chart with the X-axis = member display name, Y-axis = `(paid - share)` per the currency. Toggle "Show per-currency" cycles through currencies if multi-currency.
4. **Per-member daily spend** — heatmap or sparkline list per member.
5. **Resulting balances** — bilateral pairs.

Export as a single PNG (rendered in CMP via `ImageBitmap` capture; see `06 §4.5`). The PNG mirrors the screen exactly.

## 13. Exports

### 13.1 Formats

Per group, on demand:

- **CSV** — one row per expense + one row per share + one row per settlement, in three sheets concatenated with header markers (or three separate CSVs in a zip).
- **JSON** — full dump of expenses, shares, settlements, history, members, comments, receipts (metadata only — file URLs are signed and time-limited).
- **PDF** — typeset summary: cover page (group name, date range, total spend per currency), expense list grouped by day, balances, member totals.

### 13.2 Trigger

From group settings → "Export." Server-side Edge Function `export_group(group_id, format)` builds the artifact, uploads to a temp bucket, and returns a signed URL valid for 24h.

### 13.3 Free, unconditional

No paywall, no row limit, no rate limit beyond standard abuse protection (1 export per minute per user).

## 14. Notification model

### 14.1 Triggers (server-side, fanned out via FCM/APNs Edge Function)

| Trigger | Recipients | Pref key |
|---|---|---|
| Expense added | participants + payer (excluding the actor) | `expense_added` |
| Expense edited | participants + payer (excluding actor) | `expense_edited` |
| Settlement marked paid | recipient of payment (`to_user_id`) | `settlement_received` |
| Comment added | participants + payer (excluding actor) | `comment_added` |
| Conflict reminder | admin only | `conflict_reminder` |
| Member joined | all active members (excluding the new member) | `member_joined` |

### 14.2 Per-channel suppression

Per `02 §3.16`. The Edge Function MUST consult `notification_preferences` per recipient before sending.

### 14.3 Archived-group suppression (D-11)

If the recipient has `members.archived_at IS NOT NULL` for the relevant group, the notification is dropped entirely (silenced). This is independent of per-channel prefs.

### 14.4 Payload

Deep links into the relevant screen. Minimum payload:

```json
{
  "type": "EXPENSE_ADDED",
  "group_id": "<uuid>",
  "expense_id": "<uuid>",
  "title": "Andrew added 'Dinner' for $48",
  "body": "Your share: $16"
}
```

The client opens the relevant screen on tap (`05 §11`).

## 15. Receipts (compression, thumbnails, lazy fetch) — D-22, OQ-11

ShareCost optimizes for not bloating either Supabase Storage or the device.

### 15.1 Upload pipeline (client-side, before any network call)

1. **Compress.** Images are re-encoded to JPEG (or HEIC where supported) at quality ≥ 0.80 with the long edge capped at 2048 px. PDFs are uploaded unmodified. The compressed file's size becomes `receipts.byte_size` and is what the 30 MB cap (`02 §3.10`) measures.
2. **Thumbnail.** A thumbnail (long edge ≤ 320 px, JPEG ~0.7) is generated locally. For PDFs, page 1 is rendered to a JPEG via the platform helper (`06 §5.9`). Its size becomes `receipts.thumb_byte_size`.
3. **Upload both.** `request_receipt_upload` returns presigned PUT URLs for the full image and the thumbnail; the client uploads both, then calls `confirm_receipt_upload` (`04 §7`).

The compression and thumbnail generation are deterministic-enough for caching but are NOT required to be byte-reproducible; they run once on the uploading device.

### 15.2 Lazy fetch & 14-day cache

- Receipts are **never** bulk-downloaded. Only receipt **metadata** rows sync to the device (`02 §7`).
- A receipt scroller renders thumbnails by fetching the thumbnail object on first display.
- The full compressed image is fetched only when the user taps to open it full-screen.
- Both are stored in Coil's disk cache with a **14-day TTL** (`07 §10.1`). After 14 days an entry is evicted and re-fetched on next view.

Rationale (per OQ-11): a receipt viewed for one trip is very unlikely to be needed while settling a later, unrelated trip, so persisting every receipt locally wastes space. Lazy fetch + short TTL keeps the on-device footprint small while keeping a just-viewed receipt instant.

### 15.3 Deletion (author-only, D-26, OQ-06)

Only the uploader (`receipts.uploaded_by`) may delete a receipt. `delete_receipt` (`04 §2.3`) soft-deletes the row, schedules the Storage objects (full + thumbnail) for removal, and decrements `groups.storage_bytes_used` via the trigger. Any other member attempting deletion receives `NOT_AUTHORIZED`.

---

**Read next:** [`04-api-and-sync.md`](04-api-and-sync.md).
