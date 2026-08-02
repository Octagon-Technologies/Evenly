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
