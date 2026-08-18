# `supabase/` — schema, RLS, RPCs, edge functions

Project ref **`wfpfgbipjmkysalfmyub`** ("Evenly"). `schema.sql` is the canonical schema (23 tables,
verified 2026-08-01). The client half of these rules is in
`../code/shared/src/commonMain/kotlin/app/splitevenly/data/AGENTS.md`.

## Applying changes

Migrations are **additive and idempotent** — `alter table … add column if not exists … default …`. Apply via
the dashboard SQL editor or the Supabase MCP `apply_migration`. First-time project setup is in `SETUP.md`
(same directory).

**A new column on a synced entity MUST land here first.** The client's full-row upsert sends every field, so
a column the server lacks breaks **all** sync for that table, not just that column. Server migration
before, or in the same change as, the Room entity edit.

## ⚠️ Realtime: publish exactly one table

The client ignores every realtime payload — an event only means "pull now". So the publication contains
**only `group_activity`** (one row per group, bumped once per writing transaction by statement-level
`AFTER INSERT/UPDATE` triggers). Its own membership RLS scopes delivery to that group's members.

**NEVER re-add app tables to the `supabase_realtime` publication.** Publishing all tables fanned out one
message *per row per connected client* and burned **13.9M messages against a 5M quota**. Triggers skip
`shares` (no `group_id`; covered by the same-transaction `expenses` bump) and `users` (no group scope).

## `merge_expense` — the zone-aware merge RPC

`merge_expense(p_expense, p_shares, p_base_split_version, p_actor)` is how expenses reach the server. It
merges Zone-1 metadata per-field by newest `*_updated_at`, and guards the Zone-2 split with a **causal
`split_version`**: base matches ⇒ apply and advance; base stale ⇒ keep canonical and APPEND the loser to the
append-only `superseded_split_edits` audit. It returns the merged canonical `{expense, shares}` and
soft-deletes removed shares server-side.

Never replace this with a blind upsert or a whole-expense compare-and-swap. The full model and its rationale
are in the client-side `data/AGENTS.md`.

**A new defaulted column on `expenses` or `shares` must be added to `_expense_defaults()` /
`_share_defaults()` in the same migration.** Both RPCs insert via `jsonb_populate_record(base, payload)`,
which takes a field from the payload when the key is present and from `base` when it is absent. Passing
`null::public.expenses` as that base — which is what they did — turned every column an older client
doesn't send into an explicit NULL, so the column default never ran. Every defaulted column here is
`not null default X`, so that is not a quiet wrong number: it is a not-null violation that fails the
INSERT and takes that expense's sync down completely. It breaks the additive-migration promise at
exactly the moment it is being relied on — add the column server-side first, as the rule requires, and
every not-yet-updated client stops being able to create expenses.

**Client timestamps are untrusted; clamp them with `_clamp_client_ts()`.** Every timestamp in
`commit_expense`/`merge_expense` arrives inside the client payload and neither RPC authenticates
`p_actor`, so a wound-forward clock or a crafted payload stamping `title_updated_at = 2099` wins
`greatest(...)` **forever** — that field silently never accepts another edit from anyone. The clamp is
`min(value, server_now + 60s)`: the 60s absorbs ordinary device skew (clamping to exactly `now()` makes
a marginally-fast phone lose its own writes), and it turns permanent damage into a 60-second annoyance.
Any new client-supplied `*_updated_at` goes through it too.

The older `commit_expense` RPC and the `expense_edit_conflicts` parking table are **inert** — nothing
populates them, and they are slated for removal once the current branch settles. Don't build on them.

## `claim_placeholder` — first claim wins

`claim_placeholder(p_group_id, p_placeholder_user_id, p_claimer_user_id, p_now)` stamps
`members.placeholder_claim_completed_at` **only where it is currently null** and returns
`{won, winner_user_id, winner_name}`. `security definer` with an explicit membership check, since
definer bypasses RLS. Two offline members can otherwise both merge the same placeholder and split its
history across two accounts silently.

The client calls it at **flush** time (an undone claim never reaches it) and **before** pushing the
merged rows — a loser then reverses rows no other client has pulled. Don't move it after the push.

## Account deletion — request/cancel/purge, never a direct delete of shared data

