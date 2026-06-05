# 05 — UX & Screens

> Screen-by-screen specification. Each screen lists: purpose, entry points, state (loading / empty / error / populated), components, interactions, and accessibility notes. All UI is built in Compose Multiplatform (CMP) using a single shared component library described in `06 §4`.
>
> **Default theme:** dark. Color contrast ratios MUST meet WCAG 2.1 AA (`07 §3`). All text uses the platform's dynamic type scale.

---

## 0. Information architecture

```
[Auth flow] ──▶ [Onboarding] ──▶ [Home (group list)] ──┬─▶ [Group home]
                                                       │     ├─▶ [Expense detail]
                                                       │     ├─▶ [Add/Edit expense]
                                                       │     ├─▶ [Balances]
                                                       │     ├─▶ [Trip overview]
                                                       │     ├─▶ [Conflicts tab]
                                                       │     ├─▶ [Group settings]
                                                       │     └─▶ [Reconcile past activity] (one-time)
                                                       │
                                                       ├─▶ [Profile & settings]
                                                       └─▶ [Archived groups]
```

A bottom nav inside a group home: **Expenses** (default), **Balances**, **Conflicts** (visible only when count > 0), **Overview**. Plus a "More" overflow → group settings, export, archive.

Outside groups: tab bar with **Home**, **Profile**.

## 1. Auth & onboarding

### 1.1 Sign-in screen

Triggered on cold start when no session exists.

- Top: large ShareCost wordmark + tagline "Track shared expenses. Pay through your own app."
- Buttons (stacked, full-width):
  - "Continue with Google"
  - "Continue with Apple"
  - "Continue with Facebook"
  - "Continue with Email" (magic link)
- Bottom: tiny links to Privacy and Terms.

Empty/error: provider-specific error returned as a snackbar at the bottom.

Accessibility: each button labeled with the provider name; logo is decorative.

### 1.2 Email magic-link screen

After tapping "Continue with Email":

- TextField: email (autofill `username`).
- Primary button: "Send magic link" — disabled while invalid; loading spinner inside while in flight.
- On success: full-screen confirmation "Check your email — we sent a link to <email>." with "Try a different email" link.

Tapping the link in email opens the app via universal link, completes auth, and lands on §1.3.

### 1.3 First-launch onboarding

For accounts that have just been created:

1. **Set display name** (pre-filled from OAuth if available). Required.
2. **Set base currency** — an explicit step (not buried), pre-filled with `USD` (D-27, OQ-04). The chosen value is stored as `users.base_currency` and is editable later in Profile → Base currency (`§10.2`). This currency — not a hardcoded USD — is what drives the user's display rollups everywhere.
3. **Add a payment app handle (optional)** — list of Venmo, Cash App, Zelle, PayPal. Each: tap to expand, enter handle. Set first one as primary. **Skippable** but the screen explains: "Without a payment handle, others can't pay you back through your preferred app — they'll see your name and have to settle some other way."
4. **Help improve ShareCost (anonymous analytics)** — a single toggle, **default ON**, with copy: "Share anonymous, non-identifying usage and crash data to help us fix bugs and improve the app. No names, emails, amounts, or expense details are ever included. You can change this any time in Profile → Privacy." (D-25, OQ-07.) The toggle gates Firebase Analytics + Crashlytics collection (`07 §4`). Turning it off calls the platform `setAnalyticsCollectionEnabled(false)` / `setCrashlyticsCollectionEnabled(false)`.
5. **Notification permission** — system prompt invoked after the user taps "Continue."

Lands on Home (empty state if no groups yet).

## 2. Home (group list)

Top bar: avatar (taps → Profile), title "ShareCost," "+ New group" FAB or button.

Body:

- Section 1: **Active groups** — sorted by `MAX(expenses.expense_date) DESC`. Each row shows:
  - Emoji + name
  - Member count + "you owe / you're owed" summary in the user's base currency (computed as a vector then converted at display time using current FX; if no debts, "All settled").
  - Last activity ("Andrew added $48 · 2h ago").
