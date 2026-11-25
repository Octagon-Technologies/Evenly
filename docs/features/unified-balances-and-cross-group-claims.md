# Unified balances (People with a group filter) + cross-group name claims

**Status:** brief agreed, not started. Decisions below are final unless marked *Open*.
**Owner agent/session:** design session 2026-11-25 (remote); implementation to run in a local session.
**Mockup:** not yet made. Required before any Compose (AGENTS.md §7): the filtered People screen, the in-group
entry point, and the "Are these you?" Home card.
**Extends / supersedes parts of:** [people-tab.md](people-tab.md) (see §9 for exactly which lines change).

> **Written against `main` at `bfabfd3` on a remote checkout.** The owner may have local, unpushed work on the
> People tab or linking. Before building, run the checklist in §8 and reconcile anything below that names a
> file, function or DB version against what is actually on disk. If local code already does part of a slice,
> keep the local code and tick the slice; do not rebuild it from this doc.

---

## 1. Behavior brief

**Problem.**
1. *Two balance screens disagree.* Inside a group, the Balances tab shows "Purity owes you $107.84". The People
   tab shows "Purity owes you $426.69". Both are right (one group vs every group), but nothing on screen says
   so, and a user reads it as a bug.
2. *A claim covers one group only.* Andrew has Purity as a typed-in name in New York, DC and New Jersey and has
   linked all three on his People tab. He invites Purity to New York; she claims "Purity". DC and NJ still hold
   an unclaimed "Purity", and Purity has no way to know or to take them. Worse, Andrew's People tab currently
   drops the link to the claimed name and splits her into two rows (§5.1).

**User outcome.** There is one place to see balances, and its numbers always say what they cover. A friend who
claims their name once is offered every other group where someone they know linked that name, and can take
each in one tap.

**Non-goals.**
- No netting across groups. A person you owe in one group and who owes you in another appears in both
  sections (decision D4).
- No change to what other members of a group see about money, except the normal result of a claim (the typed
  name becomes the real account).
- No automatic claim into a group without the claimer's Yes, except the one auto-resolve case in D10.
- No fuzzy matching of names. Links stay explicit, made by a person.
- No change to the web claim flow (`WEB_CLAIM_SPEC.md`); web guests are not offered other groups.

**Platforms and systems affected.** Android, iOS, `supabase/` (one new table + RPCs). Layers: `domain/people`,
`data/` (new entity, DAO, sync, sign-out wipe), `ui/screen/people`, `ui/screen/group`, `ui/screen/home`,
`ui/navigation`. Read `domain/AGENTS.md`, `data/AGENTS.md`, `ui/AGENTS.md`, `supabase/AGENTS.md` first.

---

## 2. Decisions (final)

Balances

| # | Decision |
|---|---|
| D1 | One balances screen: **People**, reached from the bottom nav (Groups, People). |
| D2 | People has a **group filter**: "All groups" (default) or any one group. "One-time" (quick splits) is also a filter value. Switching should be obvious and one tap away. |
| D3 | Inside a group, the tab that is "Balances" today opens **the same People screen, pre-filtered to that group**, with a visible scope chip and a "See all groups" action. Same composable, same numbers, same math. Widening to All is always something the user does on purpose. |
| D4 | **"You owe" is shown everywhere now**, not deferred. Sections: *Owe you*, *You owe*, *All settled* (collapsed). **No netting**: a person can appear in both *Owe you* and *You owe*. Totals are per currency, never summed across currencies. |
| D5 | Every total says its scope: "Owed to you" / "You owe" on All, "Owed to you in New York - Bien" when filtered. |

Cross-group claims ("offers")

