# App Overview: ShareCost

## 1. App Name

ShareCost.

## 2. One-Line Description

A free shared-expense tracker that records who owes whom and hands settlement off to the user's own payment app.

## 3. Elevator Pitch

ShareCost helps groups of people who keep owing each other small amounts of money (trip companions, roommates, couples, recurring social groups) record expenses, see clear bilateral balances, and settle through whichever payment app they already use. The app is a tracking layer only: it never custodies money, never asks for a bank account, and never imposes daily caps or timers on the core flow. It exists because the dominant incumbent in this category has started gating basic entry behind a subscription, and users are actively looking for a replacement that stays free, stays fast, and respects the social texture of shared debts. The sharpest reason to choose ShareCost is that you can pay back a single expense, through your own payment app, without ever giving a third party your bank details.

## 4. Target Users

- Travel groups planning trips, vacations, or group events (strongest signal across all four competitor review pools).
- Couples and close-friend two-person ledgers with rolling balances that never reach zero.
- Roommates and household groups tracking many small recurring expenses over months.
- Recurring social groups (dinner clubs, hobby groups) whose membership shifts over time.

## 5. Core Features (Table Stakes)

- **Unlimited groups and members.** Create any number of groups with a custom name and emoji; add unlimited members per group.
- **Single-tap join.** Invite via shared link or QR code; joiners enter the group without creating an account if they prefer.
- **Account model.** Email-based with OAuth (Google, Apple, Facebook); no phone numbers; one verified email maps to one user identity.
- **Add expense.** Capture amount, payer, participants, date, category, and optional receipt attachments; built-in calculator on the amount field.
- **Split modes.** Even split, by share, by percentage, and by exact amount.
- **Outside-the-group payer or recipient.** Mark an expense as paid (or covered) by a named non-member without making them a balance-bearing member.
- **Smart defaults.** The add-expense flow remembers the last-used payer, split mode, participants, and category per user per group.
- **Edit and delete.** Any edit or deletion automatically recalculates affected balances.
- **Offline entry.** Expenses entered without connectivity sync on reconnection.
- **Multi-currency with daily FX refresh.** Multiple currencies per group with per-expense currency override and suggestions when entering abroad. Rates are fetched against USD once per day from Frankfurter (ECB-backed, no API key required) when online and cached locally; offline, the most recent cached rates are used. The app ships with a build-time baked-in rate snapshot so conversions work on first launch before any network fetch completes, and a fetch is attempted at first launch to refresh the snapshot. The FX provider is encapsulated behind a clean-architecture data-source boundary so a future swap to a different provider is a localized change.
- **Real-time bilateral balances.** Balance view always shows "X owes Y" pairs; debts are never simplified or netted across the group.
- **Per-user group archive.** Each member can archive a group from their own dashboard. Archiving hides the group from that user's main list and moves it to an Archived section; it does not affect other members and does not change the group's underlying state (balances stay live). Outstanding balances do not block archive.
- **Group admin role.** The user who creates the group is automatically admin. The admin is the recipient of operational notifications (most relevantly in v1, the unresolved-conflict reminder). If the admin leaves the group, the role auto-transfers to the longest-tenured remaining member; if no members remain, conflict reminders go silent. v1 ships with this behavior only: no opt-in, no opt-out, no manual transfer, no self-promotion, no multi-admin.
- **Share group link.** Persistent "Copy link" affordance alongside the system share sheet.
- **Push notifications.** Deep-link into the relevant screen; per-channel controls so the user can quiet specific event types.
- **Receipt attachments.** Attach one or more receipts to any expense as images (camera or library) or PDFs. Max 10 attachments per expense and max 30 MB per attachment. Attachments can be viewed, downloaded, or removed individually; saved to the device library on demand.
- **Dark mode.** Default-on dark theme.
- **Accessibility.** Full VoiceOver and TalkBack support.
- **In-app feedback.** Direct support channel from within the app.
- **No ads, no caps, no entry timers.** The free tier is the full product.

## 6. Differentiation Features (with importance ratings)

**Individual-expense settlement**  |  Importance: 10/10  |  Confidence: High