- Section 2: collapsible **Archived (N)** — opens the Archived groups screen.

Empty: large illustration + "No groups yet — create one to start tracking."

Loading: skeleton rows (3 visible).

Pull-to-refresh: triggers `pull_home` (`04 §6.7`).

### 2.1 New group sheet

Slide-up sheet with:

- Emoji picker (defaults 💸).
- TextField: name (max 60).
- Currency picker: searchable; **defaults to the creator's base currency** (`users.base_currency`), not a hardcoded USD (D-27, OQ-04).
- "Create" button.

On tap: calls `create_group`, navigates to Group home of the new group.

## 3. Group home

Sticky top bar:

- Back arrow.
- Emoji + group name (taps → Group settings).
- Right-side overflow: "Share group link," "Add expense" shortcut.

Below the top bar: bottom nav described in §0.

### 3.1 Expenses tab (default)

Top: three sub-tabs **Active · All · Settled**. Default Active.

Underneath: search icon (taps → Search overlay, §3.6).

Body: day-grouped list with sticky date headers ("Today," "Yesterday," "Wed, May 22, 2026"). Each day section shows:

- Active expenses for the day in chronological order.
- A "Settled (N)" tray at the bottom of the day section (collapsed by default in Active tab; expanded by default in Settled tab). Tap to expand.

Each expense row:

- Left: category icon (or default).
- Top line: title in body text. Right side: remaining amount in **large** body-bold, original amount below in small muted (per `03 §1` and the differentiator spec).
- Below: payer + "you paid $X" / "you owe $Y" attribution.
- Right side: chevron + unread comment indicator (small dot) if applicable.
- Tap → Expense detail.

Floating Action Button: **+ Add expense**.

Empty state per tab:

- Active: "No outstanding expenses." Big emoji ✅.
- All: "No expenses yet. Tap + to add one."
- Settled: "No settled expenses yet."

Loading: skeleton rows (5 visible).

Sort & filter affordance: small "Filter" icon in the top bar opens a sheet with:

- Sort by: Date (default), Amount, Payer, Category, Participant.
- Filter by: Member (multi-select), Category (multi-select), Date range (presets + custom).

### 3.2 Balances tab

Top: currency selector (only visible if group has > 1 currency in use).

Body, two sections:

1. **Pairwise debts** — list of "X owes Y $Z" rows in the selected currency. Tap a row → Settle person flow (`§7`).
2. **Spending by category** card — see `03 §11.3`.

Below: muted total ("You owe Bob $20, Charlie $5; You're owed by Alice $15.").

Empty: "All settled in this currency. 🎉"

### 3.3 Conflicts tab

Visible only when there are unresolved conflicts. The bottom nav shows a small numeric badge.

Body: list of conflicts grouped by expense, each row showing:

- The expense title + amount.
- "Added: Tyler." (the new member triggering this conflict)
- Two buttons: **Include Tyler** (primary) and **Skip Tyler** (text-only).

Tap **Include Tyler** → opens a small sheet:

- Reminder of the original split (e.g., "Andrew: $20, Bob: $30, Charlie: $50 — total $100").
- Input: "Tyler's share" — empty by default (per App_Overview, no pre-fill).
- Live "Remaining to distribute: $X" indicator computed from existing participants' shares minus Tyler's entered share. Must end at 0 by reducing one or more existing shares proportionally (handled in math, displayed live).
- "Confirm."

Tap **Skip Tyler** → calls `resolve_conflict(dismiss)` directly, no further confirmation.

### 3.4 Overview tab (Trip overview)

Per `03 §12`. One scrollable screen:

1. Header: group emoji + name, date range pill (default "All time"), per-currency total chips.
2. **Spend by day** bar chart.
3. **Spend by member** bar chart.
4. **Resulting balances** list (read-only pairwise debts).
5. "Export as image" button → renders the screen to a PNG and opens the system share sheet.