| # | Decision |
|---|---|
| D6 | **Who may link a name to a person:** any **active member of the group that name sits in**. Not restricted to the group owner, and not restricted to whoever sent the invite. A link from someone who is not in that group never produces an offer. |
| D7 | **When the claimer is asked:** (a) right after they claim their first name, for every other name linked to it; and (b) any time later that someone links another name to their real account. |
| D8 | **Where:** a needs-action card at the **top of the Groups home**, one row per group. Each row has three buttons: **Yes**, **Dismiss for now**, **Not me**. |
| D9 | **Yes**: joins the claimer to that group (if not already a member) and merges the typed name's history onto their account, with the same first-claim-wins rule as today. **Dismiss for now**: hides the row until the next time they open the app; nothing is written to the server. **Not me**: removes the offer permanently. |
| D10 | **Same group, auto.** Once the claimer is a member of a group, a later link that points at a name they already confirmed there resolves automatically (no prompt). A **different** typed name in that same group still prompts them, and Yes merges it into their existing membership. |
| D11 | Any member's link prompts the claimer: they decide which names are them. Disagreement between members (one links, another says "not the same") does not block the offer. |
| D12 | **The linker sees the outcome, quietly.** Yes: the roster just shows the real account (already true after a claim). Not me: the linker's People tab shows a small "Purity said this isn't her" note and stops merging the two. |

---

## 3. Acceptance criteria

### 3.1 People screen (D1 to D5)