A user can pay back a single expense, not the whole balance. Two entry points: tap any expense and record a full or partial payment against it, or add a payment against a person and then tag the specific expenses it covers (the payment cannot be saved without tags). Each expense tracks, per participant, two values: the original share owed and the current remaining balance. The expense detail and list row display the remaining balance prominently (larger font) with the original amount shown beneath in smaller, muted text. Partial payments reduce the remaining balance; a full payment clears it. The running group balance is the sum of remaining balances across unpaid line items. This is the difference between a balance app and a ledger app.

Why it matters: forced full-balance settlement is the second-most-cited grievance against the dominant incumbent, and the only workable model for couples and roommates whose balances never naturally zero out.

**Settle via deep link to the user's payment app**  |  Importance: 10/10  |  Confidence: High

During onboarding, the user picks one or more preferred payment apps (Zelle, Cash App, Venmo, PayPal) and stores their handle on their profile. Other members see those preferences on the payee's profile. When paying, the user taps an expense (or a balance), picks full or partial amount, picks one of the payee's preferred apps, and ShareCost opens that app via deep link with amount and recipient pre-filled. On return, the user confirms the transfer completed and the expense is marked paid. ShareCost never custodies money and never asks for bank details.

Why it matters: the "no middleman, no KYC, no bank link" promise is the inverse of the most painful thing about the dominant competitor and is the single most concrete trust signal we offer.

**Mid-trip member addition with conflict resolution and auto-refunds**  |  Importance: 9/10  |  Confidence: High

When a member is added, the adder chooses between "add to all past expenses" or "future only." Under "all past," equal-split expenses are silently recomputed to include the new member; uneven splits (by share or by exact amount) land in a Conflicts tab where any group member can either modify the split to include the new member or dismiss them from that specific expense. If a re-split changes the share of someone who has already settled, ShareCost auto-generates a refund from the original payer to the over-settler, linked to the original expense and labeled as an auto-refund. Removing a member reverses these auto-refunds. Unresolved conflicts do not block any group action; instead, ShareCost surfaces a periodic reminder to the group admin. Default cadence is weekly; the admin can adjust it to daily or every three days. When opening a conflict to add the new member, the system does not pre-fill a suggested share; the resolver enters the new member's share manually.

Why it matters: no current app handles retroactive member adds without forcing users to redo expenses manually; this is the source of multiple "I had to rebuild the whole trip" complaints.

**Per-expense conversation threads with resolve**  |  Importance: 8/10  |  Confidence: Medium

Every expense has a comment thread visible to the participants of that expense (the payer and the people on the split). Non-participants do not see the thread or its unread indicator. Comments carry author attribution and timestamps. Threads can be marked resolved (collapsed but never deleted) and reopened. A solo note (someone clarifying a single charge) is just a single-comment thread. The expense row shows an unread indicator to participants when there are new or unresolved comments.

Why it matters: this is the workflow money discussions actually have; routing them into a separate chat app loses the reference to the expense. Attribution is what makes it more useful than a plain notes field.

**Per-expense history log**  |  Importance: 7/10  |  Confidence: Medium

Every mutation that affects an expense (creation, edit, participant change, split-mode change, settlement, refund linkage, retroactive add, conflict-tab decision, auto-refund) is appended to a per-expense history log. The log is visible as a collapsible "History" section on the expense detail screen. Entries are never edited or deleted.

Why it matters: silent-change perception is what kills trust. A verifiable audit trail forecloses "this expense disappeared" arguments and makes the retroactive-add flow legible to everyone in the group.

**Refund (full or partial) linked to an original expense**  |  Importance: 7/10  |  Confidence: Medium