### 3.5 Archive (under Group settings)

A single "Archive group" action in Group settings (`§9`). On tap, confirmation: "Archive [group name]? It'll move to Archived groups; you won't get notifications. You can unarchive any time." Two buttons: Cancel / Archive.

Archived list screen: same row component as Home, but each row has an "Unarchive" affordance on swipe (Android) or trailing context (iOS).

### 3.6 Search overlay

Full-screen overlay with a search field and live results. Searches across:

- Expense titles
- Member display names
- Category names

Tap result → navigate.

## 4. Expense detail

Triggered by tapping any expense row.

Top bar: back arrow, expense title (truncated), overflow menu (Edit / Delete / Refund / Add receipt / Share image).

Body, scrollable:

### 4.1 Header card

- Original amount in muted, **remaining in large**, both with currency.
- "Paid by [Payer]" with avatar.
- Expense date.
- Category + subcategory chip.
- Optional receipts (horizontal scroller of thumbnails; tap to open a full-screen viewer with swipe between). Thumbnails are fetched lazily when the scroller renders; the full compressed image is fetched only on tap; both are cached for 14 days (`03 §15.2`). A receipt the author uploaded shows a delete affordance; for non-authors the delete option is hidden (D-26, OQ-06).

### 4.2 Split breakdown

Per-participant list. Each row:

- Avatar + display name.
- Share owed: bold.
- Remaining: muted, with a small "Paid X of Y" progress bar if partial.
- Trailing action button: if this is me and remaining > 0, "Settle this" → §7.

### 4.3 Comments section

- Header "Comments (N)" with unread-dot if applicable.
- List of comments newest-last (chat-like). Author avatar + name + time. Tap-and-hold → Resolve / Delete (author only).
- Composer at the bottom (multiline, send button).
- "Resolve thread" affordance at the top of the list once there are messages.

Hidden entirely for non-participants of this expense (per `03 §10.1`).

### 4.4 History section

Collapsible. Default collapsed. Header "History (N events)."

When expanded, list of events newest-first per `03 §9.1`.

### 4.5 Refund section

If this expense has refunds linked to it: list them with a distinct refund-icon and a tap to navigate to the refund's detail page.

### 4.6 Loading / error

- Loading: full-screen skeleton matching the layout.
- Error fetching: full-screen error with "Try again."

## 5. Add / edit expense

### 5.1 Header

- Back arrow ("X" if creating, "←" if editing).
- Title "New expense" or "Edit expense."
- "Save" button (top right, enabled when valid).

### 5.2 Body (scrollable form)

1. **Amount** — large calculator-style number input with currency picker beside it. Built-in calculator: `+ − × ÷` keys overlay the numeric keypad. Currency picker remembers last-used per group.
2. **Title** — single-line text field.
3. **Paid by** — defaults to current user. Tap to open picker:
   - List of active members (selectable as payer).
   - Tap "Someone outside the group" to enter a free-text name (sets `payer_outside_name`).
4. **Participants** — chip list. Default = last-used participants (smart default). Tap a member chip to toggle. "+ Add" allows:
   - Add an existing member from the group.
   - Add a placeholder participant (free-text name; calls `add_placeholder_participant`).
5. **Split** — segmented control: Even · Share · % · Exact.
   - Even: no inputs.
   - Share: per-participant integer field "1," "2," "3"... (defaults all to 1, equivalent to Even).
   - Percentage: per-participant numeric field; live "Total: 99.95%" indicator with a "Distribute remainder" button that fills the leftover into the largest existing percentage.
   - Exact: per-participant subunit amount field; live "Off by $X" delta on the right edge of the screen. **Itemized toggle** here per `03 §1.4`: when on, participants enter pre-tax/pre-tip subtotals and two extra rows appear:
     - **Tax** — single amount field (`tax_subunits`), always split proportionally to subtotals.
     - **Tip** — single amount field (`tip_subunits`) plus a small segmented toggle **Proportional · Even** (`tip_split_mode`, default Proportional) (D-21, OQ-05).
     Each participant row then shows subtotal + tax share + tip share = final share; the "Off by $X" delta is computed against `subtotal sum + tax + tip` vs. the entered amount.
