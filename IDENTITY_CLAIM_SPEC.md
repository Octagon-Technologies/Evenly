# Spec — "Is this you?" identity claim

Status: **ready to build.** Every open question is answered; §8 records the decisions.
Branch target: `feat/identity-claim`
Owner decisions locked in this doc: card = A1+A3 hybrid (always expanded), settings = B1, undo = 5-second
window, first-claim-wins guard included, `users.created_by` added, renames do not re-open the question,
itemized claims reassign with the rest.

---

## 1. Goal

A group logs expenses against people by name before those people have the app. When they join, the
group ends up with **two of the same person**: a name carrying real money, and a fresh account
carrying none. Today the only way to fix that is the join sheet (a one-shot moment most people skip)
or a `Reconcile` row buried in Group settings under a label nobody parses.

**The goal:** anyone who joins a group with unclaimed names is asked, in the place they actually
look, whether one of those names is them. The question keeps asking until they answer it, gets
shorter every time they answer part of it, and disappears for good once they're through.

Success looks like: a group that has *ever* used placeholder names converges on one row per real
person, without anyone having to know the word "reconcile".

**Non-goal:** deduplicating *other* people. See §6.

---

## 2. Decisions made, and why

### 2.1 The card is always expanded (A1 + A3 as one component)

Not a collapsed pill. A pill is opt-in, and the whole failure mode we're fixing is that people
don't go looking. The card is open, readable at a glance, and answerable without navigating.

It has two shapes driven by one input:

| Condition | Shape | Heading |
| --- | --- | --- |
| Exactly one unclaimed name, and it plausibly matches my name | **Named** | "Are you Chelimo?" |
| Anything else (no match, or several names) | **List** | "Were you here before you joined?" |

Both shapes carry **evidence** — expense count and amount per name, and for the named shape a
scrollable strip of the actual expenses. "Are you Chelimo?" is unanswerable from a name alone;
"Chelimo bought the airport taxi" is answerable in a second.

Name matching only ever changes *copy and ordering*. It **never gates whether the card appears.**
The motivating case (added as "Chelimo", signed up as "Andrew") produces no match at all, and is
precisely the case that must not be missed.

### 2.2 Group settings: the row lives inside Members (B1)

The `Reconcile` settings group is deleted. In its place, a row as the **first child of the Members
card**, above the roster:

> **Claim a name as me** · *2 names here have no account*

Rationale: it reads as an action *on this list* because it sits inside the list. It hides entirely
when the group has no unclaimed names. `Reconcile` survives only as the internal route name.

The `Placeholder` roster chip is renamed **"No account"**. "Placeholder" is our word, not the user's.

### 2.3 Undo by delay, not by inverse (the important one)

The confirm sheet commits, the card disappears, and a toast sits for **5 seconds** with **Undo**.

Five, not the platform-standard three: the consequence is other people's balances, and three seconds
is not enough to read a toast, register that it was the wrong name, and reach for the button.

Under the hood the merge is **not written during those 5 seconds.** The commit is scheduled; Undo
cancels it. The alternative — write immediately and build an un-claim — is materially harder and
riskier:

- After `shares.user_id` is rewritten from the placeholder to me, nothing in the row records where
  it came from. Reversing correctly needs a journal of every share id, expense id, and prior payer.
- The merge bumps `split_version` and marks expenses dirty. A sync between the merge and the undo
  pushes the merged state to everyone; the inverse then has to fight `merge_expense` to walk it back.
- A half-reversed merge is silently wrong money across several people's balances.

Deferring means there is no partial state to repair. The cost is a 5-second window in which the
database still shows the old numbers, which is acceptable because the user is looking at a toast
that says what just happened.

**Commit is flushed early** on any of: the 5 seconds elapse, the user leaves the group screen, or
the app is backgrounded. Undo is only offered while the commit is still pending. If the process dies
inside the window, nothing was written and the card returns on next launch. Losing an un-made merge
is safe; a half-made one is not.

---

## 3. What we are building

### 3.1 The card — `IdentityClaimCard`

**Where:** `ui/screen/group/IdentityClaimCard.kt` (new file). Rendered by `GroupExpensesTab`
immediately below the `Active / All / Settled` segmented row and the filter chips, above the first
day header. Shown on every sub-tab and regardless of filter state.

**Priority:** if the identity card is visible, the **Drafts pill is suppressed** for that render.
That strip already stacks the offline banner, filter chips, and the unresolved-bills section; a
fifth interstitial buries the feed. One prompt at a time, identity first.

**Named shape**

