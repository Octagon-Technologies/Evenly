# Spec — claiming from the web, without the app

Status: **ready to build.** Owner decisions are locked in §2 and recorded in §10.
Branch target: `feat/web-claim`
Mockup: `design/web-claim-mockup.html` (13 mobile frames, the visual source of truth for this feature)

---

## 1. Goal

Twelve people eat dinner. One of them has Evenly. Today that person either assigns all twelve people's
items by hand from memory, or the bill gets split evenly and someone quietly overpays for everyone
else's wine.

**The goal:** everyone at that table claims what they actually ate, from a QR on the payer's phone, with
no app, no account, and no password. They leave as a real person in the group, carrying real money.

Success looks like: a 12-person itemised bill fully claimed before anyone leaves the restaurant, and one
row per real human afterwards.

**Non-goal:** a web version of Evenly. This is one bill, one job, 72 hours. Everything else stays in the
app, and §9 lists what that deliberately excludes.

---

## 2. Decisions made, and why

### 2.1 A web guest is a placeholder user. Nothing new is invented.

The app already models "a person in a group who has no account": `users.is_placeholder`, scoped by
`placeholder_group_id`, created by `created_by`, resolved later by `claim_placeholder()` and the
"Is this you?" card in `IDENTITY_CLAIM_SPEC.md`.

Web claiming produces **exactly that artifact**. A guest who claims a katsu curry from a browser is
indistinguishable, in the database, from a name the payer typed in the app. Which means:

- Balances, settlements, exports and the ledger need **zero** changes to accommodate web guests.
- When that guest installs Evenly three weeks later, the existing identity-claim flow asks
  "Are you Purity?" and merges her web history into her account. No second merge path.

This is the load-bearing decision. Everything else follows from it. If a future change makes a web guest
anything other than a placeholder user, it has broken the feature's integration with the app.

### 2.2 Identity is group-scoped and durable. Authorisation is bill-scoped and expires.

These are two different things with two different lifetimes, and conflating them is the mistake this
section exists to prevent.

| | Identity | Authorisation |
| --- | --- | --- |
| **What** | "I am Purity in this group" | "I may write to bill `7fq2`" |
| **Where** | `web_sessions` row + `localStorage` token | Signed link, checked per request |
| **Scope** | The group, forever | One expense |
| **Expires** | Never | 72 hours after the bill was scanned |

The consequence the owner asked for: a guest who claimed at Ramen night is **recognised** at Sunday
roast without typing anything, even though the Ramen night link is long dead. Her placeholder persists;
only her permission to edit that particular bill expired.

### 2.3 Recognition, not a search box

Asking a returning guest to find themselves in a list of names is worse than asking them to type, so
they will type, and we get a duplicate. Three tiers, in order:

1. **Token matches** → we know who she is. No question asked, straight to claiming.
2. **No token, group has unclaimed placeholders** → show them, each with **one or two real expenses**
   (`Airport Uber · 28 Jul · $12.40`). A name is unanswerable; a name plus what it spent money on is
   answerable in a second. Same evidence rule as `IDENTITY_CLAIM_SPEC.md §2.1`.
3. **None of them is her** → type a name, subject to §2.4.

### 2.4 Exact duplicate names are blocked, not warned

Within one group, two active people may not share a display name. If a guest types a name that exactly
matches an existing person, `Continue` stays disabled and we offer one-tap suffixes (`Purity L`,
`Purity D`, `Purity 2`).

Rationale: the whole feature depends on *pointing at people*. Two identical names make "who did you share
the plate with?" unanswerable at the moment it matters, and unanswerable forever afterwards in the
ledger. A warning would be dismissed; the block costs one tap.

The escape hatch **"Wait, I am that Purity"** is always on screen, because the likeliest cause of a
collision is that it genuinely is her and she missed the evidence list.