6. **Date** — defaults to today. Tap to open date picker.
7. **Category & subcategory** — two pickers (subcategory list filters by selected category).
8. **Notes** — multiline text.
9. **Receipts** — gallery of attached thumbnails + "+ Add" (camera / library / PDF). Per-attachment delete via overflow.

### 5.3 Validation

Disable Save until:

- `amount > 0`
- Title 1-120 chars
- At least one participant
- Split inputs sum correctly (mode-dependent)
- Payer is set (member or outsider)

Inline error messages under each field on Save attempt.

### 5.4 Edit-specific

- Show a small banner "Editing will recompute shares for everyone." if changes affect splits.
- If a participant has been removed and they had applied settlements: show a confirmation modal "Bob has paid $15 of his share. Continue?" with Cancel / Continue. On Continue, the system applies the rules in `03 §3.2` (auto-refund if needed).

### 5.5 Drafts & receipt-first (D-24, OQ-13)

The Add-expense form is draft-backed and synced across the user's devices (`03 §3.5`).

- **Auto-save.** The first meaningful edit (any field, or an attached receipt) materializes a server-synced draft. Edits debounce (~2 s idle / on backgrounding) into `save_draft`. A subtle "Draft saved" affordance appears; no blocking spinner. Auto-save works offline.
- **Receipt-first.** The user can attach a receipt before entering any other field; the receipt binds to the draft (`receipts.draft_id`). The form can be left and resumed later from another device.
- **Resume.** When ≥ 1 draft exists for a group, the Group home Expenses tab shows a **"Drafts (N)"** strip above the list (visible only to the owner). Tapping opens a draft picker; selecting one reopens the form pre-filled. The Add-expense FAB resumes the most recent draft if one exists, with a "Start blank" option.
- **Convert.** Tapping Save validates per `§5.3` and calls `add_expense` with the draft's id; on success the draft disappears and its receipts re-point to the new expense.
- **Discard.** An explicit "Discard draft" action (overflow menu) calls `delete_draft`. Drafts are not auto-purged.
- **Privacy.** Drafts and their receipts are never visible to other members.

## 6. Settle (single expense)

Entry point: "Settle this" button on a share row.

Modal sheet with:

1. Heading "Settle '[Expense title]'."
2. Recipient avatar + name.
3. Amount field — defaults to my remaining; editable; "Max" button.
4. Payment app picker — segmented or list with the recipient's handles. If none, only "Mark paid manually" is shown.
5. "Open in [Venmo]" primary button — calls deep link, then opens the confirm sheet (`03 §5.3`).
6. "Mark paid manually" secondary — opens the confirm sheet directly (no deep link).

### 6.1 Settled tab default sort (D-10)

The Settled sub-tab on the expenses list sorts by `expense_date` desc — same as Active. This is consistent navigation; reading the tab feels like the bottom of the ledger rather than a payment history.

### 6.2 FX disclosure on cross-currency settlement

If the user is settling an expense in a currency different from `paymentCurrency`, the form shows below the amount field:

> "Paying $20.00 USD ≈ £15.30 (rate: 0.765)"

If `staleness > 7 days` (per D-07), an additional muted line beneath:

> "Rate as of 2026-05-12 — refresh when connected."

## 7. Settle a person (whole bilateral balance, multi-expense)

Entry point: Balances tab → tap a debt row.

Full-screen flow:

### Step 1 — pick expenses

- Header: "Settle with Bob in USD." (single currency if only one applies; if multiple currencies are involved, page shows "Multi-currency — payment in USD" per D-05).
- List: every active share you owe Bob, with checkboxes. Default = all selected.
- Total of selected at the bottom; FX-converted to payment currency if cross-currency.
- "Continue."

