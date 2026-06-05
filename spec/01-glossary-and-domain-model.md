# 01 — Glossary & Domain Model

> Defines the nouns of ShareCost and how they relate. Read this before `02-data-model.md` (which is the concrete schema) and before `03-business-rules.md` (which is the verbs over these nouns).

---

## 1. Glossary (canonical terms)

These terms are normative. Implementing agents MUST use these names (or the camelCase / snake_case mechanical mapping) in code and UI.

| Term | Definition |
|---|---|
| **User** | A real person with an account (OAuth or magic-link). Has a stable `userId`, exactly one verified email, a display name, optional avatar, and zero or more **payment-app handles**. |
| **Placeholder participant** | A non-account person added to a group by a User. Has a `userId` (UUIDv7), a display name, and a flag `isPlaceholder = true`. Has no email, no auth, no payment handles, cannot receive push, cannot initiate settlement on their own. May later be **claimed** by a User and merged. |
| **Group** | A container for expenses among a fixed set of **members**. Has a name, emoji, base currency, admin (one User), creation date, and an invite-link token. |
| **Member** | A `(User, Group)` join row. Holds membership status (`active`, `left`), join date, and per-user-per-group archive flag. A placeholder participant becomes a member of exactly one group when created (no cross-group sharing of placeholders). |
| **Admin** | The single member with the admin flag set. Recipient of operational notifications (e.g., conflict reminders). Auto-transfers to the longest-tenured remaining active member when the current admin leaves. |
| **Expense** | A record of money paid by one party on behalf of others. Has: amount + currency, payer (a member, or an "outside party" string), participants (subset of members), split mode, split inputs, optional category + subcategory, optional receipt attachments, optional date, optional notes. Carries per-participant `share_owed` and `remaining` balances. |
| **Split mode** | One of: `EVEN`, `BY_SHARE`, `BY_PERCENTAGE`, `BY_EXACT`. Determines how `share_owed` is computed from the amount and the inputs. |
| **Share** (a.k.a. _participation_) | A `(Expense, Member)` row holding `share_owed_subunits` (immutable after creation/last edit) and `remaining_subunits` (decreases as the member pays down their slice). |
| **Settlement** | A payment recorded against one or more expenses. Reduces the corresponding `remaining_subunits` values. May be initiated by deep link into a payment app; the user confirms completion on return. |
| **Refund** | A new expense with `kind = REFUND` and a `refund_of_expense_id` link, with payer and participants typically inverted from the original. Distinct icon, distinct list-row treatment. |
| **Auto-refund** | A Refund generated automatically by the retro-add flow when a re-split lowers an already-settled member's share. Same row type as Refund but with `is_auto = true` and a link to the conflict-resolution event that produced it. |
| **Conflict** | A pending decision arising from a retro member-add on a non-equal-split expense. Each Conflict is a `(Expense, NewMember, MemberWhoTriggeredAdd)` row visible in the **Conflicts tab** until resolved by any group member. |
| **Conflict resolution** | The act of, for one Conflict row, either (a) modifying the expense's split to include the new member with an explicit share, or (b) dismissing the new member from that specific expense. |
| **Comment** | A message attached to an expense, visible only to that expense's payer and participants. Has author, timestamp, and `is_resolved`. |
| **History event** | An append-only, immutable record of any mutation that affected an expense. Has author, timestamp, type (`CREATED`, `EDITED`, `PARTICIPANT_ADDED`, `PARTICIPANT_REMOVED`, `SPLIT_CHANGED`, `SETTLED`, `REFUND_LINKED`, `RETRO_RESPLIT`, `CONFLICT_RESOLVED`, `AUTO_REFUND_GENERATED`, `COMMENT_ADDED`, `COMMENT_RESOLVED`), and an opaque JSON payload. |
| **Payment-app handle** | A `(User, PaymentApp, handle)` triple. `PaymentApp` is one of `VENMO`, `ZELLE`, `CASH_APP`, `PAYPAL` for v1. `handle` is the literal user-facing identifier (e.g., `@andrew-c` for Venmo, the email for Zelle/PayPal, `$andrew` for Cash App). |
| **FX rate** | A `(date, base_currency, quote_currency, rate)` row. All rates are stored as `(USD, X)` pairs; cross-pair conversion goes via USD. |
| **Receipt attachment** | A file (JPEG/PNG/HEIC/PDF) attached to an expense **or a draft**. Uploaded **compressed** (≥ 80% quality) with a separate client-generated thumbnail; ≤ 30 MB (compressed) each, ≤ 10 per parent. Stored in Supabase Storage with signed URLs, fetched lazily, cached 14 days. Deletable only by its uploader. (D-22/D-26.) |
| **Draft** | A half-filled Add-Expense form owned by one user, synced across that user's devices, private to the owner. Supports attaching a receipt before the expense is finalized ("receipt-first"). Converts into an expense on submit. (D-24.) |
| **Itemized split** | A `BY_EXACT` expense with `has_tax_row = true`, where participants enter pre-tax subtotals and the expense additionally carries `tax_subunits` (split proportionally) and `tip_subunits` (split per `tip_split_mode`: `PROPORTIONAL` or `EVEN`). (D-21.) |
| **Invite token** | A URL-safe random string (≥ 22 chars) that uniquely identifies a group's join link. Rotation (admin-only) invalidates the old token. |