Comparison is case-insensitive and whitespace-normalised. It is **not** fuzzy: `Purity` blocks `purity`
but not `Purity G`. Fuzzy matching is used only for *suggesting* an existing person (§3.2), never for
blocking, because a false positive block is a dead end.

### 2.5 A claimed line is never hidden

Mary claims the chicken she shared with Jane. If the list filtered to "what's left", Jane could not find
it, could not join it, and the split would be silently wrong.

**Every line is always visible, whatever its state.** A claimed line changes what it *offers* you, never
whether it exists. This is already true of the app (`BillClaimScreen.kt:169` renders `items(state.items)`
with no filter) and must stay true on web, where the temptation to show only "what's left" is far
stronger because the screen is claim-first rather than assignment-first.

### 2.6 The affordance is a chip in the people row

The chip row is the list of people on a line. Joining a line means adding yourself to that list, so the
control is a chip in that row, not a button in the price column:

| Line state | Chips |
| --- | --- |
| Nothing claimed | `＋ I had this` (dashed blue) |
| Someone else on it, units left | their chips, then `＋ I had this` |
| Someone else on it, none left | their chips, then `＋ Add me` (solid blue tint) |
| You are on it | `You` (solid blue) |
| Partly claimed | people chips, `＋ I had this`, then `1 left` (muted, not tappable) |

Two things fall out of this that matter more than the aesthetics. The tap target becomes the whole row,
which matters when twelve people are doing this one-handed. And `＋ Add me` never has to say the word
"shared", so we sidestep a vocabulary problem entirely: the row shows Mary, you add yourself, sharing is
the inference.

**No prose in the claim list.** No "Bob had one, Steve had the other". Chips only. `1 left` is a chip,
not a sentence, and carries no affordance weight because it is not tappable.

### 2.7 An item edit is announced. Joining a claim is not.

These look inconsistent and are not. Do not "harmonise" them.

- **Editing a line** (add, reprice, requantify, remove) changes the bill total and therefore *everyone's*
  money. It applies **instantly** and is **announced**: the payer is told what changed, and can undo it.
- **Joining a claim** changes exactly two people's money, and one of them is holding a phone at the same
  table. It applies **instantly**, is attributed, and the person affected can remove you. Nothing is
  announced, because there is nobody to tell who is not already looking at the line.

The surviving asymmetry is the **announcement**, not a wait. An earlier version of this spec held an item
edit behind the payer's individual approval. That gate is gone. The risk model here is honest mistakes,
not bad faith: this is a group of friends splitting a dinner, and against an honest mistake **an undo is
worth exactly as much as an approval** while costing nothing in the overwhelming case where the edit was
fine. Approve-each taxes the 95% to catch the 5%, and the tax falls at the worst possible moment, with
twelve people standing up to leave.

Everything that made the gate safe survives: every change is **attributed**, **reversible**, and
**recorded permanently**. Only the waiting went.

**Anyone on the bill may undo**, and there is deliberately no arbitration. An undo is itself an attributed
entry in the log, and undoing an already-undone change is a no-op (first-undo-wins, conditional update,
same shape as `claim_placeholder`). The worst case is A edits, B undoes, A edits again, with every step
visible to everyone and a name against it. **The log is the tiebreak.** Do not add a rights hierarchy on
top of it.

### 2.8 The install prompt is a footnote

One line at the foot of the page: a hairline, a benefit, a link. Not a card, not a button, no icon,
nothing to dismiss. It routes to the correct store by user agent.

Rationale: the guest is mid-task and did not come here for us. A card-sized pitch competing with the
primary action reads as a paywall and is the single most likely thing to make someone abandon the bill.
Copy is benefit-first and never mentions "downloading":

> Skip typing your name on every bill. **Get Evenly →**

### 2.9 Two links, kept apart

| | Bill link | Group invite |
| --- | --- | --- |
| Scope | One expense | The group |
| Life | 72 hours | Until rotated |
| Needs an account | No | Yes |
| Surfaced as | QR on the expense, primary | `Invite them to the group on Evenly`, secondary |