```
[avatar]  Are you Chelimo?
          Someone added this name before you joined. It has 3 expenses and no account.
          [ Dinner at La Negra $24.00 ] [ Airport taxi $14.50 ] [ Cenote trip $18.00 ]
          [ That's me ]  [ Not me ]  [ Later ]
```

**List shape**

```
Were you here before you joined?
These names have expenses but no account. If one is you, claim it so the history lands on
your balance.

 [AC]  Chelimo      3 expenses · $56.50     [ That's me ] [ No ]
 [A]   Andrew (2)   2 expenses · $27.40     [ That's me ] [ No ]
 [TR]  Tyler R.     1 expense  · $31.00     [ That's me ] [ No ]

 None of these are me
```

**Copy constraints:** no em dashes (hook-enforced). The words *placeholder*, *reconcile*, *claim a
past member*, and *merge* do not appear in user-facing strings. "Claim" survives only in the
settings row label, where it has an object ("Claim a name as me").

**Cap:** at most **3** names inline. Beyond that, show the top 3 by expense count plus a
`See all N` row that opens the full-screen list (§3.2).

**Three answers, never two.** A two-button card forces the unsure to lie, and most of them will
press dismiss, which is the permanent one.

| Button | Effect | Storage |
| --- | --- | --- |
| **That's me** | Opens the confirm sheet (§3.3) | Nothing until confirmed |
| **Not me** / **No** | That name leaves my list forever | Server, one row for that name |
| **None of these are me** | Every currently-listed name leaves my list forever | Server, one row per listed name |
| **Later** | Card returns next visit | Device only, nothing decided |

After a "No" the card re-renders in place with the remaining names, and switches from list shape to
named shape if exactly one is left. When the last name is answered the card is replaced by a single
confirmation note for the rest of that session, then never renders again in that group.

### 3.2 The full-screen list — the existing `ReconcileScreen`, revised

Kept, retitled, and reachable from settings and from `See all N`.

- Title "Claim a name" (was "Reconcile"), heading "Are any of these you?" retained.
- Rows gain the **"No"** action and a **"None of these are me"** footer button, matching the card.
- The list is always *unclaimed names in this group* minus *names I've already answered*. The
  narrowing is not a separate feature; it's the same filter the card uses.
- Its existing multi-select "These are me (n)" is retained for the genuine multi-name claim.
- Empty state: "Nothing left to claim. You've been through every name in this group."

### 3.3 The confirm sheet — `ReconcileConfirmModal`, revised

The card never merges directly. Every "That's me" lands here, and it must show the money:

```
Move Chelimo's history to you?
Chelimo stops being a separate person in this group. Everyone's balances change.

  YOU'LL TAKE ON
  Dinner at La Negra            $24.00
  Airport taxi                  $14.50
  Cenote trip                   $18.00
  Added to what you owe         $56.50

  [ Yes, that's me ]   [ Cancel ]
```

If the placeholder also **paid** for anything, a second block: "You'll be recorded as having paid"
with those expenses. If it has no expenses at all, the sheet says so plainly rather than showing an
empty box.

### 3.4 The undo toast

Appears on confirm, **5 seconds**, `Chelimo is now you` + `Undo`. Standard app toast styling, above
the FAB. Dismissing the toast by swipe does **not** cancel the merge; only `Undo` does. The 5 seconds
are a single constant shared by the toast and the deferred commit, so the two can never disagree.

### 3.5 Group settings

- Delete the `Reconcile` `SettingsGroup`.
- Add a first row inside the `Members · n` card: **"Claim a name as me"**, subtitle
  `n names here have no account`, opening the list screen. Hidden when `n == 0`.
- Rename the roster chip `Placeholder` → **"No account"** (`ChipVariant.Amber` unchanged).

---

## 4. Data

Three server-side changes, all in one migration, all applied **before any client code** (non-negotiable
#4: the full-row upsert sends every field, so a column the server lacks breaks sync for that whole
table).

### 4.1 New table: the "not me" answers

```sql
create table if not exists public.placeholder_claim_answers (
  id text primary key,
  group_id text not null,
  placeholder_user_id text not null,   -- the name being ruled out
  answered_by_user_id text not null,   -- the account saying "not me"
  answered_at bigint not null,
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  unique (group_id, placeholder_user_id, answered_by_user_id)
);
create index if not exists pca_group_idx on public.placeholder_claim_answers (group_id);
```

Only "not me" is stored. "That's me" is already represented by the existing merge, and "Later" is
device-local (a `SecureStorage` key, cleared on next launch).

