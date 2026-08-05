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
  permanently.** Step 6 was planned to add a membership-scoped table policy and deliberately did not:
  RLS is still `using (true)` app-wide, so *any* policy here makes `token_hash` readable by every
  authenticated user, and that hash is the entire authorisation check `web-claim` performs. The app
  reaches the table only through the `security definer` RPCs below, none of which return the hash.
- **`pending_item_edits`** — the bill's change log: a guest's add/relabel/reprice/requantify/remove,
  written **already `APPLIED`**. The name predates the drop of the approval gate
  (`WEB_CLAIM_PATCH_PLAN.md`) and the table is synced, so renaming it costs more than it explains.
  **Synced**, so it carries the same permissive `for all to authenticated` policy as the rest of this
  schema, and is in the doorbell trigger loop. `previous_line_total_subunits` is what an undo restores;
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

## RLS — currently permissive, and that is a P0 before prod

The loop at `schema.sql:455` generates `for all to authenticated using (true) with check (true)` for every
table. That means **any authenticated user, including an anonymous one, can read, overwrite, or delete every
row in the database.** It is deliberate for testing and it is a one-account mass-data-loss vector.

Tighten to membership-scoped before any non-test user exists; the policy sketch is already in `schema.sql`
(see the commented `expenses_member_read` example around line 610).

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

Receipts live in a **public** bucket `receipts`; object policies are currently permissive and should be
tightened alongside RLS. A soft-deleted `receipts` row best-effort deletes its Storage object
(`ActivityRepositoryImpl.deleteReceipt`). **Never** issue a bucket-wide or prefix-wide delete.

## Edge functions

| Function                    | Status                                                     |
| --------------------------- | ---------------------------------------------------------- |
| `extract-receipt`           | Live — Claude vision → structured bill draft (multi-page)  |
| `web-claim`                 | Live — the web claim security boundary; `verify_jwt = false` (own token auth) |
| `push-notify`               | Deployed but **inert** until `FCM_SERVICE_ACCOUNT` is set  |
| `dispatch_push`             | Push fan-out                                                |
| `export_group`              | Group data export                                           |
| `refresh_fx_rates`          | FX rate refresh                                             |
| `notify_admin_of_conflicts` | Legacy — tied to the retired conflicts model               |

Push targets the `device_tokens` table. `extract-receipt` only ever *pre-fills* an editable item list; a
human verifies before any money is computed.

## Destructive SQL

`DROP`, `TRUNCATE`, and unscoped `DELETE` are forbidden in any migration, RPC, or edge function. The rule
is about **rows**: `drop trigger`/`drop policy`/`drop function` on a superseded object destroys no user
data and is how this file already retires things (a stale overload left callable is worse). Today the
schema holds no `drop table`/`truncate`; the only `DELETE`s are the per-user `delete_my_account()` RPC
and `web-claim`'s prune of its own expired `web_claim_write_log` rows, scoped to one token. Keep it that
way. The full production data-safety ruleset (audit log, soft-delete cascade, PITR, irreversible
-operation headers) lives in the client-side `data/AGENTS.md`; it is gated on the app going live, but Rule 2
above applies now.