The QR is primary because holding up a screen beats messaging eleven people who are standing up to leave.

### 2.10 The web computes money, and CI proves it matches Kotlin

Owner's call: hand-port `ItemizedAllocator` and `BillSplit` to TypeScript so the guest's total is instant
and survives a flaky connection.

The risk this accepts is real: two implementations of the most dangerous code in the app, able to drift
apart silently. The mitigation is mandatory, not optional. See §7.

---

## 3. What we are building

All screens are drawn in `design/web-claim-mockup.html`. This section is the behaviour behind them.

### 3.1 Landing — `/b/:token`

Resolve the token, then branch:

| Condition | Screen |
| --- | --- |
| Valid token, session cookie matches a `web_sessions` row for this group | **Welcome back** (frame 3) |
| Valid token, no session, group has unclaimed placeholders | **Pick your name** with evidence (frame 1) |
| Valid token, no session, no placeholders | **Name entry** (frame 2) |
| Expired | **Expired** (frame 9), read-only |
| Revoked / bill deleted / group deleted | **Link no longer works**, no bill data |

The header always shows group name, venue, item count, bill total and claim progress **before** asking
for anything. The page has to prove it is real before it asks for a name.

### 3.2 Name entry

- Fuzzy match (normalised Levenshtein ≤ 2, or one string containing the other) against existing
  placeholders **suggests** with evidence: "There's already a Purity here. She claimed the katsu curry at
  Ramen night. Did you mean her?"
- Exact match **blocks** (§2.4).
- The name is trimmed, whitespace-collapsed, and capped at 40 characters.

### 3.3 Claim list — the main screen

Per §2.5 and §2.6. Sticky footer carries the running total and `I'm done`. Progress bar shows claimed
amount against bill total and people-claimed against participants.

`I'm done` writes `bill_participants.done_at`. It is a nudge-silencer, not a lock: the guest can come
back and change anything until the link expires.

### 3.4 The share sheet

Opened from any line. Everyone on the bill is a tappable chip; `＋ Someone else` is last, smallest, and
subject to §2.4. Shows the resulting per-person amount live ("Split 2 ways · $7.00 each") before commit.

### 3.5 Joining a claimed line

A dedicated sheet, because it changes someone else's money. It states the consequence before the commit:

> Mary was paying $18.00, now $9.00. You'll pay $9.00.
> Mary will see that you joined, and can take herself off if you've got it wrong.

Backed by `join_item_portion` (§5.3).

### 3.6 Adding or editing a line

Guest may add a line, and edit label / quantity / unit price on any line. Every such write **applies**
(§5.2), is attributed, shows its effect on the bill total before commit, and **is announced** to the
payer as an undoable change. It never waits on anyone.

### 3.7 Done — the total

