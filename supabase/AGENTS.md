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

## Web claim (`WEB_CLAIM_SPEC.md`) — steps 1–3 landed

Additive per the spec's §11 build order. Step 2 (`BillRepository.joinItem` + a `setPortion` fix) wired
`join_item_portion` into the app; step 3 is the `web-claim` edge function (see its own README) — the
security boundary and CRUD surface, minus payer-role assignment and pending-edit approval (both
deferred, see that README). Steps 4–6 (the TS money engine + CI gate, the Svelte surface, and the app's
payer screens) are still ahead. A fourth table, `web_claim_write_log` (insert-only, no grants — the
`web-claim` function's per-token rate-limit log, mirroring `receipt_scan_log`), landed with step 3.

- **`web_sessions`** — browser-to-placeholder binding, group-scoped and durable. RLS enabled, **zero**
  policies: only the (not-yet-built) `web-claim` edge function's service key ever touches it.
- **`web_bill_links`** — the 72h revocable bill token, stored hashed. RLS enabled, zero policies for
  now; the payer's in-app share/revoke screen (step 6) adds a scoped policy in its own migration.
- **`pending_item_edits`** — a guest's proposed add/relabel/reprice/requantify/remove, awaiting the
  payer's individual approval. **Synced** (the app reads and decides on it in step 6), so it already
  carries the same permissive `for all to authenticated` policy as the rest of this schema, and is in
  the doorbell trigger loop.
- **`join_item_portion(item_id, joiner_user_id, portion_id, now, over_claim_ack)`** — the write a
  client can never safely make itself: converting someone else's solo `item_claims` row into a shared
  `item_shares` portion. `security definer`; the caller-identity check only fires when `auth.uid()` is
  present, since this is called from both the app (real auth) and the edge function (service key, no
  JWT). Granted to `authenticated`; wired into the app in step 2 (`BillRepository.joinItem` +
  `BillRepositoryImpl.setPortion`), closing a live double-counting bug where adding someone to the
  app's fixed `"<item>__all"` shared portion never retired their pre-existing solo claim. `p_portion_id`
  may name a portion that isn't live yet (the app's own naming convention) — the RPC creates it under
  that exact id via the same conversion-or-fresh-portion path used when no name is given at all.
- **`claim_web_placeholder(group_id, placeholder_user_id, session_id, now)`** — "first wins" for a web
  guest's "That's me", but unlike `claim_placeholder` it's a **5-second race window**, not a permanent
  lock: a guest may legitimately re-claim the same placeholder from a second device long after the
  first (spec E7). Callable only by the service role — revoked from `anon` and `authenticated`.

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

`DROP`, `TRUNCATE`, and unscoped `DELETE` are forbidden in any migration, RPC, or edge function. Today the
schema is clean — zero `DROP`/`TRUNCATE`, and the only `DELETE` is the per-user `delete_my_account()` RPC.
Keep it that way. The full production data-safety ruleset (audit log, soft-delete cascade, PITR, irreversible
-operation headers) lives in the client-side `data/AGENTS.md`; it is gated on the app going live, but Rule 2
above applies now.