### Step 2 — confirm amount + app

- Same shape as the single-expense settle modal (`§6`).
- Amount editable down; allocator distributes per `03 §4.3`.

### Step 3 — deep link + confirm

- Identical to §6 from here.

## 8. Payment-app deep link confirmation

Triggered after a deep link attempt or after the fallback toast (per `03 §5.3`).

Modal sheet (non-dismissible by back gesture — must tap a button):

- Heading: "Settled $X with @handle?"
- Two buttons (stacked, full-width):
  - **Yes — mark paid** (primary).
  - **Not yet** (text).
- Below: small "Trouble paying? Copy handle: [@handle]" with a copy icon.

## 9. Group settings

Top bar: back, "Group settings."

Sections:

1. **About** — emoji editor, name editor, base currency editor. Beneath the base-currency editor, a muted footnote (OQ-12): "Base currency affects display totals only — your expenses stay in their original currencies." Changing it never re-denominates any expense.
2. **Members** — list with avatars; chips for `Admin`, `Placeholder`, `Left`. Tap a member to view: their joined date; "Remove from group" (active members only); "Rename" (placeholders only). Long-press a placeholder to inspect which expenses they appear on.
3. **Share group** — copyable invite link, QR code (full-screen on tap), "Rotate invite link" admin-only.
4. **Conflict reminders** (admin-only) — radio: Weekly / Every 3 days / Daily.
5. **Categories** — list with edit/delete; "Add category" / "Add subcategory."
6. **Storage** — bar showing usage (e.g., "212 MB used of 1 GB"). When over 500 MB, a banner "Approaching cap — export to free up space." admin-only.
7. **Export** — buttons CSV / JSON / PDF. Each triggers `export_group` and shows a "Downloading…" indicator, then the system share sheet with the artifact.
8. **Danger zone** — "Archive group (for me)," "Leave group" (with confirmation).

## 10. Profile & settings

Top bar: back, "Profile."

Sections:

1. **Account** — avatar, display name (editable), email (read-only), "Sign out."
2. **Base currency.**
3. **Payment apps** — list of stored handles; each editable. "+ Add payment app."
4. **Notifications** — toggle per channel from `02 §3.16`.
5. **Privacy** — "Share anonymous analytics & crash data" toggle (mirrors the onboarding consent, D-25/OQ-07; gates Firebase Analytics + Crashlytics per `07 §4`). Default ON. A muted line restates that no names, emails, amounts, or expense details are collected and that no advertising ID is ever used.
6. **Appearance** — theme: System / Light / Dark (default Dark; see `05` header and App_Overview §5).
7. **Support** — "Send feedback" (opens a sheet with subject + body + auto-attached diagnostics, attached only with per-submission consent) and "Privacy policy" / "Terms" links.
8. **Account deletion** — "Delete my account" → strong confirmation flow.

## 11. Deep-link navigation map

Push notifications and external deep links route as:

| Type | Lands on |
|---|---|
| `EXPENSE_ADDED` / `_EDITED` | Expense detail |
| `SETTLEMENT_RECEIVED` | Group home, Balances tab, scrolled to the affected pair |
| `COMMENT_ADDED` | Expense detail, scrolled to Comments section |
| `CONFLICT_REMINDER` | Group home, Conflicts tab |
| `MEMBER_JOINED` | Group home, Members section of Group settings |
| Invite URL | Join group sheet (`§12`) |

**Archived groups (D-11 + OQ-02).** The model is:

1. Pushes never fire to a member who has archived the group (`03 §14.3`). So a push tap can never land on an archived group.
2. When the user **actively opens** an archived group — via a universal link (e.g., a forwarded email) or by tapping its row in Archived groups — the group **auto-unarchives** on entry (`members.archived_at = NULL`) and pushes resume. This is the only place archive flips as a side effect of navigation.
3. **One-time tooltip.** The first time an auto-unarchive happens for a user (tracked by a local one-shot flag, e.g. `prefs.seen_auto_unarchive_tooltip`), show a dismissible tooltip on the group home: "We unarchived [group] because you opened a link to it. Re-archive any time from group settings." It shows once per user, not per group.