Itemised: each claimed line with its sharing noted, then tax ("share of what you ate") and tip ("split
evenly"), then the total. `$4.50 juice, $34.20 total` reads as a bug unless the extras are broken out.

Then the payer's single preferred payment handle with a copy button, `Change what I claimed`, and the
install footnote.

### 3.8 Payer role

A guest may be named the payer of an existing bill. They may **never** create an expense, upload a
receipt, or trigger OCR. Writing the payer advances `expenses.split_version` (§5.5).

### 3.9 In the app — three screens for the payer

1. **What changed.** One card per applied guest edit: who, what, and for an edit the before → after. Each
   carries **Undo**, which restores exactly what was there. Opening the screen marks the changes seen, so
   the banner clears; the list stays afterwards as the bill's permanent history.
2. **Who's still to claim.** Polled, app-only. Per-person state (`hasn't opened the link` / `opened it,
   claimed nothing` / claimed). Unclaimed remainder with two resolutions: split between the outstanding
   people, or split evenly across everyone.
3. **Share.** QR primary, share-link secondary, group invite tertiary, plus the scope reassurance:
   "This link opens this bill only. It can't see your balances, your other expenses, or anyone's history."

---

## 4. Security model

This is the part that can go badly wrong. RLS is currently `for all to authenticated using (true)`
(`supabase/AGENTS.md`), which is already a P0. Attaching a public URL to it without care turns a known
internal risk into a public breach.

### 4.1 The anon role never touches PostgREST

**Every** guest read and write goes through one edge function, `web-claim`, which holds the service key
and does its own authorisation. The `anon` role gets no new grants and no new policies. The web bundle
ships **no Supabase client and no anon key.**

If a future change has the browser talking to PostgREST directly, this feature has become a data breach.

### 4.2 The token

`/b/:token` where token is a 128-bit random value, base62. Stored **hashed** (SHA-256) in
`web_bill_links`; the plaintext exists only in the URL and the QR.

Checked on every request: exists, not revoked, not expired, bill not deleted, group not deleted.

### 4.3 What a valid token authorises

Exactly this, and the edge function enforces it per-endpoint rather than trusting a claim in the request:

- **Read**: that expense, its items, its claims/shares, its participants' *display names only*, the
  payer's *one* preferred payment handle, and the group's *name*.
- **Read for identity**: unclaimed placeholders in the group with, per placeholder, at most 2 expenses
  as `{title, date, amount}`.
- **Write**: item claims and shares on that expense; that expense's line items and their log rows, only
  through the two `security definer` RPCs in §5.8, never as a direct table write; the payer field of that
  expense; new placeholder users in that group; `bill_participants` rows for that expense.

Everything else is denied. In particular a token grants **no** access to: group balances, the group's
other expenses, member email addresses, receipt images, settlements, comments, or any other group.

### 4.4 A web guest may never act as an account holder

The identity list shows **unclaimed placeholders only**. Real accounts are never selectable.

Otherwise anyone with the link could tap "I'm Andrew" and spend Andrew's money. If you have the app, use
the app: the name-entry screen says "Have Evenly? Open the bill there instead."

### 4.5 Rate limits and abuse

Per-token, enforced in the edge function: 120 writes/min, 40 new placeholders per bill, 200 items per
bill. Exceeding a limit returns the expired-style screen rather than an error, and flags the bill for the
payer.

A leaked link costs one bill's item list for 72 hours, and the payer can revoke it in one tap. That
bounded blast radius is the entire reason for §2.2.

### 4.6 Privacy of the evidence rows

The evidence strip leaks that "a person called Purity spent $12.40 on an Airport Uber on 28 Jul" to
anyone holding a bill link. That is the minimum needed to make recognition work, and it is the same
information the group's own members already see. Balances are never included. Capped at 2 expenses.

---

## 5. Data

### 5.1 New table — `web_sessions`

Binds a browser to a placeholder user, group-scoped and durable (§2.2).

```sql
create table if not exists public.web_sessions (
  id text primary key,
  group_id text not null,
  user_id text not null,               -- the placeholder this browser is
  token_hash text not null unique,     -- SHA-256 of the cookie value
  created_at bigint not null,
  last_seen_at bigint not null,
  revoked_at bigint
);
create index if not exists web_sessions_group_idx on public.web_sessions (group_id);
```

Not synced to Room; the app never reads it. `anon` and `authenticated` get **no** grants — only the
service key inside `web-claim` touches it.

### 5.2 New table — `pending_item_edits`

The permanent, attributed log of every guest edit to the menu (§2.7). The name predates the decision to
drop the approval gate and is kept because renaming a synced table costs more than it explains.

```sql
create table if not exists public.pending_item_edits (
  id text primary key,
  expense_id text not null,
  group_id text not null,
  item_id text,                        -- null for an ADD
  kind text not null,                  -- ADD | RELABEL | REPRICE | REQUANTITY | REMOVE
  proposed_label text,
  proposed_quantity integer,
  proposed_unit_price_subunits bigint,
  previous_label text,                 -- captured at proposal time, for before → after
  previous_quantity integer,
  previous_unit_price_subunits bigint,
  proposed_by text not null,
  proposed_at bigint not null,
  decided_at bigint,
  decided_by text,
  decision text,                       -- APPLIED | UNDONE
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1
);
create index if not exists pending_item_edits_expense_idx on public.pending_item_edits (expense_id);
```

Synced (the app reads it and can undo from it). **Server migration lands before the Room entity** per
`AGENTS.md §4.4`. A row is written **already `APPLIED`**, in the same transaction that changes
`expense_items`, with `decided_by` = the guest and `decided_at` = the moment it landed. An undo restores
`previous_*` on the item and re-stamps the row `UNDONE`. `item_id` is **always** filled in, including for
an `ADD` — the created line's id is written back, or the undo has nothing to target. Rows are kept as an
audit trail, never deleted.

### 5.3 New RPC — `join_item_portion`

**This is the one that cannot be done client-side.** Mary claimed the chicken solo, so there is a row in
`item_claims` owned by Mary. `item_claims` is documented as "partitioned by user, each device writes only
its own, so concurrent claiming is conflict-free". Jane joining means Mary's solo claim must become a
two-person portion, and Jane may not write Mary's row without destroying that invariant.

```
join_item_portion(p_item_id, p_joiner_user_id, p_portion_id, p_now)
  → { ok, portion_id, members[] }
```

`security definer`, with an explicit check that the joiner is a participant of that bill. In one
transaction:

1. If `p_portion_id` is given, add the joiner to that portion (`item_shares`).
2. Else if the target has a solo `item_claims` row, soft-delete it and create an `item_shares` portion
   containing both the original claimer and the joiner, `quantity = 1`, `added_by = joiner`.
3. Else create a new portion containing just the joiner.
4. Reject if it would push `totalAssigned` past the line quantity, unless the caller passes an explicit
   over-claim acknowledgement.

No client ever writes another client's row; the server does. Precedent: `claim_placeholder`.

**The app needs this too.** The same gesture exists in `BillClaimScreen`, and it has the same invariant
problem today. Porting it is in scope.

### 5.4 New RPC — `claim_web_placeholder`

First-claim-wins for "That's me" on the evidence list, mirroring `claim_placeholder`. Two browsers must
not both become Purity.

```
claim_web_placeholder(p_group_id, p_placeholder_user_id, p_session_id, p_now)
  → { won, winner_is_me }
```

Stamps a claim on the placeholder only where currently unclaimed by a live session.

### 5.5 Payer, and `split_version`

`expenses.payer_user_id` sits in **Zone 2** of the merge model, guarded by the causal `split_version`
(`supabase/AGENTS.md`). A guest setting the payer must go through `merge_expense` semantics with the
`split_version` they read, not a blind update. Stale base loses and the guest is told the payer changed.

### 5.6 The doorbell

Guest writes must bump `group_activity` so the app pulls. The existing statement-level triggers fire on
service-key writes, so this works with no change — but it must be **verified**, because a silently
missing bump means the payer's phone never learns the bill was claimed.

**Never** add any of these tables to the `supabase_realtime` publication (`AGENTS.md §4.3`).

### 5.7 New columns

`web_bill_links` (token, expense, expiry, revocation, creator) is new. On `expenses`, nothing new.
On `users`, nothing new: a web guest is `is_placeholder = true`, `placeholder_group_id = <group>`,
`created_by = <the guest's own session's user id>` once created.

Note on `created_by`: `IDENTITY_CLAIM_SPEC.md` uses it to never ask someone whether they are a name they
created themselves. A self-created web placeholder is exactly that case, so setting it correctly here
keeps the later identity-claim card honest.

### 5.8 The Zone-2 write path for a guest

A guest changing Zone 2 is the one thing the edge function cannot express as a table write. It holds a
service key and has **no `auth.uid()`**, so it cannot go through `merge_expense` (which runs as the
authenticated caller) and must not update `expenses` blind: skipping the `split_version` bump means the
payer's next push carries a stale base and **silently reverts the guest's change**, which is precisely
the failure the causal model exists to prevent.

Three `security definer` RPCs, all service-role only (explicit `revoke ... from anon, authenticated` —
Supabase auto-grants `EXECUTE` at creation time regardless of `revoke ... from public`):

```
apply_web_bill_edit(expense_id, group_id, kind, item_id, label, quantity, unit_price_subunits, actor, now)
  → { ok, edit_id, item_id, amount_subunits, split_version }
undo_web_bill_edit(edit_id, actor, now)
  → { ok, changed, item_id, amount_subunits, split_version }
set_web_bill_payer(expense_id, user_id, base_split_version, actor, now)
  → { ok, stale, payer_user_id, payer_name, split_version }
```

The first two change `expense_items` and re-derive `expenses.amount_subunits`, always advancing
`split_version`. `set_web_bill_payer` implements the **same causal rule as `merge_expense`**: base matches
⇒ apply and advance; base stale ⇒ refuse and return who the payer now is (E25). Calling `merge_expense`
itself is the alternative and means constructing a whole expense payload for a one-field change; the
dedicated RPC is preferred, written against the same rule.

---

## 6. Sync and staleness

- **Web polls; web never subscribes.** The realtime doorbell is membership-RLS-scoped and an anon browser
  cannot subscribe to it. Poll the claim list every 5s while the tab is visible, immediately on focus,
  and never while hidden.
- **The app polls too, on the payer's "who's still to claim" screen only** (§3.9.2). Everywhere else the
  existing doorbell is enough.
- **No offline support on web.** A failed write shows a retry, not an optimistic success. Pretending a
  claim landed when it did not is worse than a spinner, because the guest walks away believing they are
  done.

---

## 7. Money correctness

The TS port of `ItemizedAllocator` + `BillSplit` is authoritative for what the guest *sees* and never for
what the ledger *records*. If they disagree, Kotlin wins and web is stale by one poll.

**The CI gate is mandatory.** Extract the cases in `ItemizedAllocatorTest.kt` and `BillSplitTest.kt` into
a shared `test-vectors/bill-split.json`, and run it against both implementations in CI. Divergence must
be a red build. Without this, §2.10 is an unbounded correctness risk rather than a managed one.

Cases the vectors must cover, because they are where a hand-port goes wrong: penny remainders on odd
splits, `EVEN` vs `PROPORTIONAL` tip, gratuity and discount, over-assignment (`OVERCLAIMED`), partial
assignment (`UNCLAIMED`), multi-portion lines, and extras riding on the whole bill's subtotal rather than
only the claimed part.

---

## 8. Edge cases

### Identity

| # | Case | Behaviour |
| --- | --- | --- |
| E1 | Purity exists from an earlier Uber, opens the restaurant link | Evidence list, taps her name, keeps her history (§2.3) |
| E2 | A genuinely different Purity arrives | Exact-match block, one-tap suffix (§2.4) |
| E3 | Two browsers tap "That's me" on the same placeholder | `claim_web_placeholder`, first wins; loser sees "Someone already claimed that name" and re-picks |
| E4 | Guest taps the wrong name | `Not Purity? Use a different name` is on every screen; releases the session and returns to the list |
| E5 | **One phone passed around the table** | The most likely real failure. The session is offered, never assumed: the Welcome-back screen leads with `Claim my items` but keeps `Not Purity?` at equal prominence |
| E6 | Guest clears storage mid-bill | Falls to the evidence list; her own claims on *this* bill appear as evidence, so she finds herself |
| E7 | Guest opens the link on a second device | Two sessions, same placeholder. Allowed; both write as her |
| E8 | An app member opens the web link | Not offered any identity. "Have Evenly? Open the bill there instead." (§4.4) |
| E9 | Guest installs the app later | Existing "Is this you?" card merges her in. No new path (§2.1) |
| E10 | Payer claims the placeholder in-app while the guest is mid-claim | `claim_placeholder` already stamps it; the guest's next poll shows her name is now an account and her session becomes read-only |

### Claiming

| # | Case | Behaviour |
| --- | --- | --- |
| E11 | Jane joins Mary's solo chicken | `join_item_portion` (§5.3), consequence shown first (§3.5) |
| E12 | Mary disagrees | She removes herself from the portion; the schema already gives the member final say |
| E13 | Bob claims one chicken, Steve the other | Two individual claims, `RESOLVED`, $18 each. **Not** sharing. Verified against `BillSplit.kt:186` |
| E14 | Two guests claim the last unit at once | Server-side check in `join_item_portion`; loser is told it went and offered `＋ Add me` instead |
| E15 | Deliberate over-claim (3 claimed of 2) | Allowed, surfaced as `OVERCLAIMED` on the line and to the payer. Never silently capped |
| E16 | Payer deletes an item a guest claimed | Guest's next poll drops the line and adjusts the total, with a one-line note saying what went |
| E17 | Three people never claim | Payer's screen resolves the remainder: split between them, or evenly across everyone (§3.9.2) |
| E18 | Guest claims, then the payer reprices the line | Claim survives, amount changes, guest's total updates on poll |

### Editing

| # | Case | Behaviour |
| --- | --- | --- |
| E19 | **Guest's total includes her edit** | Because it *applied*, not because it is provisional. There is no `waiting for Andrew` state and no line marked as such. If someone undoes it her next poll shows the line as it was, and the change stays in the bill's history with her name on it. She must never see a number she cannot account for |
| E20 | Two guests edit the same line | Last write wins on the item; both edits are logged, attributed, and separately undoable |
| E21 | Link expires with edits already made | The edits and the log survive. Expiry ends writes, not history |
| E22 | An added line is undone after other people claimed it | The line is soft-deleted and the claims on it go with it, with a note to each claimer. Undoing a `REMOVE` revives only the claims that removal killed, matched on its exact `deleted_at` stamp, so a claim someone dropped themselves at the same moment is not resurrected |
| E23 | Guest deletes a scanned line | It goes immediately, claims and all, and is announced as an undoable change like any other edit |

### Payer, link, lifecycle

| # | Case | Behaviour |
| --- | --- | --- |
| E24 | Guest names themselves payer | Allowed. Advances `split_version` via merge semantics (§5.5) |
| E25 | Two guests both claim to be payer | Causal `split_version`: second read is stale, loses, and is told who the payer now is |
| E26 | Link expires mid-session | Next write returns expired; the page becomes the read-only Expired screen with the total and the payment handle intact |
| E27 | Payer extends or reopens | New expiry on the same token, no new QR |
| E28 | Payer revokes | Token dead immediately; page shows "Link no longer works" with **no** bill data |
| E29 | Bill or group deleted | Same as revoked. Soft-delete only, per `AGENTS.md §4.5` |
| E30 | Link shared publicly | Bounded by §4.3 and §4.5. Payer can revoke |
| E31 | Guest hits `I'm done`, then wants to change something | `Change what I claimed` returns them to the list. `done_at` is a nudge-silencer, not a lock |

---

## 9. What we are NOT building

Naming these prevents them being smuggled in as "obvious".

- **A web app.** No group view, no balances, no expense list, no settle-up flow, no history.
- **Expense creation from web.** No receipt upload, no OCR, no new bills. Uploading arbitrary images to an
  unauthenticated endpoint is a different security problem, and OCR is a per-call cost attached to a
  public URL.
- **Web accounts, passwords, OTP or email.** The token is the whole session model.
- **Non-itemised expenses.** An Uber split evenly needs no one's input. If they want to watch it, that is
  what the app is for.
- **Offline web.** §6.
- **Editing anything outside the one bill.** Not the group name, not other people's profiles, not
  settlements.
- **Desktop-first anything.** Mobile is the product surface. Wider viewports get a centred column, not a
  different design.

---

## 10. Decisions record

| Decision | Choice | §|
| --- | --- | --- |
| What a web guest *is* | A placeholder user | 2.1 |
| Link scope | One bill, 72 hours, revocable | 2.2 |
| Identity persistence | Browser token → placeholder, group-scoped, durable | 2.2 |
| Returning guest | Recognised, never asked to search | 2.3 |
| Duplicate names | Exact match blocked, suffix offered | 2.4 |
| Claimed lines | Never hidden | 2.5 |
| Claim affordance | A chip in the people row | 2.6 |
| Item editing | Instant, announced to the payer, undoable by anyone on the bill | 2.7 |
| Joining a claim | Instant, attributed, reversible by the affected person | 2.7 |
| Install prompt | One-line footnote, never a card or button | 2.8 |
| Distribution | QR on the expense; group invite kept separate | 2.9 |
| Money math | Hand-ported to TS, CI-gated against Kotlin vectors | 2.10, 7 |
| Payer | A guest may be payer; may never create a bill | 3.8 |
| Settle-up | Payer's one preferred handle, payer-toggleable | 3.7 |
| Stack | Svelte, static, one edge function | 11 |

---

## 11. Build order

Each step ends green and useful on its own.

1. **Schema + RPCs.** `web_bill_links`, `web_sessions`, `pending_item_edits`, `join_item_portion`,
   `claim_web_placeholder`. Verify the `group_activity` bump fires on service-key writes (§5.6).
2. **`join_item_portion` in the app.** It fixes a live invariant violation in `BillClaimScreen`
   independently of any web work, so it ships first and alone.
3. **The `web-claim` edge function.** Token resolution, the §4.3 authorisation matrix, rate limits.
   Tested against the matrix before a single line of UI exists.
4. **The TS allocator + `test-vectors/bill-split.json` + the CI gate.** Before any UI consumes a number.
5. **The Svelte surface**, reusing `design/src/design.css` tokens. Frames 1 to 9.
6. **The app's three payer screens.** Review changes, who's still to claim, share/QR.
7. **`ux-firsttimer` cold walk** as Sam and Diego, on a real phone browser, per `AGENTS.md §7`.

---

## 12. Open questions

1. ~~**Does the payer's approval gate apply to the *first* scan?**~~ **Answered: there is no gate.** The
   question was real — twelve people correcting OCR on a freshly-scanned receipt generate twelve cards for
   a bill the payer never read, and that is exactly when a payer starts approving without looking. The
   fix considered first was to hold the gate dormant until the payer confirmed the scan, and there is no
   such point to hang it on: `verified` lives only in editor UI state
   (`ItemizedBillState.showUnverifiedNotice`, `EditBillState.verified`), is derived at scan time, and is
   gone the moment the bill saves. No column, nothing in the schema. So the gate went instead (§2.7), and
   with it the question.
2. **`1 left` when the count is large.** A line with quantity 12 and 9 claimed reads `3 left`, which is
   fine. A line with quantity 1 never shows it. No known problem, but worth a look on a real receipt.
   **Still open** — judge it in the cold walk (§11 step 7), not from a fixture.
3. ~~**Currency display for a guest** whose device locale differs from the group's base currency.~~
   **Answered: the bill's own currency, never converted.** `web/src/lib/money/` has no FX and the bundle
   ships no Supabase client, so there is nothing to convert with and nothing to convert from. Written
   down in `web/AGENTS.md` so it is not "fixed" later.