- Given the filter is **All groups**, then *Owe you* lists everyone with a positive balance toward you in any group or one-time split, and *You owe* lists everyone you owe in any group or one-time split. Each row shows the person's amount summed across only the groups where the balance runs that direction.
- Given Purity owes you $100 in NY and you owe her $30 in DC, then she appears in *Owe you* at $100 (NY) and in *You owe* at $30 (DC). Nothing is netted.
- Given the filter is **one group**, then both sections show only that group's balances, and each person's number equals that group's pairwise balance (the number today's group Balances tab shows).
- Given you open a group's balances tab, then the People screen opens filtered to that group with a scope chip; tapping "See all groups" switches to the People tab on All.
- Given you tap a person while filtered, then their page shows only that group's expenses and amount, still labelled with the scope. *(Default; confirm in mock.)*
- The total cards show "Owed to you" and "You owe" per currency, with the scope in the label.
- *Record payment* works in both directions: on an *Owe you* row it records them paying you (today's flow); on a *You owe* row it records you paying them. Split across groups oldest-first, editable, same validation as today.
- Group filter options list only groups (and "One-time") where you have a non-zero or recently settled balance; empty groups are not offered.

### 3.2 Offers (D6 to D12)

- Given Andrew (active member of NY, DC, NJ) linked NY "Purity", DC "Purity" and NJ "Purity", when Purity claims NY "Purity" via invite, then her Groups home shows "Are these you?" with rows for DC and NJ.
- Given Purity claimed NY last week, when Andrew creates a new group "Lisbon", adds a typed "Purity" and links it to her, then the Lisbon row appears on her card.
- Given Andrew links a name in a group he is **not** an active member of, then no offer is created. (He cannot link it anyway today: his People tab only sees his groups. The server must still enforce it.)
- Each row shows: group name, the name as typed, who linked it ("Andrew added you as Purity"), expense count, and what that name owes / is owed per currency.
- **Yes** → Purity becomes an active member of DC; DC's "Purity" history merges onto her account; DC opens populated; the row disappears. If someone else already claimed DC "Purity", she sees who did and the row clears (first claim wins, as today).
- **Dismiss for now** → row hidden for this app session; it is back the next time the app is opened. No server write.
- **Not me** → row gone for good; never offered again for that name to her, whoever links it. Andrew's People tab shows "Purity said this isn't her" against DC "Purity" and stops merging DC "Purity" into her row.
- Given Purity is already in DC and confirmed DC "Purity", when another DC member links DC "Purity" to her, then nothing is shown (D10).
- Given Purity is already in DC, when someone links DC "Pookie" to her, then the card shows a DC row for "Pookie"; Yes merges "Pookie" into her existing DC membership.
- Given the linker has left the group by the time Purity taps Yes, then the offer is void: the row disappears with a short "This invite is no longer available". *(Default; see Open O4.)*

### 3.3 Non-happy states

| State | Expected |
|---|---|
| Nobody owes anyone | Empty state; no total cards. |
| Before first pull | Blank, never a false "nobody owes you" (`HydrationTracker`, as today). |
| Two currencies | Each currency on its own line in totals, rows, and offer rows; Record payment asks which. |
| Offline | Balances local-first. Offer card shows cached offers; Yes / Not me queue and run on reconnect; Yes shows "Joining when you're back online". |
| Yes loses the race | "Sumeya already claimed this name"; row clears. |
| Offer target group deleted | Offer disappears. |
| Accept succeeds, pull fails | Group shows as pending with retry (existing join path), never "No expenses". |

---

## 4. Data and server design

### 4.1 Why a new table

`person_links` is private to its owner (RLS `owner_user_id = auth.uid()`), so Purity cannot read Andrew's links,
and her claim cannot find them. Offers need a row both the linker and the claimer can read.

### 4.2 `identity_offers` (new, synced, soft-delete)

| Column | Notes |
|---|---|
| `id` text pk | |
| `group_id` text | The group the typed name lives in. |
| `placeholder_user_id` text | The typed name (always a placeholder of `group_id`). |
| `target_user_id` text | The real account being offered the name (Purity). |
| `linker_user_id` text | Who made the link; must be an active member of `group_id` at creation. |
| `status` text | `PENDING`, `ACCEPTED`, `NOT_ME`, `VOID`. |
| `summary` jsonb | Snapshot for the card: display name, expense count, per-currency amount. Purity is not a member, so she cannot read the group's expenses; the summary is the only group data she sees before Yes. |
| `created_at`, `updated_at`, `row_version`, `deleted_at` | Standard. |

- Unique active row per `(placeholder_user_id, target_user_id)`: many linkers produce one offer. Keep the earliest linker as `linker_user_id` (shown on the card).
- RLS: select where `auth.uid()` is `target_user_id` or `linker_user_id`. No direct insert/update from clients; all writes go through the RPCs below (security definer). Delete revoked.
- **Not** in `supabase_realtime` (AGENTS.md §4.3). It has no doorbell through the group because the target is not a member. The 60s pull tick carries it, like `person_links`. *Open O2* if a faster nudge is wanted (push notification).
- Server table lands before the Room entity (AGENTS.md §4.4). New Room DB version: check the current number locally (People took 35, payment-proof 36 as of `bfabfd3`).

### 4.3 How offers are created (server, not client)

Recommended: server-side, so an offer exists the moment a claim or link lands, without waiting for the linker
to reopen the app.

1. **On link.** When a `person_links` SAME row is upserted, a trigger or the sync RPC resolves the owner's
   cluster (recursive over that owner's live SAME links, minus NOT_SAME pairs). If the cluster contains a real
   account R (or a placeholder already claimed by R), then for each placeholder P in the cluster, create an offer
   (P → R) when all of these hold:
   - P is still an unclaimed placeholder of its group G;
   - the link owner is an active member of G (D6);
   - no `NOT_ME` exists for (P, R);
   - P is not already merged into R.
2. **On claim.** After `claim_placeholder` wins for (P0 → R), find every link owner whose cluster contains P0
   and run step 1 for their clusters. This is the "claim NY, get offered DC and NJ" path.
3. **D10 auto-resolve.** If R is already an active member of G and P is a name R already confirmed in G (i.e. P
   is already merged into R), create nothing. A *different* P in G gets a normal offer.

### 4.4 RPCs

- `accept_identity_offer(offer_id)`: caller must be `target_user_id`; offer `PENDING`; `linker_user_id` still an active member of `group_id` (else set `VOID` and return void); P still an unclaimed placeholder of G. If R is not a member of G, insert an ACTIVE members row for R. Then apply the same stamp as `claim_placeholder` (first claim wins) and return `{won, winner_user_id, winner_name}`. Set `ACCEPTED` on win.
- `decline_identity_offer(offer_id)`: caller must be target; sets `NOT_ME`.
- The **money merge stays client-side**, as today. `reconcilePlaceholder` → `PlaceholderMergeDao.mergePlaceholder` runs in one transaction on the claimer's device, then pushes. After accept, the client must mark the group pending, **pull it** (the claimer had no rows for G), then run the merge, then push. That is the same order the join-with-claim path uses (`GroupRepositoryImpl` join → `reconcilePlaceholder` → hydration). Follow `claim_placeholder`'s ordering note: call the RPC at flush time, before pushing merged rows.

### 4.5 Linker side of Not me (D12)

When the linker's device pulls an offer it created with status `NOT_ME`, it writes a `NOT_SAME` `person_links`
row for (P, R) and soft-deletes the SAME link. People shows "Purity said this isn't her" on that name (P's row or
the person page "Same name elsewhere" area). Soft delete only.

---

## 5. Scout findings (remote, `bfabfd3`)

### 5.1 Claimed names split a person on People (bug, independent fix)

`domain/people/People.kt` ~line 193 to 205 builds the union-find only over `sameLinks` whose ids are in
`everyone`. The comment says a link to "a placeholder claimed since" is "ignored rather than trusted". After
Purity claims NY "Purity", Andrew's links still point at the NY placeholder id. That id is now a LEFT member whose
history moved to her real id, so the link drops. Expected result: one row for real Purity (NY) and one for
placeholder Purity (DC + NJ). **Not yet reproduced on a device.**

**Fix:** before grouping, map every placeholder with `placeholder_claimed_by = R` (`MemberEntity.placeholderClaimedBy`)
to R. Then a link to P0 is a link to R, and the "two accounts" guard still holds. Pure, in `domain/`, unit-test
it in the People tests. Ship this first; it does not depend on anything else here.

### 5.2 Existing pieces to reuse

| Need | Existing code |
|---|---|
| In-group "Are any of these you?" claim card (pattern for the offer card) | `ui/screen/group/IdentityClaimCard.kt`, `IdentityChecklist.kt`, `IdentityChecklistPage.kt`; data via `claimAnswerDao.observeUnansweredNames` in `GroupRepositoryImpl` |
| Merge a placeholder into a real member (covers the "Pookie" case once the claimer is a member) | `GroupRepositoryImpl.reconcilePlaceholder` → `PlaceholderMergeDao.mergePlaceholder` |
| Join + claim + hydrate ordering | `GroupRepositoryImpl` join path (~line 238 to 258) |
| First-claim-wins server stamp | `supabase/schema.sql` `claim_placeholder` (~line 1670) |
| Private links | `supabase/schema.sql` `person_links` (~line 125); `domain/people/People.kt` |
| People UI | `ui/screen/people/` (`PeopleListScreen`, `PersonScreen`, `RecordPaymentSheet`, `LinkNamesScreen`, `KnownPeople`) |
| Group balances tab to replace | `ui/screen/group/GroupBalancesTab.kt`, `GroupBalancesMapping.kt`; tab declared in `GroupHomeScreen.kt` ~line 128 |
| Payment split math | `domain/people/PaymentSplit.kt` |

### 5.3 Risks

- **Merge on a group the claimer just joined.** The merge runs locally, so it needs the group's rows pulled
  first. A merge on a partial pull is wrong money. Gate the merge on the group's hydration completing.
- **Offer summary leaks a little group data** to a non-member (name, count, amount for their own name). This is
  acceptable because it is about them, but it must not include other members' names or expense titles.
- **Abuse:** a member links a stranger's real account to a typed name to pull them into a group. Mitigated
  because Yes is required, and Not me is permanent. Consider rate-limiting offers per linker.
- **Record payment in the You owe direction** reuses `applySettlement` with payer and payee swapped. Check
  oldest-first allocation still holds.

---

## 6. Test plan

| Criterion | Proof |
|---|---|
| Claimed placeholder aliases to its claimer; person stays one row | `PeopleBalancesTest` (or local equivalent) |
| You owe: per-group direction, no netting, per currency | `PeopleBalancesTest` |
| Group filter: numbers equal the group's pairwise balance | `PeopleBalancesTest` vs existing group balance calc |
| Record payment, You owe direction | `PaymentSplitTest` |
| Offer creation rules (D6, D10, NOT_ME dedupe, cluster) | SQL tests against a branch DB, or a pure Kotlin mirror of the rule if creation stays client-side |
| accept/decline RPC guards (caller, linker membership, first-claim-wins) | SQL check against `supabase/schema.sql` on a branch |
| Merge after accept only after hydration | Repository unit test |
| Screens | iOS sim, light + dark; `ux-firsttimer` cold walk (Sam the invited friend) |
| Both targets | AGENTS.md §5 commands |

---

## 7. Slices (each a commit, green on both platforms)

- [ ] **0. Reconcile with local.** Run §8. Update this doc's file references if local differs.
- [ ] **1. Alias claimed placeholders in People** (§5.1). Domain + test only.
- [ ] **2. "You owe" in the domain**: PeopleBalances returns both directions, no netting, plus group-scoped variant. Tests.
- [ ] **3. Mockups** (`design/brainstorm/`): People with filter + both sections + scope labels; in-group entry; offer card on Groups home. Owner signs off.
- [ ] **4. People screen**: filter, *You owe* section, scope labels, Record payment both directions.
- [ ] **5. Group tab → scoped People**: replace `GroupBalancesTab` content, scope chip, "See all groups".
- [ ] **6. Server**: `identity_offers` table + RLS + creation on link/claim + `accept_identity_offer` / `decline_identity_offer`. Apply to a branch first.
- [ ] **7. Client data**: Room entity, DAO, sync, sign-out wipe; linker-side NOT_ME → NOT_SAME link.
- [ ] **8. Offer card UI** on Groups home + accept flow (RPC → pull → merge → push) + Dismiss for now (session-local) + "isn't her" note on linker's People.
- [ ] **9.** Update `people-tab.md` per §9, `docs/features/README.md` status, and any `AGENTS.md` convention touched.

---

## 8. Porting checklist for the local session

1. `git fetch origin && git log --oneline origin/main -5`. Compare with local branches: is the People tab, `person_links`, or any linking/claim work ahead of `bfabfd3` locally?
2. Grep for anything already built: `grep -rn "identity_offer\|You owe\|placeholderClaimedBy" code/shared/src/commonMain`.
3. Read local `docs/features/people-tab.md`. If it already changed the "Who you owe is out of v1" line or the group Balances tab, trust local and adjust §9.
4. Check the Room DB version in the local `AppDatabase` before adding the entity.
5. Check whether the local `People.kt` grouping still drops claimed-placeholder links (§5.1) before writing the fix.
6. Then start at slice 1.

---

## 9. Changes to `people-tab.md` when this lands

- Remove the non-goal "Who you owe is out of v1". Replace with D4 (both directions, no netting).
- Acceptance "A person's amount in a group is that group's own balance... A group where you owe them... is left out" becomes: owe-them groups appear under *You owe*.
- Add the group filter and the in-group entry (D2, D3).
- Constraint "Links never change anything other people see" gets an exception: a link can create an **offer** to the linked person (§4). It still never changes another member's roster or ledger without the claimer's Yes.

---

## 10. Open questions (defaults in use until answered)

| # | Question | Default |
|---|---|---|
| O1 | Label of the in-group tab: keep "Balances" or rename "People"? | Keep "Balances" (it is what the group calls it); the screen header shows the scope chip. Decide in mock. |
| O2 | Should a new offer send a push notification, or only show on next open / 60s pull? | Card only. |
| O3 | "Dismiss for now": exactly when does it come back? | Next cold start or return from background. |
| O4 | Linker left the group before Yes: void the offer, or keep it if another current member also linked it? | Keep if any current member of G has an equivalent live link; void otherwise. |
| O5 | Offers created server-side (trigger/RPC) vs by the linker's client on pull? | Server-side (§4.3) so claims get offers immediately. |
| O6 | D10 interpretation: "automatic for that particular name" = a re-link of a name the claimer already confirmed is silently resolved. | As written; confirm with owner. |

---

## 11. Completion report

*Fill in at the end.*