## 12. Join group flow

Triggered by universal link `https://sharecost.app/j/<token>` or scheme `sharecost://join?t=<token>`.

- If not signed in: route to §1.1 with `?next=join&t=<token>`. After auth, resume.
- If signed in: open a sheet:
  - Group emoji + name + member count.
  - Button "Join."
- On Join:
  - Call `join_group_by_token(token)` — returns `{ group, placeholder_candidates }`.
  - If `placeholder_candidates.length > 0`: navigate to Reconcile (§13).
  - Else: navigate to Group home.

### 12.1 Already a member

If the user is already an active member of the group, the sheet says "You're already in this group" and the button becomes "Open group." Cancel dismisses.

## 13. Reconcile past activity

Per `01 §3.2`. Full-screen flow.

### 13.1 Selection step

- Header: "Are any of these you?"
- Subheader: "Pick names that have been used for you in earlier expenses. You can pick multiple."
- List of placeholders. Each card:
  - Display name (large).
  - Chip strip showing 3-5 expense thumbnails with original amount + currency. Tap a chip to view that expense full-screen in a peek modal.
  - Tap the card body to toggle selection — selected cards get a **clear green outline** (2dp, color `#37D39A` or equivalent), unselected have no outline.
- Bottom bar: "These are me ([N])" primary (always enabled, even at N=0); "These are not me" secondary text link.

### 13.2 Confirmation modal

If N ≥ 1, on tapping primary:

- Title: "You are also: [comma-separated names]."
- Body: scrollable list. Each row:
  - Expense title.
  - Original amount + currency.
  - Italic "Listed as '[placeholder name]'" so the user can disambiguate.
- Cancel / Confirm.

On Confirm: calls `claim_placeholders(group_id, placeholder_ids[])`, navigates to Group home.

### 13.3 No-merge happy path

If N = 0 or the placeholder list was empty to begin with: skip the confirmation modal entirely; go straight to Group home.

### 13.4 Re-entry

The Reconcile screen is available again from Group settings → Members → "Find me in past entries" for users whose `placeholder_claim_completed_at IS NULL`. After first successful completion, the link disappears.

## 14. Empty & error states (cross-cutting)

| State | Visual |
|---|---|
| **No network** | Persistent thin offline banner at the top: "Offline — your changes will sync." Auto-dismisses on reconnect. |
| **Sync error** | Per-row "Pending sync" badge (small dot + "Sync failed, tap to retry"). |
| **Empty list** | Big icon + helpful text + primary CTA. |
| **Loading list** | Skeleton rows (animated shimmer). |
| **Hard error** | Full-screen error + "Reload" button + "Send feedback" link. |

## 15. Accessibility specifics

- All tap targets ≥ 44×44 dp.
- All non-decorative icons have content descriptions.
- VoiceOver / TalkBack labels for expense rows: "[Title], paid by [Payer], your share [X remaining of Y owed], [date]."
- Color is never the sole indicator of state (e.g., selected reconcile cards use the green outline AND a checkmark icon).
- Reduce-motion respected: skeleton shimmer pauses; settle confirmation sheet uses opacity-only transition.
- Dynamic type up to XXXL supported; rows wrap rather than truncate at large sizes.

## 16. Theming tokens (informative; complete set in `06 §4.2`)

- `bg.primary`, `bg.secondary`, `bg.elevated`
- `text.primary`, `text.secondary`, `text.muted`, `text.inverse`
- `accent.primary` (brand green for "settled," selection outline)
- `accent.warning` (amber for "approaching cap")
- `accent.danger` (red for errors)
- `border.default`, `border.focus`

All tokens have light + dark values; default theme is dark.

---

**Read next:** [`06-architecture-and-stack.md`](06-architecture-and-stack.md).