## 2. Entity-relationship overview

```
User ──┬─── (PaymentAppHandle) [0..*]
       │
       └─── Member ───────────────────────── Group
                │                              │
                │                              ├── InviteToken (1)
                │                              ├── Category [0..*]  (per-group, seeded)
                │                              │   └── Subcategory [0..*]
                │                              │
                ├── Share ── Expense           │
                │              │               │
                │              ├── Receipt [0..*]
                │              ├── Comment [0..*]
                │              ├── HistoryEvent [1..*]   (append-only)
                │              ├── Settlement (allocation rows) [0..*]
                │              ├── Refund (as another Expense) [0..*]
                │              └── Conflict [0..*]   (cleared on resolution)
                │
                └── ArchiveState (per Member)

Group ── FxRate snapshots are GLOBAL (not per-group).
```

Cardinalities:

- **Group** has 1..N **Members** (1 if last member just left; group is then "abandoned" — see `03 §7.5`).
- **Expense** has 1..N **Shares**. A payer who is also a participant has a Share like any other.
- **Expense** has 0..1 `refund_of_expense_id` (forward link). Refund chains are not allowed (refund-of-a-refund is rejected at validation).
- **Conflict** is keyed `(expense_id, new_member_id)`. A conflict exists only for non-equal-split expenses; equal-split expenses are silently recomputed.

## 3. Member identity model

### 3.1 User vs. placeholder

- A **User** has authentication, a verified email, a stable `userId`, and can be in many groups.
- A **Placeholder participant** has the same `userId` shape (UUIDv7) and is stored in the same `users` table with `is_placeholder = true`. It belongs to exactly one group (enforced by a unique index on `(placeholder_group_id, lower(display_name))` where `is_placeholder`).
- A placeholder MUST NOT receive a Supabase auth row, MUST NOT be sent push, MUST NOT have payment-app handles, MUST NOT be the admin of any group.

### 3.2 Placeholder claim & merge (the "Tyler joins" flow)

This is the most subtle identity flow in the app. Implementing agents MUST follow it exactly.

**Setup.** Bob has an account. Bob creates a group called "Trip." Bob adds Tyler to several expenses by typing the name "Tyler" — the app creates a placeholder participant in the group with `userId = TYLER_PLACEHOLDER_1`. Later, Bob (or another member) adds an expense and types "Ty" instead — the app creates a second placeholder `TYLER_PLACEHOLDER_2`. Both are independent placeholders attached to the same group.

**Trigger.** Tyler taps Bob's invite link (or installs the app and pastes the invite URL). Tyler completes auth (OAuth or magic-link). Tyler now has a real `userId = TYLER_REAL`.

**Reconcile screen (mandatory at first claim attempt).** Before adding Tyler as a member of the group, the app fetches all placeholder participants in that group and presents them in a multi-select list. The screen MUST:

1. Show every placeholder with its display name and a small horizontal scroller of the expenses it's on (each chip showing `original amount + currency + truncated expense title`).
2. Allow tap-to-toggle selection with a **clear green outline** on selected items. Tapping a selected item deselects.
3. Show a "These are not me" CTA (visually weaker than the primary button) that proceeds without merging.
4. Show a primary CTA "These are me (N)" enabled when at least zero items are selected (zero is allowed — it's the happy path of a fresh joiner).

**Confirmation modal.** On tapping the primary CTA with N ≥ 1 selected:

- Title: "You are also [Tyler, Ty]."
- Body lists every expense that any selected placeholder appears on, formatted as: `[expense title]  [original amount + currency]  — listed as "Ty"` (the name used per expense, so Tyler can sanity-check).
- Cancel and Confirm buttons.

**On confirm:**

1. Insert a `members` row `(TYLER_REAL, group_id, joined_at = now)`.
2. For each selected `placeholder_user_id`:
   - In every `shares` row where `user_id = placeholder_user_id`: rewrite `user_id` to `TYLER_REAL`.
   - In every `expenses` row where `payer_user_id = placeholder_user_id`: rewrite `payer_user_id` to `TYLER_REAL`.
   - Delete the `members` row for the placeholder. Delete the `users` row for the placeholder (it has no other references).
   - Append a `HistoryEvent` of type `PARTICIPANT_MERGED` to every affected expense with payload `{ from: placeholder_user_id, fromName: <name>, to: TYLER_REAL }`.
3. The operation runs as a single Supabase RPC `claim_placeholders(group_id, real_user_id, placeholder_ids)` inside a transaction.
4. After commit, Tyler lands on the group's expense list with everything attributed to his real name.

**Happy path: nothing to merge.** If the placeholder list is empty, or Tyler selects zero items, skip the confirmation modal and proceed to step 1 (insert member row), then land on the group.

**Re-entry.** A `Reconcile past activity` action MUST remain available on the group home screen for any member who:
- Was added via invite link AND
- Has no `placeholder_claim_completed_at` recorded for this group.

The action opens the same reconcile screen with fresh data. Tyler can claim placeholders later. The action disappears after the first successful run (whether or not any placeholders were selected).

**Self-rename propagation.** When a User edits their display name, the change applies globally to that User — the `display_name` lives on the `users` row, not duplicated per group. There is therefore no per-expense rewrite needed for self-renames; all reads JOIN to `users.display_name`. The same applies when a placeholder is claimed: existing expense rows reference `users.id`, and the displayed name follows that join.

### 3.3 Outside party (non-member payer or recipient)

Distinct from placeholders. Specified at the per-expense level only:

- `payer_outside_name: String?` — when set, `payer_user_id` is NULL and this string is shown as the payer.
- The amount counted as paid does NOT contribute to any group balance; this expense only adjusts what the participants owe **each other** (the outside party absorbs the cost from outside the group's ledger).

Difference from a placeholder: a placeholder accrues balances over time and can be claimed. An outside party is per-expense, ephemeral, and never balance-bearing.

### 3.4 Self-as-payer-only

A User can appear as a payer on an expense without being a participant of the split. Balance math treats this as: payer paid the full amount, owes themselves nothing, and the participants collectively owe them the full amount per their shares. This is already covered by the general algorithm in `03 §3` and is called out here only because the UI must allow toggling "I paid but I'm not on the split."

## 4. Group lifecycle

### 4.1 Creation

A User taps "New group." Inputs: name (required, ≤ 60 chars), emoji (optional, defaults to 💸), base currency (defaults to the **creator's `users.base_currency`** — falling back to `USD` only if unset — picker shows ISO 4217 list; D-27, OQ-04). On create:

1. Insert `groups` row with `admin_user_id = creator`.
2. Insert `members` row `(creator, group_id, joined_at = now, is_admin = true)`.
3. Generate a fresh `invite_token`.
4. Seed default `categories` rows (see `02 §3.7`).
5. Land creator on the group's home (empty Active tab).

### 4.2 Invite & join

- Invite URL: `sharecost://join?t=<token>` (custom scheme) plus a universal/app link `https://sharecost.app/j/<token>` that opens the app if installed, otherwise routes to a static landing page. QR code encodes the universal link.
- Tap-through on a device with the app installed: deep link opens the in-app "Join group?" sheet showing group name, emoji, member count, and a `Join` button. Member count is fetched live (cached). On `Join`:
  - If signed in: insert `members` row and route to placeholder reconcile (`§3.2`).
  - If not signed in: route to auth, preserving the token; complete auth, return to join sheet.
- Tap-through on a device without the app: landing page with store links. The token is preserved in pasteboard (universal link best-effort) so post-install attribution can resume the join.

### 4.3 Leaving a group

A member can leave at any time, **regardless of outstanding balances**. Leaving:

- Flips `members.status` to `left` and stamps `left_at = now`. Row is retained so historical attribution survives.
- Removes the member from any further expenses (cannot be a new participant, cannot be a payer of a new expense, cannot be a settlement recipient via deep link).
- Their past `Shares` and `Settlements` are unchanged.
- If the leaver was admin, transfer admin to the longest-tenured active member by `joined_at ASC`.
- If no active members remain, the group is **abandoned**: no admin, no conflict reminders, no further mutations possible by non-members. Reads still work for the leaver(s).

### 4.4 Archive (per member)

A member can archive a group from their own dashboard. Archive is a **per-`(user, group)` setting**, recorded in `members.archived_at`. While archived:

- The group does not appear in the home list (it appears under Settings → Archived groups).
- Push notifications for this group are **silenced** to this member (per D-11). Other members are unaffected.
- The group's data is otherwise unchanged; balances stay live; other members see no difference.

Unarchive restores the group to the home list and reactivates notifications.

## 5. Expense lifecycle

```
[draft, in form] ──submit──▶ [active, remaining>0] ──fully paid──▶ [settled]
                                  │                          ▲
                                  ├── partial payment ───────┘ (back to active until 0)
                                  │
                                  ├── edited ─▶ [active]   (history event appended)
                                  ├── refunded ─▶ refund Expense created; original unchanged
                                  └── deleted ─▶ [deleted]  (soft delete; history retained)
```

Notes:

- An expense is **settled** iff `SUM(remaining_subunits) == 0` across all its Shares.
- "Active" / "Settled" / "All" in the UI are filters over this state, not separate stored states.
- Hard deletion is reserved for owner-data export-then-delete flows (compliance). Standard "Delete" is a soft delete that hides the row and updates balance computation by treating it as if it had never existed.

## 6. Currency & FX

- Each Expense carries its own currency. A group has a `base_currency` used for display rollups only (see `03 §6`).
- Balances are kept per-currency-pair (Alice owes Bob $20 + £15, never auto-converted at rest).
- FX rates are global (not per-group), keyed by `(date, USD-to-quote-currency)`. Cross-pair conversion always routes via USD.
- Three FX sources, in priority order:
  1. **Live cache** in Room, refreshed daily from Frankfurter.
  2. **Baked snapshot** shipped with the app binary, used when the live cache is empty (e.g., first launch with no network).
  3. **Stale cache** when the daily fetch has been failing — surfaced with a hint only when > 7 days old.

Full FX math in `03 §6`.

## 7. Permissions model (informal)

This is the per-role intuition; the normative version is the RLS policies in `02 §5`.

| Action | Admin | Active member | Left member | Non-member | Placeholder |
|---|---|---|---|---|---|
| Read group expenses | ✔ | ✔ | ✔ (their historical shares) | ✘ | n/a |
| Create expense in group | ✔ | ✔ | ✘ | ✘ | n/a |
| Edit / delete an expense | ✔ | ✔ (any expense in their group) | ✘ | ✘ | n/a |
| Resolve a Conflict | ✔ | ✔ | ✘ | ✘ | n/a |
| Add a member | ✔ | ✔ | ✘ | ✘ | n/a |
| Remove a member | ✔ | ✔ (any active member) | ✘ | ✘ | n/a |
| Rotate invite token | ✔ | ✘ | ✘ | ✘ | n/a |
| Delete a receipt | ✔ if uploader | ✔ if uploader | ✘ | ✘ | n/a |
| Edit / discard own draft | ✔ (own) | ✔ (own) | ✘ | ✘ | n/a |
| Archive group (personal) | ✔ | ✔ | ✘ | ✘ | n/a |
| Receive conflict reminder push | ✔ (only admin) | ✘ | ✘ | ✘ | n/a |
| Comment on an expense | ✔ if participant | ✔ if participant | ✘ | ✘ | n/a |

Notable: any active member (not just admin) can resolve conflicts, edit expenses they didn't create, and remove members. v1 explicitly does not gate member-management by admin role; admin in v1 is **only** the conflict-reminder recipient and tie-breaker for token rotation. This is consistent with App_Overview §6 ("admin v1 ships with this behavior only") and is a deliberate simplification.

## 8. Payment-app deep link model (intuition)

A User stores zero or more `payment_app_handles`. Settling against another User:

1. Pick a `target_user` (the payee).
2. Read their `payment_app_handles`; if empty, surface a small inline "No payment apps set — copy [handle]?" affordance (still allows clipboard fallback with the user's email or phone-as-handle if available).
3. Pick a payment app (the user can choose, defaults to the payee's first listed app).
4. Construct the deep link with `amount` and `recipient_handle` pre-filled (see `03 §5.1` for per-app URL templates).
5. Open the link via the platform's URL opener. On failure (catch `ActivityNotFoundException` on Android; on iOS `canOpenURL == false`), fall back to clipboard.
6. After return-from-app (best-effort detection via app-foreground event), prompt: "Did the transfer go through?" with `Yes — mark paid` and `Not yet`.

Full URL templates and per-app fallback behavior in `03 §5`.

---

**Read next:** [`02-data-model.md`](02-data-model.md).