Three `security definer` RPCs replace the old hard-delete `delete_my_account()` (Play Store "Delete
account URL" requirement; full rationale in `data/AGENTS.md` Rule 9). `request_account_deletion()` and
`cancel_account_deletion()` only stamp/clear `users.deletion_requested_at` — caller-scoped, no other
table touched. `purge_deleted_accounts()` has no caller context and runs off a daily `pg_cron` job
(`purge-deleted-accounts`, `0 3 * * *`); it anonymizes any `users` row whose 30-day grace period has
elapsed (never deletes it), soft-leaves that user's active `members` rows, hands off group admin where
they were sole admin, hard-deletes their `device_tokens`, then removes the `auth.users` row. Each
target's purge runs in its own nested `BEGIN...EXCEPTION` block so one bad row can't abort the whole
nightly batch. All grace-period arithmetic is `bigint` epoch-millis (matching `users.created_at`/
`updated_at`) — seed the day-count constant as an explicit `::bigint` literal, since
`30 * 24 * 60 * 60 * 1000` overflows `int4` before Postgres ever promotes it.

## Group deletion — the only hard delete of user data in this schema

A group can be deleted **for everyone**, by any ACTIVE member, recoverable for 30 days from Home →
Recently deleted. The delete and the restore are **not RPCs**: they are local-first Room writes to
`groups.deleted_at` / `deleted_by` (added 2026-08-17) that ride the normal `SyncEngine` push, so both
work offline. Two existing properties are what carry them, and neither may be "tidied":

- **RLS never filters `deleted_at`** (rule 3 below), so a deleted group stays readable and writable by
  its members. That is what lets any of them restore it.
- **`members` rows stay ACTIVE through a delete.** Soft-leaving them is the obvious-looking move and
  breaks two things silently: `is_group_member` goes false, revoking the grant on the row the member
  needs to restore, and `pull`'s `activeGroupIds` drops the group, so no other device ever learns it
  was deleted.

Only the purge needs the server, because only the server can act 30 days later.
`purge_deleted_groups()` carries a `-- DESTRUCTIVE OPERATION` header with its pre-checks filled in per
Rule 8, is revoked from `anon` **and** `authenticated`, and hard-deletes the group plus all 23 of its
row sets, its placeholder `users` rows, and its receipt bytes under the `receipts/<group_id>/` prefix
(never bucket-wide).

**It exists but is NOT scheduled, on purpose.** Its own header forbids scheduling on a project without
PITR, and P0 #6 in `data/AGENTS.md` is still open; it was scheduled on the live project on 2026-08-17
and unscheduled the same day. Do not re-add the `cron.schedule` call to close the gap it leaves — the
one-liner to enable it, and what the gap costs, are commented at that spot in `schema.sql`. Until it
runs, tombstones simply accumulate past 30 days. Nothing user-facing breaks (the client still purges
its own copy on day 30), but the confirm sheet's promise that a group is "gone for good, including
from our servers" is not yet true server-side, which makes this a **launch blocker, not a nice-to-have**.

**Three tables are deliberately NOT purged**, and each for a different reason: `group_passes` (an
Evenly Pro pass is a *purchase* — support and finance need the record, and one member deleting a group
must never destroy what another paid for; the confirm sheet tells the deleter it is not refunded),
`receipt_scan_log` (per-USER cost ledger and rate-limit history; erasing it on group delete would also
hand anyone a way to reset their own scan quota), and `users`/`user_subscriptions` for real accounts
(per-person, shared across groups). `group_passes.group_id` is left dangling by design.

The client runs the **same 30-day rule on its own timer** (`GroupPurgeDao`, triggered from Home) rather
than following the server, because after the server purge there is nothing left to follow: the group
stops appearing in any pull response, and `land()` only ever upserts what came back. The two windows
are the same number in two places (`domain/group/RecentlyDeleted.WINDOW_DAYS`) and must move together.

## The guest's Zone-2 write path — the one thing `merge_expense` cannot do

`merge_expense` runs as the authenticated caller. The `web-claim` edge function has a service key and
**no `auth.uid()`**, so a guest changing a bill's money needs its own path. Three `security definer`
RPCs, all **service-role only** (`revoke ... from anon, authenticated`), sharing `_reprice_web_bill`:

- **`apply_web_bill_edit`** — writes `expense_items` *and* the `pending_item_edits` log row in one
  transaction, stamped `APPLIED`. Captures `previous_*` server-side (they are what Undo restores, so a
  client-supplied value could restore the wrong price) and writes the created line's id back into
  `item_id` on an ADD (without it Undo has nothing to target).
- **`undo_web_bill_edit`** — first-undo-wins via a conditional update on `decision = 'APPLIED'`; a
  second call returns `changed: false` rather than raising. Undoing a REMOVE revives the claims that
  removal killed by matching its **exact `deleted_at` stamp** — widen that to a range and an undo
  resurrects claims people deliberately dropped in the same minute.
- **`set_web_bill_payer`** — the same causal rule as `merge_expense`: base matches ⇒ apply and advance;
  base stale ⇒ refuse and return who the payer now is (spec E25).

**Every one of them advances `split_version`.** Skipping the bump is not cosmetic: the payer's next push
would carry a base causally ahead of a change it never saw, `merge_expense` would apply the older split
on top, and the guest's edit would revert with nothing to show it ever happened.

Nothing in the app calls these. The payer's own undo is a local-first Room write that reaches the server
through `merge_expense` like every other in-app money edit.

## Web claim (`WEB_CLAIM_SPEC.md`) — steps 1–6 landed

Additive per the spec's §11 build order. Step 2 (`BillRepository.joinItem` + a `setPortion` fix) wired
`join_item_portion` into the app; step 3 is the `web-claim` edge function (see its own README) — the
security boundary and CRUD surface, minus payer-role assignment and pending-edit approval (both
deferred, see that README). Step 4 is the TS money port + `test-vectors/bill-split.json` + its CI gate
(`web/AGENTS.md`); step 5 is the Svelte surface in `web/`; step 6 is the app's three payer screens
(review changes, who's still to claim, share/QR) and the link-lifecycle RPCs below.

**Step 6 confirmed the pattern below again:** building the review screen is what settled that an
unapproved `ADD` has no `expense_items` row at all (the edge function only ever inserts a proposal), so
spec E22's "claims on a rejected ADD are soft-deleted" describes a state that cannot occur.

**Building step 5 found three holes in step 3, now filled:** `share` (the share sheet names people who
are not holding a phone — `join` only ever adds the caller), `leave` (§2.7 promises joining is
reversible, and only solo claims could be undone), and `pendingEdits` on `/bill` plus `totalSubunits`
on the header (E19 needs a guest's own unapproved edit inside her total; §3.1 needs the bill's amount
before anyone is asked for a name). A screen is the only honest test of an API.

A fourth table, `web_claim_write_log` (insert-only, no grants — the `web-claim` function's per-token
rate-limit log, mirroring `receipt_scan_log`), landed with step 3.

- **`web_sessions`** — browser-to-placeholder binding, group-scoped and durable. RLS enabled, **zero**
  policies: only the `web-claim` edge function's service key ever touches it.
- **`web_bill_links`** — the 72h revocable bill token, stored hashed. RLS enabled, **zero policies,
  permanently.** Step 6 was planned to add a membership-scoped table policy and deliberately did not,
  and tightening the rest of the schema did **not** change that: `token_hash` is the entire
  authorisation check `web-claim` performs, so a member-readable policy would hand every member of a
  group a working bearer token for its bills. Zero policies is the design, not a gap left to fill. The
  app reaches the table only through the `security definer` RPCs below, none of which return the hash.
- **`pending_item_edits`** — the bill's change log: a guest's add/relabel/reprice/requantify/remove,
  written **already `APPLIED`**. The name predates the drop of the approval gate
  (`WEB_CLAIM_PATCH_PLAN.md`) and the table is synced, so renaming it costs more than it explains.
  **Synced**, so it is membership-scoped by `group_id` like the rest of the schema, and is in the
  doorbell trigger loop. Its policy is declared at its own definition rather than in the RLS section's
  loop, because the table is created ~1100 lines after that loop runs — it carried the *permissive*
  policy for the same reason, and that is precisely how the first tightening pass nearly missed it.
  `previous_line_total_subunits` is what an undo restores;
  `previous_unit_price_subunits` is display only and rebuilding a line total from it loses a penny.
- **`join_item_portion(item_id, joiner_user_id, portion_id, now, over_claim_ack)`** — the write a
  client can never safely make itself: converting someone else's solo `item_claims` row into a shared
  `item_shares` portion. `security definer`; the caller-identity check only fires when `auth.uid()` is
  present, since this is called from both the app (real auth) and the edge function (service key, no
  JWT). Granted to `authenticated`; wired into the app in step 2 (`BillRepository.joinItem` +
  `BillRepositoryImpl.setPortion`), closing a live double-counting bug where adding someone to the
  app's fixed `"<item>__all"` shared portion never retired their pre-existing solo claim. `p_portion_id`
  may name a portion that isn't live yet (the app's own naming convention) — the RPC creates it under
  that exact id via the same conversion-or-fresh-portion path used when no name is given at all. It
  also retires the **joiner's own** live solo claim on that line, in every path and before units are
  counted: the app compensated for that half client-side, so the web path (no client mirror)
  double-counted anyone who claimed a line and then joined a portion of it.
- **`create_web_bill_link` / `extend_web_bill_link` / `revoke_web_bill_link` / `web_bill_link_status`
  (`expense_id, actor[, now, ttl_ms]`)** — step 6's payer-side link lifecycle. All four share
  `web_bill_link_guard`, which re-derives ACTIVE membership of the expense's group and refuses to act
  as anyone but `auth.uid()`. **The plaintext token is minted server-side and returned exactly once**:
  `gen_random_uuid()` through `base62_encode` (Kotlin/Native has no crypto-grade RNG in `commonMain`,
  and a guessable bill token is a public read of someone's bill). It is stored only as
  `encode(sha256(convert_to(token,'utf8')),'hex')` — byte-identical to the edge function's `sha256Hex`,
  which is what makes a token minted here resolve there. The creating device keeps the plaintext in its
  own `SecureStorage`; **nothing can hand it back**, so a payer on a second device sees a live link with
  no QR and is offered a rotation. `create_*` revokes any live link on the same bill first, so a bill
  has at most one usable token. `web_bill_link_status` also returns per-participant `web_sessions`
  activity — the one fact Room cannot hold, since that table is service-key-only.
- **`claim_web_placeholder(group_id, placeholder_user_id, session_id, session_token_hash, now)`** —
  "first wins" for a web guest's "That's me", but unlike `claim_placeholder` it's a **5-second race
  window**, not a permanent lock: a guest may legitimately re-claim the same placeholder from a second
  device long after the first (spec E7). Service-role only.
- **`create_web_placeholder(group_id, expense_id, name, user_id, member_id, session_id,
  session_token_hash, participant_id, now)`** — the §2.4 name-uniqueness decision re-taken under an
  advisory lock, plus the user/member/session/participant inserts in one transaction. The edge
  function's own matching still drives the UI copy and the suffix suggestions; only this decides.
  Service-role only.

**A write that settles a race must happen inside the lock that serialises it.** Returning a verdict and
letting the caller do the write leaves the deciding write outside the transaction, and the advisory lock
stops meaning anything — two callers each look, each see nothing, each win. Both web-claim identity RPCs
were written that way first. Check it in any new one.

**Anon must never gain EXECUTE by default.** Supabase auto-grants `EXECUTE` to `anon`/`authenticated`
at function-creation time, independent of `revoke ... from public` — confirmed via `get_advisors`
(`anon_security_definer_function_executable`). Any new `security definer` function needs an *explicit*
`revoke ... from anon` (and `from authenticated` if it's edge-function-only), not just `from public`.

## `group_passes` — the Pro entitlement, server-owned (`PRO_PASS_SPEC.md`)

RevenueCat says a purchase happened; **this table decides who is Pro**. It cannot be the buyer's
device-side `CustomerInfo`: the other five people in the group bought nothing and still need Pro.

Synced (Room mirror, `SyncEngine` pull, doorbell trigger) but **pull-only** — the client has SELECT and
nothing else. Writes come from `activate-pass` / `revenuecat-webhook` (service key) only. It is
deliberately **outside the permissive `_rw` loop** and must stay outside: under `using (true)` any
authenticated user could insert themselves a pass expiring in 2099, which is free unlimited paid
Claude-vision calls for anyone who reads the anon key out of the APK.

`(store, store_txn_id)` is unique — the **idempotency key**. The client's activate call, a webhook
retry, and the reconciliation sweep all race to insert the same purchase; one wins, the rest no-op.
Without it one $0.99 charge becomes three stacked passes.

**`store = 'test_store'` is RevenueCat's Test Store, and `PRO_ALLOW_TEST_STORE` is its off switch.**
The Test Store runs the whole purchase round trip over simulated money with no App Store Connect or Play
Console product, which is the only way this feature was verifiable end to end before those existed. Its
public SDK key ships inside every build configured with it, so `mapStore` refuses `test_store` unless
`PRO_ALLOW_TEST_STORE=true` — an ungated path would make a leaked test key worth unlimited paid vision
calls. **Unset that secret before launch**, and keep the value distinct from `promo`: a simulated pass
must never read as revenue, and must stay findable once the test config is gone.

**`user_subscriptions` is the second route to Pro** (`PRO_PASS_SPEC.md` §5.2): a personal, auto-renewing
subscription, one row per *person* (renewals update it; billing history stays in RevenueCat). Same
server-owned, pull-only, outside-the-`_rw`-loop shape as `group_passes`, but read is scoped to *people you
share a group with* — the badge names whoever is paying, and that must not leak a stranger's billing state.
Its doorbell is `bump_group_activity_subscriber`, which wakes **every** group the subscriber is active in,
because one renewal changes the badge in all of them.

`group_pro_status(group_id, now)` returns **at most one row, and no row means not Pro** — there is no
`is_pro = false` row. It is the **only** place that knows there are two routes: it unions the group's live
passes with the live subscriptions of its ACTIVE members and returns the latest-expiring candidate, with
`source` saying which answered. That ordering is also what makes stacking work: buying while Pro inserts a
row starting at the current expiry, so two friends who each buy a week give the group two weeks.
Membership is *joined*, never materialised into a grant row, so leaving a group drops it back to free at
the next pull. `expires_at > now` is strict. Adding `source` was a breaking signature change (drop +
create, not `create or replace`); it shipped alone safely only because both callers test whether a row came
back and never read a column. `group_free_scans_used(group_id)` counts
`receipt_scan_log` rows with `outcome = 'ok'` — the free allowance needed no new table. Both are
`security definer` and **revoked from `anon` and `authenticated`**: the app uses a Kotlin mirror of the
status rule so a badge costs no round trip, and only `extract-receipt` enforces.

**`revoke insert, update, delete` is not enough — RLS does not apply to TRUNCATE.** Supabase grants ALL
on a new public table to `anon`/`authenticated`, so a signed-in user could wipe the table in one
statement regardless of policies. The project-wide sweep (`revoke truncate on all tables in schema
public from anon, authenticated;`) now runs in the RLS section of `schema.sql`, and a live grants query
returns zero tables still granting it. **It is not automatic for a table you add later** — Supabase
grants ALL at creation time, so re-run the sweep, or revoke on the new table as `group_passes` does.

**`my_group_scan_usage(group_id)` is the only way a client learns its group's scan count.**
`receipt_scan_log`'s RLS is `user_id = auth.uid()`, so a member querying the table sees only the scans
*they* did; the group's total would come out wrong, not denied, which is the dangerous shape of failure.
The RPC is `security definer`, re-derives the caller's ACTIVE membership, and is granted to
`authenticated` only. `group_free_scans_used` stays revoked from every client role. Its `free_limit`
mirrors `extract-receipt`'s `FREE_SCANS_PER_GROUP`; the env var is the enforcing copy, so a divergence
mislabels a meter and never changes a refusal.

## The three Evenly Pro functions (`PRO_PASS_SPEC.md` §6)

All three are **thin HTTP shells over `_shared/revenuecat.ts`**, which holds `syncSubscription` and
`activatePass`. That is deliberate: the webhook has to re-run the *same* logic as the client-facing
calls, and a second copy would drift. It reads **v1 `/subscribers/{app_user_id}`** rather than the v2
customer endpoints the spec names: v2 returns subscriptions and entitlements only, and `activate-pass`
has to verify a **consumable**, which appears only in v1's `non_subscriptions`. The **secret** key lives
only in edge-function env; the apps carry the public SDK keys.

- **`sync-subscriber`** takes **no body**. It resolves the caller from their JWT, asks RevenueCat what
  that subscriber owns, and upserts `user_subscriptions`. With no client-supplied transaction id there is
  nothing to forge and nothing to make idempotent, which is what makes it safe on every launch and what
  lets it double as **Restore** with no separate code path. It **fails closed**: an unreachable
  RevenueCat is a 503, never "no entitlement found, so expire them", which would un-Pro every paying
  customer during an outage. A lapsed subscription **expires** the row rather than deleting it, because
  the row is also how the app says "ended 16 Aug".
- **`activate-pass`** is the one that must accept a client-supplied `groupId`: the group binding exists
  nowhere in the store's data model. Everything else is verified. It scopes the transaction lookup to
  **the caller's own** subscriber document, so a transaction id alone is not proof of ownership; it
  refuses a product that is not one of the three passes rather than guessing a duration; and the
  durations are **ours**, stamped on the server clock, because a consumable receipt says nothing about
  how long it lasts and a wound-back device clock would otherwise be a free month. Stacking starts at the
  group's current pass expiry, not at now. A unique-violation on `(store, store_txn_id)` is re-read and
  returned as **success** — that is the idempotency key doing its job, not a failure.
- **`revenuecat-webhook`** (`verify_jwt = false`, shared secret in the Authorization header, same pattern
  as `web-claim`). **It is not a switch over event types.** Every subscription lifecycle event resolves to
  one call to `syncSubscription`, driven by RevenueCat's *answer* rather than the event name, so an event
  type we have never seen cannot corrupt state. Refunds need no branch of their own: `refunded_at` comes
  back on the subscriber document and becomes `revoked_at`. `NON_RENEWING_PURCHASE` is the **pass
  backstop** for a client that died between the charge and its activate call; the group id rides on the
  `evenly_group_id` subscriber attribute and, when it is absent, the event is parked in
  `pro_orphan_purchases` and alerted, **never guessed** — picking a group would hand a paid entitlement to
  a different set of people. It answers **200 for anything understood and handled, including "nothing to
  do"**, because RevenueCat retries non-2xx for hours; 5xx is reserved for "we could not do the work and
  want the retry". Unconfigured means **refusing**, not accepting: an unauthenticated writer here could
  hand any account a subscription.

## RLS — membership-scoped

Every one of the 20 app tables is scoped to **ACTIVE** members of the row's group. This replaced a loop
generating `for all to authenticated using (true) with check (true)`, under which any signed-in account could
read, overwrite, or delete every other group's ledger. Proven, not assumed: as a signed-in non-member, all 20
tables return 0 rows of another group's data, and update/delete/insert against them affect 0 rows or raise.

Four rules govern anything you add here:

1. **Never add a permissive policy, not even "just for testing".** Permissive policies OR together, so one
   surviving `using (true)` silently defeats every policy beside it. That is also why the old `_rw` loop had
   to be deleted rather than supplemented.
2. **Go through `is_group_member(group_id)`**, the `security definer` helper with a pinned `search_path`.
   Definer is load-bearing: a membership policy *on* `members` that selects *from* `members` recurses
   forever. `shares` is the only table with no `group_id` and uses `can_access_expense(expense_id)` instead.
3. **Never filter `deleted_at is null` in a policy.** Deletions travel as soft-deleted rows; hiding
   tombstones stops them reaching other devices and the data resurrects on the next pull.
4. **Wrap `auth.uid()` as `(select auth.uid())`** so it is evaluated once per statement, not once per row.

Four tables are deliberately *not* plain membership scoping, and each arm is load-bearing:

- **`groups`** — insert also accepts `created_by = auth.uid()`. `SyncEngine.push` sends users → groups →
  members, so a newly created group is inserted before its creator has a membership row.
- **`members`** — also accepts `user_id = auth.uid()`, which is what carries pull step 1 (it selects by
  user_id before knowing any group, and lands LEFT rows), joining, and leaving.
- **`users`** — read is "me, or anyone I share a group with"; scoping it to `id = auth.uid()` would blank out
  every co-member and placeholder name in the app. The co-member arm does **not** filter the other side to
  ACTIVE: claimed placeholders and departed members are soft-left and their names still have to resolve on
  historical rows. Write is my own row, or a placeholder in one of my groups.
- **`device_tokens`** — per-user (`user_id = auth.uid()`), never group-scoped. It is a route to one person's
  lock screen.

**Membership gates which group you may write to, not which row inside it.** Any ACTIVE member can edit or
soft-delete any expense, settlement, or comment in their groups, including ones they did not create. That
matches how the app already behaves. Per-actor rules (only the payer may edit their settlement) would be a
separate design.

**`merge_expense` and `commit_expense` are SECURITY INVOKER**, so these policies are enforced *inside* them.
`can_access_expense` is a definer function and therefore sees the expense row the same transaction just
inserted. Check the security mode before assuming a new RPC bypasses anything.

**Join-by-link cannot be a policy.** An RLS predicate cannot see the query's `WHERE`, so nothing can express
"allow this row because they supplied its token" — any policy permitting that read permits reading every
group. `resolve_group_by_invite_token` (definer) is the only path, and
`SupabaseRemoteGroupGateway.resolveByToken` is its only caller. A plain select on `groups` there returns zero
rows and breaks cross-device join silently.

**Any view over an RLS-protected table needs `with (security_invoker = true)`.** A plain `create view`
defaults to `SECURITY DEFINER`, which runs as the view owner and bypasses the underlying table's RLS
entirely — every authenticated user sees every other user's rows through the view even though the table
itself is locked down. `get_advisors` catches this (`security_definer_view`, ERROR level); run it after
adding any view.

**Two tables are already exempt from the loop and must stay out of it:** `superseded_split_edits`
(insert-only; it holds full rejected money payloads no client may read) and
`placeholder_claim_answers` (membership-scoped read, insert/update as yourself only, delete revoked).
Adding either to the `_rw` array would grant exactly the access they exist to deny.

**The service-role key never appears in client or shared code** — edge functions only, via env vars.
`SupabaseConfig` ships the **anon** key, which is public and RLS-gated, and is safe to commit. Reaching for
the service-role client is never the default; if a task genuinely needs it, say so in a comment explaining
*why RLS cannot express the rule*, and keep it read-only or tightly scoped.

## Storage

Receipts live in a **public** bucket `receipts`. **This is the last known read hole in the project, and
membership-scoped RLS did not close it:** `public = true` makes the bucket's four `storage.objects` policies
decorative, so anyone holding an object URL reads that receipt photo without authenticating at all — names,
amounts, often a card's last four. Closing it means a private bucket plus signed URLs, and `publicUrl` has no
expiry to renew, so it is a **client** change and was deliberately left out of the RLS work rather than
half-done. The `receipts` *table* is membership-scoped; only the bytes are open.

A soft-deleted `receipts` row best-effort deletes its Storage object
(`ActivityRepositoryImpl.deleteReceipt`). **Never** issue a bucket-wide or prefix-wide delete.

## Edge functions

| Function                    | Status                                                     |
| --------------------------- | ---------------------------------------------------------- |
| `extract-receipt`           | Live — Claude vision → structured bill draft (multi-page)  |
| `web-claim`                 | Live — the web claim security boundary; `verify_jwt = false` (own token auth) |
| `push-notify`               | Deployed but **inert** until `FCM_SERVICE_ACCOUNT` is set  |
| `dispatch_push`             | **Directory is EMPTY — never written or deployed**          |
| `export_group`              | Live — group CSV export; Pro-gated server-side              |
| `refresh_fx_rates`          | **Directory is EMPTY — never written or deployed**          |
| `notify_admin_of_conflicts` | **Directory is EMPTY**; legacy, tied to the retired conflicts model |
| `apple-link-token`          | Deployed but **inert** until `APPLE_*` secrets are set — see its README |
| `apple-revoke-token`        | Deployed but **inert** until `APPLE_*` secrets are set — see its README |
| `sync-subscriber`           | Deployed but **inert** until `REVENUECAT_SECRET_KEY` is set  |
| `activate-pass`             | Deployed but **inert** until `REVENUECAT_SECRET_KEY` is set  |
| `revenuecat-webhook`        | Deployed; **refuses every request** until `REVENUECAT_WEBHOOK_SECRET` is set; `verify_jwt = false` |
| `admin`                     | The admin dashboard's whole security boundary; `verify_jwt = false` (own gate) |
| `feedback`                  | Feedback submission from all three entry points; `verify_jwt = false` (two are anonymous) |

`admin` and `feedback` are `ADMIN_FEEDBACK_SPEC.md`, build-order steps 1 to 4. Both carry
`verify_jwt = false`, and for `admin` that is the opposite of what it looks like: platform-level
verification would 401 the CORS preflight before the function's own gate runs, and it accepts the
project **anon key** as a valid JWT, which proves nothing about who is calling. The real check is
`requireAdmin` — verify the token against the auth server, look the user up in `admin_users`, **403
before touching any data** — and it runs on every action with no exceptions. `admin_users` is an
**email allowlist, never a domain rule**: the owner account is a `gmail.com` address, so a domain rule
admits every Google account on earth. There is no bootstrap branch in code; the first row is inserted
by hand (`admin/README.md`).

`feedback` takes an **optional** Authorization header (in-app is signed in, `/feedback` and the claim
flow are strangers). A present-but-invalid token is a 401 and never a silent downgrade to anonymous,
so an expired session cannot file someone's bug report under "some stranger". `feedback_tickets` is
**write-only from the client** in v1 — nothing reads a ticket back — which is what keeps it out of the
sync engine entirely. A "your past tickets" screen would make it a synced entity under `data/AGENTS.md`'s
rules and the schema-before-entity hook.

Rate limiting for the public endpoints (`waitlist`, `feedback`) is `_shared/rateLimit.ts`: 30 per hour
per principal, counted in `public_write_log`, mirroring `web_claim_write_log`. It stores
`sha256(kind:ip)` and never the address — an IP is PII and the only question the table answers is
"same caller again?". It **fails closed on a broken count and open on a missing IP**: a failed query
means the limiter cannot do its job, but turning a header quirk into a blanket refusal would take the
endpoint down for everyone rather than protecting it.

`_shared/slack.ts` is the two-channel webhook helper: `SLACK_ALERT_WEBHOOK_URL` for ops,
`SLACK_FEEDBACK_WEBHOOK_URL` for support, deliberately separate channels. **Feedback is never behind
the `ops_alerts` cooldown.** That cooldown exists so one failing receipt scan cannot spam a channel;
every ticket is a distinct human, and dropping the fifth because four arrived that minute is silently
losing your users' words. Inbound rate limiting is the lever for that. `extract-receipt` and
`revenuecat-webhook` still carry inline copies of the cooldown helper — migrate them next time either
is touched for its own reasons, not as a standalone redeploy.

`apple-link-token`/`apple-revoke-token` (Apple Sign In native plan §5 P4, Guideline 5.1.1(v)) exchange a
native Apple authorization code for a refresh token on sign-in and revoke it on account deletion, stored
in `apple_oauth_tokens` (RLS enabled, zero policies — service-role only, matching `web_sessions`). Both
share `_shared/appleClientSecret.ts`, an ES256 client-secret JWT signer.

**`extract-receipt` requires `groupId` (400 without it) and enforces the Pro quota.** It began as
optional analytics attribution; as the quota key, a nullable field is a bypass. Order of checks: user →
groupId → ACTIVE membership (403) → per-user rate limit (429) → Pro pass → free allowance → **402
`quota_exhausted`**, all before any paid call. 402 and 429 are deliberately distinct: one opens a
paywall, the other opens a wait, and the client cannot choose the right screen from a single status.

The quota reads through the **service** client, never the caller's. `receipt_scan_log`'s RLS is
`user_id = auth.uid()`, so the caller's client sees only their own scans — counting the group's
allowance that way gives every member a private 5. Every new check **fails closed**, like the rate-limit
read above it. `FREE_SCANS_PER_GROUP` (default 5) is env-overridable so the number can be tuned without
a redeploy.

`extract-receipt`'s two tiers share one `ANTHROPIC_API_KEY`, so a 401/403 or a credit-exhausted 400 from
Anthropic fails identically on both — that's not a bad photo, it's the account itself broken, and it
will stay broken for every user until a human fixes it. That case short-circuits past the escalation
tier and returns the same `{ configured: false }` shape as the "key not set" path, so the client's
existing unconfigured-service handling (no retry offered, straight to manual entry) covers it for free.
It also best-effort-posts to Slack via `SLACK_ALERT_WEBHOOK_URL` (optional; a no-op if unset) — inert
until configured, same pattern as `APPLE_*` above — so this doesn't go unnoticed until a user complains.
The cooldown (one post per `kind` per hour, so a burst of failing scans doesn't spam the channel) lives
in `ops_alerts` (RLS enabled, zero policies — service-role only, same as `receipt_opus_escalations`).

Push targets the `device_tokens` table. `extract-receipt` only ever *pre-fills* an editable item list; a
human verifies before any money is computed.

**`extract-receipt` asks the model to transcribe and never to compute.** No field in its tool schema holds
a value the model has to work out, and its prompt contains no instruction to add, check, or balance
anything: amounts are the digits as printed (`"22.74"`), and every sum lives in `billMath.ts`. Two defects
on 2026-08-08 came from breaking that — an integer `*_subunits` field the model rescaled by truncation, and
a `subtotal` field defined as the sum of its own siblings, which made the reconciliation gate a check of the
model against itself and shipped a $9.50-short bill as `verified: true`. **Never add a gate whose two sides
both come from the model**; it cannot fail, and it reads like safety in the diff. Full account in
`RECEIPT_OCR_PLAN.md`.

## Destructive SQL

`DROP`, `TRUNCATE`, and unscoped `DELETE` are forbidden in any migration, RPC, or edge function. The rule
is about **rows**: `drop trigger`/`drop policy`/`drop function` on a superseded object destroys no user
data and is how this file already retires things (a stale overload left callable is worse). Today the
schema holds no `drop table`/`truncate`; the only `DELETE`s are per-user and scoped to one row's own
data: `purge_deleted_accounts()`'s `device_tokens`/`auth.users` cleanup (30 days after
`request_account_deletion`, see `data/AGENTS.md` Rule 9 — `expenses`/`shares`/`settlements`/`receipts`
are never touched, only anonymized in place), and `web-claim`'s prune of its own expired
`web_claim_write_log` rows, scoped to one token. Keep it that way. The full production data-safety
ruleset (audit log, soft-delete cascade, PITR, irreversible
-operation headers) lives in the client-side `data/AGENTS.md`; it is gated on the app going live, but Rule 2
above applies now.
