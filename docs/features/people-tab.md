# People tab: who owes you, across every group

**Status:** in progress (`feat/people-tab`)
**Owner agent/session:** Claude (People tab session, 2026-11-23)
**Mockup:** `design/brainstorm/people-tab-final.html` (locked). Rounds 1-3 (`people-tab.html`, `-v2`, `-v3`)
hold the options that were rejected and why.

Paths below are under `code/shared/src/commonMain/kotlin/app/splitevenly/` unless noted.

## 1. Behavior brief

**Problem.** The same friend sits in several of your groups. To see what they owe you, or to record that
they paid you back, you open every group one by one. When the friend has a different name in an old group
("Pookie" in Boston, "Purity" everywhere else) nothing connects the two.

**User outcome.** One People tab lists everyone who owes you, the total across every group and one-time
split, where it comes from, and lets you record one payment that is spread across those groups.

**Non-goals.**
- **"Who you owe" is out of v1.** Name links are made on one account's phone, so a debtor-side view of
  someone else's links could be a false breakdown. Debts never cancel across groups: if you owe them in
  one group, that group simply does not appear.
- No proof-of-payment attachment here. That is `feat/payment-proof` (`design/brainstorm/payment-proof-and-web-parity.html`).
- No per-expense clearing. Payments are per group today (`settlements` has no `expense_id` scope across
  groups); a payment still allocates oldest-first inside each group as `applySettlement` already does.
- No fuzzy name matching. Only an exact name (case and spacing ignored) is ever suggested.
- Links never change anything other people see. Boston still shows "Pookie".

**Platforms and systems affected.** Android, iOS, `supabase/` (one new owner-scoped table). No web change.

## 2. Acceptance criteria

**List (bottom nav: Groups, People).**
- A total card "Owed to you" shows the sum per currency (never summed across currencies).
- One row per person: name, grey group line with at most two group names, then a blue "+N more" that
  expands the row in place to every group with its amount. One or two groups: no "more", no expander.
- A person's amount in a group is that group's own balance between you and them (the same number the
  group's Balances tab shows). A group where you owe them, or are even, is left out.
- A blue dot after a name means their page has a same-name match to review.
- "All settled" (people who owed you before and now owe nothing) is a collapsed section at the bottom.

**Person page.**
- Hero "Owes you $X" with a "Record payment" button.
- A collapsible "Same name elsewhere" card lists each other group where a name typed by hand matches theirs
  exactly, with Link and No. "+ Link other names" is always under it.
- A timeline of every expense behind the number, newest first, grouped by month, each tagged with its group
  ("One-time" for a quick split). Tapping an expense opens it. No payment rows.

**Link other names (B).** Lists only names that were typed by hand, owe you money, are not linked to
anyone yet, and are not in any group this person is already in (one person can't be in a group twice).
Grouped under group headers. Tick and save.

**Record payment.** Amount field (up to what they owe in that currency). Split defaults to oldest group
first. "Change" makes every group amount editable; editing one never moves the others. A bar reads
"$X of $Y" with "$Z left", "$Z over" or "Adds up". Save stays tappable: if it doesn't add up, or a group is
set above its balance, Save shakes the bar and outlines the bad group in red, and nothing is written.
Saving writes one ordinary payment per group (each allocates oldest-first in its group).

**Adding someone to a group by name (D).** Typing shows "People you know" from your other groups whose
name contains what you typed. Picking one adds them under that name and links them to that person, so no
duplicate is created.

**States that are not the happy path.**
- Nobody owes you: empty state, no total card.
- Before the first pull lands: blank page, never a false "nobody owes you" (`HydrationTracker`).
- A person owes in two currencies: the hero and rows show each currency; Record payment asks which.
- Offline: everything is local-first; links and payments push later.

## 3. Constraints

- Links are a new synced table `person_links`, private to the owner (`owner_user_id = auth.uid()`), never
  group-scoped, never in the realtime publication. Server table lands before the Room entity.
- Soft-delete only: unlinking or "No" sets `deleted_at` / writes a `NOT_SAME` row; nothing is hard-deleted.
- Only names typed by hand (placeholders) can be linked to another person. Two real accounts are never
  linked.
- Money math stays in `domain/`; grouping people (union of links) is pure and unit-tested.

## 4. Test plan

| Criterion | Proof |
| --- | --- |
| Per-group amount equals the group balance; owe-them groups excluded | `PeopleBalancesTest` |
| Linking unions names; NOT_SAME and same-group rules hold | `PeopleBalancesTest` |
| Split validation (left / over / over a group's balance) | `PaymentSplitTest` |
| Both targets compile, unit tests | AGENTS.md §5 commands |
| Screens | iOS simulator run, light + dark; `ux-firsttimer` |

## 6. Slices

1. `person_links` server table + Room entity + DAO + sync + sign-out wipe.
2. Domain: `PeopleBalances` (grouping, per-group amounts, suggestions, pool) + `PaymentSplit`.
3. Repository + People list + person page + Link other names + Record payment.
4. "People you know" in add-by-name.

## 7. Completion report

**Verification (2026-11-23):** `compileAndroidMain`, `compileKotlinIosSimulatorArm64`, `testAndroidHostTest`,
`iosSimulatorArm64Test` (incl. `PeopleTest`), `:androidApp:assembleDebug` all green. `person_links` applied to
the live project (`wfpfgbipjmkysalfmyub`); advisors show only the generic anonymous-sign-in note every table has.
App builds and launches on the `Evenly-people-tab` simulator (signed out).

**Walked on the iOS simulator, signed in (2026-11-24):** People list, a person's page, the Record payment
sheet (not saved: it would have recorded a real payment) and the "Link other names" empty state. Fixed on the
way: an "Owe you" label above the rows; owed amounts in green (`c.settled`, the colour Home uses for "you're
owed"); a full-width total card; "+ Link other names" shown alone when there is nothing to review; no split
box when the payment covers a single group.

**Known gaps / unverified:**
- That account has one bill, so "+N more", same-name matches, linking, a multi-group split and "People you
  know" have only unit-test coverage, not a run with real data.
- "People you know" is on the Members sheet's Add new member page (`MembersSheet.kt`) only, under the name
  field as you type, not the add-expense participant sheet or the bill flow. Group settings no longer adds
  members (2026-11-24).
- A cross-group payment is N ordinary payments; undoing it means removing each one in its group.
- `DB version 35` will collide with `feat/payment-proof` if that branch also bumps; renumber at merge.