**RLS:** readable by any active member of `group_id`; insertable only where
`answered_by_user_id = auth.uid()`; no update, no delete. **Do not add this table to the
`supabase_realtime` publication** (non-negotiable #3).

### 4.2 New column: who created a name

```sql
alter table public.users add column if not exists created_by text;   -- non-null only when is_placeholder
```

Set at placeholder-creation time to the acting user. It exists for exactly one purpose: **never ask
someone whether they are a name they typed in themselves.** The alternative was inferring it from the
payer of the earliest expense the name appears in, which is wrong whenever someone adds a person to a
bill they didn't pay for.

Existing placeholders backfill as `null`, which reads as "creator unknown" and means those names are
offered to everyone, including whoever created them. That's the pre-feature behaviour and it degrades
gracefully; no backfill script.

Mirror on `UserEntity` as `created_by`. It rides the existing `users` sync with no new wiring.

### 4.3 New RPC: first claim wins

```sql
create or replace function public.claim_placeholder(
  p_group_id text, p_placeholder_user_id text, p_claimer_user_id text, p_now bigint
) returns boolean ...
```

Sets `members.placeholder_claim_completed_at` **only where it is currently null**, and returns whether
this caller won. `security definer`, with a membership check on `p_group_id` so it can't be used to
claim into a group you don't belong to.

Two members can both claim the same name while offline; without the guard, both merges land and the
name's history is split across two accounts with no signal that anything went wrong. The loser's
client rolls back its local merge and surfaces:

> **Someone else already claimed this name.** Chelimo now belongs to Maya.

The RPC is called at **flush** time, not at confirm time, so an undone claim never touches it.

### 4.4 Sync

Room entity `PlaceholderClaimAnswerEntity` + `PlaceholderClaimAnswerDao`, wired into `SyncEngine`
exactly like the other flat tables: `selectIn(..., "group_id", groupIds)` in `pull`, and
`step { pushDirty("placeholder_claim_answers", dao.allForSync()) { it.id } }` in `push`. No custom
merge logic; the unique key makes re-insert idempotent.

### 4.5 Why this is synced and not a local flag

| | Local "card dismissed" flag | Synced per-name answer |
| --- | --- | --- |
| Reinstall / second device | Card returns, re-asks names already ruled out | Answers travel with the account |
| A new name is added later | Card is hidden, so it's never asked about | Unanswered, so the card returns for that one name |
| Group-level knowledge | None; every member re-answers forever | All members ruled it out → the name is confirmed account-less |
| Two people claim the same name | Both can | First claim wins; it vanishes from everyone's list |

The narrowing falls straight out of the model: the list is *names in this group* minus *names I've
answered*. Nobody has to "dismiss forever" — they run out of question.

### 4.6 On "they opened it and claimed nobody, so it's none of them"

Right instinct, and almost enough. "Opened and left" has at least three causes: *none of these are
me*, *I'm not sure, I'll ask Maya*, and *I mis-tapped back*. Only the first is an answer. Writing
permanent rows for four names because someone hit the back button is a decision they never made.

So: the explicit **"None of these are me"** button writes the answers; closing the sheet or
navigating away writes nothing. Same single tap, but now it's a decision rather than an inference.
That button is what makes the shortcut real.

---

## 5. Merge correctness

§5.1 to §5.3 are **pre-existing defects** in `GroupRepositoryImpl.reconcilePlaceholder`. They're rare
today only because almost nobody finds the Reconcile screen; putting the action on the group's front
page makes them load-bearing, so they are **in scope**. §5.4 is the new guard.

All of it lands in one transaction. A merge that half-applies is worse than one that doesn't run.

### 5.1 P0 — duplicate share when I'm already in the same expense

`ShareDao.reassignUserInGroup` is a blind
`UPDATE shares SET user_id = :toUserId WHERE user_id = :fromUserId`. If an expense has **both** my
share and the placeholder's (someone added me *and* "Chelimo" to the same dinner), the update
produces two active share rows with the same `(expense_id, user_id)`.

- Room's index on `(expense_id, user_id)` is **not unique** ([ShareEntity.kt:27](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/entity/ShareEntity.kt:27)), so it succeeds locally and silently.
- The server has a **partial unique index** `shares_expense_user_active_uidx` over `deleted_at is null`
  ([supabase/schema.sql:121](supabase/schema.sql:121)). The push fails, and takes the expense with it.

Result: I appear twice in the expense, and sync breaks for it.

**Fix:** the merge folds colliding shares instead of blindly reassigning. For each expense where both
users hold an active share: sum `share_owed_subunits` (and the units / percentage / exact fields
consistently with the expense's split mode) onto my row, soft-delete the placeholder's row with a
tombstone, and bump `row_version`. Settlement allocations pointing at the tombstoned share id are
re-pointed at the surviving share. Non-colliding shares reassign as they do today.

Needs unit coverage in `commonTest`: collision, no collision, collision on an itemized bill, and a
collision where the placeholder's share was partially settled.

### 5.2 P1 — settlements and allocations still point at the retired name

`reconcilePlaceholder` reassigns shares and payers but never touches `settlements`. If anyone
recorded "I paid Chelimo back in cash", that settlement keeps `to_user_id = <placeholder>` after
Chelimo becomes me, so the payment stops offsetting the debt it was made against.

**Fix:** reassign `settlements.from_user_id` / `to_user_id` within the group as part of the same
transaction, bumping `row_version`. `settlement_allocations` reference `share_id`, so they follow
5.1's re-pointing.

### 5.3 P1 — itemized bills keep pointing at the retired name

`item_claims` and `item_shares` both carry `user_id`. A placeholder that had items assigned to it on
a bill (which is the normal case now that "Who had what?" is assignment-first and the phone holder
assigns for everyone) keeps those rows after the merge, so the bill's per-person totals and the
expense's shares disagree.

**Fix:** reassign `user_id` on both tables within the group's bills, with the **same collision
handling as §5.1** — if I already had a claim on the same item, fold the units/portions onto my row
and tombstone the placeholder's rather than creating two rows for one person on one line item.

Coverage: a bill where only the placeholder claimed, and a bill where both of us claimed the same
line.

### 5.4 First claim wins

Both claimers merge locally, both push, and without a guard the name's history ends up split across
two accounts with nothing signalling that it happened.

The flush calls the `claim_placeholder` RPC (§4.3) **before** pushing the merged rows. If it returns
false, the client rolls the local merge back and shows the "someone else already claimed this name"
message. Because the merge is one transaction, the rollback is one transaction too.

Ordering matters: the RPC is the first thing the flush does, not the last. Pushing rows and then
discovering you lost means reversing rows that other clients may already have pulled.

---

## 6. What we are NOT building

- **Merging two people who are both not me.** "These two Tylers are the same person" is a
  group-level edit to other people's money and needs its own confirm, permissions story, and undo.
  Out of scope; revisit after this ships.
- **A member-detail screen** (mockup C3). No one would navigate to it.
- **Automatic merging on a name match**, ever, under any confidence.
- **Re-prompting the person who created the name.** They know they aren't Chelimo (§7.6).
- **Prompting on the Balances or Overview tabs.** One home for the question.
- **Any change to the join-sheet identity picker.** It stays as the first opportunity; this is the
  safety net for everyone who skipped it.
- **An un-claim / reverse-merge API.** §2.3 makes it unnecessary.

---

## 7. Edge cases

All resolved. Nothing here is left open.

1. **Placeholder shares an expense with me** — fold, don't duplicate. §5.1.
2. **Placeholder is party to a settlement** — reassign it. §5.2.
3. **Two members claim the same name** — first wins, loser rolls back and is told. §5.4.
4. **Placeholder has zero expenses** — still listed; evidence line reads "No expenses yet". The
   confirm sheet says nothing will move.
5. **Placeholder has expenses in several currencies** — evidence line shows the count only, no
   summed amount, since summing across currencies is meaningless.
6. **I created this name** — never prompt me about it, via the new `users.created_by` (§4.2).
   Placeholders created before the migration have `created_by = null` and are offered to everyone,
   which is exactly today's behaviour.
7. **A new name is added after I answered everything** — unanswered, so the card returns for that
   one name. This is the main reason answers are per-name rather than per-card.
8. **A name I ruled out is later renamed** — do not re-ask. The person didn't change, only the
   label, and a rename would otherwise be a way to nag someone who already answered. Answers are
   keyed by the placeholder's **id**, so a rename is invisible to this logic by construction; there
   is no code to write, only a rule not to add any.
9. **I leave and rejoin the group** — answers are keyed by (group, name, me) and survive.
10. **More than 3 unclaimed names** — card shows 3 + "See all N".
11. **Offline claim** — the merge is local and the RPC can't run, so the claim stays pending until
    connectivity returns; the guard (§5.4) runs at that point and may still roll it back. The card
    does not reappear while a claim is pending, and the "someone else claimed this" message can
    therefore arrive well after the tap. It names who won so it isn't baffling.
12. **Offline "No"** — writes the local row, pushes later. Harmless if duplicated (unique key).
13. **Group is archived** — no card.
14. **I am the only member with an account** — card still shows; a solo user with placeholder
    friends may well have been added under a different name by someone else's device. Low value but
    not harmful.
15. **The claimed name was itself already claimed by someone else mid-session** —
    `placeholder_claim_completed_at` is non-null, so it is filtered out of every list; if the card is
    already on screen it re-renders without it.
16. **Undo pressed after the flush** (backgrounded at 4.9s) — Undo is hidden the moment the commit
    flushes, so the button cannot be pressed against a written merge.
17. **App killed inside the 5s window** — nothing was written; card returns.
18. **Claiming a name that is in an unresolved bill** — `item_claims` and `item_shares` reassign with
    the same collision folding as shares. §5.3.
19. **A second claim confirmed while a first is still pending** — only one pending claim per group at
    a time; confirming another flushes the first immediately rather than queueing two.

---

## 8. Decisions on record

Kept so a later session doesn't relitigate them.

| # | Question | Decision | Consequence |
| --- | --- | --- | --- |
| 1 | First-claim-wins guard now, or later? | **Now** | `claim_placeholder` RPC + rollback path. §4.3, §5.4 |
| 2 | How do we know who created a name? | **Add `users.created_by`** | Second schema change; exact rather than inferred. §4.2 |
| 3 | Does renaming a name re-open the question? | **No** | Answers key on the placeholder id, so this is free. §7.8 |
| 4 | Reassign `item_claims` / `item_shares`? | **Yes** | Third merge fix, with collision folding. §5.3 |
| 5 | Undo window length | **5 seconds** | One shared constant for toast + deferred commit. §2.3, §3.4 |

Rejected along the way, with the reason:

- **Inferring the creator from the earliest expense's payer** — wrong whenever someone adds a person
  to a bill they didn't pay for.
- **Writing "not me" for every name when someone opens the list and leaves** — "opened and left" also
  means "I'm not sure" and "I mis-tapped back". The explicit *None of these are me* button carries
  the same single tap and is an actual decision. §4.6.
- **A reverse-merge API** — §2.3.
- **Gating the card on a name match** — misses the motivating case entirely. §2.1.

---

## 9. Build order

Each step is independently green before the next; commit per step (project rule §6).

1. **Migration** — `placeholder_claim_answers` table + indexes + RLS, `users.created_by`, and the
   `claim_placeholder` RPC. Applied to Supabase and committed to `supabase/schema.sql`. **Nothing
   else moves first.**
2. **Merge correctness** — §5.1 share folding, §5.2 settlement reassignment, §5.3 item claims, all
   inside one transaction in `reconcilePlaceholder`, with `commonTest` coverage. This is a standalone
   bug fix that stands on its own and should be committed separately even if the rest slips.
3. **Data layer** — entity, DAO, `SyncEngine` wiring, `created_by` on `UserEntity` and the
   placeholder-creation path, and an `observeUnansweredNames(groupId, userId)` query that is the
   single source for both the card and the list screen (unclaimed, minus answered, minus mine).
4. **Claim guard + deferred commit** — a `PendingClaim` holder in the repository layer with the flush
   triggers from §2.3, the RPC call at flush, and the rollback path. Not in a Composable.
5. **The card** — `IdentityClaimCard` + wiring in `GroupExpensesTab` / `GroupTabRoutes`, drafts-pill
   suppression, confirm sheet reuse, 5-second undo toast.
6. **Settings + list screen** — B1 row, chip rename, `ReconcileScreen` revisions.
7. **`ux-firsttimer`**, walked cold as Sam the invited friend, every P0/P1 cleared.

Steps 1 to 4 are a self-contained data-layer chunk and the natural boundary for a separate session.
Nothing in 5 or 6 can be built against a schema that isn't applied.

**Verification, both platforms, before each commit** (§5 of `AGENTS.md`):

```bash
cd code && ./gradlew :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64 :shared:testAndroidHostTest
```

Then run it: `code/iosApp/run-ios-sim.sh`, left running.

### Acceptance

- A member who joins a group with unclaimed names sees the card on the Expenses tab without
  navigating, with no name match required.
- "No" removes exactly one name and survives a reinstall.
- "None of these are me" empties the list and the card never renders again in that group.
- A claim shows the money before it moves, and can be undone from the toast for a full 5 seconds.
- Undo leaves the database **byte-identical** to before the confirm; nothing was ever written.
- Claiming a name that shares an expense with me produces **one** share row, correct total, and a
  clean push. Same for a shared line item on a bill.
- A name I created is never offered to me.
- Renaming a name I ruled out does not bring it back.
- Two devices claiming the same name concurrently: one wins, the other rolls back and is told who
  won. No group ends up with the history split across both.
- `grep -rn "Placeholder\|Reconcile\|reconcile" ` over user-facing Compose strings returns nothing.