Tapping "Add refund" on an existing expense opens a refund form pre-filled with the same participants, the same split, and the payer as recipient. The user can edit the participant list or switch to "split by exact amount" for asymmetric refunds (one person's dish was wrong). The refund appears as its own line with a distinct icon and a "refund for [expense name]" label; the original expense is preserved unchanged.

Why it matters: every other app forces refunds to be modeled as new negative expenses, polluting history and breaking the link back to what was refunded.

**Free, unrestricted data export**  |  Importance: 7/10  |  Confidence: High

Every group has an export action that produces CSV, JSON, and a PDF summary. Always free, no tier gating, no row limits.

Why it matters: data portability is the concrete promise against future enshittification. Users who feel safe leaving feel safe staying.

**Sub-categories with default icons and per-subcategory spending breakdown**  |  Importance: 6/10  |  Confidence: Medium

A two-level category tree (Food → Groceries / Restaurant / Snacks; Transport → Fuel / Taxi / Public; etc.) with sensible default icons. Users can add, edit, or delete categories and subcategories; deleting one reassigns its expenses to "Other." The balance tab includes a tappable breakdown card showing spending by category and subcategory across a selected date range.

Why it matters: better categorization plus a visible breakdown is the most-requested analytics feature in competitor complaint pools, and competitors that offer it gate it behind a paywall.

**Date-grouped expense view with multi-axis sort and filter**  |  Importance: 6/10  |  Confidence: Medium

The primary expense view is grouped by day with sticky date headers (the day, not the trip phase, is the unit of organization). Three top-level tabs scope the view: **Active** (the default, showing only expenses with a non-zero remaining balance), **All** (every expense, settled and active), and **Settled** (fully-paid expenses only). Within each tab, fully-settled expenses for a given day collapse into a "Settled" tray under that day's header, expandable on tap. Expenses can be logged with any past or future date with no special pre-trip or post-trip framing. Sort options: by date, amount, payer, category, participant. Filter options: by member, category, date range.

Why it matters: trips are remembered as days, and household ledgers are read by day; a flat list is consistently called out as the single most confusing thing about the current best-in-class competitor.

**Itemized restaurant split with tax handling**  |  Importance: 6/10  |  Confidence: Medium

When using "split by exact amount," the UI shows the bill total at the top, per-person amount rows below, and a reconciliation delta if they do not add up. An optional tax row, when toggled on, distributes a tax amount proportionally to each person's pre-tax subtotal.

Why it matters: this is the most common real-world unequal-split scenario; getting it tedious sends users back to spreadsheets.

**Trip overview**  |  Importance: 5/10  |  Confidence: Medium

A single screen showing group total spend, per-day totals, per-member daily spend, per-member share, and per-member resulting balance. Exportable as a single image for sharing in chat. Accessible at any time, not just at trip end.

Why it matters: the social moment of a trip ending is "let's settle up;" the app should make that one screen instead of a scroll.

**Web companion (read and light edit)**  |  Importance: 5/10  |  Confidence: Low

A logged-in web client that lets users view balances, run exports, and add expenses. Not full parity with mobile; enough that a desktop-only user is not stuck.

Why it matters: the dominant lightweight competitor removed its web version and users are vocally angry; restoring this surface is a cheap trust win.

## 7. Signature Features

- **Pay back a single expense, not the whole balance.** Tap one item, send the exact amount, mark it paid; the rest of your balance stays untouched.
- **Settle through your own payment app. No bank link, no middleman.** Pick Venmo, Zelle, Cash App, or PayPal once; every settlement opens that app pre-filled with amount and recipient.
- **Add someone to the trip without redoing the past.** Mid-trip member adds resolve equal splits automatically, surface uneven splits for one-tap review, and generate refunds when someone has already settled.

## 8. Explicitly Dropped or Merged

- **Dropped: "No automated debt reminders" as a feature.** This is a philosophy decision (we do not build a reminder surface), not a feature. Stated as a brand principle, not listed in the feature set.
- **Dropped: Reliable sync as a differentiation feature.** Sync reliability is a quality requirement for every feature in the app, not a separately specced feature; specifying it as a feature implies the alternative is also a feature.
- **Dropped: Receipt OCR with quick-split capture.** Evidence is thin (one passing reference and one comparison mention). Photo attachment is already table stakes; OCR pre-fill is a nicety the plan itself describes as not the substance. Reconsider for v2.
- **Dropped: Per-expense external URL field.** Single image-only citation; near-zero user volume requesting it. Pure bloat at v1.
- **Dropped: Color-coded payer accent on the expense list.** Pure visual polish; can ship as a v1.1 enhancement without a spec line.
- **Merged: Working "Copy group link" share sheet → into Group Management table stakes.** It is a single-button UI fix, not a differentiation feature.
- **Merged: Sticky payer / smart defaults → into Add Expense table stakes.** It is a defaults policy, not a separately testable feature.
- **Merged: "Someone outside the group" payer → into Add Expense table stakes.** It is a payer-field option, not a top-tier differentiator.
- **Merged: End-of-trip cross-check view + group total and per-day totals → Trip Overview.** Both describe the same screen; one merged feature is clearer.
- **Merged: Flexible date handling with rich sort and filter + date-grouped chronological list → single feature.** They describe the same view's behavior.
- **Merged: Sub-categories with default icons + per-subcategory spending breakdown → single feature.** The breakdown is the visible payoff of the category tree; specifying them apart invites shipping one without the other.

## 9. Gaps and Created Features

None identified for v1. One gap (recurring expenses) is noted in Section 11 as a deliberate v2 candidate.

## 10. Open Questions for the Product Owner

- **Cumulative receipt storage cap per group.** Per-attachment limit (30 MB) and per-expense count (10) are set; total group storage is not. Pick a soft cap (warn the admin) and a hard cap (block new uploads) before Supabase storage budget freezes.
- **Settled tab default sort.** Inside the Settled tab, what is the default ordering: by settled date (most recently settled first), by original expense date, or by amount? Affects whether the tab reads as a payment history or as the bottom of the expense ledger.
- **FX rate staleness signal.** If the daily fetch fails for several days running and the user is converting on a stale baked or cached rate, do we show a small "rate as of [date]" hint on converted amounts, or stay silent? Silent is simpler; the hint matters more for high-value cross-currency expenses.
- **Per-user archive: balance change behavior.** Archive is purely a personal view filter and never auto-resurfaces. If user A archives a group but their balance later changes (someone settles, a new expense tags them), does the user get a push notification, or does the archived group stay silent until A manually unarchives? Default proposal: push notifications still fire normally even when archived; the group just stays off the home list.
- **First-launch FX fetch failure handling.** If the build-time baked snapshot is several months old at install and the first-launch fetch fails, do we show the user a warning before they make their first foreign-currency expense, or silently use the stale rates? Tied to the staleness-signal question above.

## 11. Out of Scope for v1

- International payment apps (M-Pesa, UPI, SEPA, IBAN). Launch is US-focused.
- Multi-language support. English only at launch.
- Itinerary attachment or any trip-planning functionality.
- Receipt OCR (on-device or LLM fallback); revisit for v1.1.
- Receipt-specialized OCR APIs (Veryfi, Mindee).
- Per-expense external URL field.
- Color-coded payer accent on the expense list.
- iOS Live Activities, Lock Screen widgets, and Shortcuts integration.
- Android home-screen widget.
- Cosmetic monetization (themes, icon packs).
- Pre-trip / post-trip phase framing.
- Free-form tag layer above categories.
- Subscription tiers of any kind.
- Debt simplification (will never ship, at any version).
- Automated debt reminders to other users (will never ship, at any version).
- Bank account linking, KYC, or any form of money custody (will never ship, at any version).

**Deferred to a future version (not v1, but on the post-launch radar):**

- **Recurring expenses (rent, utilities, subscriptions).** The roommate segment is described in the plan as "many small recurring expenses over months," and manual re-entry every month is exactly the friction that pushes that segment back to spreadsheets. A v2 feature should let the user create an expense and mark it recurring with a configurable cadence; the recurrence date defaults to today and is editable, so a rent entry created on the 5th can recur on the 5th (or any other date the user picks) rather than being forced to a static "1st of the month." Each generated occurrence should carry the same payer, participants, amount, and split as the template, and be individually editable and pausable.
- **Opt-in admin model.** v1 ships with creator-only admin plus automatic transfer to the longest-tenured remaining member if the creator leaves. v2 should add manual transfer (any admin can promote any member), multi-admin groups, step-down (provided at least one other admin remains), and member self-promotion via a confirmation-gated toggle. The reason to defer: the only operational role admin plays in v1 is receiving conflict reminders, and it is unclear whether users will voluntarily take on the admin label for a balance tracker (there is no obvious upside to claiming the role). Ship the auto-managed model first; add manual controls only if real usage shows the auto-transfer rule producing bad outcomes (e.g., the wrong person is silently appointed and has no way to hand the role off).
