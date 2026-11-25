-- Evenly — Supabase schema for the synced tables.
-- Columns mirror the Room @Entity rows 1:1 (snake_case). Run in the dashboard SQL Editor.
-- Types: TEXT (uuid/strings/ISO dates), BIGINT (epoch-ms timestamps + minor-unit money + row_version),
--        INTEGER (share units), DOUBLE PRECISION (share %), BOOLEAN (flags).
-- ids are client-generated UUIDv7 strings; we keep them TEXT so the client owns id generation.

create table if not exists public.users (
  id text primary key,
  is_placeholder boolean not null default false,
  display_name text not null,
  email text,
  avatar_url text,
  base_currency text default 'USD',
  venmo_handle text,
  cashapp_handle text,
  paypal_handle text,
  zelle_handle text,
  preferred_payment_app text,
  placeholder_group_id text,
  -- Who typed this name in. Non-null only for is_placeholder rows; it exists for exactly one purpose:
  -- never ask someone whether they are a name they created themselves. Rows predating the column stay
  -- null ("creator unknown") and are offered to everyone, which is the pre-feature behaviour.
  created_by text,
  -- Off until the user opts in and the OS grants permission (the client flips them on at the grant).
  notify_new_expenses boolean not null default false,
  notify_payments boolean not null default false,
  notify_conflict_reminders boolean not null default false,
  theme_mode text not null default 'System',
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1
);

-- Existing projects: the create above is a no-op, so the default flip needs its own statement.
alter table public.users alter column notify_new_expenses set default false;
alter table public.users alter column notify_payments set default false;

create table if not exists public.groups (
  id text primary key,
  name text not null,
  emoji text not null default '💸',
  base_currency text not null default 'USD',
  admin_user_id text,
  invite_token text not null unique,
  invite_token_rotated_at bigint,
  reminder_cadence text not null default 'WEEKLY',
  last_conflict_reminder_at bigint,
  storage_bytes_used bigint not null default 0,
  created_at bigint not null,
  created_by text not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  deleted_at bigint
);

create table if not exists public.members (
  id text primary key,
  group_id text not null,
  user_id text not null,
  status text not null default 'ACTIVE',
  is_admin boolean not null default false,
  joined_at bigint not null,
  left_at bigint,
  archived_at bigint,
  placeholder_claim_completed_at bigint,
  -- WHO claimed this placeholder. completed_at records that a name was claimed but not by whom, and the
  -- loser of a concurrent claim has to be told who won (see claim_placeholder() at the bottom).
  placeholder_claimed_by text,
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  unique (group_id, user_id)
);
create index if not exists members_group_idx on public.members (group_id);
create index if not exists members_user_idx on public.members (user_id);

-- ── "Is this you?" identity claim: the "not me" answers ──────────────────────────────────────────
-- Only NEGATIVE answers are stored. "That's me" is already represented by the merge
-- (members.placeholder_claim_completed_at) and "Later" is device-local. Synced rather than a local
-- "card dismissed" flag so answers survive a reinstall, travel to a second device, and let a name added
-- LATER still be asked about while the already-answered ones stay gone. The list a member sees is
-- simply "unclaimed names in this group" minus "names I've answered" — nobody has to dismiss forever,
-- they run out of question.
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

-- RLS: unlike the permissive `_rw` loop further down, this table is membership-scoped from day one —
-- it is new, so there is no back-compat cost, and an answer is a personal statement about identity.
--   select : any ACTIVE member of the group (so "everyone ruled it out" is knowable group-wide)
--   insert : only as yourself
--   update : only your own row. The client's push is a PostgREST upsert (on conflict do update), so a
--            re-push of an unchanged row needs the UPDATE branch or this table's push wedges. Scoped to
--            your own row it can only rewrite a statement you already made, so it grants nothing new.
--   delete : revoked outright.
alter table public.placeholder_claim_answers enable row level security;
drop policy if exists placeholder_claim_answers_read on public.placeholder_claim_answers;
create policy placeholder_claim_answers_read on public.placeholder_claim_answers
  for select to authenticated
  using (exists (
    select 1 from public.members m
    where m.group_id = placeholder_claim_answers.group_id
      and m.user_id = auth.uid()::text
      and m.status = 'ACTIVE'));
drop policy if exists placeholder_claim_answers_insert on public.placeholder_claim_answers;
create policy placeholder_claim_answers_insert on public.placeholder_claim_answers
  for insert to authenticated
  with check (answered_by_user_id = auth.uid()::text);
drop policy if exists placeholder_claim_answers_update on public.placeholder_claim_answers;
create policy placeholder_claim_answers_update on public.placeholder_claim_answers
  for update to authenticated
  using (answered_by_user_id = auth.uid()::text)
  with check (answered_by_user_id = auth.uid()::text);
revoke delete on public.placeholder_claim_answers from anon, authenticated;
-- ⚠️ Deliberately NOT in the `supabase_realtime` publication. See the doorbell section below.

-- People tab (docs/features/people-tab.md): one account's private statement that two names in its
-- groups are the same person ("Pookie" in Boston is "Purity"), or that a same-name suggestion is NOT.
-- Owner-scoped, never group-scoped: a link changes only the owner's People tab, so no other member may
-- read it, and nobody else's roster or ledger is touched. Ids only, no names.
--   kind     : 'SAME' or 'NOT_SAME'
--   user_id  : the name being attached (always a placeholder for SAME)
--   person_id: the name it is attached to (a placeholder or a real account)
-- Unlinking soft-deletes (deleted_at); delete is revoked like every user-data table.
create table if not exists public.person_links (
  id text primary key,
  owner_user_id text not null,
  user_id text not null,
  person_id text not null,
  kind text not null default 'SAME' check (kind in ('SAME', 'NOT_SAME')),
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  deleted_at bigint
);
create index if not exists person_links_owner_idx on public.person_links (owner_user_id);
alter table public.person_links enable row level security;
drop policy if exists person_links_owner_rw on public.person_links;
create policy person_links_owner_rw on public.person_links
  for all to authenticated
  using (owner_user_id = (select auth.uid())::text)
  with check (owner_user_id = (select auth.uid())::text);
revoke delete on public.person_links from anon, authenticated;
-- ⚠️ Deliberately NOT in the `supabase_realtime` publication, and no doorbell trigger: it has no
-- group_id, and only the owner's own devices care. The 60s pull tick carries it.

create table if not exists public.expenses (
  id text primary key,
  group_id text not null,
  kind text not null default 'EXPENSE',
  title text not null,
  notes text,
  amount_subunits bigint not null,
  currency text not null,
  expense_date text not null,
  payer_user_id text,
  payer_outside_name text,
  split_mode text not null,
  has_tax_row boolean not null default false,
  tax_subunits bigint not null default 0,
  tip_subunits bigint not null default 0,
  tip_split_mode text not null default 'PROPORTIONAL',
  gratuity_subunits bigint not null default 0,   -- "Split the bill": proportional like tax
  discount_subunits bigint not null default 0,   -- "Split the bill": proportional reduction
  other_charges_subunits bigint not null default 0, -- delivery fee / bottle deposit / card surcharge; proportional like tax
  category_id text,
  subcategory_id text,
  refund_of_expense_id text,
  is_auto_refund boolean not null default false,
  status text not null default 'ACTIVE',
  created_by text not null,
  last_editor text,                              -- vestigial (old commit_expense CAS); merge_expense uses split_updated_by
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  deleted_at bigint,
  -- Track F zone-aware merge (see merge_expense()). Zone 1 = per-field last-edited stamps so independent
  -- metadata edits coexist; Zone 2 = a causal split_version guarding the money value as one atomic unit.
  title_updated_at bigint,                       -- Zone 1
  notes_updated_at bigint,                       -- Zone 1
  category_updated_at bigint,                    -- Zone 1 (covers category_id + subcategory_id)
  date_updated_at bigint,                        -- Zone 1
  split_version bigint not null default 1,       -- Zone 2 causal version (amount/split_mode/payer/extras/shares)
  split_updated_by text                          -- who last advanced the split
);
create index if not exists expenses_group_idx on public.expenses (group_id, expense_date);

create table if not exists public.shares (
  id text primary key,
  expense_id text not null,
  user_id text not null,
  share_owed_subunits bigint not null,
  remaining_subunits bigint default 0,   -- DERIVED client-side (owed - Σ settlement allocations); kept for back-compat, no longer authoritative
  share_units integer,
  share_percentage double precision,
  share_exact_subunits bigint,
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  deleted_at bigint                        -- soft-delete: a participant removed on an expense edit is tombstoned, never hard-deleted
);
create index if not exists shares_expense_idx on public.shares (expense_id);
-- Partial unique over ACTIVE rows only, so a soft-deleted share doesn't block re-adding the same member.
create unique index if not exists shares_expense_user_active_uidx
  on public.shares (expense_id, user_id) where deleted_at is null;

create table if not exists public.settlements (
  id text primary key,
  group_id text not null,
  from_user_id text not null,
  to_user_id text not null,
  payment_currency text not null,
  payment_amount_subunits bigint not null,
  payment_app text,
  deep_link_attempted boolean not null default false,
  deep_link_succeeded boolean,
  settled_at bigint not null,
  notes text,
  created_by text not null,
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  deleted_at bigint
);
create index if not exists settlements_group_idx on public.settlements (group_id, settled_at);
-- Payment edits (one-time-splits Part B): an edit voids the payment and records a replacement. Every
-- replacement names the FIRST payment of its chain, so "one live payment per chain" is checkable no
-- matter how many times it was edited. Null on a payment that was never edited.
alter table public.settlements add column if not exists original_settlement_id text;
create index if not exists settlements_original_idx on public.settlements (original_settlement_id)
  where original_settlement_id is not null;

-- Settlement allocations: how a payment applied to specific shares. These are the GROUND TRUTH for
-- payments — the client derives each share's `remaining = owed - Σ applied` from them (and skips
-- allocations of soft-deleted settlements), so they must sync. `group_id` is denormalised for pull
-- scoping; append-only in practice (a void soft-deletes the parent settlement, excluding its rows).
create table if not exists public.settlement_allocations (
  id text primary key,
  settlement_id text not null,
  group_id text not null,
  share_id text not null,
  applied_amount_subunits bigint not null,
  applied_currency text not null,
  fx_rate_used double precision,
  fx_rate_date text,
  created_at bigint not null,
  row_version bigint not null default 1,
  unique (settlement_id, share_id)
);
create index if not exists settlement_allocations_group_idx on public.settlement_allocations (group_id);
create index if not exists settlement_allocations_settlement_idx on public.settlement_allocations (settlement_id);
create index if not exists settlement_allocations_share_idx on public.settlement_allocations (share_id);

-- "Split the bill" (itemized expenses): the line items of a bill (the creator-owned "menu"). The LINE
-- TOTAL (line_total_subunits) is the entered source of truth — it matches how receipts print ("4 …
-- $96.00") and stays penny-exact even when it doesn't divide evenly. Per-unit is DERIVED for display
-- (round(line_total / quantity)); the claim math splits the line total across claimed units via the
-- largest-remainder allocator, so no penny leaks. unit_price_subunits is vestigial (kept populated with
-- the rounded per-unit for backward compat; read line_total_subunits as truth). group_id is denormalised
-- for pull scoping. Soft-delete (Rule 1): an item removed from the bill is tombstoned so the removal syncs.
create table if not exists public.expense_items (
  id text primary key,
  expense_id text not null,
  group_id text not null,
  label text not null,
  quantity integer not null,
  unit_price_subunits bigint not null,
  line_total_subunits bigint not null default 0,
  sort_order integer not null,
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  deleted_at bigint
);
create index if not exists expense_items_expense_idx on public.expense_items (expense_id);
create index if not exists expense_items_group_idx on public.expense_items (group_id);

-- Item claims: one person's stake in an item ("I had 2 of these"). The LIVE, multi-device layer —
-- partitioned by user (each device writes only its own), so concurrent claiming is conflict-free.
-- quantity is the claimed unit count (countable) or an equal weight (shared item). Soft-delete (Rule 1).
create table if not exists public.item_claims (
  id text primary key,
  item_id text not null,
  expense_id text not null,
  group_id text not null,
  user_id text not null,
  quantity integer not null,
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  deleted_at bigint
);
create index if not exists item_claims_expense_idx on public.item_claims (expense_id);
create index if not exists item_claims_group_idx on public.item_claims (group_id);
-- Partial unique over ACTIVE rows: one live claim per (item, user); a tombstone can coexist with a re-add.
create unique index if not exists item_claims_item_user_active_uidx
  on public.item_claims (item_id, user_id) where deleted_at is null;

-- Item shares: one person's membership in a line's SHARED split (the "I split this with these people"
-- set). The active rows per item are the sharer group; the line's leftover units split evenly across it.
-- A line's sharing is a set of PORTIONS: portion_id groups a slice's members, quantity is its unit count.
-- Multiple distinct portions can coexist on one line ("2 solo, 3 solo, 1 shared, 2 left"). Legacy rows
-- (null portion_id) = one implicit all-leftover portion. Additive auto-union within a portion; added_by
-- records who put a member in; the member has final say (they can leave). Soft-delete (Rule 1).
create table if not exists public.item_shares (
  id text primary key,
  item_id text not null,
  expense_id text not null,
  group_id text not null,
  user_id text not null,
  portion_id text,
  quantity integer not null default 1,
  added_by text not null,
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  deleted_at bigint
);
create index if not exists item_shares_expense_idx on public.item_shares (expense_id);
create index if not exists item_shares_group_idx on public.item_shares (group_id);
-- A person can be in more than one portion of the same line at once (per-serving assignment: solo on
-- one serving, shared with someone else on another) — the uniqueness key is per-portion, not per-item.
-- The per-ITEM index below it replaced is already gone from this file, so a from-scratch apply is clean;
-- this drop is only for a legacy environment reapplying the file, where the old index would still be
-- live and would reject exactly the per-serving assignment the new one is here to allow.
drop index if exists item_shares_item_user_active_uidx;
create unique index if not exists item_shares_item_user_portion_active_uidx
  on public.item_shares (item_id, user_id, portion_id) where deleted_at is null;

-- Bill participants: who a "Split the bill" expense is FOR (the creator picks them). The bill surfaces to
-- each as a "claim your items" card until they've claimed. done_at is the per-person "I'm done" stamp
-- (a nudge-silencer, not a resolution of the bill). Soft-delete (Rule 1).
create table if not exists public.bill_participants (
  id text primary key,
  expense_id text not null,
  group_id text not null,
  user_id text not null,
  done_at bigint,
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  deleted_at bigint
);
create index if not exists bill_participants_expense_idx on public.bill_participants (expense_id);
create index if not exists bill_participants_group_idx on public.bill_participants (group_id);
create unique index if not exists bill_participants_expense_user_active_uidx
  on public.bill_participants (expense_id, user_id) where deleted_at is null;

create table if not exists public.conflicts (
  id text primary key,
  group_id text not null,
  expense_id text not null,
  added_user_id text not null,
  triggered_by_user_id text not null,
  created_at bigint not null,
  resolved_at bigint,
  resolution text,
  unique (expense_id, added_user_id)
);
create index if not exists conflicts_group_idx on public.conflicts (group_id);

-- Expense edit-collision conflicts: when two devices edit the same expense from the same base_version,
-- the commit_expense() RPC accepts the first (canonical) and PARKS the loser here (its full rejected
-- payload), instead of silently clobbering. Surfaced in-app for a deliberate pick-a-side resolution.
create table if not exists public.expense_edit_conflicts (
  id text primary key,
  group_id text not null,
  expense_id text not null,
  base_version bigint not null,      -- the version the rejected edit was based on
  server_version bigint not null,    -- the canonical version it lost to
  rejected_by text not null,         -- actor user id of the loser (whose edit was parked)
  server_actor text,                 -- actor who wrote the canonical version (the "winner"); null on legacy rows
  rejected_expense text not null,    -- loser's full expense payload (JSON as text, so the Room wire-DTO round-trips it)
  rejected_shares text not null,     -- loser's full share set (JSON as text)
  created_at bigint not null,
  resolved_at bigint,
  resolution text,                   -- 'KEEP_CURRENT' | 'USE_REJECTED'
  resolved_by text
);
create index if not exists expense_edit_conflicts_group_idx on public.expense_edit_conflicts (group_id);
create index if not exists expense_edit_conflicts_expense_idx on public.expense_edit_conflicts (expense_id);

-- Track F: append-only audit of split edits that lost the causal guard in merge_expense() (the superseded
-- "loser"). Server-side only — NOT synced. The loser learns it was superseded from the merge_expense
-- response and shows a one-sided nudge; this table is the recoverable record. Never updated/deleted.
create table if not exists public.superseded_split_edits (
  id text primary key,
  group_id text not null,
  expense_id text not null,
  base_split_version bigint not null,     -- the split version the rejected edit was built on
  server_split_version bigint not null,   -- canonical split version at the moment of rejection
  superseded_by text,                     -- actor whose split edit was set aside
  rejected_expense text not null,         -- full rejected expense payload (JSON text)
  rejected_shares text not null,          -- full rejected share set (JSON text)
  created_at bigint not null
);
create index if not exists superseded_split_edits_expense_idx on public.superseded_split_edits (expense_id);

-- RLS (P1 #13): this append-only audit holds FULL rejected money payloads (amounts, notes, user ids).
-- It is written ONLY by merge_expense() (which runs as the authenticated caller) and read by NO client,
-- so it takes a single INSERT-only policy — NOT the permissive `_rw` loop below, which would grant the
-- SELECT/UPDATE/DELETE this table must never expose. With RLS on and no read/update/delete policy, those
-- are denied by default; the explicit REVOKE is belt-and-suspenders against a stray table grant.
alter table public.superseded_split_edits enable row level security;
drop policy if exists superseded_split_edits_insert on public.superseded_split_edits;
create policy superseded_split_edits_insert on public.superseded_split_edits
  for insert to authenticated with check (true);
revoke update, delete on public.superseded_split_edits from anon, authenticated;

-- ── Expense activity (F5): comments, receipts, append-only history ───────────────────────────────
create table if not exists public.comments (
  id text primary key,
  expense_id text not null,
  group_id text not null,
  user_id text not null,
  body text not null,
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint,
  row_version bigint not null default 1
);
create index if not exists comments_expense_idx on public.comments (expense_id);
create index if not exists comments_group_idx on public.comments (group_id);

-- Per-expense chat moderation (CHAT_MODERATION_SPEC.md): a blocker's filter over one sender's
-- messages in one expense's comment thread, not a group- or sender-scoped block. Synced wire-mirror,
-- same shape as `comments`, soft-delete tombstone doubles as "unblock". `reason` distinguishes a
-- deliberate Block from a Report (which currently performs the same write) purely for a future
-- escalation surface -- nothing reads it yet.
create table if not exists public.expense_blocked_users (
  id text primary key,                 -- "<expense_id>__<blocker_user_id>__<blocked_user_id>"
  expense_id text not null,
  group_id text not null,              -- denormalized, same reason as comments.group_id
  blocker_user_id text not null,
  blocked_user_id text not null,
  reason text not null default 'block',
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint,
  row_version bigint not null default 1
);
create index if not exists expense_blocked_users_expense_idx on public.expense_blocked_users (expense_id);
create index if not exists expense_blocked_users_group_idx on public.expense_blocked_users (group_id);
create index if not exists expense_blocked_users_blocker_idx on public.expense_blocked_users (blocker_user_id);

create table if not exists public.receipts (
  id text primary key,
  expense_id text not null,
  group_id text not null,
  uploaded_by text not null,
  storage_path text not null,
  url text,
  mime_type text not null,
  size_bytes bigint not null,
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint,
  row_version bigint not null default 1
);
create index if not exists receipts_expense_idx on public.receipts (expense_id);
create index if not exists receipts_group_idx on public.receipts (group_id);

-- Proof of payment (docs/features/payment-proof.md): a screenshot attached to a payment, so the person who
-- was paid can check it in one tap. Same shape and bucket as `receipts` (path `<group_id>/payments/<chain
-- id>/<id>.<ext>`, so the receipts bucket policies and the group purge's prefix delete already cover it).
-- A separate table, not a nullable `receipts.settlement_id`: every installed app assumes a receipt has an
-- expense. `settlement_id` is the payment's CHAIN id (`original_settlement_id ?? id`): editing a payment
-- voids it and writes a replacement, and the screenshot must survive that. One live proof per chain; a
-- replacement soft-deletes the old row (client- and web-enforced, not a constraint, because tombstones of
-- the old row and the new row can sync in either order).
create table if not exists public.payment_proofs (
  id text primary key,
  settlement_id text not null,
  group_id text not null,
  uploaded_by text not null,
  storage_path text not null,
  url text,
  mime_type text not null,
  size_bytes bigint not null,
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint,
  row_version bigint not null default 1
);
create index if not exists payment_proofs_settlement_idx on public.payment_proofs (settlement_id);
create index if not exists payment_proofs_group_idx on public.payment_proofs (group_id);

-- ── Categories (per-group, copy-on-write defaults) ───────────────────────────────────────────────
-- A group has ZERO rows here until it first customizes; until then the client uses its built-in default
-- categories. The first edit materializes the full default set (is_default=true) then mutates. `key` is
-- what an expense stores in expenses.category_id ('food' for a default, a uuid for custom); unique per
-- group among live rows. Synced wire-mirror, soft-delete tombstones.
create table if not exists public.categories (
  id text primary key,
  group_id text not null,
  key text not null,
  label text not null,
  icon text not null,
  color text not null,
  sort_order bigint not null default 0,
  is_default boolean not null default false,
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint,
  row_version bigint not null default 1
);
create index if not exists categories_group_idx on public.categories (group_id);
create unique index if not exists categories_group_key_active_idx
  on public.categories (group_id, key) where deleted_at is null;

create table if not exists public.expense_history (
  id text primary key,
  expense_id text not null,
  group_id text not null,
  actor_user_id text,
  type text not null,
  detail text,
  created_at bigint not null,
  row_version bigint not null default 1
);
create index if not exists expense_history_expense_idx on public.expense_history (expense_id);
create index if not exists expense_history_group_idx on public.expense_history (group_id);

-- ── Push: device tokens (F7) ─────────────────────────────────────────────────────────────────────
-- One row per device FCM/APNs token, keyed by the token. A server-side sender (edge function) joins
-- this to group membership to target a notification; the client only registers/refreshes its token.
create table if not exists public.device_tokens (
  id text primary key,
  user_id text not null,
  platform text not null,
  updated_at bigint not null
);
create index if not exists device_tokens_user_idx on public.device_tokens (user_id);

-- ── Receipt-OCR rate limiting ───────────────────────────────────────────────────────────────────
-- One row per successful `extract-receipt` scan. Device-local-ish in purpose (not a synced entity —
-- not in the client's SyncEngine table list), used only server-side by the edge function to enforce a
-- per-user rate limit (20 scans/hour) against the paid, Claude-vision-backed OCR endpoint.
create table if not exists public.receipt_scan_log (
  id uuid primary key default gen_random_uuid(),
  -- ON DELETE CASCADE so purge_deleted_accounts() (which deletes the auth.users row after the 30-day
  -- grace) doesn't FK-violate once scan rows exist. A rate-limit log is ephemeral operational data, not
  -- financial history, so a hard cascade is correct (P1 #12). The migration below re-adds it on live DBs.
  user_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);

-- P1 #12: existing DBs created the FK without ON DELETE — re-add it with the cascade (idempotent).
do $$
begin
  alter table public.receipt_scan_log drop constraint if exists receipt_scan_log_user_id_fkey;
  alter table public.receipt_scan_log
    add constraint receipt_scan_log_user_id_fkey
    foreign key (user_id) references auth.users(id) on delete cascade;
end $$;

alter table public.receipt_scan_log enable row level security;

drop policy if exists "own scan log" on public.receipt_scan_log;
create policy "own scan log" on public.receipt_scan_log
  for all to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid());

-- ── Cost ledger columns (Plan A) ────────────────────────────────────────────────────────────────
-- Widens the rate-limit row into the authoritative record of what each scan COST, so the freemium
-- free-scan allowance can be set from measured data. All nullable: the row is inserted BEFORE the
-- paid call (see the insert below) and only completed afterwards, so a crash mid-scan leaves these
-- null rather than absent -- completed_at null is itself a finding.
alter table public.receipt_scan_log add column if not exists group_id text references public.groups(id) on delete set null;
alter table public.receipt_scan_log add column if not exists page_count int;
alter table public.receipt_scan_log add column if not exists outcome text;
alter table public.receipt_scan_log add column if not exists tiers_used text[];
alter table public.receipt_scan_log add column if not exists input_tokens int;
alter table public.receipt_scan_log add column if not exists output_tokens int;
alter table public.receipt_scan_log add column if not exists cost_micros bigint;
alter table public.receipt_scan_log add column if not exists duration_ms int;
alter table public.receipt_scan_log add column if not exists completed_at timestamptz;
-- Diagnostics for a misread (2026-08-08). `raw_draft` is the record_receipt tool input exactly as the
-- model emitted it, before normalization: without it a postmortem can only infer what the model did, which
-- is how "why did tax come back as 0" stayed an inference. `residual_subunits` is our computed total minus
-- the receipt's printed total, so the size and direction of misreads is queryable across scans instead of
-- one screenshot at a time.
alter table public.receipt_scan_log add column if not exists raw_draft jsonb;
alter table public.receipt_scan_log add column if not exists residual_subunits bigint;
alter table public.receipt_scan_log add column if not exists verified boolean;

do $$
begin
  alter table public.receipt_scan_log drop constraint if exists receipt_scan_log_outcome_check;
  alter table public.receipt_scan_log add constraint receipt_scan_log_outcome_check
    check (outcome is null or outcome in ('ok', 'not_receipt', 'invalid_draft', 'failed', 'rate_limited', 'breaker_open'));
end $$;

-- Weekly-query view: cost and volume per user per day. Cheap to keep around, expensive to redo by hand.
-- security_invoker: a plain `create view` defaults to SECURITY DEFINER, which runs as the view owner and
-- BYPASSES the "own scan log" RLS policy above -- every authenticated user would see every other user's
-- cost data. security_invoker makes the view run as the querying user instead, so RLS still applies.
create or replace view public.receipt_scan_cost_daily
  with (security_invoker = true) as
select
  user_id,
  date_trunc('day', created_at) as day,
  count(*) as scans,
  count(*) filter (where outcome = 'ok') as scans_ok,
  count(*) filter (where array_length(tiers_used, 1) > 1) as scans_escalated,
  sum(coalesce(input_tokens, 0)) as input_tokens,
  sum(coalesce(output_tokens, 0)) as output_tokens,
  sum(coalesce(cost_micros, 0)) as cost_micros
from public.receipt_scan_log
group by user_id, date_trunc('day', created_at);

-- Org-wide circuit breaker for the Opus tier of the extract-receipt cascade (Haiku -> Sonnet -> Opus).
-- Opus is the priciest model in the cascade; this table lets the edge function cap total Opus spend
-- across ALL users in a rolling window, independent of the per-user receipt_scan_log limit above. Only
-- the edge function (service role) reads/writes this table -- RLS is enabled with NO policies, so no
-- client role can read or write it at all (service role bypasses RLS regardless).
create table if not exists public.receipt_opus_escalations (
  id uuid primary key default gen_random_uuid(),
  -- Audit trail only (who triggered the priciest tier) -- the circuit-breaker check itself just counts
  -- rows, it doesn't filter by user. ON DELETE SET NULL so account deletion doesn't need to touch this
  -- operational log.
  user_id uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);

alter table public.receipt_opus_escalations enable row level security;
-- Deliberately no policies: no authenticated/anon client should ever read or write this table.

-- Cooldown log for edge-function-originated ops alerts (Slack today), so a burst of identical
-- failures (e.g. every scan while ANTHROPIC_API_KEY is broken) posts once per cooldown window per
-- `kind`, not once per request. Only the edge function (service role) reads/writes this table -- RLS
-- is enabled with NO policies, so no client role can read or write it at all.
create table if not exists public.ops_alerts (
  id uuid primary key default gen_random_uuid(),
  kind text not null,
  sent_at timestamptz not null default now()
);

create index if not exists ops_alerts_kind_sent_at_idx on public.ops_alerts (kind, sent_at desc);

alter table public.ops_alerts enable row level security;
-- Deliberately no policies: no authenticated/anon client should ever read or write this table.

-- ── Evenly Pro: purchases the webhook could not attribute (PRO_PASS_SPEC.md §6.3) ───────────────
-- A NON_RENEWING_PURCHASE arrives with no `evenly_group_id` subscriber attribute, so nothing on the
-- server can say which group the money was for. The group binding exists nowhere in the store's data
-- model, so there is nothing to derive it from and **guessing is not an option** — picking a group for
-- someone would hand a different set of people a paid entitlement.
--
-- Parked here for a human instead, with the whole event body kept: a support conversation needs the
-- receipt, not our summary of it. Resolved by hand (activate for the right group, or refund), then
-- stamped. Service-role only, like every other ops table here.
create table if not exists public.pro_orphan_purchases (
  id            uuid primary key default gen_random_uuid(),
  store         text not null,
  store_txn_id  text not null,
  app_user_id   text not null,
  product_id    text,
  reason        text not null,
  payload       jsonb not null,
  created_at    bigint not null,
  resolved_at   bigint
);

-- The same idempotency key as group_passes, for the same reason: RevenueCat retries a webhook it did
-- not get a 2xx for, and one unattributable charge must not become five rows for a human to read.
create unique index if not exists pro_orphan_purchases_txn_idx
  on public.pro_orphan_purchases (store, store_txn_id);
create index if not exists pro_orphan_purchases_open_idx
  on public.pro_orphan_purchases (created_at desc) where resolved_at is null;

alter table public.pro_orphan_purchases enable row level security;
-- Deliberately no policies: it holds a stranger's purchase record and no client has business reading it.
revoke all on public.pro_orphan_purchases from anon, authenticated;

-- ── Row-Level Security: membership-scoped ───────────────────────────────────────────────────────
-- A row is reachable only by ACTIVE members of its group. This replaced a `for all to authenticated
-- using (true) with check (true)` loop over these same 20 tables, under which any signed-in account
-- could read, overwrite, or delete every other group's ledger.
--
-- ⚠️ If you are adding a table here, do NOT reintroduce a permissive policy "just for testing".
-- Permissive policies OR together, so a single surviving `using (true)` silently defeats every policy
-- in this section.
--
-- "Member" means status = 'ACTIVE', matching `SyncEngine.activeGroupIds` (a departed member's device
-- already stops pulling the group) and the four policies that predate this section.
--
-- NOTE: no policy here filters `deleted_at is null`, deliberately. Deletions travel as soft-deleted
-- rows; hiding tombstones would stop deletions reaching other devices and the data would resurrect on
-- the next pull (AGENTS.md §4.5).

-- SECURITY DEFINER is load-bearing: a membership policy ON `members` that selects FROM `members`
-- recurses infinitely. A definer function is not subject to the caller's policies, so it terminates.
-- `search_path` is pinned because a definer function with a mutable one lets the caller shadow
-- `members` with a relation of their own.
--
-- `(select auth.uid())` rather than a bare `auth.uid()`: the scalar subquery is evaluated once per
-- statement instead of once per row, which is the difference between a usable sync and an unusable one.
create or replace function public.is_group_member(p_group_id text)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select exists (
    select 1
    from public.members m
    where m.group_id = p_group_id
      and m.user_id = (select auth.uid())::text
      and m.status = 'ACTIVE'
  );
$$;

-- `shares` is the ONLY app table with no `group_id`, so it reaches the group through its parent
-- expense. Kept as a definer helper so the join lives in one place and `expenses`' own RLS is not
-- re-evaluated inside `shares`' policy.
create or replace function public.can_access_expense(p_expense_id text)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select exists (
    select 1
    from public.expenses e
    join public.members m on m.group_id = e.group_id
    where e.id = p_expense_id
      and m.user_id = (select auth.uid())::text
      and m.status = 'ACTIVE'
  );
$$;

revoke all on function public.is_group_member(text) from public, anon;
revoke all on function public.can_access_expense(text) from public, anon;
grant execute on function public.is_group_member(text) to authenticated;
grant execute on function public.can_access_expense(text) to authenticated;

-- Every policy below drives off (user_id, group_id) with status ACTIVE. `members_user_idx` alone makes
-- that a scan of every group the user belongs to; this makes it one index lookup.
create index if not exists members_user_group_active_idx
  on public.members (user_id, group_id) where status = 'ACTIVE';

-- Retire the permissive policies. These must be DROPPED, not merely supplemented.
do $$
declare t text;
begin
  foreach t in array array[
    'users','groups','members','expenses','shares','settlements','settlement_allocations',
    'conflicts','expense_edit_conflicts','comments','expense_blocked_users','receipts','categories',
    'expense_history','device_tokens','expense_items','item_claims','item_shares',
    'bill_participants','payment_proofs'
  ]
  loop
    execute format('alter table public.%I enable row level security;', t);
    execute format('drop policy if exists %I on public.%I;', t || '_rw', t);
  end loop;
end $$;

-- The plainly group-scoped tables: each carries its own `not null group_id`, so each is one helper
-- call against an indexed column. `pending_item_edits` belongs to this set but is created ~1100 lines
-- below, so it repeats this exact policy shape at its own definition rather than here.
do $$
declare t text;
begin
  foreach t in array array[
    'expenses','settlements','settlement_allocations','conflicts','expense_edit_conflicts',
    'comments','expense_blocked_users','receipts','categories','expense_history',
    'expense_items','item_claims','item_shares','bill_participants','payment_proofs'
  ]
  loop
    execute format('drop policy if exists %I on public.%I;', t || '_member_rw', t);
    execute format(
      'create policy %I on public.%I for all to authenticated '
      'using (public.is_group_member(group_id)) '
      'with check (public.is_group_member(group_id));',
      t || '_member_rw', t
    );
  end loop;
end $$;

-- The INSERT arm is why this is not a plain `is_group_member(id)`: `SyncEngine.push` sends the roster
-- as users → groups → members, so when a newly created group is inserted its creator still has no
-- membership row on the server. `created_by` is the only thing that can vouch for that write.
drop policy if exists groups_member_rw on public.groups;
create policy groups_member_rw on public.groups
  for all to authenticated
  using (public.is_group_member(id))
  with check (
    public.is_group_member(id)
    or created_by = (select auth.uid())::text
  );

-- The `user_id = auth.uid()` arm carries three flows the membership arm cannot:
--   read   : `SyncEngine.pull` step 1 selects this table by user_id BEFORE it knows any group, and
--            deliberately lands the LEFT rows too — `is_group_member` is false for those.
--   insert : joining a group. The membership being created is the thing being checked, so nothing else
--            could authorise it. Costs: anyone holding a group's id (UUIDv7, ~74 random bits, never
--            exposed to non-members) can add themselves. That is the join mechanism.
--   update : leaving. A self-leave writes status = 'LEFT', which the membership arm would reject.
drop policy if exists members_member_rw on public.members;
create policy members_member_rw on public.members
  for all to authenticated
  using (
    public.is_group_member(group_id)
    or user_id = (select auth.uid())::text
  )
  with check (
    public.is_group_member(group_id)
    or user_id = (select auth.uid())::text
  );

-- `merge_expense` is SECURITY INVOKER, so this policy is evaluated inside it. `can_access_expense` is
-- a definer function and therefore sees the expense row the same transaction just inserted.
drop policy if exists shares_member_rw on public.shares;
create policy shares_member_rw on public.shares
  for all to authenticated
  using (public.can_access_expense(expense_id))
  with check (public.can_access_expense(expense_id));

-- Read: myself, or anyone I share a group with. Scoping this to `id = auth.uid()` would make every
-- co-member and every placeholder invisible and blank out every name in the app.
--
-- `them` is deliberately NOT filtered to ACTIVE while `me` is: a claimed placeholder and a departed
-- member are both soft-left, and their names still have to resolve on historical expenses and shares.
--
-- Write: my own profile, or a placeholder belonging to a group I am in (creating and renaming
-- placeholders are ordinary group actions). This arm closes the sharpest hole in the old policy —
-- under `using (true)` any signed-in account could rewrite anyone's payment handles, which is a route
-- to being paid in their place.
drop policy if exists users_member_rw on public.users;
create policy users_member_rw on public.users
  for all to authenticated
  using (
    id = (select auth.uid())::text
    or exists (
      select 1
      from public.members me
      join public.members them on them.group_id = me.group_id
      where me.user_id = (select auth.uid())::text
        and me.status = 'ACTIVE'
        and them.user_id = users.id
    )
  )
  with check (
    id = (select auth.uid())::text
    or (is_placeholder and public.is_group_member(placeholder_group_id))
  );

-- Per-user, not per-group: an FCM/APNs token is a route to one person's lock screen and has no group
-- scope at all. `PushController` only ever writes the signed-in user's own row.
drop policy if exists device_tokens_member_rw on public.device_tokens;
create policy device_tokens_member_rw on public.device_tokens
  for all to authenticated
  using (user_id = (select auth.uid())::text)
  with check (user_id = (select auth.uid())::text);

-- ⚠️ RLS DOES NOT APPLY TO TRUNCATE. Supabase grants ALL on every new public table to
-- anon/authenticated, so without this sweep every policy above is one `TRUNCATE` away from
-- irrelevant. Re-run it after adding a table.
revoke truncate on all tables in schema public from anon, authenticated;

-- ── Join-by-link: the one read that CANNOT be membership-scoped ──────────────────────────────────
-- `GroupRepositoryImpl.joinByToken` resolves an invite token against `groups` for a user who is not a
-- member yet, by definition. An RLS predicate cannot see the query's WHERE clause, so no policy can
-- express "allow this row because they supplied its token" — any policy permitting that read permits
-- reading every group in the database. Hence a definer RPC: the token match happens inside the
-- function, where it IS the authorisation check rather than a filter the caller chose.
--
-- It leaks nothing the token does not already grant, and the token is a UUIDv7 (~74 random bits), so
-- it is not enumerable. Returns the whole row so the client decodes `GroupEntity` unchanged.
-- `SupabaseRemoteGroupGateway.resolveByToken` is the only caller; a plain select there returns zero
-- rows now and would silently break cross-device join.
create or replace function public.resolve_group_by_invite_token(p_token text)
returns setof public.groups
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select g.*
  from public.groups g
  where g.invite_token = p_token
    and g.deleted_at is null
  limit 1;
$$;

revoke all on function public.resolve_group_by_invite_token(text) from public, anon;
grant execute on function public.resolve_group_by_invite_token(text) to authenticated;

-- ── Invite preview: the group NAME for a live invite token, nothing else ─────────────────────────
-- The `/j/<token>` web landing ("You're invited to <group> on Evenly") has no Supabase session and the
-- web bundle ships no Supabase client or key (web/AGENTS.md), so it reaches this only through the
-- `web-claim` edge function's `invite-preview` action, which holds the service key and rate-limits per
-- caller. Returns ONE text column: no member list, no other group field, no row-shaped result a future
-- `select *` could widen. NULL for unknown, malformed and deleted-group tokens alike, so the response
-- cannot be used to tell "no such token" from "deleted group". Executable by service_role only: granting
-- it to anon would let anyone hit it straight through PostgREST without the edge function's rate limit.
create or replace function public.invite_preview_group_name(p_token text)
returns text
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select g.name
  from public.groups g
  where g.invite_token = p_token
    and g.deleted_at is null
  limit 1;
$$;

revoke all on function public.invite_preview_group_name(text) from public, anon, authenticated;
grant execute on function public.invite_preview_group_name(text) to service_role;

-- ── Realtime: the per-group DOORBELL (replaces per-table CDC) ────────────────────────────────────
-- The client IGNORES realtime payloads: an event only ever means "something changed, pull now".
-- Publishing the 16 app tables therefore fanned out one message PER ROW PER CONNECTED CLIENT for no
-- benefit — combined with a blind full-table re-push it burned 13.9M messages against a 5M quota.
-- Instead: ONE tiny row per group, bumped by statement-level triggers, is the ONLY published table,
-- and its own membership RLS policy scopes delivery to that group's members. A sync cycle now costs
-- ~1 message per online member instead of ~rows × every connected client.
--
-- ⚠️ Do NOT re-add the app tables to `supabase_realtime`. That single line is what the overage was.

create table if not exists public.group_activity (
  group_id   text primary key,
  bumped_at  timestamptz not null default now()
);

alter table public.group_activity enable row level security;
grant select on public.group_activity to authenticated;

-- Membership-scoped: WALRUS evaluates this per subscriber, so a bump reaches only that group's
-- members (verified: insider sees it, outsider sees nothing). No insert/update policy exists, so a
-- client can never ring the doorbell itself — only the SECURITY DEFINER triggers below.
drop policy if exists group_activity_member_read on public.group_activity;
create policy group_activity_member_read on public.group_activity
  for select to authenticated
  using (
    exists (
      select 1 from public.members m
      where m.group_id = group_activity.group_id
        and m.user_id = (select auth.uid())::text
    )
  );

-- Two variants: PL/pgSQL cannot reference a transition table from dynamic SQL, and `groups` keys on
-- `id` while every other table keys on `group_id`.
--
-- SECURITY DEFINER: the definer bypasses the read-only RLS above to write the bump.
-- Exception-swallowed: a doorbell failure must NEVER abort the user's real write (Rule 1 territory —
-- a missed bump costs at most 60s of latency via the client's fallback tick; a lost expense is
-- unrecoverable). Verified by test: a forced doorbell failure leaves the user's insert committed.
-- The `is distinct from` arm: now() is constant within a transaction, so a multi-statement writer
-- (merge_expense fires 2–3 times) collapses to exactly ONE WAL record per group per transaction.
create or replace function public.bump_group_activity()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  begin
    insert into public.group_activity (group_id, bumped_at)
    select distinct n.group_id, now() from new_rows n where n.group_id is not null
    on conflict (group_id) do update set bumped_at = excluded.bumped_at
      where group_activity.bumped_at is distinct from excluded.bumped_at;
  exception when others then
    null;
  end;
  return null;
end;
$$;

create or replace function public.bump_group_activity_groups()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  begin
    insert into public.group_activity (group_id, bumped_at)
    select distinct n.id, now() from new_rows n where n.id is not null
    on conflict (group_id) do update set bumped_at = excluded.bumped_at
      where group_activity.bumped_at is distinct from excluded.bumped_at;
  exception when others then
    null;
  end;
  return null;
end;
$$;

-- AFTER INSERT + AFTER UPDATE as a PAIR: declaring a transition table forbids a combined
-- `insert or update` trigger. A PostgREST bulk upsert fires both, with inserted vs conflict-updated
-- rows split correctly across the two transition tables. No DELETE trigger — everything soft-deletes.
--
-- `shares` is deliberately ABSENT: it carries no group_id (the generic body would raise 42703 and,
-- but for the exception handler, abort merge_expense). Safe — shares are only ever written
-- server-side inside merge_expense / _replace_expense_shares, which always update `expenses` in the
-- same transaction, and that bump already covers the change. `users` has no group scope and has
-- never been published (profile renames propagate via pull).
do $$
declare t text;
begin
  foreach t in array array[
    'members','expenses','settlements','settlement_allocations','conflicts',
    'expense_edit_conflicts','comments','receipts','categories','expense_history',
    'expense_items','item_claims','item_shares','bill_participants','payment_proofs'
  ]
  loop
    execute format('drop trigger if exists bump_activity_ins on public.%I;', t);
    execute format('drop trigger if exists bump_activity_upd on public.%I;', t);
    execute format(
      'create trigger bump_activity_ins after insert on public.%I '
      'referencing new table as new_rows for each statement '
      'execute function public.bump_group_activity();', t);
    execute format(
      'create trigger bump_activity_upd after update on public.%I '
      'referencing new table as new_rows for each statement '
      'execute function public.bump_group_activity();', t);
  end loop;
end $$;

drop trigger if exists bump_activity_ins on public.groups;
drop trigger if exists bump_activity_upd on public.groups;
create trigger bump_activity_ins after insert on public.groups
  referencing new table as new_rows for each statement
  execute function public.bump_group_activity_groups();
create trigger bump_activity_upd after update on public.groups
  referencing new table as new_rows for each statement
  execute function public.bump_group_activity_groups();

-- Publish ONLY the doorbell, and unpublish every app table. Older already-installed clients
-- subscribe schema-wide, so they still receive doorbells and still pull — no forced upgrade.
do $$
begin
  begin
    execute 'alter publication supabase_realtime add table public.group_activity';
  exception when duplicate_object then null;
  end;
end $$;

do $$
declare t text;
begin
  foreach t in array array[
    'groups','members','expenses','shares','settlements','settlement_allocations','conflicts',
    'expense_edit_conflicts','comments','receipts','categories','expense_history','expense_items',
    'item_claims','item_shares','bill_participants'
  ]
  loop
    begin
      execute format('alter publication supabase_realtime drop table public.%I;', t);
    exception
      when undefined_object then null;
      when others then null;
    end;
  end loop;
end $$;

-- ── Storage: the `receipts` bucket (F5) ──────────────────────────────────────────────────────────
-- Receipt bytes live in a PUBLIC bucket so the client's `publicUrl(path)` renders without signing.
--
-- ⚠️ OPEN, and NOT closed by the membership-scoped RLS above: `public = true` means the four
-- `storage.objects` policies below are decorative. Anyone holding (or guessing) an object URL reads
-- that receipt photo without authenticating at all — a receipt carries names, amounts, and often a
-- card's last four. Closing it means a private bucket plus signed URLs, which is a client change
-- (`publicUrl` has no expiry to renew), so it is deliberately out of scope here rather than
-- half-done. This is the last known read hole in the project.
insert into storage.buckets (id, name, public)
values ('receipts', 'receipts', true)
on conflict (id) do update set public = true;

do $$
begin
  drop policy if exists receipts_obj_select on storage.objects;
  create policy receipts_obj_select on storage.objects for select to authenticated using (bucket_id = 'receipts');
  drop policy if exists receipts_obj_insert on storage.objects;
  create policy receipts_obj_insert on storage.objects for insert to authenticated with check (bucket_id = 'receipts');
  drop policy if exists receipts_obj_update on storage.objects;
  create policy receipts_obj_update on storage.objects for update to authenticated using (bucket_id = 'receipts');
  drop policy if exists receipts_obj_delete on storage.objects;
  create policy receipts_obj_delete on storage.objects for delete to authenticated using (bucket_id = 'receipts');
end $$;

-- ── Account deletion: request → 30-day grace → nightly anonymizing purge (Rule 9) ────────────────
-- Replaces the old hard-delete `delete_my_account()` (Play Store "Delete account URL" requirement;
-- rationale in `data/AGENTS.md` Rule 9). These definitions were applied to the live project on
-- 2026-08-08 and folded back into this file on 2026-08-16 (review finding B1) — the file had kept
-- shipping the superseded function while the app called RPCs the file never mentioned, so a
-- from-scratch apply produced a database where account deletion 404s at the first tap.

-- The two columns the flow rides on. Additive per §"Applying changes"; nullable, so no defaults race.
alter table public.users add column if not exists deletion_requested_at bigint;
alter table public.users add column if not exists deleted_at bigint;

-- Sign-in email uniqueness (review B3): the Room mirror has always declared UNIQUE on email and its
-- KDoc claimed the server enforced it — now it does. Partial over non-null so placeholders (no email)
-- stay unlimited; lower() matches the client's COLLATE NOCASE lookup semantics for ASCII emails.
create unique index if not exists users_email_lower_uidx
  on public.users (lower(email)) where email is not null;

-- Stamp only — nothing is deleted yet, so the request is cancellable for the whole grace period.
-- Returns the epoch-ms instant the purge becomes eligible, for the client's "deletes on <date>" copy.
create or replace function public.request_account_deletion()
returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
  uid text := auth.uid()::text;
  requested_at bigint := (extract(epoch from now()) * 1000)::bigint;
  purge_at bigint;
  grace_period_ms constant bigint := 30::bigint * 24 * 60 * 60 * 1000;
begin
  if uid is null then
    raise exception 'not authenticated';
  end if;
  update public.users
    set deletion_requested_at = requested_at, updated_at = requested_at
    where id = uid and deleted_at is null
    returning deletion_requested_at + grace_period_ms into purge_at;
  if purge_at is null then
    raise exception 'account not found or already deleted';
  end if;
  return purge_at;
end;
$$;
revoke all on function public.request_account_deletion() from public, anon;
grant execute on function public.request_account_deletion() to authenticated;

create or replace function public.cancel_account_deletion()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare uid text := auth.uid()::text;
begin
  if uid is null then
    raise exception 'not authenticated';
  end if;
  update public.users
    set deletion_requested_at = null, updated_at = (extract(epoch from now()) * 1000)::bigint
    where id = uid and deleted_at is null;
end;
$$;
revoke all on function public.cancel_account_deletion() from public, anon;
grant execute on function public.cancel_account_deletion() to authenticated;

-- The nightly purge: anonymize (never row-delete) every account whose grace period has elapsed.
-- Admin is handed off to the longest-tenured other ACTIVE member first; memberships soft-leave;
-- device_tokens (ephemeral, non-financial) hard-delete; the auth.users credential is removed last.
-- Each target runs in its own exception block so one bad row can't abort the whole batch.
-- The day-count seeds as an explicit ::bigint — 30 * 24 * 60 * 60 * 1000 overflows int4 before
-- Postgres promotes it. Service-role/cron only: no caller context, and EXECUTE is revoked below.
create or replace function public.purge_deleted_accounts()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  target_id text;
  grp record;
  now_ms bigint;
  grace_period_ms constant bigint := 30::bigint * 24 * 60 * 60 * 1000;
begin
  now_ms := (extract(epoch from now()) * 1000)::bigint;
  for target_id in
    select id from public.users
    where deletion_requested_at is not null
      and deletion_requested_at < now_ms - grace_period_ms
      and deleted_at is null
  loop
    begin
      for grp in
        select g.id as group_id, m2.user_id as new_admin
        from public.members m1
        join public.groups g on g.id = m1.group_id
        join lateral (
          select user_id from public.members m2
          where m2.group_id = m1.group_id and m2.status = 'ACTIVE' and m2.user_id <> target_id
          order by m2.joined_at asc
          limit 1
        ) m2 on true
        where m1.user_id = target_id and m1.status = 'ACTIVE' and g.admin_user_id = target_id
      loop
        update public.groups set admin_user_id = grp.new_admin, updated_at = now_ms
          where id = grp.group_id;
        update public.members set is_admin = true, updated_at = now_ms
          where user_id = grp.new_admin and group_id = grp.group_id;
      end loop;

      update public.members
        set status = 'LEFT', left_at = now_ms, updated_at = now_ms
        where user_id = target_id and status = 'ACTIVE';

      delete from public.device_tokens where user_id = target_id;

      update public.users
        set display_name = 'Deleted user',
            email = null,
            avatar_url = null,
            venmo_handle = null,
            cashapp_handle = null,
            paypal_handle = null,
            zelle_handle = null,
            preferred_payment_app = null,
            deleted_at = now_ms,
            updated_at = now_ms
        where id = target_id;

      delete from auth.users where id = target_id::uuid;
    exception when others then
      raise warning 'purge_deleted_accounts: failed for user %: %', target_id, sqlerrm;
    end;
  end loop;
end;
$$;
revoke all on function public.purge_deleted_accounts() from public, anon, authenticated;

-- Nightly at 03:00 UTC. Guarded so the file still applies on a project without pg_cron enabled;
-- cron.schedule upserts by jobname, so re-running this file never duplicates the job.
do $$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform cron.schedule('purge-deleted-accounts', '0 3 * * *', 'select public.purge_deleted_accounts()');
  else
    raise notice 'pg_cron not installed - schedule purge-deleted-accounts manually';
  end if;
end $$;

-- DESTRUCTIVE OPERATION: removes a callable grant, zero rows touched.
-- Pre-checks (true as of 2026-08-16): no app version ever shipped calling delete_my_account (the
-- shipped client calls request/cancel_account_deletion only — grep SupabaseAuthSession.kt); the live
-- project already has no such function (verified via pg_proc, it was dropped when the RPCs above were
-- applied on 2026-08-08). Recovery: recreate from git history of this file.
drop function if exists public.delete_my_account();

-- ── Optimistic-concurrency commit for expenses (versioning + parked conflicts) ───────────────────
-- The client routes every expense create/edit through commit_expense() instead of a blind upsert.
-- It compares the caller's base_version against the canonical row_version under a row lock, so the
-- FIRST writer to advance base->base+1 wins; a stale writer's payload is PARKED in
-- expense_edit_conflicts and the canonical row is left untouched (no silent last-write-wins clobber).
-- Outcome is order-independent: swap who commits first and you still get one canonical row + one
-- parked conflict. Shares are replaced atomically with the expense — removed participants are
-- soft-deleted (Rule 1), never hard-deleted, so the tombstone propagates on the next pull.
-- Defaults for `shares`, same contract as _expense_defaults() below: adding a defaulted column to
-- `shares` means adding it here in the same migration.
create or replace function public._share_defaults()
returns public.shares
language plpgsql
stable
as $$
declare r public.shares;
begin
  r.remaining_subunits := 0;
  r.row_version        := 1;
  return r;
end;
$$;

-- These four helpers are pure and fully schema-qualified inside, so pinning an EMPTY search_path costs
-- nothing and keeps them off the `function_search_path_mutable` advisor. Do the same for any new one.
alter function public._clamp_client_ts(bigint)      set search_path = '';
alter function public._clamp_expense_payload(jsonb) set search_path = '';
alter function public._expense_defaults()           set search_path = '';
alter function public._share_defaults()             set search_path = '';

create or replace function public._replace_expense_shares(p_expense_id text, p_shares jsonb, p_now bigint)
returns void language plpgsql as $$
declare
  v_ids text[];
begin
  select coalesce(array_agg(s->>'id'), array[]::text[]) into v_ids
    from jsonb_array_elements(p_shares) s;

  update public.shares
     set deleted_at = p_now, updated_at = p_now, row_version = row_version + 1
   where expense_id = p_expense_id
     and deleted_at is null
     and id <> all(v_ids);

  -- Base row, not null: see _expense_defaults() for why. `shares.row_version` is `not null default 1`,
  -- so a payload without it becomes an explicit NULL and the whole share set fails to insert.
  insert into public.shares as sh
    select * from jsonb_populate_recordset(public._share_defaults(), p_shares)
  on conflict (id) do update set
    user_id              = excluded.user_id,
    share_owed_subunits  = excluded.share_owed_subunits,
    share_units          = excluded.share_units,
    share_percentage     = excluded.share_percentage,
    share_exact_subunits = excluded.share_exact_subunits,
    updated_at           = excluded.updated_at,
    deleted_at           = excluded.deleted_at,
    row_version          = sh.row_version + 1;
end;
$$;

-- ── Client clocks are UNTRUSTED input ───────────────────────────────────────────────────────────
-- Every timestamp in `commit_expense` / `merge_expense` arrives inside the client payload, and neither
-- RPC authenticates `p_actor`. A device with a wound-forward clock (or a crafted payload) that stamps
-- `title_updated_at = 2099` wins `greatest(...)` forever: the field silently stops accepting any later
-- edit from anybody, with nothing on screen to say why. Clamping to the SERVER clock is what makes a
-- bad clock a bounded annoyance instead of permanent damage.
--
-- The 60s of slack is deliberate: it absorbs ordinary device skew, and the client's own last-write-wins
-- guards compare against these values, so clamping to exactly `now()` would make a marginally-fast
-- phone lose its own writes. Ties still go to whoever the causal `split_version` says, not the clock.
create or replace function public._clamp_client_ts(p_ts bigint)
returns bigint
language sql
stable  -- NOT immutable: it reads now(), which is fixed per transaction but not across them.
as $$
  select least(coalesce(p_ts, 0), (extract(epoch from now()) * 1000)::bigint + 60000);
$$;

-- Clamp the timestamps INSIDE the payload, so the create path is covered too.
--
-- Clamping only at the point of comparison is not enough and was the first version of this fix: the
-- `not found` branch of both RPCs inserts via `jsonb_populate_record`, which copies `title_updated_at`
-- and friends straight out of the payload. A poisoned stamp on a brand-NEW expense therefore sailed
-- past a clamp that only guarded the merge branch, and the field was locked from birth. Sanitising the
-- jsonb once, before anything reads it, is what makes that impossible to get wrong again.
--
-- Keys absent from the payload stay absent — this must not resurrect the NULL-over-default bug that
-- `_expense_defaults()` exists to fix, so it only rewrites keys that are actually present.
create or replace function public._clamp_expense_payload(p_expense jsonb)
returns jsonb
language sql
stable
as $$
  select coalesce(
    (select jsonb_object_agg(
              key,
              case when key in ('updated_at', 'created_at', 'deleted_at',
                                'title_updated_at', 'notes_updated_at',
                                'category_updated_at', 'date_updated_at')
                    and jsonb_typeof(value) = 'number'
                   then to_jsonb(public._clamp_client_ts((value #>> '{}')::bigint))
                   else value
              end)
       from jsonb_each(p_expense)),
    p_expense);
$$;

-- ── The base row `jsonb_populate_record` fills in from ──────────────────────────────────────────
-- `jsonb_populate_record(base, payload)` takes each field from `payload` when the key is PRESENT and
-- from `base` when it is absent. Both expense RPCs used to pass `null::public.expenses`, so every
-- column the client did not send became an explicit NULL — the column default never ran.
--
-- Every defaulted column on `expenses` is `not null default X`, so that NULL is not a quiet wrong
-- number: it is a not-null violation that fails the INSERT and takes that expense's sync down
-- entirely. Which is the additive-migration promise broken exactly where it is relied on most: add a
-- column server-side first (as the rule requires), and every client that has not shipped the matching
-- field yet stops being able to create expenses at all. Before finding #25 that surfaced as "you're
-- offline".
--
-- **Adding a column with a default to `expenses` means adding it here, in the same migration.** That
-- is the whole maintenance burden of this function, and it is why the defaults are spelled out by name
-- rather than derived positionally from the catalog.
create or replace function public._expense_defaults()
returns public.expenses
language plpgsql
stable
as $$
declare r public.expenses;
begin
  r.kind                   := 'EXPENSE';
  r.has_tax_row            := false;
  r.tax_subunits           := 0;
  r.tip_subunits           := 0;
  r.tip_split_mode         := 'PROPORTIONAL';
  r.gratuity_subunits      := 0;
  r.discount_subunits      := 0;
  r.other_charges_subunits := 0;
  r.is_auto_refund         := false;
  r.status                 := 'ACTIVE';
  r.row_version            := 1;
  r.split_version          := 1;
  return r;
end;
$$;

-- On every write we stamp `last_editor = p_actor` so a parked conflict can name the winner. Before
-- parking, we suppress a *no-op* edit (one materially identical to canonical) so a stale re-push or two
-- converged edits don't surface a pointless self-conflict; a genuine divergence records `server_actor`.
create or replace function public.commit_expense(
  p_expense jsonb,
  p_shares jsonb,
  p_base_version bigint,
  p_actor text
) returns jsonb
language plpgsql
as $$
declare
  -- Sanitise the client's clocks ONCE, before anything reads them (#19). Everything below uses
  -- v_exp, never p_expense, so the create path gets the same clamp as the merge path.
  v_exp jsonb := public._clamp_expense_payload(p_expense);
  v_id text := v_exp->>'id';
  v_group_id text := v_exp->>'group_id';
  v_now bigint := coalesce((v_exp->>'updated_at')::bigint, 0);
  v_current public.expenses%rowtype;
  v_new_version bigint;
  v_conflict_id text;
  v_current_shares jsonb;
  v_incoming_shares jsonb;
  v_same boolean;
begin
  select * into v_current from public.expenses where id = v_id for update;

  if not found then
    insert into public.expenses
      select * from jsonb_populate_record(public._expense_defaults(), v_exp);
    update public.expenses set last_editor = p_actor where id = v_id;
    perform public._replace_expense_shares(v_id, p_shares, v_now);
    return jsonb_build_object('status', 'created', 'version', coalesce((v_exp->>'row_version')::bigint, 1));
  end if;

  if v_current.row_version = p_base_version and v_current.deleted_at is null then
    v_new_version := p_base_version + 1;
    update public.expenses set
      title              = v_exp->>'title',
      notes              = v_exp->>'notes',
      amount_subunits    = (v_exp->>'amount_subunits')::bigint,
      currency           = v_exp->>'currency',
      expense_date       = v_exp->>'expense_date',
      payer_user_id      = v_exp->>'payer_user_id',
      payer_outside_name = v_exp->>'payer_outside_name',
      split_mode         = v_exp->>'split_mode',
      category_id        = v_exp->>'category_id',
      subcategory_id     = v_exp->>'subcategory_id',
      updated_at         = v_now,
      row_version        = v_new_version,
      last_editor        = p_actor
    where id = v_id;
    perform public._replace_expense_shares(v_id, p_shares, v_now);
    return jsonb_build_object('status', 'committed', 'version', v_new_version);
  end if;

  -- Base is stale (canonical advanced past p_base_version). Before parking, compare the incoming
  -- payload to canonical: if the scalar fields AND the active share split are all identical, the two
  -- edits converged (or this is a stale re-push of an already-applied change). Parking it would surface
  -- a pointless "you edited this while you did too" card with the same numbers on both sides, so return
  -- 'noop' and let the client silently adopt canonical instead.
  select coalesce(jsonb_object_agg(user_id, share_owed_subunits), '{}'::jsonb)
    into v_current_shares
    from public.shares where expense_id = v_id and deleted_at is null;
  select coalesce(jsonb_object_agg(s->>'user_id', (s->>'share_owed_subunits')::bigint), '{}'::jsonb)
    into v_incoming_shares
    from jsonb_array_elements(p_shares) s
    where s->>'deleted_at' is null;

  v_same := v_current.deleted_at is null
    and v_current.title              is not distinct from v_exp->>'title'
    and v_current.notes              is not distinct from v_exp->>'notes'
    and v_current.amount_subunits    is not distinct from (v_exp->>'amount_subunits')::bigint
    and v_current.currency           is not distinct from v_exp->>'currency'
    and v_current.expense_date       is not distinct from v_exp->>'expense_date'
    and v_current.payer_user_id      is not distinct from v_exp->>'payer_user_id'
    and v_current.payer_outside_name is not distinct from v_exp->>'payer_outside_name'
    and v_current.split_mode         is not distinct from v_exp->>'split_mode'
    and v_current.category_id        is not distinct from v_exp->>'category_id'
    and v_current.subcategory_id     is not distinct from v_exp->>'subcategory_id'
    and v_current_shares = v_incoming_shares;

  if v_same then
    return jsonb_build_object('status', 'noop', 'version', v_current.row_version);
  end if;

  v_conflict_id := v_id || ':' || p_base_version::text || ':' || p_actor;
  insert into public.expense_edit_conflicts(
    id, group_id, expense_id, base_version, server_version, rejected_by, server_actor,
    rejected_expense, rejected_shares, created_at)
  values (
    v_conflict_id, v_group_id, v_id, p_base_version, v_current.row_version, p_actor, v_current.last_editor,
    v_exp::text, p_shares::text, v_now)
  on conflict (id) do nothing;
  return jsonb_build_object('status', 'conflict', 'server_version', v_current.row_version, 'conflict_id', v_conflict_id);
end;
$$;

-- Track F — merge_expense() replaces commit_expense()'s whole-expense CAS with a zone-aware merge.
--   Zone 1 (title/notes/category/date): per-field newest-timestamp wins, so independent metadata edits
--     coexist (two people editing different fields both survive — no conflict).
--   Zone 2 (amount/currency/split_mode/payer/bill-extras/shares): one atomic unit guarded by a CAUSAL
--     split_version. p_base_split_version is the client's last server-confirmed split version; the client
--     sets its local split_version to base+1 iff it changed the split. So:
--       client_changed = client.split_version > base;  server_advanced = canonical.split_version > base
--       changed & !advanced -> apply the client's split, bump canonical split_version + stamp actor
--       changed &  advanced -> SUPERSEDED: keep canonical split, append the loser to superseded_split_edits
--       !changed            -> canonical split stands (client adopts server's on the returned payload)
--   A soft delete (deleted_at set) is a tombstone: plain LWW by updated_at (Rule 1), no zone logic.
--   Returns the merged canonical {expense, shares} so the client adopts it directly (no re-pull). This is
--   what retires the parked-conflict pick-a-side: the causal winner is applied and the loser is logged +
--   nudged one-sidedly, never surfaced as a two-sided "you both edited this" card.
create or replace function public.merge_expense(
  p_expense jsonb,
  p_shares jsonb,
  p_base_split_version bigint,
  p_actor text
) returns jsonb
language plpgsql
as $$
declare
  -- Sanitise the client's clocks ONCE, before anything reads them (#19). Everything below uses
  -- v_exp, never p_expense, so the create path gets the same clamp as the merge path.
  v_exp jsonb := public._clamp_expense_payload(p_expense);
  v_id text := v_exp->>'id';
  v_group_id text := v_exp->>'group_id';
  v_now bigint := coalesce((v_exp->>'updated_at')::bigint, 0);
  v_cur public.expenses%rowtype;
  v_client_split_ver bigint := coalesce((v_exp->>'split_version')::bigint, 1);
  v_client_changed boolean;
  v_server_advanced boolean;
  v_status text;
  v_title text; v_title_at bigint;
  v_notes text; v_notes_at bigint;
  v_cat text; v_subcat text; v_cat_at bigint;
  v_date text; v_date_at bigint;
  v_same boolean;                        -- #6: is a "superseded" edit actually identical to canonical?
  v_current_shares jsonb;
  v_incoming_shares jsonb;
begin
  select * into v_cur from public.expenses where id = v_id for update;

  if not found then
    insert into public.expenses select * from jsonb_populate_record(public._expense_defaults(), v_exp);
    update public.expenses set split_updated_by = p_actor where id = v_id;
    perform public._replace_expense_shares(v_id, p_shares, v_now);
    return jsonb_build_object(
      'status', 'created',
      'split_version', coalesce((v_exp->>'split_version')::bigint, 1),
      'expense', (select to_jsonb(e) from public.expenses e where e.id = v_id),
      'shares',  (select coalesce(jsonb_agg(to_jsonb(s)), '[]'::jsonb) from public.shares s where s.expense_id = v_id));
  end if;

  if (v_exp->>'deleted_at') is not null then
    if v_now >= v_cur.updated_at then
      update public.expenses
         set deleted_at = (v_exp->>'deleted_at')::bigint, status = 'DELETED',
             updated_at = v_now, row_version = v_cur.row_version + 1
       where id = v_id;
    end if;
    return jsonb_build_object(
      'status', 'deleted',
      'split_version', v_cur.split_version,
      'expense', (select to_jsonb(e) from public.expenses e where e.id = v_id),
      'shares',  (select coalesce(jsonb_agg(to_jsonb(s)), '[]'::jsonb) from public.shares s where s.expense_id = v_id and s.deleted_at is null));
  end if;

  if coalesce((v_exp->>'title_updated_at')::bigint, 0) > coalesce(v_cur.title_updated_at, 0) then
    v_title := v_exp->>'title'; v_title_at := coalesce((v_exp->>'title_updated_at')::bigint, 0);
  else v_title := v_cur.title; v_title_at := v_cur.title_updated_at; end if;

  if coalesce((v_exp->>'notes_updated_at')::bigint, 0) > coalesce(v_cur.notes_updated_at, 0) then
    v_notes := v_exp->>'notes'; v_notes_at := coalesce((v_exp->>'notes_updated_at')::bigint, 0);
  else v_notes := v_cur.notes; v_notes_at := v_cur.notes_updated_at; end if;

  if coalesce((v_exp->>'category_updated_at')::bigint, 0) > coalesce(v_cur.category_updated_at, 0) then
    v_cat := v_exp->>'category_id'; v_subcat := v_exp->>'subcategory_id'; v_cat_at := coalesce((v_exp->>'category_updated_at')::bigint, 0);
  else v_cat := v_cur.category_id; v_subcat := v_cur.subcategory_id; v_cat_at := v_cur.category_updated_at; end if;

  if coalesce((v_exp->>'date_updated_at')::bigint, 0) > coalesce(v_cur.date_updated_at, 0) then
    v_date := v_exp->>'expense_date'; v_date_at := coalesce((v_exp->>'date_updated_at')::bigint, 0);
  else v_date := v_cur.expense_date; v_date_at := v_cur.date_updated_at; end if;

  v_client_changed := v_client_split_ver > p_base_split_version;
  v_server_advanced := v_cur.split_version > p_base_split_version;

  if v_client_changed and not v_server_advanced then
    v_status := 'merged';
    update public.expenses set
      amount_subunits    = (v_exp->>'amount_subunits')::bigint,
      currency           = v_exp->>'currency',
      split_mode         = v_exp->>'split_mode',
      payer_user_id      = v_exp->>'payer_user_id',
      payer_outside_name = v_exp->>'payer_outside_name',
      has_tax_row        = coalesce((v_exp->>'has_tax_row')::boolean, false),
      tax_subunits       = coalesce((v_exp->>'tax_subunits')::bigint, 0),
      tip_subunits       = coalesce((v_exp->>'tip_subunits')::bigint, 0),
      tip_split_mode     = coalesce(v_exp->>'tip_split_mode', 'PROPORTIONAL'),
      gratuity_subunits  = coalesce((v_exp->>'gratuity_subunits')::bigint, 0),
      discount_subunits  = coalesce((v_exp->>'discount_subunits')::bigint, 0),
      other_charges_subunits = coalesce((v_exp->>'other_charges_subunits')::bigint, 0),
      split_version      = v_cur.split_version + 1,
      split_updated_by   = p_actor
    where id = v_id;
    perform public._replace_expense_shares(v_id, p_shares, v_now);
  elsif v_client_changed and v_server_advanced then
    -- Causally stale split edit. But before logging it, check whether the incoming split is materially
    -- IDENTICAL to canonical (#6): if so this is a SELF-supersede — a stale re-push after a lost response,
    -- or two overlapping push loops sending the same edit. Logging it would spam the append-only audit AND
    -- raise a false "your change was superseded" notice to the author whose edit actually won. Treat an
    -- identical payload as a merged no-op (adopt canonical, no audit), mirroring commit_expense's v_same.
    select coalesce(jsonb_object_agg(user_id, share_owed_subunits), '{}'::jsonb)
      into v_current_shares from public.shares where expense_id = v_id and deleted_at is null;
    select coalesce(jsonb_object_agg(s->>'user_id', (s->>'share_owed_subunits')::bigint), '{}'::jsonb)
      into v_incoming_shares from jsonb_array_elements(p_shares) s where s->>'deleted_at' is null;
    v_same := v_cur.amount_subunits    is not distinct from (v_exp->>'amount_subunits')::bigint
      and v_cur.currency           is not distinct from v_exp->>'currency'
      and v_cur.split_mode         is not distinct from v_exp->>'split_mode'
      and v_cur.payer_user_id      is not distinct from v_exp->>'payer_user_id'
      and v_cur.payer_outside_name is not distinct from v_exp->>'payer_outside_name'
      and v_cur.tax_subunits       is not distinct from coalesce((v_exp->>'tax_subunits')::bigint, 0)
      and v_cur.tip_subunits       is not distinct from coalesce((v_exp->>'tip_subunits')::bigint, 0)
      and v_cur.tip_split_mode     is not distinct from coalesce(v_exp->>'tip_split_mode', 'PROPORTIONAL')
      and v_cur.gratuity_subunits  is not distinct from coalesce((v_exp->>'gratuity_subunits')::bigint, 0)
      and v_cur.discount_subunits  is not distinct from coalesce((v_exp->>'discount_subunits')::bigint, 0)
      and v_cur.other_charges_subunits is not distinct from coalesce((v_exp->>'other_charges_subunits')::bigint, 0)
      and v_current_shares = v_incoming_shares;
    if v_same then
      v_status := 'merged'; -- canonical already equals the incoming split; adopt it, log nothing
    else
      v_status := 'superseded';
      insert into public.superseded_split_edits(
        id, group_id, expense_id, base_split_version, server_split_version, superseded_by,
        rejected_expense, rejected_shares, created_at)
      values (
        v_id || ':' || p_base_split_version::text || ':' || p_actor,
        v_group_id, v_id, p_base_split_version, v_cur.split_version, p_actor,
        v_exp::text, p_shares::text, v_now)
      on conflict (id) do nothing;
    end if;
  else
    v_status := 'merged';
  end if;

  update public.expenses set
    title = v_title, title_updated_at = v_title_at,
    notes = v_notes, notes_updated_at = v_notes_at,
    category_id = v_cat, subcategory_id = v_subcat, category_updated_at = v_cat_at,
    expense_date = v_date, date_updated_at = v_date_at,
    updated_at = greatest(v_cur.updated_at, v_now),
    row_version = v_cur.row_version + 1
  where id = v_id;

  return jsonb_build_object(
    'status', v_status,
    'split_version', (select split_version from public.expenses where id = v_id),
    'expense', (select to_jsonb(e) from public.expenses e where e.id = v_id),
    'shares',  (select coalesce(jsonb_agg(to_jsonb(s)), '[]'::jsonb) from public.shares s where s.expense_id = v_id and s.deleted_at is null));
end;
$$;

-- ── First claim wins: claim_placeholder() ───────────────────────────────────────────────────────
-- Two members can both claim the same name while offline. Without a guard both merges land and the
-- name's history ends up split across two accounts with nothing signalling that it happened. This
-- stamps `placeholder_claim_completed_at` ONLY where it is currently null and reports whether the
-- caller won, plus who did if they didn't.
--
-- Ordering matters: the client calls this at FLUSH time (so a claim undone inside the 5s window never
-- touches it) and BEFORE pushing the merged rows — a loser then reverses rows no other client has
-- pulled yet, instead of un-publishing money other people have already seen.
create or replace function public.claim_placeholder(
  p_group_id text,
  p_placeholder_user_id text,
  p_claimer_user_id text,
  p_now bigint
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid text := auth.uid()::text;
  v_row public.members%rowtype;
  v_updated int;
  v_winner text;
begin
  if v_uid is null or v_uid is distinct from p_claimer_user_id then
    raise exception 'claim_placeholder: caller may only claim as themselves';
  end if;
  -- security definer bypasses RLS, so this membership check is what stops a claim into a group you are
  -- not in (which would rewrite money for people you have no relationship with).
  if not exists (
    select 1 from public.members m
    where m.group_id = p_group_id and m.user_id = p_claimer_user_id and m.status = 'ACTIVE'
  ) then
    raise exception 'claim_placeholder: not an active member of this group';
  end if;

  select * into v_row from public.members
   where group_id = p_group_id and user_id = p_placeholder_user_id;

  -- No server row for this name yet (created offline, not pushed). Nothing to contest: the claim wins
  -- and the client's own member push carries the stamp up.
  if not found then
    return jsonb_build_object('won', true, 'winner_user_id', p_claimer_user_id, 'winner_name', null);
  end if;

  update public.members set
      placeholder_claim_completed_at = p_now,
      placeholder_claimed_by = p_claimer_user_id,
      status = 'LEFT',
      left_at = p_now,
      is_admin = false,
      updated_at = greatest(updated_at, p_now),
      row_version = row_version + 1
    where group_id = p_group_id
      and user_id = p_placeholder_user_id
      and placeholder_claim_completed_at is null;
  get diagnostics v_updated = row_count;

  select m.placeholder_claimed_by into v_winner from public.members m
   where m.group_id = p_group_id and m.user_id = p_placeholder_user_id;

  return jsonb_build_object(
    -- Idempotent: a retry after a lost response sees its own id as the winner and still wins. A claim
    -- stamped before this function existed has a null winner and is reported as a loss with no name.
    'won', v_updated > 0 or v_winner is not distinct from p_claimer_user_id,
    'winner_user_id', v_winner,
    'winner_name', (select u.display_name from public.users u where u.id = v_winner));
end;
$$;
revoke all on function public.claim_placeholder(text, text, text, bigint) from public;
grant execute on function public.claim_placeholder(text, text, text, bigint) to authenticated;

-- ── Web claim (WEB_CLAIM_SPEC.md) — §11 step 1: schema + RPCs ─────────────────────────────────────

-- Binds a browser to a placeholder user, group-scoped and durable (spec §2.2, §5.1). Not synced to
-- Room; the app never reads it. Only the web-claim edge function's service key touches it — no grant
-- to anon or authenticated, so RLS-enabled-with-no-policies denies both by default.
create table if not exists public.web_sessions (
  id text primary key,
  group_id text not null,
  user_id text not null,               -- the placeholder this browser is
  token_hash text not null unique,     -- SHA-256 of the cookie value; plaintext exists only in the cookie
  created_at bigint not null,
  last_seen_at bigint not null,
  revoked_at bigint
);
create index if not exists web_sessions_group_idx on public.web_sessions (group_id);
create index if not exists web_sessions_user_idx on public.web_sessions (user_id);

-- Web guests scan too (2026-11-23, the group page's "Scan a receipt"). A guest is a placeholder with no
-- auth.users row, so their scans carry `web_session_id` instead of `user_id`; exactly one is set. Only the
-- service role writes guest rows (through web-claim -> extract-receipt), so the RLS policy is unchanged.
alter table public.receipt_scan_log alter column user_id drop not null;
alter table public.receipt_scan_log add column if not exists web_session_id text references public.web_sessions(id) on delete cascade;
do $$
begin
  alter table public.receipt_scan_log drop constraint if exists receipt_scan_log_one_principal;
  alter table public.receipt_scan_log add constraint receipt_scan_log_one_principal
    check ((user_id is null) <> (web_session_id is null));
end $$;
create index if not exists receipt_scan_log_web_session_idx on public.receipt_scan_log (web_session_id, created_at)
  where web_session_id is not null;
create index if not exists receipt_scan_log_group_guest_idx on public.receipt_scan_log (group_id, created_at)
  where web_session_id is not null;

alter table public.web_sessions enable row level security;
-- Deliberately no policies: anon and authenticated get zero access, matching spec §5.1.

-- Permanent, revocable, bill-scoped authorisation link (WEB_CLAIM_SPEC.md §2.2, §2.9, §4.2).
-- The token and its SHA-256 hash are stored only behind membership-checked RPCs; RLS remains enabled
-- with no policies. The edge function uses the hash, while active group members can recover the same
-- token through create_web_bill_link so every device renders the same QR.
create table if not exists public.web_bill_links (
  id text primary key,
  expense_id text not null,
  group_id text not null,
  token_hash text not null unique,
  token text,
  created_by text not null,
  created_at bigint not null,
  expires_at bigint,
  revoked_at bigint,
  extended_count integer not null default 0,
  updated_at bigint not null,
  row_version bigint not null default 1
);
create index if not exists web_bill_links_expense_idx on public.web_bill_links (expense_id);
create index if not exists web_bill_links_group_idx on public.web_bill_links (group_id);
alter table public.web_bill_links enable row level security;
revoke all on public.web_bill_links from anon, authenticated;
alter table public.web_bill_links add column if not exists token text;
alter table public.web_bill_links alter column expires_at drop not null;
-- Existing QR codes were already shared. Keep currently live ones usable permanently; their token
-- cannot be recovered from its old hash, so a second device may mint one new stable token on first open.
update public.web_bill_links
   set expires_at = null
 where revoked_at is null
   and expires_at > (extract(epoch from clock_timestamp()) * 1000)::bigint;

-- A guest edit awaiting the payer's individual approval (spec §2.7, §5.2). This IS synced — the
-- payer's app reads and decides on it (build-order step 6) — so per data/AGENTS.md the server
-- migration (including grants) lands now, ahead of the Room entity, not deferred to step 6.
create table if not exists public.pending_item_edits (
  id text primary key,
  expense_id text not null,
  group_id text not null,
  item_id text,                        -- null for an ADD
  kind text not null,                  -- ADD | RELABEL | REPRICE | REQUANTITY | REMOVE
  proposed_label text,
  proposed_quantity integer,
  proposed_unit_price_subunits bigint,
  previous_label text,                 -- captured when the edit was applied, for before → after
  previous_quantity integer,
  previous_unit_price_subunits bigint, -- DISPLAY only ("Price each  $18.00 → $20.00")
  -- What Undo restores, and the reason this column exists rather than being derived. Per-unit is a
  -- ROUNDED view of the line total (`domain/AGENTS.md`: the line total is the entered source of truth),
  -- so rebuilding a $10.00 line over 3 units from round(333.33) × 3 gives back $9.99. An undo that is a
  -- penny off is a wrong dollar amount in somebody's real dinner, which is the one thing this layer may
  -- never do.
  previous_line_total_subunits bigint,
  proposed_by text not null,
  proposed_at bigint not null,
  decided_at bigint,
  decided_by text,
  decision text,                       -- APPROVED | REJECTED
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1
);
create index if not exists pending_item_edits_expense_idx on public.pending_item_edits (expense_id);
create index if not exists pending_item_edits_group_idx on public.pending_item_edits (group_id);
-- Additive for a project created before the approval gate was dropped (`WEB_CLAIM_PATCH_PLAN.md`), and
-- server-side FIRST per `AGENTS.md` §4.4: the client's full-row upsert sends every field, so a column
-- the server lacks breaks ALL sync for this table, not just this column.
alter table public.pending_item_edits add column if not exists previous_line_total_subunits bigint;

-- RLS: membership-scoped by `group_id`, identical in shape to the loop in the Row-Level Security
-- section above. It is repeated here only because this table is created ~1100 lines after that
-- section, so the loop cannot reach it on a from-scratch apply.
--
-- It previously declared its own copy of the permissive `_rw` policy, which is exactly how a
-- tightening sweep misses a table: the loop gets audited and the lone straggler does not. If you add a
-- table down here, add its policy down here too.
alter table public.pending_item_edits enable row level security;
drop policy if exists pending_item_edits_rw on public.pending_item_edits;
drop policy if exists pending_item_edits_member_rw on public.pending_item_edits;
create policy pending_item_edits_member_rw on public.pending_item_edits
  for all to authenticated
  using (public.is_group_member(group_id))
  with check (public.is_group_member(group_id));
revoke truncate on public.pending_item_edits from anon, authenticated;

-- Doorbell: a guest's pending edit must wake the payer's app (spec §5.6). Same statement-level
-- AFTER INSERT/UPDATE pattern as every other synced table — added to the existing trigger loop.
drop trigger if exists bump_activity_ins on public.pending_item_edits;
drop trigger if exists bump_activity_upd on public.pending_item_edits;
create trigger bump_activity_ins after insert on public.pending_item_edits
  referencing new table as new_rows for each statement
  execute function public.bump_group_activity();
create trigger bump_activity_upd after update on public.pending_item_edits
  referencing new table as new_rows for each statement
  execute function public.bump_group_activity();

-- The one write that cannot be done client-side (spec §5.3): converting Mary's solo claim into a
-- shared portion with Jane means writing a row Jane does not own, which breaks the "item_claims is
-- partitioned by user" invariant if done directly. security definer, explicit participant check.
--
-- Called from TWO contexts with different auth: the app (authenticated, real auth.uid()) and the
-- web-claim edge function (service key, auth.uid() is null — the edge function already authorised
-- the caller against the bill token). The self-check below only fires when auth.uid() IS present,
-- so it protects the app path without breaking the service-role path; RLS is already fully
-- permissive for authenticated today (this file's RLS section above), so this isn't loosening
-- anything that wasn't already open.
-- Generalized in step 2 (WEB_CLAIM_SPEC.md §11): the app's join gesture names its shared portion
-- "<item>__all" by convention (BillRepositoryImpl.setPortion), but the original version only let a
-- caller join a NAMED portion that already existed, forcing every fresh conversion onto an
-- auto-generated id instead. Now, if p_portion_id names a portion that isn't live yet, the
-- conversion-or-fresh-portion logic creates it under THAT id rather than inventing one, so the app's
-- own naming convention survives a join. Also returns 'quantity' so the client doesn't need a second
-- round trip to mirror the portion locally.
create or replace function public.join_item_portion(
  p_item_id text,
  p_joiner_user_id text,
  p_portion_id text,
  p_now bigint,
  p_over_claim_ack boolean default false
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid text := auth.uid()::text;
  v_expense_id text;
  v_group_id text;
  v_line_quantity int;
  v_assigned int;
  v_portion_id text := p_portion_id;
  v_portion_quantity int;
  v_portion_exists boolean;
  v_target_claim public.item_claims%rowtype;
  v_target_count int;
  v_share_id text;
  v_members text[];
  v_added_new_unit boolean := false;
  v_joiner_claim public.item_claims%rowtype;
begin
  if v_uid is not null and v_uid is distinct from p_joiner_user_id then
    raise exception 'join_item_portion: caller may only join as themselves';
  end if;

  select ei.expense_id, ei.group_id, ei.quantity
    into v_expense_id, v_group_id, v_line_quantity
    from public.expense_items ei
    where ei.id = p_item_id and ei.deleted_at is null;
  if not found then
    raise exception 'join_item_portion: item not found';
  end if;

  if not exists (
    select 1 from public.bill_participants bp
    where bp.expense_id = v_expense_id and bp.user_id = p_joiner_user_id and bp.deleted_at is null
  ) then
    raise exception 'join_item_portion: not a participant of this bill';
  end if;

  -- Retire the JOINER'S OWN live solo claim on this line, in every path, before anything else counts
  -- units. A guest who solo-claims a line and then joins a portion of it would otherwise hold a live
  -- item_claims row AND a live item_shares row at once — double-counted in assignedQuantityByItem and
  -- in the money split. The app's mirror (BillRepositoryImpl.applyJoinOutcomeLocally) already retires
  -- it client-side; the web path has no such compensation, so it has to happen here, which is also the
  -- only place that owns the invariant. This row belongs to the caller, so writing it breaks no
  -- partition rule. Doing it BEFORE v_assigned is computed keeps the overclaim math honest: a joiner
  -- swapping a solo claim for a portion membership adds no net unit.
  select * into v_joiner_claim from public.item_claims
    where item_id = p_item_id and user_id = p_joiner_user_id and deleted_at is null
    limit 1;
  if found then
    update public.item_claims set
      deleted_at = p_now, updated_at = p_now, row_version = row_version + 1
    where id = v_joiner_claim.id;
  end if;

  -- Idempotent no-op: already an active member of the named portion.
  if v_portion_id is not null and exists (
    select 1 from public.item_shares s
    where s.item_id = p_item_id and s.portion_id = v_portion_id
      and s.user_id = p_joiner_user_id and s.deleted_at is null
  ) then
    select array_agg(user_id) into v_members from public.item_shares
      where item_id = p_item_id and portion_id = v_portion_id and deleted_at is null;
    select quantity into v_portion_quantity from public.item_shares
      where item_id = p_item_id and portion_id = v_portion_id and deleted_at is null limit 1;
    return jsonb_build_object('ok', true, 'portion_id', v_portion_id, 'quantity', v_portion_quantity, 'members', to_jsonb(v_members));
  end if;

  -- Units currently assigned = solo claims + one count per distinct active portion (never per member).
  select coalesce(sum(c.quantity), 0) into v_assigned
    from public.item_claims c where c.item_id = p_item_id and c.deleted_at is null;
  v_assigned := v_assigned + coalesce((
    select sum(x.quantity) from (
      select distinct on (s.portion_id) s.portion_id, s.quantity
      from public.item_shares s
      where s.item_id = p_item_id and s.portion_id is not null and s.deleted_at is null
      order by s.portion_id, s.created_at
    ) x
  ), 0);

  v_portion_exists := v_portion_id is not null and exists (
    select 1 from public.item_shares s
    where s.item_id = p_item_id and s.portion_id = v_portion_id and s.deleted_at is null
  );

  if v_portion_exists then
    -- Joining a portion that's already live doesn't change the unit count it holds.
    select quantity into v_portion_quantity from public.item_shares
      where item_id = p_item_id and portion_id = v_portion_id and deleted_at is null
      order by created_at limit 1;
  else
    -- No LIVE portion under this id — whether the caller named one that doesn't exist yet (the app's
    -- fixed "<item>__all" convention) or passed none at all: convert the line's one solo claim into a
    -- shared portion under [v_portion_id] (naming a fresh one only if the caller didn't), or start a
    -- fresh single-member portion if there's no solo claim to convert. A line with MORE THAN ONE active
    -- solo claim is ambiguous from these arguments alone — the caller must resolve it explicitly.
    select count(*) into v_target_count from public.item_claims
      where item_id = p_item_id and deleted_at is null and user_id <> p_joiner_user_id;
    if v_target_count > 1 then
      raise exception 'join_item_portion: ambiguous target — pass an explicit, already-live portion_id';
    end if;

    select * into v_target_claim from public.item_claims
      where item_id = p_item_id and deleted_at is null and user_id <> p_joiner_user_id
      limit 1;

    if found then
      if v_portion_id is null then
        v_portion_id := p_item_id || '__joined_' || v_target_claim.user_id;
      end if;
      v_portion_quantity := v_target_claim.quantity;
      update public.item_claims set
        deleted_at = p_now, updated_at = p_now, row_version = row_version + 1
      where id = v_target_claim.id;
      insert into public.item_shares
        (id, item_id, expense_id, group_id, user_id, portion_id, quantity, added_by, created_at, updated_at)
      values
        (p_item_id || '__' || v_target_claim.user_id || '__' || v_portion_id, p_item_id, v_expense_id,
         v_group_id, v_target_claim.user_id, v_portion_id, v_portion_quantity, p_joiner_user_id, p_now, p_now)
      on conflict (id) do update set deleted_at = null, updated_at = p_now, row_version = item_shares.row_version + 1;
    else
      if v_portion_id is null then
        v_portion_id := p_item_id || '__solo_' || p_joiner_user_id;
      end if;
      v_portion_quantity := 1;
      v_added_new_unit := true;
    end if;
  end if;

  if not p_over_claim_ack and v_added_new_unit and v_assigned + 1 > v_line_quantity then
    raise exception using
      errcode = 'P0001',
      message = 'OVERCLAIMED',
      detail = format('item %s: %s of %s units already assigned', p_item_id, v_assigned, v_line_quantity);
  end if;

  v_share_id := p_item_id || '__' || p_joiner_user_id || '__' || v_portion_id;
  insert into public.item_shares
    (id, item_id, expense_id, group_id, user_id, portion_id, quantity, added_by, created_at, updated_at)
  values
    (v_share_id, p_item_id, v_expense_id, v_group_id, p_joiner_user_id, v_portion_id, v_portion_quantity,
     p_joiner_user_id, p_now, p_now)
  on conflict (id) do update set deleted_at = null, updated_at = p_now, row_version = item_shares.row_version + 1;

  select array_agg(user_id) into v_members from public.item_shares
    where item_id = p_item_id and portion_id = v_portion_id and deleted_at is null;

  return jsonb_build_object('ok', true, 'portion_id', v_portion_id, 'quantity', v_portion_quantity, 'members', to_jsonb(v_members));
end;
$$;
revoke all on function public.join_item_portion(text, text, text, bigint, boolean) from public, anon;
grant execute on function public.join_item_portion(text, text, text, bigint, boolean) to authenticated;
-- Why the `revoke ... from anon` two lines above is spelled out explicitly: Supabase grants EXECUTE to
-- anon/authenticated by default at function-creation time, independently of `revoke ... from public`,
-- so revoking from `public` alone leaves anon holding EXECUTE. join_item_portion must never be callable
-- by anon (spec §4.1) — only the app (authenticated) and the web-claim edge function's service key
-- (which bypasses grants entirely). Confirmed via get_advisors
-- (`anon_security_definer_function_executable`). Every security definer function below names anon (and
-- `authenticated` where it is edge-function-only) in its own revoke for the same reason.

-- First-claim-wins for "That's me" on the web evidence list (spec §5.4, mirroring claim_placeholder).
-- Called ONLY by the web-claim edge function's service key — never granted to anon/authenticated,
-- since a web guest is never an authenticated Supabase user (spec §4.1: the browser never touches
-- PostgREST or holds credentials at all).
--
-- Unlike claim_placeholder, "first wins" here is a SHORT RACE WINDOW, not a permanent lock: spec E7
-- explicitly allows a returning guest to claim the SAME placeholder again from a second device, long
-- after the first. An advisory transaction lock serializes truly concurrent callers (two browsers
-- racing for the same name in the same moment); a claim landing more than 5s after the placeholder's
-- last live session is a legitimate later re-claim, not a contested race, and always wins. This does
-- NOT touch members.placeholder_claim_completed_at — that column is reserved for a real account merge
-- (spec §2.1); this is a much lighter "recognise this browser as her" claim.
--
-- The winning session row is inserted HERE, inside the advisory lock, not by the caller afterwards.
-- An earlier version returned `won` and left the insert to the edge function: the xact lock released
-- when this function returned, so two racers could each look, each see no session yet, and each be
-- told they won — the lock guarded a read of state nobody had written yet. The write that decides the
-- race has to happen under the lock that serializes it.
create or replace function public.claim_web_placeholder(
  p_group_id text,
  p_placeholder_user_id text,
  p_session_id text,
  p_session_token_hash text,
  p_now bigint
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_lock_key bigint := hashtextextended(p_group_id || ':' || p_placeholder_user_id, 0);
  v_existing_session text;
begin
  if not exists (
    select 1 from public.users
    where id = p_placeholder_user_id and is_placeholder = true and placeholder_group_id = p_group_id
  ) then
    raise exception 'claim_web_placeholder: not an unclaimed placeholder in this group';
  end if;
  if exists (
    select 1 from public.members m
    where m.group_id = p_group_id and m.user_id = p_placeholder_user_id
      and m.placeholder_claim_completed_at is not null
  ) then
    raise exception 'claim_web_placeholder: already claimed by an account holder — not offered on web';
  end if;

  perform pg_advisory_xact_lock(v_lock_key);

  select ws.id into v_existing_session
    from public.web_sessions ws
    where ws.group_id = p_group_id and ws.user_id = p_placeholder_user_id and ws.revoked_at is null
      and ws.id <> p_session_id
      and ws.created_at >= p_now - 5000
    order by ws.created_at asc
    limit 1;

  if v_existing_session is not null then
    return jsonb_build_object('won', false, 'winner_is_me', false, 'winner_session_id', v_existing_session);
  end if;

  insert into public.web_sessions (id, group_id, user_id, token_hash, created_at, last_seen_at)
  values (p_session_id, p_group_id, p_placeholder_user_id, p_session_token_hash, p_now, p_now);

  return jsonb_build_object('won', true, 'winner_is_me', true, 'winner_session_id', p_session_id);
end;
$$;
revoke all on function public.claim_web_placeholder(text, text, text, text, bigint) from public, anon, authenticated;
-- Supersedes the 4-arg version, which returned `won` without writing anything and so could declare two
-- winners (above). Dropping a superseded FUNCTION destroys no rows — this is not the DROP that
-- `supabase/AGENTS.md` forbids, and leaving the old overload live would leave the race callable.
drop function if exists public.claim_web_placeholder(text, text, text, bigint);

-- Creating a web guest's placeholder, atomically and race-free (spec §2.4, §3.2). The edge function
-- still does the name matching that drives the UI — the block message, the suffix suggestions, the
-- fuzzy "did you mean her?" — but the *decision* has to be re-taken here, under a lock, because §2.4
-- is load-bearing: check-then-insert lets two guests typing "Purity" at the same table both pass the
-- check and both land, and the whole feature depends on being able to point at people by name.
--
-- Normalisation mirrors the edge function's normalizeName() exactly: trim, collapse internal
-- whitespace, cap at 40, compare case-insensitively. Deliberately NOT fuzzy — a false-positive block
-- is a dead end (spec §2.4).
--
-- Doing the user + member + session + participant inserts in one transaction also removes the
-- half-created guest: previously an insert could fail after the user row landed, leaving a named
-- placeholder with no session, unreachable by the browser that just created it and offered to
-- everyone else as evidence.
create or replace function public.create_web_placeholder(
  p_group_id text,
  p_expense_id text,
  p_name text,
  p_user_id text,
  p_member_id text,
  p_session_id text,
  p_session_token_hash text,
  p_participant_id text,
  p_now bigint
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_name text := left(regexp_replace(btrim(p_name), '\s+', ' ', 'g'), 40);
  v_lock_key bigint;
  v_clash text;
begin
  if v_name = '' then
    raise exception 'create_web_placeholder: name required';
  end if;
  v_lock_key := hashtextextended(p_group_id || ':' || lower(v_name), 0);
  perform pg_advisory_xact_lock(v_lock_key);

  select u.display_name into v_clash
    from public.members m
    join public.users u on u.id = m.user_id
    where m.group_id = p_group_id and m.status = 'ACTIVE'
      and lower(left(regexp_replace(btrim(u.display_name), '\s+', ' ', 'g'), 40)) = lower(v_name)
    limit 1;
  if v_clash is not null then
    return jsonb_build_object('created', false, 'conflict', true, 'name', v_clash);
  end if;

  -- created_by = the guest's own new id (spec §5.7): a self-created placeholder is never asked "is this
  -- you?" later, since IDENTITY_CLAIM_SPEC.md skips anyone who created their own name.
  insert into public.users (id, is_placeholder, display_name, placeholder_group_id, created_by, created_at, updated_at)
  values (p_user_id, true, v_name, p_group_id, p_user_id, p_now, p_now);

  insert into public.members (id, group_id, user_id, status, joined_at, created_at, updated_at)
  values (p_member_id, p_group_id, p_user_id, 'ACTIVE', p_now, p_now, p_now);

  insert into public.web_sessions (id, group_id, user_id, token_hash, created_at, last_seen_at)
  values (p_session_id, p_group_id, p_user_id, p_session_token_hash, p_now, p_now);

  -- NULL from the group invite page (`/j/`), which joins the group without a bill.
  if p_expense_id is not null then
    insert into public.bill_participants (id, expense_id, group_id, user_id, created_at, updated_at)
    values (p_participant_id, p_expense_id, p_group_id, p_user_id, p_now, p_now);
  end if;

  return jsonb_build_object('created', true, 'conflict', false, 'name', v_name);
end;
$$;
revoke all on function public.create_web_placeholder(text, text, text, text, text, text, text, text, bigint) from public, anon, authenticated;

-- Step 3 (WEB_CLAIM_SPEC.md §4.5): the web-claim edge function's per-token write rate limit (120
-- writes/min). Mirrors receipt_scan_log's pattern — insert-only, service-role-only, no grants to
-- anon/authenticated (the edge function is the only writer, same as web_sessions/web_bill_links).
create table if not exists public.web_claim_write_log (
  id text primary key,
  token_hash text not null,
  created_at bigint not null
);
create index if not exists web_claim_write_log_token_idx on public.web_claim_write_log (token_hash, created_at);
alter table public.web_claim_write_log enable row level security;
-- Deliberately no policies — only the web-claim edge function's service key touches this table.

-- ── Web claim — §11 step 6: the payer's in-app link lifecycle + claim progress ────────────────────
--
-- `web_bill_links` retains RLS with zero policies. Its bearer token remains private to the table and is
-- returned only through the `security definer` RPCs below, each of which re-derives active group
-- membership. Every member of that group can recover the same permanent token; guests still receive it
-- only by being handed the QR/link. The token is bill-scoped and remains live until revoked or the bill
-- or group is deleted.

-- Base62 over a bytea, most-significant byte first. Pure arithmetic on `numeric`, which is exact well
-- past 2^128, so no precision is lost. Used only for link tokens; `immutable` so it can be inlined.
create or replace function public.base62_encode(p_bytes bytea) returns text
language plpgsql
immutable
-- Pinned even though this touches no tables: it is called from inside `security definer` functions, and
-- an unpinned search_path there is the standard advisory finding (`function_search_path_mutable`).
set search_path = pg_catalog
as $$
declare
  alphabet constant text := '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';
  v numeric := 0;
  v_out text := '';
  i int;
begin
  for i in 0 .. length(p_bytes) - 1 loop
    v := v * 256 + get_byte(p_bytes, i);
  end loop;
  if v = 0 then return '0'; end if;
  while v > 0 loop
    v_out := substr(alphabet, (v % 62)::int + 1, 1) || v_out;
    v := div(v, 62);
  end loop;
  return v_out;
end;
$$;
revoke all on function public.base62_encode(bytea) from public, anon, authenticated;

-- Shared guard for every RPC below: the caller must be an ACTIVE member of the group that owns this
-- (live) expense. `security definer` bypasses RLS, so this check is the only thing standing between a
-- signed-in stranger and minting a public link to someone else's dinner. Returns the group id.
create or replace function public.web_bill_link_guard(p_expense_id text, p_actor text) returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid text := auth.uid()::text;
  v_group_id text;
begin
  if v_uid is null or v_uid is distinct from p_actor then
    raise exception 'web_bill_link: caller may only act as themselves';
  end if;
  select e.group_id into v_group_id from public.expenses e
    where e.id = p_expense_id and e.deleted_at is null;
  if v_group_id is null then
    raise exception 'web_bill_link: expense not found';
  end if;
  if not exists (
    select 1 from public.members m
    where m.group_id = v_group_id and m.user_id = p_actor and m.status = 'ACTIVE'
  ) then
    raise exception 'web_bill_link: not an active member of this group';
  end if;
  return v_group_id;
end;
$$;
revoke all on function public.web_bill_link_guard(text, text) from public, anon, authenticated;

-- Get or mint the bill's permanent link. The same token is returned to each active group member, so
-- opening the QR screen on another device reuses the same code. RLS has no policies and the RPC repeats
-- the membership guard before returning this bearer credential. Legacy active rows have token=null;
-- keep their QR working and mint one stable token row alongside them on first open after migration.
-- Entropy comes from `gen_random_uuid()` (a CSPRNG, 122 bits), never the client.
create or replace function public.create_web_bill_link(
  p_expense_id text,
  p_actor text,
  p_now bigint,
  p_ttl_ms bigint default null
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_group_id text := public.web_bill_link_guard(p_expense_id, p_actor);
  v_token text := public.base62_encode(uuid_send(gen_random_uuid()));
  v_id text := gen_random_uuid()::text;
  v_existing public.web_bill_links%rowtype;
begin
  -- Serialise first opens so two members arriving together cannot mint competing stable links.
  perform 1 from public.expenses where id = p_expense_id for update;

  select * into v_existing from public.web_bill_links
    where expense_id = p_expense_id
      and revoked_at is null
      and (expires_at is null or expires_at > p_now)
      and token is not null
    order by created_at desc
    limit 1
    for update;

  if v_existing.id is not null then
    return jsonb_build_object('id', v_existing.id, 'token', v_existing.token, 'expires_at', v_existing.expires_at);
  end if;

  insert into public.web_bill_links (
    id, expense_id, group_id, token_hash, token, created_by, created_at, expires_at, updated_at
  ) values (
    v_id, p_expense_id, v_group_id,
    encode(sha256(convert_to(v_token, 'utf8')), 'hex'),
    v_token, p_actor, p_now, null, p_now
  );

  return jsonb_build_object('id', v_id, 'token', v_token, 'expires_at', null);
end;
$$;
revoke all on function public.create_web_bill_link(text, text, bigint, bigint) from public, anon;
grant execute on function public.create_web_bill_link(text, text, bigint, bigint) to authenticated;

-- Compatibility RPC for older clients. Bill links no longer expire, so this validates membership but
-- never changes the permanent link or its expiry.
create or replace function public.extend_web_bill_link(
  p_expense_id text,
  p_actor text,
  p_now bigint,
  p_ttl_ms bigint default 259200000
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_group_id text := public.web_bill_link_guard(p_expense_id, p_actor);
begin
  return jsonb_build_object('ok', true, 'expires_at', null);
end;
$$;
revoke all on function public.extend_web_bill_link(text, text, bigint, bigint) from public, anon;
grant execute on function public.extend_web_bill_link(text, text, bigint, bigint) to authenticated;

-- Kill the link now (spec E28). The row survives as history; only `revoked_at` is stamped, so this is a
-- soft delete like everything else in this schema.
create or replace function public.revoke_web_bill_link(
  p_expense_id text,
  p_actor text,
  p_now bigint
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_group_id text := public.web_bill_link_guard(p_expense_id, p_actor);
  v_count int;
begin
  update public.web_bill_links set
      revoked_at = p_now, updated_at = p_now, row_version = row_version + 1
    where expense_id = p_expense_id and revoked_at is null;
  get diagnostics v_count = row_count;
  return jsonb_build_object('ok', v_count > 0);
end;
$$;
revoke all on function public.revoke_web_bill_link(text, text, bigint) from public, anon;
grant execute on function public.revoke_web_bill_link(text, text, bigint) to authenticated;

-- What the share screen needs to describe the link, and what the "who's still to claim" screen needs to
-- tell "hasn't opened the link" apart from "opened it, claimed nothing". Never returns `token_hash`.
--
-- `opened` is the newest `last_seen_at` of a live web session for that person **in this group**, not on
-- this bill: identity is group-scoped and durable (spec §2.2), so a guest recognised at Ramen night has
-- a session before she ever opens tonight's link. The client therefore treats `opened >= link.created_at`
-- as "opened this link" and anything older as "hasn't". That comparison lives client-side so a re-issued
-- link automatically resets everyone to "hasn't opened" without a second server concept.
create or replace function public.web_bill_link_status(
  p_expense_id text,
  p_actor text
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_group_id text := public.web_bill_link_guard(p_expense_id, p_actor);
  v_link public.web_bill_links%rowtype;
  v_sessions jsonb;
begin
  select * into v_link from public.web_bill_links
    where expense_id = p_expense_id
    order by (revoked_at is null) desc, created_at desc
    limit 1;

  select coalesce(jsonb_agg(jsonb_build_object('user_id', s.user_id, 'opened_at', s.seen)), '[]'::jsonb)
    into v_sessions
    from (
      select ws.user_id, max(ws.last_seen_at) as seen
        from public.web_sessions ws
        join public.bill_participants bp
          on bp.user_id = ws.user_id and bp.expense_id = p_expense_id and bp.deleted_at is null
       where ws.group_id = v_group_id and ws.revoked_at is null
       group by ws.user_id
    ) s;

  return jsonb_build_object(
    'exists', v_link.id is not null,
    'link_id', v_link.id,
    'created_at', v_link.created_at,
    'expires_at', v_link.expires_at,
    'revoked_at', v_link.revoked_at,
    'extended_count', coalesce(v_link.extended_count, 0),
    'sessions', v_sessions);
end;
$$;
revoke all on function public.web_bill_link_status(text, text) from public, anon;
grant execute on function public.web_bill_link_status(text, text) to authenticated;

-- ── The guest's Zone-2 write path (WEB_CLAIM_SPEC.md §5.8) ────────────────────────────────────────
--
-- A web guest changing a bill's MONEY is the one thing the web-claim edge function cannot express as a
-- table write. It holds a service key and has NO auth.uid(), so it cannot go through merge_expense
-- (which runs as the authenticated caller and derives the actor from the session), and it must not
-- update `expenses` blind: skipping the split_version bump means the payer's next push carries a base
-- that is causally AHEAD of a change it never saw, and merge_expense then applies the payer's older
-- split on top — silently reverting the guest's edit. That is precisely the failure the causal model
-- exists to prevent (`data/AGENTS.md`, "Expenses sync through a ZONE-AWARE MERGE RPC").
--
-- So: three security-definer RPCs, all SERVICE-ROLE ONLY. Every one of them ends by re-deriving
-- expenses.amount_subunits from the live lines and advancing split_version, via the shared helper
-- below, so there is exactly one place that arithmetic lives.
--
-- Not calling merge_expense from here is a deliberate choice, not an oversight: it takes a whole
-- expense payload plus the full share set, and constructing one for a one-field change would mean the
-- edge function reconstructing the split it is not allowed to compute. These are written against the
-- SAME causal rule instead — see set_web_bill_payer.

-- Bill total = Σ(live line totals) + tax + gratuity + tip − discount, and one more turn of the causal
-- version. Identical to BillRepositoryImpl's `total(...)` and to BillPendingEdits'; if that rule ever
-- changes, it changes in both places or a guest's bill stops matching the payer's.
--
-- `shares` are deliberately NOT touched. An itemized bill's shares are a LOCAL derived materialization
-- (`data/AGENTS.md`) — every device re-derives them from items + claims on pull, and SyncEngine's
-- rematerializeGroups does exactly that when a bill's sources change. Writing them here would freeze a
-- second, competing copy of the split on the server.
create or replace function public._reprice_web_bill(p_expense_id text, p_actor text, p_now bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_e public.expenses%rowtype;
  v_lines bigint;
begin
  select * into v_e from public.expenses where id = p_expense_id for update;
  if not found or v_e.deleted_at is not null then
    raise exception 'web bill: expense not found';
  end if;

  select coalesce(sum(line_total_subunits), 0) into v_lines
    from public.expense_items where expense_id = p_expense_id and deleted_at is null;

  update public.expenses set
    amount_subunits = v_lines
      + coalesce(v_e.tax_subunits, 0) + coalesce(v_e.gratuity_subunits, 0)
      + coalesce(v_e.tip_subunits, 0) + coalesce(v_e.other_charges_subunits, 0)
      - coalesce(v_e.discount_subunits, 0),
    split_version    = v_e.split_version + 1,
    split_updated_by = p_actor,
    updated_at       = greatest(v_e.updated_at, p_now),
    row_version      = v_e.row_version + 1
  where id = p_expense_id;

  return jsonb_build_object(
    'amount_subunits', (select amount_subunits from public.expenses where id = p_expense_id),
    'split_version',   (select split_version from public.expenses where id = p_expense_id));
end;
$$;
revoke all on function public._reprice_web_bill(text, text, bigint) from public, anon, authenticated;

-- A guest's menu edit, APPLIED (spec §2.7, §3.6). The proposal row and the change to `expense_items`
-- land in ONE transaction, and the row lands already stamped APPLIED — there is no waiting state and no
-- second step. `pending_item_edits` keeps its name because renaming a synced table costs more than it
-- explains; what it now holds is the permanent, attributed, undoable log of who changed what.
--
-- item_id is ALWAYS written back, including for an ADD, where it is the id of the line this call just
-- created. Without it Undo has nothing to target and an added line becomes unremovable from the app.
--
-- previous_* are captured HERE, from the row as it is at this instant, rather than trusted from the
-- client: they are what Undo restores, so a stale or hostile client value would restore the wrong price.
--
-- The web editor collects a PER-UNIT price; the bill stores the line total as truth (`domain/AGENTS.md`),
-- so every branch multiplies back out. unit_price_subunits is kept populated with the rounded per-unit
-- for backward compatibility and is never read as truth.
create or replace function public.apply_web_bill_edit(
  p_expense_id text,
  p_group_id text,
  p_kind text,
  p_item_id text,
  p_label text,
  p_quantity integer,
  p_unit_price_subunits bigint,
  p_actor text,
  p_now bigint
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_item public.expense_items%rowtype;
  v_item_id text := p_item_id;
  v_quantity integer;
  v_per_unit bigint;
  v_line_total bigint;
  v_edit_id text;
  v_priced jsonb;
begin
  if p_kind not in ('ADD', 'RELABEL', 'REPRICE', 'REQUANTITY', 'REMOVE') then
    raise exception 'apply_web_bill_edit: unknown kind %', p_kind;
  end if;

  if p_kind = 'ADD' then
    v_quantity := greatest(coalesce(p_quantity, 1), 1);
    v_line_total := coalesce(p_unit_price_subunits, 0) * v_quantity;
    v_item_id := gen_random_uuid()::text;
    insert into public.expense_items
      (id, expense_id, group_id, label, quantity, unit_price_subunits, line_total_subunits,
       sort_order, created_at, updated_at)
    values
      (v_item_id, p_expense_id, p_group_id,
       coalesce(nullif(btrim(coalesce(p_label, '')), ''), 'Item'), v_quantity,
       round(v_line_total::numeric / v_quantity), v_line_total,
       -- Lands at the end of the receipt: it was not printed on it.
       coalesce((select max(sort_order) from public.expense_items where expense_id = p_expense_id), -1) + 1,
       p_now, p_now);
  else
    select * into v_item from public.expense_items
      where id = p_item_id and expense_id = p_expense_id and deleted_at is null
      for update;
    if not found then
      raise exception 'apply_web_bill_edit: item not on this bill';
    end if;

    if p_kind = 'REMOVE' then
      update public.expense_items set
        deleted_at = p_now, updated_at = p_now, row_version = row_version + 1
      where id = v_item.id;
      -- Claims on a line that no longer exists must go with it, or they keep counting toward people's
      -- tabs (spec E16). Stamped with EXACTLY p_now, which is what makes an Undo able to revive these
      -- and only these — see undo_web_bill_edit.
      update public.item_claims set deleted_at = p_now, updated_at = p_now, row_version = row_version + 1
        where item_id = v_item.id and deleted_at is null;
      update public.item_shares set deleted_at = p_now, updated_at = p_now, row_version = row_version + 1
        where item_id = v_item.id and deleted_at is null;
    elsif p_kind = 'RELABEL' then
      update public.expense_items set
        label = coalesce(nullif(btrim(coalesce(p_label, '')), ''), v_item.label),
        updated_at = p_now, row_version = row_version + 1
      where id = v_item.id;
    elsif p_kind = 'REPRICE' then
      if p_unit_price_subunits is null then
        raise exception 'apply_web_bill_edit: REPRICE needs a price';
      end if;
      v_quantity := v_item.quantity;
      v_line_total := p_unit_price_subunits * v_quantity;
      update public.expense_items set
        line_total_subunits = v_line_total,
        unit_price_subunits = round(v_line_total::numeric / greatest(v_quantity, 1)),
        updated_at = p_now, row_version = row_version + 1
      where id = v_item.id;
    elsif p_kind = 'REQUANTITY' then
      if p_quantity is null then
        raise exception 'apply_web_bill_edit: REQUANTITY needs a quantity';
      end if;
      v_quantity := greatest(p_quantity, 1);
      -- Per-unit is held constant, because that is what "change the quantity" means on a receipt: three
      -- bowls cost three times one bowl.
      v_per_unit := coalesce(
        p_unit_price_subunits,
        round(v_item.line_total_subunits::numeric / greatest(v_item.quantity, 1)));
      v_line_total := v_per_unit * v_quantity;
      update public.expense_items set
        quantity = v_quantity,
        line_total_subunits = v_line_total,
        unit_price_subunits = round(v_line_total::numeric / greatest(v_quantity, 1)),
        updated_at = p_now, row_version = row_version + 1
      where id = v_item.id;
    end if;
  end if;

  v_edit_id := gen_random_uuid()::text;
  insert into public.pending_item_edits
    (id, expense_id, group_id, item_id, kind,
     proposed_label, proposed_quantity, proposed_unit_price_subunits,
     previous_label, previous_quantity, previous_unit_price_subunits, previous_line_total_subunits,
     proposed_by, proposed_at, decided_at, decided_by, decision, created_at, updated_at)
  values
    (v_edit_id, p_expense_id, p_group_id, v_item_id, p_kind,
     p_label, p_quantity, p_unit_price_subunits,
     v_item.label, v_item.quantity,
     case when v_item.id is null then null
          else round(v_item.line_total_subunits::numeric / greatest(v_item.quantity, 1))::bigint end,
     v_item.line_total_subunits,
     p_actor, p_now, p_now, p_actor, 'APPLIED', p_now, p_now);

  v_priced := public._reprice_web_bill(p_expense_id, p_actor, p_now);
  return jsonb_build_object(
    'ok', true, 'edit_id', v_edit_id, 'item_id', v_item_id,
    'amount_subunits', v_priced->'amount_subunits', 'split_version', v_priced->'split_version');
end;
$$;
revoke all on function public.apply_web_bill_edit(text, text, text, text, text, integer, bigint, text, bigint)
  from public, anon, authenticated;

-- Undo. **Anyone on the bill may call it** (spec §2.7) — there is deliberately no rights hierarchy and
-- no arbitration. An undo is itself an attributed entry in the log, so the worst case is A edits,
-- B undoes, A edits again, with every step visible and a name against it. The log is the tiebreak.
--
-- First-undo-wins is a CONDITIONAL update, the same shape as claim_placeholder: the row is locked, and
-- anything that is not currently APPLIED returns ok with changed=false rather than raising. Two taps on
-- a slow card must not un-remove a line twice.
--
-- The REMOVE branch is the subtle one. Reviving the line is easy; reviving its claims is not, because a
-- claim killed by the removal is indistinguishable from a claim its owner dropped in the same minute.
-- The revival is therefore scoped to the removal's EXACT deleted_at stamp, which is the applied-at time
-- recorded on the log row. Widen that to a range and an undo resurrects claims people deliberately let go.
create or replace function public.undo_web_bill_edit(
  p_edit_id text,
  p_actor text,
  p_now bigint
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_edit public.pending_item_edits%rowtype;
  v_applied_at bigint;
  v_quantity integer;
  v_line_total bigint;
  v_priced jsonb;
begin
  select * into v_edit from public.pending_item_edits where id = p_edit_id for update;
  if not found then
    raise exception 'undo_web_bill_edit: no such change';
  end if;
  if v_edit.decision is distinct from 'APPLIED' then
    return jsonb_build_object('ok', true, 'changed', false, 'item_id', v_edit.item_id);
  end if;
  if v_edit.item_id is null then
    -- Only reachable for a row written before item_id was backfilled on ADD. Nothing to target, and
    -- pretending otherwise would leave the log claiming an undo that never happened.
    raise exception 'undo_web_bill_edit: this change has no line to restore';
  end if;

  v_applied_at := coalesce(v_edit.decided_at, v_edit.proposed_at);

  if v_edit.kind = 'ADD' then
    update public.expense_items set
      deleted_at = p_now, updated_at = p_now, row_version = row_version + 1
    where id = v_edit.item_id and deleted_at is null;
    -- Anyone who claimed the added line goes with it (spec E22): the line is not on the bill any more,
    -- so a claim against it would keep billing for something that does not exist.
    update public.item_claims set deleted_at = p_now, updated_at = p_now, row_version = row_version + 1
      where item_id = v_edit.item_id and deleted_at is null;
    update public.item_shares set deleted_at = p_now, updated_at = p_now, row_version = row_version + 1
      where item_id = v_edit.item_id and deleted_at is null;

  elsif v_edit.kind = 'REMOVE' then
    update public.expense_items set
      deleted_at = null, updated_at = p_now, row_version = row_version + 1
    where id = v_edit.item_id;
    -- EXACT stamp, not a window. See the header.
    update public.item_claims set deleted_at = null, updated_at = p_now, row_version = row_version + 1
      where item_id = v_edit.item_id and deleted_at = v_applied_at;
    update public.item_shares set deleted_at = null, updated_at = p_now, row_version = row_version + 1
      where item_id = v_edit.item_id and deleted_at = v_applied_at;

  elsif v_edit.kind = 'RELABEL' then
    update public.expense_items set
      label = coalesce(v_edit.previous_label, label),
      updated_at = p_now, row_version = row_version + 1
    where id = v_edit.item_id;

  else -- REPRICE | REQUANTITY: restore the quantity and the LINE TOTAL exactly as they were.
    select coalesce(v_edit.previous_quantity, quantity) into v_quantity
      from public.expense_items where id = v_edit.item_id;
    v_quantity := greatest(coalesce(v_quantity, 1), 1);
    -- The stored line total, not per-unit × quantity: rebuilding from the rounded per-unit is where a
    -- $10.00 line over 3 units comes back as $9.99. Falls back to the product only for a log row written
    -- before previous_line_total_subunits existed.
    v_line_total := coalesce(
      v_edit.previous_line_total_subunits,
      coalesce(v_edit.previous_unit_price_subunits, 0) * v_quantity);
    update public.expense_items set
      quantity = v_quantity,
      line_total_subunits = v_line_total,
      unit_price_subunits = round(v_line_total::numeric / v_quantity),
      updated_at = p_now, row_version = row_version + 1
    where id = v_edit.item_id;
  end if;

  update public.pending_item_edits set
    decision = 'UNDONE', decided_at = p_now, decided_by = p_actor,
    updated_at = p_now, row_version = row_version + 1
  where id = p_edit_id;

  v_priced := public._reprice_web_bill(v_edit.expense_id, p_actor, p_now);
  return jsonb_build_object(
    'ok', true, 'changed', true, 'item_id', v_edit.item_id,
    'amount_subunits', v_priced->'amount_subunits', 'split_version', v_priced->'split_version');
end;
$$;
revoke all on function public.undo_web_bill_edit(text, text, bigint) from public, anon, authenticated;

-- A guest naming the payer (spec §3.8, §5.5, E24, E25). payer_user_id sits in Zone 2, so this is not a
-- field update — it is a causal one, and it implements the SAME rule as merge_expense:
--
--   base matches  (canonical.split_version <= p_base_split_version) -> apply and advance
--   base is stale (canonical.split_version >  p_base_split_version) -> refuse, and say who the payer is
--
-- E25 is the whole reason: two guests both tap "I paid", the second read a version that has since moved,
-- and she is TOLD ("Andrew is the payer now") rather than silently losing or silently winning. A blind
-- update would make the last request win regardless of what it was deciding against, which is the
-- wall-clock model this schema replaced everywhere else.
--
-- The named payer must already be a participant of this bill. A token authorises writes to THIS bill
-- (spec §4.3), never the nomination of an arbitrary user id as somebody's creditor.
create or replace function public.set_web_bill_payer(
  p_expense_id text,
  p_user_id text,
  p_base_split_version bigint,
  p_actor text,
  p_now bigint
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_e public.expenses%rowtype;
  v_name text;
begin
  select * into v_e from public.expenses where id = p_expense_id for update;
  if not found or v_e.deleted_at is not null then
    raise exception 'set_web_bill_payer: bill not found';
  end if;

  if not exists (
    select 1 from public.bill_participants
    where expense_id = p_expense_id and user_id = p_user_id and deleted_at is null
  ) then
    raise exception 'set_web_bill_payer: not a participant of this bill';
  end if;

  if v_e.split_version > p_base_split_version then
    select display_name into v_name from public.users where id = v_e.payer_user_id;
    return jsonb_build_object(
      'ok', false, 'stale', true,
      'payer_user_id', v_e.payer_user_id, 'payer_name', v_name,
      'split_version', v_e.split_version);
  end if;

  update public.expenses set
    payer_user_id      = p_user_id,
    payer_outside_name = null,
    split_version      = v_e.split_version + 1,
    split_updated_by   = p_actor,
    updated_at         = greatest(v_e.updated_at, p_now),
    row_version        = v_e.row_version + 1
  where id = p_expense_id;

  select display_name into v_name from public.users where id = p_user_id;
  return jsonb_build_object(
    'ok', true, 'stale', false,
    'payer_user_id', p_user_id, 'payer_name', v_name,
    'split_version', v_e.split_version + 1);
end;
$$;
revoke all on function public.set_web_bill_payer(text, text, bigint, text, bigint)
  from public, anon, authenticated;
-- All four functions above are edge-function-only, so `authenticated` is named in every revoke as well
-- as `anon`. Supabase grants EXECUTE to both at creation time regardless of `revoke ... from public`
-- (see join_item_portion's note); the service key bypasses grants entirely, which is how web-claim
-- reaches them. Nothing in the app calls these — the payer's own undo is a local-first Room write that
-- reaches the server through merge_expense like every other in-app money edit.

-- ── Apple Sign In token revocation (APPLE_SIGNIN_NATIVE_PLAN.md §5 P4, Guideline 5.1.1(v)) ────────
-- Server-side store for the Apple refresh token minted by exchanging a native Sign In with Apple
-- authorization code (apple-link-token edge function). It exists purely so account deletion can call
-- Apple's /auth/revoke (apple-revoke-token edge function) — the native flow's identity token alone
-- cannot be revoked, only a refresh/access token obtained via the code exchange.
create table if not exists public.apple_oauth_tokens (
  -- Cascades on auth.users delete like receipt_scan_log: this is ephemeral operational auth data, not
  -- user-facing financial/profile data, and the row is meaningless once the login credential is gone.
  user_id uuid primary key references auth.users(id) on delete cascade,
  refresh_token text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.apple_oauth_tokens enable row level security;
-- Deliberately no policies, permanently: only the apple-link-token/apple-revoke-token edge functions'
-- service-role key ever touches this table (matching web_sessions/web_bill_links). A refresh token is
-- a bearer credential for the user's Apple account; any policy here would let an authenticated caller
-- read another user's row via the permissive for-all-tables loop this table deliberately sits outside.

-- ── Evenly Pro: group passes (PRO_PASS_SPEC.md §5) ───────────────────────────────────────────────
-- The entitlement behind "this group has unlimited receipt scans". RevenueCat tells us a purchase
-- happened; THIS TABLE decides who is Pro. It has to work that way: the other five people in the group
-- bought nothing, so the buyer's device-side CustomerInfo cannot be the source of truth for them.
--
-- A pass is bound to ONE group at purchase and is not transferable — that is what is being sold, and
-- the paywall says so before the charge. Synced (Room mirrors it, SyncEngine pulls it, the doorbell
-- wakes it) so every member's device learns the group went Pro through the normal pull, with no push
-- and no special-casing.
create table if not exists public.group_passes (
  id             text primary key,
  group_id       text not null,
  -- users.id (text), not auth.users(id) — synced tables carry no FKs, since rows arrive in
  -- dependency-arbitrary order (data/AGENTS.md).
  purchased_by   text not null,
  tier           text not null,
  store          text not null,
  -- RevenueCat's store transaction id. (store, store_txn_id) is the IDEMPOTENCY KEY: the client's
  -- activate call, a webhook retry, and the reconciliation sweep all race to insert the same purchase,
  -- and exactly one may win. Without it one $0.99 charge becomes three stacked passes.
  store_txn_id   text not null,
  rc_app_user_id text not null,
  -- Epoch millis on the SERVER clock, never the device's. A phone with its clock wound back would
  -- otherwise buy a week and get a decade.
  starts_at      bigint not null,
  expires_at     bigint not null,
  -- Refund / chargeback (revenuecat-webhook). The group drops back to free at the next pull; nothing
  -- already scanned is ever taken away.
  revoked_at     bigint,
  deleted_at     bigint,
  deleted_by     text,
  created_at     bigint not null,
  updated_at     bigint not null,
  row_version    bigint not null default 1
);

do $$
begin
  alter table public.group_passes drop constraint if exists group_passes_tier_check;
  alter table public.group_passes add constraint group_passes_tier_check
    check (tier in ('week_1', 'week_2', 'month_1'));
  alter table public.group_passes drop constraint if exists group_passes_store_check;
  -- `test_store` is RevenueCat's Test Store (simulated money, no store product needed). It is a real
  -- row in every other respect, so it is a distinct value rather than folded into `promo`: a test pass
  -- must never read as revenue, and must stay findable when the test config is torn down. The edge
  -- function gates it behind `PRO_ALLOW_TEST_STORE`, so widening this constraint grants nothing on its own.
  alter table public.group_passes add constraint group_passes_store_check
    check (store in ('app_store', 'play_store', 'promo', 'test_store'));
end $$;

create unique index if not exists group_passes_txn_idx on public.group_passes (store, store_txn_id);
create index if not exists group_passes_group_idx on public.group_passes (group_id, expires_at desc);

-- ⚠️ DELIBERATELY OUTSIDE the membership loop above, and it must stay outside. Membership-scoped
-- WRITES are not enough here: every arm of that loop grants insert/update to any ACTIVE member, so a
-- member of their own one-person group could still insert themselves a pass expiring in 2099. That is
-- free unlimited paid Claude-vision calls for anyone who reads the anon key out of the APK. This table
-- is read-only to clients, full stop — which is why it could not wait for the pre-prod RLS work.
alter table public.group_passes enable row level security;

-- Read: members of the group only. Members need this to render the Pro badge and to know who paid.
drop policy if exists group_passes_member_read on public.group_passes;
create policy group_passes_member_read on public.group_passes
  for select to authenticated
  using (
    exists (
      select 1 from public.members m
      where m.group_id = group_passes.group_id
        and m.user_id = (select auth.uid())::text
        and m.status = 'ACTIVE'
    )
  );

-- No insert/update/delete policy, ever. Only activate-pass and revenuecat-webhook (service key) write
-- here, and the service role bypasses RLS. The client's SyncEngine must PULL this table and never push
-- it; a push would fail silently against this policy set, which is the intended outcome.
revoke insert, update, delete on public.group_passes from anon, authenticated;
-- TRUNCATE is revoked SEPARATELY and deliberately: Supabase grants ALL on a new public table to
-- anon/authenticated, and **RLS does not apply to TRUNCATE**. Revoking insert/update/delete alone
-- therefore still leaves any signed-in user able to wipe the entire table in one statement, read-policy
-- or not. Verified against a live grants query, not assumed.
--
-- This used to be true of EVERY table here, including the zero-policy ones (superseded_split_edits,
-- web_bill_links, web_sessions, apple_oauth_tokens), whose "no policies" was not the protection it
-- read as. The project-wide sweep now runs in the Row-Level Security section above; this table keeps
-- its own revoke so it stays covered even if that sweep is ever reordered.
revoke truncate, references, trigger on public.group_passes from anon, authenticated;

-- Doorbell, so the buyer's purchase wakes the other five phones (same statement-level AFTER
-- INSERT/UPDATE pattern as every other synced table).
drop trigger if exists bump_activity_ins on public.group_passes;
drop trigger if exists bump_activity_upd on public.group_passes;
create trigger bump_activity_ins after insert on public.group_passes
  referencing new table as new_rows for each statement
  execute function public.bump_group_activity();
create trigger bump_activity_upd after update on public.group_passes
  referencing new table as new_rows for each statement
  execute function public.bump_group_activity();

-- ── Evenly Pro, route 2: the personal subscription (PRO_PASS_SPEC.md §5.2) ────────────────────────
-- RevenueCat renders the paywall for THIS product and not for passes, because its paywall editor
-- cannot display consumables. That is the whole reason the subscription exists: a remotely designed,
-- A/B-testable paywall. The pass survives alongside it as the door that cannot bill you twice.
--
-- One row per PERSON, not per purchase: a store account holds at most one live subscription at a time,
-- so a renewal UPDATES this row rather than appending. Billing history stays in RevenueCat, which is
-- better at it than we are.
create table if not exists public.user_subscriptions (
  user_id        text primary key,
  product_id     text not null,
  store          text not null,
  rc_app_user_id text not null,
  period         text not null,
  started_at     bigint not null,
  -- The paid-through date on the SERVER clock. Someone who cancels stays Pro until the end of the
  -- period they already paid for, which is both correct and the difference between a lapsed
  -- subscription and a support ticket.
  expires_at     bigint not null,
  -- Renders "renews 16 Aug" vs "ends 16 Aug". It NEVER decides entitlement — expires_at alone does.
  will_renew     boolean not null default true,
  revoked_at     bigint,
  updated_at     bigint not null,
  row_version    bigint not null default 1
);

do $$
begin
  alter table public.user_subscriptions drop constraint if exists user_subscriptions_store_check;
  -- See the note on group_passes_store_check.
  alter table public.user_subscriptions add constraint user_subscriptions_store_check
    check (store in ('app_store', 'play_store', 'promo', 'test_store'));
  alter table public.user_subscriptions drop constraint if exists user_subscriptions_period_check;
  alter table public.user_subscriptions add constraint user_subscriptions_period_check
    check (period in ('monthly', 'annual'));
end $$;

create index if not exists user_subscriptions_live_idx on public.user_subscriptions (expires_at desc);

-- ⚠️ Same exemption from the permissive `_rw` loop as group_passes, for the same reason: under
-- `using (true)` any authenticated user could insert themselves a subscription expiring in 2099 and
-- hand every group they join unlimited paid Claude-vision calls.
alter table public.user_subscriptions enable row level security;

-- Read is scoped to people you actually share a group with. The badge names whoever is paying
-- (PRO_PASS_SPEC.md §8.5), which is the social half of the design, but one stranger's billing state
-- must not be readable by another. Note what is NOT in this table and so can never leak: price, store
-- receipt, or anything a support conversation would need. That lives in RevenueCat.
drop policy if exists user_subscriptions_shared_group_read on public.user_subscriptions;
create policy user_subscriptions_shared_group_read on public.user_subscriptions
  for select to authenticated
  using (
    exists (
      select 1
      from public.members me
      join public.members them on them.group_id = me.group_id
      where me.user_id = (select auth.uid())::text
        and me.status = 'ACTIVE'
        and them.user_id = user_subscriptions.user_id
        and them.status = 'ACTIVE'
    )
  );

-- Only sync-subscriber and revenuecat-webhook (service key) write here. TRUNCATE is revoked separately
-- because RLS does not apply to it and Supabase grants ALL on a new public table by default — see the
-- longer note on group_passes above.
revoke insert, update, delete on public.user_subscriptions from anon, authenticated;
revoke truncate, references, trigger on public.user_subscriptions from anon, authenticated;

-- The doorbell for a subscription has to wake EVERY group the subscriber is in, not one: a single
-- renewal changes the badge in all of them. This is why it cannot reuse bump_group_activity, which
-- reads a group_id off the changed row and there is no group_id on this table by design.
create or replace function public.bump_group_activity_subscriber()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  begin
    insert into public.group_activity (group_id, bumped_at)
    select distinct m.group_id, now()
    from new_rows n
    join public.members m on m.user_id = n.user_id and m.status = 'ACTIVE'
    on conflict (group_id) do update set bumped_at = excluded.bumped_at
      where group_activity.bumped_at is distinct from excluded.bumped_at;
  exception when others then
    null;
  end;
  return null;
end;
$$;

drop trigger if exists bump_activity_ins on public.user_subscriptions;
drop trigger if exists bump_activity_upd on public.user_subscriptions;
create trigger bump_activity_ins after insert on public.user_subscriptions
  referencing new table as new_rows for each statement
  execute function public.bump_group_activity_subscriber();
create trigger bump_activity_upd after update on public.user_subscriptions
  referencing new table as new_rows for each statement
  execute function public.bump_group_activity_subscriber();

-- The single definition of "is this group Pro right now", used by extract-receipt's enforcement check.
-- The client mirrors this in Kotlin over its local rows so the badge works offline; two implementations
-- of one rule is a real wart, accepted so that rendering a badge costs no round trip. Pin both with the
-- same vectors.
--
-- Returns AT MOST ONE ROW, and NO ROW means "not Pro" — there is no `is_pro = false` row to read. A
-- caller that forgets this reads an empty result as an error instead of as the free tier.
--
-- Returns the LATEST-EXPIRING live candidate, which is what makes stacking fall out for free: buying
-- while a pass is live inserts a row starting at the current expiry (PRO_PASS_SPEC.md §5.4), so two
-- friends who each buy a week give the group two weeks and neither feels robbed. The same ordering is
-- what settles a pass and a subscription overlapping.
--
-- `expires_at > p_now` is strict: an entitlement whose expiry equals now has expired. Pinned by the
-- boundary test rather than left to whichever comparison the caller happened to write.
--
-- There are now TWO routes to Pro and this function is the ONLY place that knows it. extract-receipt
-- and export_group both call it and neither had to change when the subscription was added, which is
-- the entire reason enforcement was funnelled through one function in the first place.
--
-- `source` says which route answered, so "who paid for this?" stays answerable without the caller
-- knowing anything about passes or subscriptions. `tier` carries the pass tier or the subscription
-- period; overloading one column is a small wart, taken deliberately so that adding the second route
-- was not a breaking change for every caller's column mapping.
--
-- Adding that column IS a breaking signature change, though: Postgres refuses to alter a function's
-- return type through `create or replace`, so it is dropped and recreated. Safe to run alone, and
-- verified rather than assumed: both callers test only whether a ROW CAME BACK
-- (`extract-receipt:361`, `export_group:110`), never a column, so neither needs redeploying for this.
-- A caller that ever starts destructuring columns loses that property and would then have to ship in
-- the same migration.
drop function if exists public.group_pro_status(text, bigint);

create function public.group_pro_status(p_group_id text, p_now bigint)
returns table (is_pro boolean, expires_at bigint, purchased_by text, tier text, source text)
language sql
stable
security definer
set search_path = public
as $$
  select true, c.expires_at, c.purchased_by, c.tier, c.source
  from (
    -- Route 1: a pass bought FOR this group, by anyone.
    select p.expires_at, p.purchased_by, p.tier, 'pass'::text as source
    from public.group_passes p
    where p.group_id = p_group_id
      and p.deleted_at is null
      and p.revoked_at is null
      and p.expires_at > p_now
    union all
    -- Route 2: any ACTIVE member of this group holding a live personal subscription.
    --
    -- Membership is JOINED here rather than materialised into a per-group grant row, so that leaving a
    -- group drops it back to free at the next pull with no second table to keep in step. The cost is
    -- this join on the scan path; the alternative is a grant table that can silently disagree with the
    -- roster, which in a money app is the worse trade.
    select s.expires_at, s.user_id, s.period, 'subscription'::text
    from public.user_subscriptions s
    join public.members m on m.user_id = s.user_id
    where m.group_id = p_group_id
      and m.status = 'ACTIVE'
      and s.revoked_at is null
      and s.expires_at > p_now
  ) c
  order by c.expires_at desc
  limit 1;
$$;

-- security definer so the edge function's enforcement check reads the same rows regardless of caller,
-- but that means the membership scoping in the read policy above does NOT apply here — hence the
-- explicit revoke. `anon` and `authenticated` get EXECUTE automatically at creation time no matter what
-- `revoke ... from public` says (see join_item_portion's note), and this function would otherwise let
-- any caller probe any group's pass state by id.
revoke execute on function public.group_pro_status(text, bigint) from public, anon, authenticated;

-- extract-receipt logs a refused scan BEFORE any paid call, and "you have no scans left" must be
-- distinguishable from "you are going too fast" (rate_limited) in the cost ledger — they lead to
-- different screens and, later, different pricing decisions.
do $$
begin
  alter table public.receipt_scan_log drop constraint if exists receipt_scan_log_outcome_check;
  alter table public.receipt_scan_log add constraint receipt_scan_log_outcome_check
    check (outcome is null or outcome in ('ok', 'not_receipt', 'invalid_draft', 'failed', 'rate_limited', 'breaker_open', 'quota_exhausted'));
end $$;

-- The free allowance (PRO_PASS_SPEC.md §4): 5 successful scans per group, for the life of the group,
-- never reset. No new counting table — receipt_scan_log already carries group_id (the Plan A cost
-- ledger columns above), which is exactly this count.
--
-- Only `outcome = 'ok'` counts. A blurry photo, a non-receipt, a server failure or a refused attempt
-- must never burn someone's free scan: being charged for our own failure is a support ticket and a bad
-- review. The gap that leaves (garbage photos cost us money without costing the user an allowance) is
-- bounded by the per-user hourly rate limit and the Opus circuit breaker, which already exist.
create or replace function public.group_free_scans_used(p_group_id text)
returns integer
language sql
stable
security definer
set search_path = public
as $$
  select count(*)::integer
  from public.receipt_scan_log l
  where l.group_id = p_group_id
    and l.outcome = 'ok';
$$;

revoke execute on function public.group_free_scans_used(text) from public, anon, authenticated;

-- The meter's read path ("2 of 5 free scans left"). The count itself lives in `receipt_scan_log`, whose
-- RLS scopes a client to its OWN rows, so a member cannot see their group's total by querying it — hence
-- a membership-checked `security definer` wrapper rather than a policy change.
--
-- Caller-scoped on purpose: it answers only for a group the CALLER is an active member of, so it cannot
-- be used to probe any group's usage by id. `group_free_scans_used` stays revoked; this is the only door.
create or replace function public.my_group_scan_usage(p_group_id text)
returns table (free_used integer, free_limit integer)
language plpgsql
stable
security definer
set search_path = public
as $$
begin
  if not exists (
    select 1 from public.members m
    where m.group_id = p_group_id
      and m.user_id = (select auth.uid())::text
      and m.status = 'ACTIVE'
  ) then
    raise exception 'not a member of this group';
  end if;

  return query
    select
      public.group_free_scans_used(p_group_id),
      -- Mirrors extract-receipt's FREE_SCANS_PER_GROUP default. The server env var is the enforcing
      -- copy; this one only feeds the meter, so a divergence miscounts a label and never a refusal.
      -- If the allowance is ever tuned from the dashboard, change it here too.
      5;
end;
$$;

revoke execute on function public.my_group_scan_usage(text) from public, anon;
grant execute on function public.my_group_scan_usage(text) to authenticated;

-- ── waitlist_signups ───────────────────────────────────────────────────────────────────────────
-- Pre-launch email capture for split-evenly.app/waitlist. Deliberately not app data: no group_id, no
-- user_id, no `updated_at`, and nothing syncs it to a device. It is a list of strangers, so it never
-- joins the sync tables and must never be added to the `supabase_realtime` publication.
--
-- RLS is enabled with NO policies, which denies anon and authenticated outright. The only writer is
-- the `waitlist` edge function holding the service key. That is the whole authorisation model, and it
-- is the reason a leaked anon key cannot dump the list -- the same boundary `web-claim` relies on.
create table if not exists public.waitlist_signups (
  id            uuid primary key default gen_random_uuid(),
  -- What they typed, kept for display and for the eventual "Hi Sam" mail merge.
  email         text not null,
  -- Lowercased and trimmed. Unique, so a double-tap or a second visit is a no-op rather than a
  -- duplicate send later. Dedupe lives here rather than in the function so a race can't beat it.
  email_norm    text not null,
  -- Which surface sent them, for attribution once ads are running.
  source        text,
  created_at    timestamptz not null default now(),
  constraint waitlist_signups_email_norm_key unique (email_norm)
);

alter table public.waitlist_signups enable row level security;

-- Belt and braces on top of "no policies": revoke the table grants PostgREST relies on, so a future
-- permissive policy added by mistake still does not expose the list to the anon key. Verified: anon
-- gets 42501 permission denied on both select and insert.
revoke all on public.waitlist_signups from anon, authenticated;

-- ══════════════════════════════════════════════════════════════════════════════════════════════
-- Admin dashboard & feedback (ADMIN_FEEDBACK_SPEC.md)
--
-- Three tables and one aggregation RPC, all with the same posture as `waitlist_signups` above: RLS
-- enabled, NO policies, grants revoked from anon/authenticated. Nothing here is app data, nothing
-- here syncs to a device, and nothing here may ever be added to the `supabase_realtime` publication.
-- The only readers/writers are the `admin` and `feedback` edge functions holding the service key.
-- ══════════════════════════════════════════════════════════════════════════════════════════════

-- ── admin_users ────────────────────────────────────────────────────────────────────────────────
-- The allowlist. Signing in with Google is necessary but not sufficient: a row here is what grants
-- access, and revoking an admin is deleting one row with no shared secret to rotate.
--
-- Allowlisting by EMAIL, never by domain (spec §2.1), and the reason is worth keeping next to the
-- table so nobody "simplifies" into it later: the owner account is a gmail.com address, and a domain
-- rule on gmail.com admits every Google account on earth.
--
-- There is deliberately no bootstrap path in code. The first row is inserted by hand after the first
-- sign-in attempt creates the auth.users row (see `admin/README.md`); an env-var-seeded "if the table
-- is empty, trust this email" branch in the edge function would be a permanent backdoor to save a
-- one-time SQL statement.
create table if not exists public.admin_users (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  -- Denormalized from auth.users so the dashboard can show who is on the list without a join into
  -- the auth schema. Display only: the gate matches on user_id, never on this column.
  email       text not null,
  added_at    timestamptz not null default now(),
  added_by    uuid references auth.users(id)
);

alter table public.admin_users enable row level security;
-- Deliberately no policies. An authenticated non-admin being able to read this table would hand them
-- the exact list of accounts worth phishing.
revoke all on public.admin_users from anon, authenticated;

-- ── feedback_tickets ───────────────────────────────────────────────────────────────────────────
-- One table behind three entry points (in-app, /feedback on the web, the guest claim flow).
--
-- v1 is WRITE-ONLY from the client: nothing reads a ticket back into the app. That is what keeps this
-- table out of the sync engine entirely. If a "your past tickets" screen is ever built it becomes a
-- synced entity and lands under `data/AGENTS.md`'s rules and the schema-before-entity hook, which is
-- a much bigger decision than it looks.
create table if not exists public.feedback_tickets (
  id              uuid primary key default gen_random_uuid(),
  -- Null for web/anonymous submissions. NOT a foreign key: if someone deletes their account you still
  -- want the bug report, so this holds the id and tolerates a dangling one. `text` rather than `uuid`
  -- because the app's own user ids are text everywhere else in this schema.
  user_id         text,
  submitter_name  text,          -- web only, optional
  submitter_email text,          -- web only, the address a manual reply goes to (spec §5.1)
  type            text not null,
  category        text not null,
  message         text not null,
  title           text,          -- null in v1; auto-titles are §4.4, and deliberately not built
  status          text not null default 'new',
  -- Internal, and never shown to the submitter. Stated in the schema as well as the spec because that
  -- is exactly the kind of thing that leaks once a "your ticket" screen gets built.
  admin_note      text,
  source          text not null,
  app_version     text,          -- app only; half of triaging a bug is knowing if it is already fixed
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);

-- Enumerations as check constraints rather than Postgres enums: adding a category later is an `alter
-- ... drop constraint` + `add constraint` in an idempotent migration, where an enum needs `alter type`
-- and cannot drop a value at all. The submit function validates the same lists; this is the backstop
-- that makes a bug there a 500 instead of a garbage row.
alter table public.feedback_tickets drop constraint if exists feedback_tickets_type_chk;
alter table public.feedback_tickets add constraint feedback_tickets_type_chk
  check (type in ('problem', 'suggestion', 'question'));

alter table public.feedback_tickets drop constraint if exists feedback_tickets_category_chk;
alter table public.feedback_tickets add constraint feedback_tickets_category_chk
  check (category in ('payments', 'account', 'missing_expense', 'splitting', 'design', 'other'));

alter table public.feedback_tickets drop constraint if exists feedback_tickets_status_chk;
alter table public.feedback_tickets add constraint feedback_tickets_status_chk
  check (status in ('new', 'in_progress', 'resolved', 'wont_fix', 'duplicate'));

alter table public.feedback_tickets drop constraint if exists feedback_tickets_source_chk;
alter table public.feedback_tickets add constraint feedback_tickets_source_chk
  check (source in ('app_ios', 'app_android', 'web', 'web_claim'));

-- The queue's default view is `new` only (spec §5), newest first. Partial index so that read stays
-- cheap regardless of how large the resolved pile gets.
create index if not exists feedback_tickets_open_idx
  on public.feedback_tickets (created_at desc) where status = 'new';
create index if not exists feedback_tickets_created_idx
  on public.feedback_tickets (created_at desc);

alter table public.feedback_tickets enable row level security;
-- Deliberately no policies: user-submitted text including email addresses, readable only through the
-- admin function's service key after its allowlist check.
revoke all on public.feedback_tickets from anon, authenticated;

-- ── public_write_log — rate limiting for the unauthenticated endpoints (spec §8) ────────────────
-- 30 submissions per hour per principal, on `waitlist` and `feedback`. Mirrors `web_claim_write_log`
-- and `receipt_scan_log`: insert-only, service-role-only, counted in a rolling window.
--
-- `principal_hash` is sha256(kind + ':' + ip-or-user-id) and never the address itself. An IP is
-- personal data under the "no PII in logs" rule (§8), and this table only ever needs to answer "have
-- I seen this same caller 30 times in the last hour", which a hash answers exactly as well.
create table if not exists public.public_write_log (
  id             uuid primary key default gen_random_uuid(),
  kind           text not null,  -- 'waitlist' | 'feedback' | 'invite-preview'
  principal_hash text not null,
  created_at     timestamptz not null default now()
);

create index if not exists public_write_log_window_idx
  on public.public_write_log (kind, principal_hash, created_at desc);

alter table public.public_write_log enable row level security;
-- Deliberately no policies -- only the edge functions' service key touches this table.
revoke all on public.public_write_log from anon, authenticated;

-- ── admin_waitlist_stats — the growth chart, aggregated in SQL (spec §3.2) ──────────────────────
-- Aggregation happens here and never by shipping every row to the browser to count there. Invisible
-- at a thousand rows and five lines either way, so there is no reason to write the version that stops
-- working.
--
-- Returns one object so the chart is one round trip:
--   baseline -- signups strictly before the window, the starting height of the cumulative line
--   total    -- every signup ever, for the headline number
--   days     -- DENSE, one entry per calendar day in the window including the zeros. Pre-launch there
--               are genuine zero days and a chart that silently omits them draws a lie.
--   bySource -- sparse (day, source, n), for the stacked breakdown. Dense here would be days x
--               sources rows to carry mostly zeros the caller can infer.
--
-- `security definer` with a pinned `search_path`, and EXECUTE revoked from every client role: the
-- only caller is the admin edge function's service key, after its allowlist check.
create or replace function public.admin_waitlist_stats(p_from timestamptz default null)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_from  timestamptz;
  v_today date := (now() at time zone 'utc')::date;
  v_result jsonb;
begin
  -- Null `p_from` is the "all" toggle: start at the first signup, or today if there are none yet.
  v_from := coalesce(
    p_from,
    (select min(created_at) from public.waitlist_signups),
    now()
  );

  select jsonb_build_object(
    'from', v_from,
    'baseline', (select count(*) from public.waitlist_signups where created_at < v_from),
    'total',    (select count(*) from public.waitlist_signups),
    'days', coalesce((
      -- `d.day::date` matters: generate_series over dates yields TIMESTAMPS, and an un-cast value
      -- serializes as "2026-08-16T00:00:00", which the chart parses as an invalid date.
      select jsonb_agg(jsonb_build_object('day', d.day::date, 'n', coalesce(c.n, 0)) order by d.day)
      from generate_series((v_from at time zone 'utc')::date, v_today, interval '1 day') as d(day)
      left join (
        select (created_at at time zone 'utc')::date as day, count(*) as n
        from public.waitlist_signups
        where created_at >= v_from
        group by 1
      ) c on c.day = d.day::date
    ), '[]'::jsonb),
    'bySource', coalesce((
      select jsonb_agg(jsonb_build_object('day', s.day, 'source', s.source, 'n', s.n) order by s.day)
      from (
        select (created_at at time zone 'utc')::date as day,
               coalesce(source, 'unknown') as source,
               count(*) as n
        from public.waitlist_signups
        where created_at >= v_from
        group by 1, 2
      ) s
    ), '[]'::jsonb)
  ) into v_result;

  return v_result;
end;
$$;

revoke execute on function public.admin_waitlist_stats(timestamptz) from public, anon, authenticated;

-- ── Group deletion: delete for everyone → 30-day Recently deleted → nightly purge ────────────────
-- A group can now be deleted, not only left. Deleting is for EVERYONE (that is the whole point: the
-- alternative was messaging five people individually to abandon a duplicate group), so it is
-- deliberately recoverable for 30 days by ANY member from Home → Recently deleted.
--
-- The delete and the restore are NOT RPCs. They are ordinary local-first Room writes to
-- `groups.deleted_at`/`deleted_by` that ride the existing `SyncEngine` push like every other
-- soft-delete in the app, which is what makes both work offline. Two existing properties carry them:
--   • RLS is membership-scoped and never filters `deleted_at`, so a deleted group stays readable and
--     writable by its members — that is what lets any of them restore it.
--   • `members` rows stay ACTIVE on delete. Soft-leaving them (the obvious-looking move) would make
--     `is_group_member` false and revoke everyone's access to the very row they need to restore, AND
--     drop the group out of `SyncEngine.pull`'s `activeGroupIds`, so no other device would ever learn
--     it was deleted. Do not "tidy" that up.
--
-- Only the purge needs the server, because only the server can act 30 days later.

alter table public.groups add column if not exists deleted_by text;

-- The nightly scan reads only tombstones; without this it is a seq scan of every group forever.
create index if not exists groups_deleted_at_idx
  on public.groups (deleted_at) where deleted_at is not null;

-- DESTRUCTIVE OPERATION — the one sanctioned hard delete of user data in this schema.
--
-- What it does: 30 days after a group was deleted, permanently removes that group and all 23 of its
-- row sets (listed in order below), its placeholder `users` rows, and its receipt bytes under the
-- `receipts/<group_id>/` prefix.
--
-- Why this is exempt from `data/AGENTS.md` Rule 1 (never hard-delete user data), decided by the owner
-- 2026-08-17: a soft delete exists so a deletion can propagate and be undone. Here it is already
-- deleted for every member, no member has any surface that can reach it, and the 30 days to change
-- their mind have elapsed. A tombstone nobody can read is not a record, it is a copy of private
-- financial data we promised to delete and then kept.
--
-- Pre-checks (true as of 2026-08-17):
--   • Which app versions read these rows after a purge? None. No shipped version has group delete at
--     all, and from this version on a client purges its own local copy on the same 30-day rule, so it
--     never asks the server for a purged group (`SyncEngine.pull` scopes to ACTIVE memberships, and
--     the `members` rows are gone).
--   • Is anything of value lost that lives nowhere else? Yes, deliberately and by request — this is
--     the point of the feature, and it is gated behind a for-everyone delete, a typed group-name
--     confirmation, a push to every member, and 30 days in Recently deleted.
--   • Recovery path: Supabase PITR, once P0 #6 is done. Until then, none. That is stated plainly
--     rather than dressed up: this function must not be scheduled on a project without PITR.
--
-- NOT purged, on purpose:
--   • `group_passes` — an Evenly Pro pass is a PURCHASE, not group content. Support and finance need
--     the record after the group is gone, and a member deleting a group must never quietly destroy
--     what another member paid for. Its `group_id` is left dangling by design. The confirm sheet
--     tells the deleter the pass is not refunded.
--   • `receipt_scan_log` — the per-scan cost ledger and rate-limit history. It is operational and
--     per-USER; erasing it on group delete would also hand anyone a way to reset their own scan quota.
--   • `user_subscriptions`, `users` (real accounts) — per-person, and shared across groups.
create or replace function public.purge_deleted_groups()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  target_id text;
  purged int := 0;
  now_ms bigint;
  grace_period_ms constant bigint := 30::bigint * 24 * 60 * 60 * 1000;
begin
  now_ms := (extract(epoch from now()) * 1000)::bigint;

  for target_id in
    select id from public.groups
    where deleted_at is not null
      and deleted_at < now_ms - grace_period_ms
  loop
    -- Per-group nested block, matching purge_deleted_accounts(): one unpurgeable group must not
    -- abort the whole nightly batch and leave every later group un-purged forever.
    begin
      -- `shares` is the only table with no `group_id`; it reaches the group through its expense, so
      -- it must go BEFORE the expenses that identify it.
      delete from public.shares
        where expense_id in (select id from public.expenses where group_id = target_id);

      delete from public.item_claims            where group_id = target_id;
      delete from public.item_shares            where group_id = target_id;
      delete from public.expense_items          where group_id = target_id;
      delete from public.bill_participants      where group_id = target_id;
      delete from public.pending_item_edits     where group_id = target_id;
      delete from public.settlement_allocations where group_id = target_id;
      delete from public.settlements            where group_id = target_id;
      delete from public.comments               where group_id = target_id;
      delete from public.expense_blocked_users  where group_id = target_id;
      delete from public.receipts               where group_id = target_id;
      delete from public.payment_proofs         where group_id = target_id;
      delete from public.expense_history        where group_id = target_id;
      delete from public.conflicts              where group_id = target_id;
      delete from public.expense_edit_conflicts where group_id = target_id;
      delete from public.superseded_split_edits where group_id = target_id;
      delete from public.expenses               where group_id = target_id;
      delete from public.categories             where group_id = target_id;
      delete from public.placeholder_claim_answers where group_id = target_id;

      -- The web-claim trio. The write log keys on token_hash only, so it has to be resolved through
      -- the links before those are removed.
      delete from public.web_claim_write_log
        where token_hash in (select token_hash from public.web_bill_links where group_id = target_id);
      delete from public.web_bill_links         where group_id = target_id;
      delete from public.web_sessions           where group_id = target_id;

      -- Placeholders are group-private by construction (`is_placeholder` + `placeholder_group_id`),
      -- so they die with the group. Real accounts are never touched here.
      delete from public.users
        where is_placeholder and placeholder_group_id = target_id;

      -- Receipt bytes. Prefix-scoped to this one group (`ReceiptUploadManager` writes
      -- `<group_id>/<expense_id>/<receipt_id>.<ext>`), never bucket-wide.
      delete from storage.objects
        where bucket_id = 'receipts' and name like target_id || '/%';

      delete from public.group_activity         where group_id = target_id;
      delete from public.members                where group_id = target_id;
      delete from public.groups                 where id = target_id;

      purged := purged + 1;
    exception when others then
      raise warning 'purge_deleted_groups: failed for group %: %', target_id, sqlerrm;
    end;
  end loop;

  if purged > 0 then
    raise notice 'purge_deleted_groups: purged % group(s)', purged;
  end if;
end;
$$;

-- Supabase auto-grants EXECUTE to anon/authenticated at creation time, independent of `revoke from
-- public`. This one is cron-only and must be callable by nobody else.
revoke all on function public.purge_deleted_groups() from public, anon, authenticated;

-- ⚠️ DELIBERATELY NOT SCHEDULED. The function above exists and is correct; nothing calls it.
--
-- Its own header says it "must not be scheduled on a project without PITR", and P0 #6 in
-- `data/AGENTS.md` (Point-in-Time Recovery + a rehearsed restore) is still open. It WAS scheduled on
-- the live project on 2026-08-17 and unscheduled the same day once that contradiction was spotted —
-- do not re-add the schedule here to "fix" the gap it leaves.
--
-- What the gap actually is: deleted groups accumulate tombstones past 30 days instead of being
-- erased. Delete, restore, and Recently deleted all work exactly as designed; the client still purges
-- its own local copy on day 30, so no user ever sees a group they cannot restore. The only thing not
-- happening is the server-side erase we promise in the confirm sheet ("gone for good, including from
-- our servers"), which makes turning this on a prerequisite for launch rather than a nice-to-have.
--
-- To enable, once PITR is on and a test restore has actually been performed:
--
--   select cron.schedule('purge-deleted-groups', '15 3 * * *', 'select public.purge_deleted_groups()');
--
-- 03:15 UTC is deliberate: 15 minutes after `purge-deleted-accounts` so the two never interleave on
-- the same `members` rows. `cron.schedule` upserts by jobname, so running it twice is safe.

-- ── Payment edits: only the two parties (one-time-splits Part B, B1) ────────────────────────────────
-- Membership RLS lets any ACTIVE member write any settlement row. A payment's AMOUNT and whether it is
-- live are now the two parties' business only (from_user_id, to_user_id). This is a trigger, not a
-- policy, on purpose: the push is a full-row upsert, so a policy on UPDATE would also refuse the
-- unchanged rows a third member's device re-sends, and a placeholder claim's reassignment of
-- from/to (written by the claimer, who is not yet the old row's party). The trigger refuses only the
-- columns that move money. `auth.uid()` is null for the service role (`web-claim`), which does its own
-- party check in `record_web_payment` / `edit_web_payment` below.
create or replace function public.settlement_party_guard()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid text := (select auth.uid())::text;
begin
  if v_uid is null then
    return new;
  end if;
  if tg_op = 'INSERT' then
    -- A replacement is an edit, so it carries the same rule. A brand-new payment does not.
    if new.original_settlement_id is not null and v_uid not in (new.from_user_id, new.to_user_id) then
      raise exception 'only the two people in a payment can change it' using errcode = '42501';
    end if;
    return new;
  end if;
  if (new.payment_amount_subunits is distinct from old.payment_amount_subunits
      or (old.deleted_at is null) <> (new.deleted_at is null)
      or new.payment_currency is distinct from old.payment_currency)
     and v_uid not in (old.from_user_id, old.to_user_id) then
    raise exception 'only the two people in a payment can change it' using errcode = '42501';
  end if;
  if (new.from_user_id is distinct from old.from_user_id or new.to_user_id is distinct from old.to_user_id)
     and v_uid not in (new.from_user_id, new.to_user_id) then
    raise exception 'only the two people in a payment can change it' using errcode = '42501';
  end if;
  return new;
end;
$$;
revoke all on function public.settlement_party_guard() from public, anon, authenticated;

drop trigger if exists settlement_party_guard on public.settlements;
create trigger settlement_party_guard before insert or update on public.settlements
  for each row execute function public.settlement_party_guard();

-- An allocation's amount is the money itself. Edits never rewrite one (they void the payment and
-- write new rows), so a changed amount is only ever legitimate from a party.
create or replace function public.settlement_allocation_party_guard()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid text := (select auth.uid())::text;
begin
  if v_uid is null or new.applied_amount_subunits is not distinct from old.applied_amount_subunits
     and new.settlement_id is not distinct from old.settlement_id then
    return new;
  end if;
  if not exists (
    select 1 from public.settlements s
    where s.id = old.settlement_id and v_uid in (s.from_user_id, s.to_user_id)
  ) then
    raise exception 'only the two people in a payment can change it' using errcode = '42501';
  end if;
  return new;
end;
$$;
revoke all on function public.settlement_allocation_party_guard() from public, anon, authenticated;

drop trigger if exists settlement_allocation_party_guard on public.settlement_allocations;
create trigger settlement_allocation_party_guard before update on public.settlement_allocations
  for each row execute function public.settlement_allocation_party_guard();

-- Two parties editing the same payment at once each void it and write a replacement, which would leave
-- both replacements live and the bill double-paid. The replacement that reaches the server last wins:
-- it voids every other live payment of its chain. Both edits keep their history rows, so the record
-- shows both. Arrival order, not client time, because a client's clock is not trusted
-- (`data/AGENTS.md`). Statement-level would miss which row arrived; this is per row, after insert.
create or replace function public.settlement_chain_last_wins()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_now bigint := (extract(epoch from clock_timestamp()) * 1000)::bigint;
begin
  if new.original_settlement_id is null or new.deleted_at is not null then
    return null;
  end if;
  update public.settlements s
     set deleted_at = v_now,
         updated_at = greatest(s.updated_at + 1, v_now),
         row_version = s.row_version + 1
   where s.group_id = new.group_id
     and (s.id = new.original_settlement_id or s.original_settlement_id = new.original_settlement_id)
     and s.id <> new.id
     and s.deleted_at is null;
  return null;
end;
$$;
revoke all on function public.settlement_chain_last_wins() from public, anon, authenticated;

drop trigger if exists settlement_chain_last_wins on public.settlements;
create trigger settlement_chain_last_wins after insert on public.settlements
  for each row execute function public.settlement_chain_last_wins();

-- What a share still owes: owed minus every live payment's allocation. Mirrors the client's
-- `derivedRemainingForShare`; the two web payment RPCs below are its only callers.
create or replace function public._share_remaining(p_share_id text)
returns bigint
language sql
stable
security definer
set search_path = public
as $$
  select sh.share_owed_subunits - coalesce((
    select sum(sa.applied_amount_subunits) from public.settlement_allocations sa
    join public.settlements st on st.id = sa.settlement_id
    where sa.share_id = sh.id and st.deleted_at is null), 0)
  from public.shares sh where sh.id = p_share_id;
$$;
revoke all on function public._share_remaining(text) from public, anon, authenticated;

-- B4: a web guest records what they paid back on ONE bill. Service-role only (`web-claim`, which has
-- already bound the browser to [p_from_user_id] through `web_sessions`). The money lands on this
-- bill's share only. A refusal is a result (`ok: false` with the ceiling), not an error: the guest
-- typed a number, and the page has to say what the most is.
create or replace function public.record_web_payment(
  p_expense_id text,
  p_from_user_id text,
  p_amount bigint,
  p_payment_app text,
  p_now bigint
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_exp public.expenses%rowtype;
  v_share_id text;
  v_remaining bigint;
  v_id text := gen_random_uuid()::text;
begin
  select * into v_exp from public.expenses where id = p_expense_id and deleted_at is null for update;
  if not found then raise exception 'bill not found'; end if;
  if v_exp.payer_user_id is null or v_exp.payer_user_id = p_from_user_id then
    return jsonb_build_object('ok', false, 'reason', 'NOT_OWED', 'ceiling', 0);
  end if;
  select id into v_share_id from public.shares
   where expense_id = p_expense_id and user_id = p_from_user_id and deleted_at is null;
  if v_share_id is null then
    return jsonb_build_object('ok', false, 'reason', 'NOT_OWED', 'ceiling', 0);
  end if;
  v_remaining := greatest(coalesce(public._share_remaining(v_share_id), 0), 0);
  if p_amount is null or p_amount <= 0 or p_amount > v_remaining then
    return jsonb_build_object('ok', false, 'reason', 'ABOVE_CEILING', 'ceiling', v_remaining);
  end if;
  insert into public.settlements (id, group_id, from_user_id, to_user_id, payment_currency,
    payment_amount_subunits, payment_app, deep_link_attempted, settled_at, created_by, created_at, updated_at)
  values (v_id, v_exp.group_id, p_from_user_id, v_exp.payer_user_id, v_exp.currency,
    p_amount, p_payment_app, false, p_now, p_from_user_id, p_now, p_now);
  insert into public.settlement_allocations (id, settlement_id, group_id, share_id, applied_amount_subunits,
    applied_currency, created_at)
  values (gen_random_uuid()::text, v_id, v_exp.group_id, v_share_id, p_amount, v_exp.currency, p_now);
  insert into public.expense_history (id, expense_id, group_id, actor_user_id, type, detail, created_at)
  values (gen_random_uuid()::text, p_expense_id, v_exp.group_id, p_from_user_id, 'SETTLED', 'amt:' || p_amount, p_now);
  return jsonb_build_object('ok', true, 'settlement_id', v_id);
end;
$$;
revoke all on function public.record_web_payment(text, text, bigint, text, bigint) from public, anon, authenticated;

-- B4: a web guest changes or removes THEIR OWN payment, from one bill's link. Only this bill's portion
-- moves; any other bill the payment covered keeps what it had (the same rule as the app's single-bill
-- edit, `respreadPayment` with a scope). [p_new_amount] = 0 removes this bill's portion, and the whole
-- payment when this bill was all it covered. History rows use the app's tokens so the app renders them.
create or replace function public.edit_web_payment(
  p_expense_id text,
  p_settlement_id text,
  p_actor text,
  p_new_amount bigint,
  p_now bigint
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_old public.settlements%rowtype;
  v_share_id text;
  v_here bigint;
  v_others bigint;
  v_room bigint;
  v_new_total bigint;
  v_new_id text := gen_random_uuid()::text;
begin
  select * into v_old from public.settlements where id = p_settlement_id for update;
  if not found or v_old.deleted_at is not null then
    return jsonb_build_object('ok', false, 'reason', 'GONE', 'ceiling', 0);
  end if;
  -- The guest is the one who paid. The person owed corrects it from the app.
  if v_old.from_user_id <> p_actor then
    return jsonb_build_object('ok', false, 'reason', 'NOT_YOURS', 'ceiling', 0);
  end if;
  select sh.id into v_share_id from public.shares sh
   where sh.expense_id = p_expense_id and sh.user_id = v_old.from_user_id and sh.deleted_at is null;
  select coalesce(sum(sa.applied_amount_subunits) filter (where sa.share_id = v_share_id), 0),
         coalesce(sum(sa.applied_amount_subunits) filter (where sa.share_id is distinct from v_share_id), 0)
    into v_here, v_others
    from public.settlement_allocations sa where sa.settlement_id = p_settlement_id;
  if v_share_id is null or v_here = 0 then
    return jsonb_build_object('ok', false, 'reason', 'NOT_COVERED', 'ceiling', 0);
  end if;
  v_room := v_here + greatest(coalesce(public._share_remaining(v_share_id), 0), 0);
  if p_new_amount is null or p_new_amount < 0 or p_new_amount > v_room then
    return jsonb_build_object('ok', false, 'reason', 'ABOVE_CEILING', 'ceiling', v_room);
  end if;
  v_new_total := v_others + p_new_amount;

  update public.settlements
     set deleted_at = p_now, updated_at = greatest(updated_at + 1, p_now), row_version = row_version + 1
   where id = p_settlement_id;

  if v_new_total > 0 then
    insert into public.settlements (id, group_id, from_user_id, to_user_id, payment_currency,
      payment_amount_subunits, payment_app, deep_link_attempted, deep_link_succeeded, settled_at, notes,
      created_by, created_at, updated_at, original_settlement_id)
    values (v_new_id, v_old.group_id, v_old.from_user_id, v_old.to_user_id, v_old.payment_currency,
      v_new_total, v_old.payment_app, v_old.deep_link_attempted, v_old.deep_link_succeeded, p_now, v_old.notes,
      p_actor, p_now, p_now, coalesce(v_old.original_settlement_id, v_old.id));
    insert into public.settlement_allocations (id, settlement_id, group_id, share_id, applied_amount_subunits,
      applied_currency, fx_rate_used, fx_rate_date, created_at)
    select gen_random_uuid()::text, v_new_id, sa.group_id, sa.share_id,
           case when sa.share_id = v_share_id then p_new_amount else sa.applied_amount_subunits end,
           sa.applied_currency, sa.fx_rate_used, sa.fx_rate_date, p_now
      from public.settlement_allocations sa
     where sa.settlement_id = p_settlement_id
       and (sa.share_id <> v_share_id or p_new_amount > 0);
    insert into public.expense_history (id, expense_id, group_id, actor_user_id, type, detail, created_at)
    values (gen_random_uuid()::text, p_expense_id, v_old.group_id, p_actor, 'SETTLEMENT_EDITED',
      'edit:' || v_old.payment_amount_subunits || ':' || v_new_total || ':' || v_old.from_user_id || ':' || p_new_amount,
      p_now);
  else
    insert into public.expense_history (id, expense_id, group_id, actor_user_id, type, detail, created_at)
    values (gen_random_uuid()::text, p_expense_id, v_old.group_id, p_actor, 'SETTLEMENT_REMOVED',
      'removed:' || v_old.payment_amount_subunits || ':' || v_old.from_user_id, p_now);
  end if;
  return jsonb_build_object('ok', true, 'settlement_id', case when v_new_total > 0 then v_new_id end);
end;
$$;
revoke all on function public.edit_web_payment(text, text, text, bigint, bigint) from public, anon, authenticated;

-- ── Push: expense + payment triggers → push-notify ──────────────────────────────────────────────
-- An AFTER INSERT row trigger on each table queues one pg_net POST to `push-notify`. The sync push is a
-- full-row upsert (INSERT ... ON CONFLICT DO UPDATE), and Postgres fires AFTER INSERT only for rows that
-- were actually inserted, so a re-push or an edit never re-notifies.
--
-- The bearer it sends is read from Vault (secret `push_notify_service_key`), never written into this file,
-- and must equal the function's PUSH_NOTIFY_SECRET env var. No secret = silent no-op, so a fresh project
-- or local stack is safe. To set both to one fresh value:
--   supabase secrets set PUSH_NOTIFY_SECRET=<value>
--   select vault.create_secret('<value>', 'push_notify_service_key');
--
-- Every path swallows its own errors: these run inside the client's sync transaction, and a push that
-- fails must never turn into a refused expense or payment (that would wedge sync for the whole table).

-- Display form of a money amount for a notification line. Deliberately small: the app's own formatter is
-- Kotlin and the lock screen only needs something a person recognises.
create or replace function public.push_money(p_subunits bigint, p_currency text)
returns text language sql immutable set search_path = public as $$
  select case
    when upper(p_currency) in ('JPY','KRW','VND','CLP','ISK','UGX','PYG','RWF','XAF','XOF') then
      coalesce(case upper(p_currency) when 'JPY' then '¥' when 'KRW' then '₩' end, upper(p_currency) || ' ')
        || to_char(p_subunits, 'FM999,999,999,990')
    else
      coalesce(case upper(p_currency)
                 when 'USD' then '$' when 'EUR' then '€' when 'GBP' then '£' when 'INR' then '₹'
                 when 'CAD' then 'CA$' when 'AUD' then 'A$' when 'KES' then 'KSh ' end,
               upper(p_currency) || ' ')
        || to_char(p_subunits / 100.0, 'FM999,999,999,990.00')
  end
$$;

create or replace function public.push_dispatch(p_body jsonb)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_key text;
begin
  select decrypted_secret into v_key from vault.decrypted_secrets where name = 'push_notify_service_key';
  if v_key is null then
    return;
  end if;
  perform net.http_post(
    url                  := 'https://wfpfgbipjmkysalfmyub.supabase.co/functions/v1/push-notify',
    body                 := p_body,
    headers              := jsonb_build_object('Authorization', 'Bearer ' || v_key,
                                               'Content-Type', 'application/json'),
    timeout_milliseconds := 5000
  );
exception when others then
  raise warning 'push_dispatch: %', sqlerrm;
end $$;
-- Definer + caller-controlled title/body: callable by a client, this is arbitrary push to any group.
revoke execute on function public.push_dispatch(jsonb) from public, anon, authenticated;

create or replace function public.notify_expense_inserted()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_actor text;
  v_group text;
begin
  -- Tombstones, auto-refunds and rows arriving already deleted are not news.
  if new.deleted_at is not null or new.status <> 'ACTIVE' or coalesce(new.is_auto_refund, false) then
    return new;
  end if;
  select name into v_group from groups where id = new.group_id and deleted_at is null;
  if v_group is null then
    return new;
  end if;
  select nullif(display_name, '') into v_actor from users where id = new.created_by;
  perform push_dispatch(jsonb_build_object(
    'groupId',       new.group_id,
    'title',         coalesce(v_actor, 'Someone') || ' added ' || coalesce(nullif(new.title, ''), 'an expense'),
    'body',          push_money(new.amount_subunits, new.currency) || ' in ' || v_group,
    'excludeUserId', new.created_by,
    'pref',          'newExpenses',
    'data',          jsonb_build_object('route', 'group', 'groupId', new.group_id, 'expenseId', new.id)
  ));
  return new;
exception when others then
  raise warning 'notify_expense_inserted: %', sqlerrm;
  return new;
end $$;
revoke execute on function public.notify_expense_inserted() from public, anon, authenticated;

drop trigger if exists expenses_push_notify on public.expenses;
create trigger expenses_push_notify after insert on public.expenses
  for each row execute function public.notify_expense_inserted();

-- A payment concerns its two parties, not the group: each party who did not record it gets one push,
-- worded from their side. Edit replacements (original_settlement_id set) are skipped; the parties
-- already heard about that payment once.
create or replace function public.notify_settlement_inserted()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_from  text;
  v_to    text;
  v_group text;
  v_amt   text;
begin
  if new.deleted_at is not null or new.original_settlement_id is not null then
    return new;
  end if;
  select name into v_group from groups where id = new.group_id and deleted_at is null;
  if v_group is null then
    return new;
  end if;
  select coalesce(nullif(display_name, ''), 'Someone') into v_from from users where id = new.from_user_id;
  select coalesce(nullif(display_name, ''), 'Someone') into v_to   from users where id = new.to_user_id;
  v_amt := push_money(new.payment_amount_subunits, new.payment_currency);

  if new.to_user_id is distinct from new.created_by then
    perform push_dispatch(jsonb_build_object(
      'groupId',     new.group_id,
      'title',       coalesce(v_from, 'Someone') || ' paid you ' || v_amt,
      'body',        'In ' || v_group,
      'onlyUserIds', jsonb_build_array(new.to_user_id),
      'pref',        'payments',
      'data',        jsonb_build_object('route', 'group', 'groupId', new.group_id, 'settlementId', new.id)
    ));
  end if;
  if new.from_user_id is distinct from new.created_by then
    perform push_dispatch(jsonb_build_object(
      'groupId',     new.group_id,
      'title',       coalesce(v_to, 'Someone') || ' recorded your ' || v_amt || ' payment',
      'body',        'In ' || v_group,
      'onlyUserIds', jsonb_build_array(new.from_user_id),
      'pref',        'payments',
      'data',        jsonb_build_object('route', 'group', 'groupId', new.group_id, 'settlementId', new.id)
    ));
  end if;
  return new;
exception when others then
  raise warning 'notify_settlement_inserted: %', sqlerrm;
  return new;
end $$;
revoke execute on function public.notify_settlement_inserted() from public, anon, authenticated;

drop trigger if exists settlements_push_notify on public.settlements;
create trigger settlements_push_notify after insert on public.settlements
  for each row execute function public.notify_settlement_inserted();

-- A screenshot attached to a payment tells the OTHER party, worded from their side. It is its own push,
-- not part of the payment's: the bytes upload after the payment row lands (often seconds, sometimes
-- after a reconnect), so the payment's push cannot know a screenshot is coming. A replacement proof on a
-- chain that already had one stays quiet. `settlement_id` is the chain id; the live row of the chain is
-- what has the current amount.
create or replace function public.notify_payment_proof_inserted()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_st      record;
  v_group   text;
  v_by      text;
  v_other   text;
  v_title   text;
begin
  if new.deleted_at is not null then
    return new;
  end if;
  if exists (select 1 from payment_proofs p where p.settlement_id = new.settlement_id and p.id <> new.id) then
    return new;
  end if;
  select * into v_st from settlements s
   where (s.id = new.settlement_id or s.original_settlement_id = new.settlement_id) and s.deleted_at is null
   order by s.created_at desc limit 1;
  if v_st.id is null then
    return new;
  end if;
  select name into v_group from groups where id = new.group_id and deleted_at is null;
  if v_group is null then
    return new;
  end if;
  select coalesce(nullif(display_name, ''), 'Someone') into v_by from users where id = new.uploaded_by;
  if new.uploaded_by = v_st.from_user_id then
    v_other := v_st.to_user_id;
    v_title := v_by || ' added a screenshot of the ' || push_money(v_st.payment_amount_subunits, v_st.payment_currency)
               || ' they paid you';
  elsif new.uploaded_by = v_st.to_user_id then
    v_other := v_st.from_user_id;
    v_title := v_by || ' added a screenshot of your ' || push_money(v_st.payment_amount_subunits, v_st.payment_currency)
               || ' payment';
  else
    return new;
  end if;
  perform push_dispatch(jsonb_build_object(
    'groupId',     new.group_id,
    'title',       v_title,
    'body',        'In ' || v_group,
    'onlyUserIds', jsonb_build_array(v_other),
    'pref',        'payments',
    'data',        jsonb_build_object('route', 'group', 'groupId', new.group_id, 'settlementId', v_st.id)
  ));
  return new;
exception when others then
  raise warning 'notify_payment_proof_inserted: %', sqlerrm;
  return new;
end $$;
revoke execute on function public.notify_payment_proof_inserted() from public, anon, authenticated;

drop trigger if exists payment_proofs_push_notify on public.payment_proofs;
create trigger payment_proofs_push_notify after insert on public.payment_proofs
  for each row execute function public.notify_payment_proof_inserted();

-- ── Quick splits (docs/features/one-time-splits.md, Part A) ────────────────────────────────────────
-- A quick split is a hidden group holding one bill: members, typed-in names, web claim, settlements and
-- merge are reused unchanged. `kind` only changes how the app lists and opens it. Additive with a
-- default, so the full-row upsert from an older client (which omits it) still lands as 'group'.
alter table public.groups add column if not exists kind text not null default 'group';
alter table public.groups drop constraint if exists groups_kind_check;
alter table public.groups add constraint groups_kind_check check (kind in ('group', 'quick'));

-- The app half of "the link opens in the app": `split-evenly.app/s/<bill token>` resolves to its quick
-- split's group row, and the app then joins it through the ordinary invite path. Same reasoning as
-- `resolve_group_by_invite_token` (a joiner is not a member, so no RLS policy can express this read).
-- Quick splits only: a normal group's bill link is claim-only and must never grant membership
-- (WEB_CLAIM_SPEC.md §2.9). A revoked, expired or deleted link resolves to nothing.
create or replace function public.resolve_quick_split_by_bill_token(p_token text)
returns setof public.groups
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select g.*
  from public.web_bill_links l
  join public.groups g on g.id = l.group_id
  join public.expenses e on e.id = l.expense_id
  where l.token_hash = encode(sha256(convert_to(p_token, 'UTF8')), 'hex')
    and l.revoked_at is null
    and (l.expires_at is null or l.expires_at > (extract(epoch from now()) * 1000)::bigint)
    and e.deleted_at is null
    and g.deleted_at is null
    and g.kind = 'quick'
  limit 1;
$$;

revoke all on function public.resolve_quick_split_by_bill_token(text) from public, anon;
grant execute on function public.resolve_quick_split_by_bill_token(text) to authenticated;

-- The typed-in names a quick split's link can claim, so the app's join screen can ask "is one of these
-- you?" on a device that has never synced the group (it reads names from local `users` + `members`).
-- Only unclaimed placeholder rows of that one quick split: no account rows, nothing the web page does
-- not already show to anyone holding the link.
create or replace function public.quick_split_names_by_bill_token(p_token text)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  with g as (select * from public.resolve_quick_split_by_bill_token(p_token))
  select jsonb_build_object(
    'users', coalesce((
      select jsonb_agg(to_jsonb(u)) from public.users u
      join public.members m on m.user_id = u.id and m.group_id = u.placeholder_group_id
      where u.is_placeholder and u.placeholder_group_id = (select id from g)
        and m.status = 'ACTIVE' and m.placeholder_claim_completed_at is null), '[]'::jsonb),
    'members', coalesce((
      select jsonb_agg(to_jsonb(m)) from public.members m
      join public.users u on u.id = m.user_id and u.is_placeholder
      where m.group_id = (select id from g)
        and m.status = 'ACTIVE' and m.placeholder_claim_completed_at is null), '[]'::jsonb)
  )
$$;

revoke all on function public.quick_split_names_by_bill_token(text) from public, anon;
grant execute on function public.quick_split_names_by_bill_token(text) to authenticated;

-- ── Web group page (docs/features/web-group-page.md) ─────────────────────────────────────────────
-- A guest without the app sees the whole group, pays people back across bills, edits expenses and adds
-- new ones, all through the `web-claim` edge function's group actions (keyed on the group invite token +
-- the guest's web session). Every function below is service-role only, like the bill-scoped web RPCs:
-- the edge function has already bound the browser to [p_actor] through `web_sessions`, and none of these
-- trust anything else the browser says about who it is.

-- Is [p_user] an ACTIVE member of [p_group]? The shared guard for every function below.
create or replace function public._web_active_member(p_group text, p_user text)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (select 1 from public.members where group_id = p_group and user_id = p_user and status = 'ACTIVE');
$$;
revoke all on function public._web_active_member(text, text) from public, anon, authenticated;

-- An even split, penny-exact the same way as Kotlin's `allocate` with equal weights: everyone gets the
-- floor, and the leftover pennies go one each to the lowest user ids (byte order, `collate "C"`, which is
-- what Kotlin's String compare gives for these ASCII ids).
create or replace function public._web_even_split(p_amount bigint, p_users text[])
returns table (user_id text, owed bigint)
language sql
immutable
set search_path = public
as $$
  with u as (
    select x as uid, row_number() over (order by x collate "C") as rn, count(*) over () as n
    from (select distinct unnest(p_users) as x) d
  )
  select uid, (p_amount / n) + case when rn <= (p_amount % n) then 1 else 0 end from u;
$$;
revoke all on function public._web_even_split(bigint, text[]) from public, anon, authenticated;

-- "I paid Joe $42" from the group page: the same rule as the app's group settle (SettlementRepositoryImpl
-- .writeSettlement). Gross, never netted: the payment pays down what [p_from] owes [p_to] in
-- [p_currency], oldest bill first, and can never be more than that. [p_expense_id] scopes it to one bill
-- ("Pay Mary back for Groceries"), like the app's per-expense settle sheet. A refusal is a result.
-- [p_actor] is who recorded it: the payer ("I paid Joe") or, for "Bob paid me $20", the person paid. It
-- is `created_by`, which is how `notify_settlement_inserted` knows to tell the OTHER side. Null = p_from.
drop function if exists public.record_web_group_payment(text, text, text, text, bigint, text, bigint, text);
create or replace function public.record_web_group_payment(
  p_group_id text,
  p_from text,
  p_to text,
  p_currency text,
  p_amount bigint,
  p_payment_app text,
  p_now bigint,
  p_expense_id text default null,
  p_actor text default null
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_total bigint := 0;
  v_left bigint;
  v_id text := gen_random_uuid()::text;
  r record;
  v_apply bigint;
  v_actor text := coalesce(p_actor, p_from);
begin
  if p_from = p_to or not public._web_active_member(p_group_id, p_from) or not public._web_active_member(p_group_id, p_to) then
    return jsonb_build_object('ok', false, 'reason', 'NOT_OWED', 'ceiling', 0);
  end if;
  -- One payment between this pair at a time, so two taps cannot both pass the ceiling check.
  perform pg_advisory_xact_lock(hashtextextended('web-pay:' || p_group_id || ':' || p_from || ':' || p_to, 0));

  create temp table if not exists _web_pay_out (share_id text, expense_id text, remaining bigint, ord bigint) on commit drop;
  truncate _web_pay_out;
  insert into _web_pay_out
  select s.id, e.id, public._share_remaining(s.id),
         row_number() over (order by e.expense_date, e.id, s.id)
    from public.shares s join public.expenses e on e.id = s.expense_id
   where e.group_id = p_group_id and e.deleted_at is null and s.deleted_at is null
     and e.payer_user_id = p_to and s.user_id = p_from and e.currency = p_currency
     and (p_expense_id is null or e.id = p_expense_id);
  delete from _web_pay_out where remaining <= 0;
  select coalesce(sum(remaining), 0) into v_total from _web_pay_out;

  if p_amount is null or p_amount <= 0 or p_amount > v_total then
    return jsonb_build_object('ok', false, 'reason', 'ABOVE_CEILING', 'ceiling', v_total);
  end if;

  insert into public.settlements (id, group_id, from_user_id, to_user_id, payment_currency,
    payment_amount_subunits, payment_app, deep_link_attempted, settled_at, created_by, created_at, updated_at)
  values (v_id, p_group_id, p_from, p_to, p_currency, p_amount, p_payment_app, false, p_now, v_actor, p_now, p_now);

  v_left := p_amount;
  for r in select * from _web_pay_out order by ord loop
    exit when v_left <= 0;
    v_apply := least(v_left, r.remaining);
    insert into public.settlement_allocations (id, settlement_id, group_id, share_id, applied_amount_subunits,
      applied_currency, created_at)
    values (gen_random_uuid()::text, v_id, p_group_id, r.share_id, v_apply, p_currency, p_now);
    insert into public.expense_history (id, expense_id, group_id, actor_user_id, type, detail, created_at)
    values (gen_random_uuid()::text, r.expense_id, p_group_id, v_actor, 'SETTLED', 'amt:' || v_apply, p_now);
    v_left := v_left - v_apply;
  end loop;
  return jsonb_build_object('ok', true, 'settlement_id', v_id);
end;
$$;
revoke all on function public.record_web_group_payment(text, text, text, text, bigint, text, bigint, text, text) from public, anon, authenticated;

-- A guest adding someone else's name while creating an expense ("Bob was there too"). The §2.4 rule from
-- `create_web_placeholder`, under the same lock key, minus the session: Bob gets no browser identity, he
-- picks his name later like any typed-in name. created_by is the guest, so Bob is still asked "is this
-- you?" when he installs the app (IDENTITY_CLAIM_SPEC.md skips only self-created names).
create or replace function public.add_web_group_name(
  p_group_id text,
  p_name text,
  p_user_id text,
  p_member_id text,
  p_actor text,
  p_now bigint
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_name text := left(regexp_replace(btrim(p_name), '\s+', ' ', 'g'), 40);
  v_clash text;
  v_clash_id text;
begin
  if v_name = '' then raise exception 'add_web_group_name: name required'; end if;
  if not public._web_active_member(p_group_id, p_actor) then raise exception 'add_web_group_name: not a member'; end if;
  perform pg_advisory_xact_lock(hashtextextended(p_group_id || ':' || lower(v_name), 0));
  select u.display_name, u.id into v_clash, v_clash_id
    from public.members m join public.users u on u.id = m.user_id
   where m.group_id = p_group_id and m.status = 'ACTIVE'
     and lower(left(regexp_replace(btrim(u.display_name), '\s+', ' ', 'g'), 40)) = lower(v_name)
   limit 1;
  if v_clash is not null then
    return jsonb_build_object('created', false, 'conflict', true, 'name', v_clash, 'user_id', v_clash_id);
  end if;
  insert into public.users (id, is_placeholder, display_name, placeholder_group_id, created_by, created_at, updated_at)
  values (p_user_id, true, v_name, p_group_id, p_actor, p_now, p_now);
  insert into public.members (id, group_id, user_id, status, joined_at, created_at, updated_at)
  values (p_member_id, p_group_id, p_user_id, 'ACTIVE', p_now, p_now, p_now);
  return jsonb_build_object('created', true, 'conflict', false, 'name', v_name, 'user_id', p_user_id);
end;
$$;
revoke all on function public.add_web_group_name(text, text, text, text, text, bigint) from public, anon, authenticated;

-- Desired shares for an EVEN or EXACT split. [p_people] is `[{ "user_id": "...", "exact": 1234 }]`
-- (`exact` only for EXACT). Raises on anything the page should have refused, so a bad payload never
-- half-writes: everyone a member, nobody twice, EXACT summing to the amount.
create or replace function public._web_desired_shares(
  p_group_id text, p_amount bigint, p_split_mode text, p_people jsonb
) returns table (user_id text, owed bigint, exact bigint)
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_ids text[];
  v_sum bigint;
begin
  select array_agg(p->>'user_id') into v_ids from jsonb_array_elements(coalesce(p_people, '[]'::jsonb)) p;
  if v_ids is null or array_length(v_ids, 1) = 0 then raise exception 'web expense: nobody to split with'; end if;
  if (select count(distinct x) from unnest(v_ids) x) <> array_length(v_ids, 1) then raise exception 'web expense: a person is listed twice'; end if;
  if exists (select 1 from unnest(v_ids) x where not public._web_active_member(p_group_id, x)) then
    raise exception 'web expense: everyone in the split must be in the group';
  end if;
  if p_split_mode = 'EVEN' then
    return query select s.user_id, s.owed, null::bigint from public._web_even_split(p_amount, v_ids) s;
  elsif p_split_mode = 'EXACT' then
    select sum((p->>'exact')::bigint) into v_sum from jsonb_array_elements(p_people) p;
    if exists (select 1 from jsonb_array_elements(p_people) p where coalesce((p->>'exact')::bigint, -1) < 0) or v_sum <> p_amount then
      raise exception 'web expense: exact amounts must add up to the total';
    end if;
    return query select p->>'user_id', (p->>'exact')::bigint, (p->>'exact')::bigint from jsonb_array_elements(p_people) p;
  else
    raise exception 'web expense: unsupported split mode %', p_split_mode;
  end if;
end;
$$;
revoke all on function public._web_desired_shares(text, bigint, text, jsonb) from public, anon, authenticated;

-- A guest adds an expense. EVEN and EXACT write their shares here. ITEMIZED writes the lines and who was
-- there (`bill_participants`) and NO shares: an itemized bill's shares are a derived materialization the
-- app re-derives on every pull (`rematerializeGroups`), and people claim their lines on the bill's link.
-- The insert fires `expenses_push_notify`, so the group hears about it like any app-added expense.
-- [p_extras] (itemized only, from a scanned receipt): `{tax, tip, gratuity, discount, other}` in subunits,
-- written to the expense's own columns so they split proportionally, exactly as on an app-made bill.
drop function if exists public.create_web_expense(text, text, text, text, bigint, text, text, text, text, jsonb, jsonb, text, bigint);
create or replace function public.create_web_expense(
  p_group_id text,
  p_actor text,
  p_expense_id text,
  p_title text,
  p_amount bigint,
  p_currency text,
  p_date text,
  p_payer text,
  p_split_mode text,
  p_people jsonb,
  p_items jsonb,
  p_category_id text,
  p_now bigint,
  p_extras jsonb default null
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_title text := left(btrim(coalesce(p_title, '')), 80);
  v_amount bigint := p_amount;
  i record;
  v_sort int := 0;
  v_tax bigint := 0;
  v_tip bigint := 0;
  v_gratuity bigint := 0;
  v_discount bigint := 0;
  v_other bigint := 0;
begin
  if not public._web_active_member(p_group_id, p_actor) or not public._web_active_member(p_group_id, p_payer) then
    raise exception 'web expense: payer and author must be in the group';
  end if;
  if v_title = '' then raise exception 'web expense: title required'; end if;
  if p_currency !~ '^[A-Z]{3}$' then raise exception 'web expense: bad currency'; end if;
  if p_date !~ '^\d{4}-\d{2}-\d{2}$' then raise exception 'web expense: bad date'; end if;

  if p_split_mode = 'ITEMIZED' then
    if jsonb_array_length(coalesce(p_items, '[]'::jsonb)) = 0 then raise exception 'web expense: add at least one line'; end if;
    if jsonb_array_length(p_items) > 200 then raise exception 'web expense: too many lines'; end if;
    select sum((x->>'line_total')::bigint) into v_amount from jsonb_array_elements(p_items) x;
    if p_extras is not null then
      v_tax := coalesce((p_extras->>'tax')::bigint, 0);
      v_tip := coalesce((p_extras->>'tip')::bigint, 0);
      v_gratuity := coalesce((p_extras->>'gratuity')::bigint, 0);
      v_discount := coalesce((p_extras->>'discount')::bigint, 0);
      v_other := coalesce((p_extras->>'other')::bigint, 0);
      if least(v_tax, v_tip, v_gratuity, v_discount, v_other) < 0 then
        raise exception 'web expense: tax, tip and discount cannot be negative';
      end if;
      v_amount := v_amount + v_tax + v_tip + v_gratuity + v_other - v_discount;
    end if;
    if exists (select 1 from jsonb_array_elements(p_items) x
               where coalesce((x->>'line_total')::bigint, -1) < 0 or coalesce((x->>'quantity')::int, 0) < 1
                  or btrim(coalesce(x->>'label', '')) = '') then
      raise exception 'web expense: every line needs a name, a quantity and a price';
    end if;
  end if;
  if v_amount is null or v_amount <= 0 then raise exception 'web expense: amount must be above zero'; end if;

  insert into public.expenses (id, group_id, title, amount_subunits, currency, expense_date, payer_user_id,
    split_mode, category_id, created_by, created_at, updated_at, title_updated_at, date_updated_at,
    category_updated_at, split_version, split_updated_by,
    tax_subunits, tip_subunits, gratuity_subunits, discount_subunits, other_charges_subunits)
  values (p_expense_id, p_group_id, v_title, v_amount, p_currency, p_date, p_payer,
    p_split_mode, p_category_id, p_actor, p_now, p_now, p_now, p_now, p_now, 1, p_actor,
    v_tax, v_tip, v_gratuity, v_discount, v_other);

  if p_split_mode = 'ITEMIZED' then
    for i in select x from jsonb_array_elements(p_items) x loop
      insert into public.expense_items (id, expense_id, group_id, label, quantity, unit_price_subunits,
        line_total_subunits, sort_order, created_at, updated_at)
      values (gen_random_uuid()::text, p_expense_id, p_group_id, left(btrim(i.x->>'label'), 80),
        (i.x->>'quantity')::int, round((i.x->>'line_total')::numeric / (i.x->>'quantity')::int)::bigint,
        (i.x->>'line_total')::bigint, v_sort, p_now, p_now);
      v_sort := v_sort + 1;
    end loop;
    insert into public.bill_participants (id, expense_id, group_id, user_id, created_at, updated_at)
    select gen_random_uuid()::text, p_expense_id, p_group_id, u, p_now, p_now
      from (select distinct p->>'user_id' as u from jsonb_array_elements(coalesce(p_people, '[]'::jsonb)) p
            union select p_payer) d
     where public._web_active_member(p_group_id, u);
  else
    insert into public.shares (id, expense_id, user_id, share_owed_subunits, remaining_subunits,
      share_exact_subunits, created_at, updated_at)
    select gen_random_uuid()::text, p_expense_id, d.user_id, d.owed, d.owed, d.exact, p_now, p_now
      from public._web_desired_shares(p_group_id, v_amount, p_split_mode, p_people) d;
  end if;

  insert into public.expense_history (id, expense_id, group_id, actor_user_id, type, detail, created_at)
  values (gen_random_uuid()::text, p_expense_id, p_group_id, p_actor, 'CREATED', null, p_now);
  return jsonb_build_object('ok', true, 'expense_id', p_expense_id, 'amount_subunits', v_amount);
end;
$$;
revoke all on function public.create_web_expense(text, text, text, text, bigint, text, text, text, text, jsonb, jsonb, text, bigint, jsonb) from public, anon, authenticated;

-- The change log behind a guest's expense edit, and what Undo restores. Not synced: the app sees the
-- edit as an ordinary `EDITED` history row plus the new split. RLS on, zero policies (service key only).
create table if not exists public.web_expense_edits (
  id text primary key,
  expense_id text not null,
  group_id text not null,
  actor_user_id text not null,
  before jsonb not null,
  after jsonb not null,
  split_version_after bigint not null,
  created_at bigint not null,
  undone_at bigint,
  undone_by text
);
create index if not exists web_expense_edits_expense_idx on public.web_expense_edits (expense_id, created_at);
alter table public.web_expense_edits enable row level security;

-- Snapshot of the editable state of a non-itemized expense: Zone-1 title + the Zone-2 money.
create or replace function public._web_expense_state(p_expense_id text)
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  select jsonb_build_object(
    'title', e.title, 'amount', e.amount_subunits, 'payer', e.payer_user_id, 'split_mode', e.split_mode,
    'shares', coalesce((select jsonb_agg(jsonb_build_object('id', s.id, 'user_id', s.user_id,
                 'owed', s.share_owed_subunits, 'exact', s.share_exact_subunits) order by s.user_id)
               from public.shares s where s.expense_id = e.id and s.deleted_at is null), '[]'::jsonb))
  from public.expenses e where e.id = p_expense_id;
$$;
revoke all on function public._web_expense_state(text) from public, anon, authenticated;

-- Writes a desired share set onto an expense the way the app's `mergeShares` does: a person who stays
-- keeps their share id (so their payments still apply to it), a person who leaves is tombstoned, a new
-- person gets a new row. [p_shares] is `[{ "id"?: ..., "user_id": ..., "owed": ..., "exact": ... }]`;
-- an `id` is honoured when it names a share of this expense (Undo restoring the exact old rows).
create or replace function public._web_write_shares(p_expense_id text, p_shares jsonb, p_now bigint)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  d record;
  v_existing text;
begin
  update public.shares set deleted_at = p_now, updated_at = greatest(updated_at + 1, p_now), row_version = row_version + 1
   where expense_id = p_expense_id and deleted_at is null
     and user_id not in (select x->>'user_id' from jsonb_array_elements(p_shares) x);
  for d in select x->>'id' as id, x->>'user_id' as user_id, (x->>'owed')::bigint as owed,
                  (x->>'exact')::bigint as exact from jsonb_array_elements(p_shares) x loop
    select id into v_existing from public.shares
     where expense_id = p_expense_id and user_id = d.user_id and deleted_at is null;
    if v_existing is null and d.id is not null then
      select id into v_existing from public.shares where id = d.id and expense_id = p_expense_id and user_id = d.user_id;
    end if;
    if v_existing is not null then
      update public.shares set share_owed_subunits = d.owed, share_exact_subunits = d.exact,
        share_units = null, share_percentage = null, deleted_at = null,
        updated_at = greatest(updated_at + 1, p_now), row_version = row_version + 1
       where id = v_existing;
    else
      insert into public.shares (id, expense_id, user_id, share_owed_subunits, remaining_subunits,
        share_exact_subunits, created_at, updated_at)
      values (gen_random_uuid()::text, p_expense_id, d.user_id, d.owed, d.owed, d.exact, p_now, p_now);
    end if;
  end loop;
end;
$$;
revoke all on function public._web_write_shares(text, jsonb, bigint) from public, anon, authenticated;

-- A guest edits an EVEN or EXACT expense: applies at once, logged, undoable by anyone, the payer told.
-- The title is Zone 1 and always lands. The money (amount, payer, split) is Zone 2 and needs the
-- `split_version` the guest's page read: stale ⇒ refuse with STALE, the same causal rule as
-- `merge_expense` and `set_web_bill_payer`, never a silent overwrite of an edit she did not see. Every
-- money change advances split_version, or the next app push would revert it (supabase/AGENTS.md).
-- Itemized bills are edited line by line on their own link, not here.
create or replace function public.apply_web_expense_edit(
  p_expense_id text,
  p_group_id text,
  p_actor text,
  p_base_split_version bigint,
  p_title text,
  p_amount bigint,
  p_payer text,
  p_split_mode text,
  p_people jsonb,
  p_now bigint
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_e public.expenses%rowtype;
  v_before jsonb;
  v_after jsonb;
  v_title text := nullif(left(btrim(coalesce(p_title, '')), 80), '');
  v_money boolean := p_amount is not null or p_payer is not null or p_people is not null;
  v_desired jsonb;
  v_edit_id text := gen_random_uuid()::text;
  v_actor_name text;
  v_payer text;
begin
  if not public._web_active_member(p_group_id, p_actor) then
    return jsonb_build_object('ok', false, 'reason', 'NOT_A_MEMBER');
  end if;
  select * into v_e from public.expenses where id = p_expense_id and group_id = p_group_id and deleted_at is null for update;
  if not found then return jsonb_build_object('ok', false, 'reason', 'GONE'); end if;
  if v_e.split_mode not in ('EVEN', 'EXACT') or (p_split_mode is not null and p_split_mode not in ('EVEN', 'EXACT')) then
    return jsonb_build_object('ok', false, 'reason', 'UNSUPPORTED');
  end if;
  if v_money and p_base_split_version is distinct from v_e.split_version then
    return jsonb_build_object('ok', false, 'reason', 'STALE', 'split_version', v_e.split_version);
  end if;
  if v_money and p_payer is not null and not public._web_active_member(p_group_id, p_payer) then
    return jsonb_build_object('ok', false, 'reason', 'NOT_A_MEMBER');
  end if;

  v_before := public._web_expense_state(p_expense_id);

  if v_title is not null and v_title <> v_e.title then
    update public.expenses set title = v_title, title_updated_at = greatest(coalesce(title_updated_at, 0) + 1, p_now),
      updated_at = greatest(updated_at + 1, p_now), row_version = row_version + 1
     where id = p_expense_id;
  end if;

  if v_money then
    select coalesce(jsonb_agg(jsonb_build_object('user_id', d.user_id, 'owed', d.owed, 'exact', d.exact)), '[]'::jsonb)
      into v_desired
      from public._web_desired_shares(p_group_id, coalesce(p_amount, v_e.amount_subunits),
             coalesce(p_split_mode, v_e.split_mode),
             coalesce(p_people, (select jsonb_agg(jsonb_build_object('user_id', s.user_id, 'exact', s.share_owed_subunits))
                                   from public.shares s where s.expense_id = p_expense_id and s.deleted_at is null))) d;
    perform public._web_write_shares(p_expense_id, v_desired, p_now);
    update public.expenses set
      amount_subunits = coalesce(p_amount, amount_subunits),
      payer_user_id = coalesce(p_payer, payer_user_id),
      split_mode = coalesce(p_split_mode, split_mode),
      split_version = split_version + 1,
      split_updated_by = p_actor,
      updated_at = greatest(updated_at + 1, p_now),
      row_version = row_version + 1
     where id = p_expense_id;
  end if;

  v_after := public._web_expense_state(p_expense_id);
  if v_after = v_before then
    return jsonb_build_object('ok', true, 'changed', false);
  end if;

  insert into public.web_expense_edits (id, expense_id, group_id, actor_user_id, before, after, split_version_after, created_at)
  values (v_edit_id, p_expense_id, p_group_id, p_actor, v_before, v_after,
          (select split_version from public.expenses where id = p_expense_id), p_now);
  insert into public.expense_history (id, expense_id, group_id, actor_user_id, type, detail, created_at)
  values (gen_random_uuid()::text, p_expense_id, p_group_id, p_actor, 'EDITED', null, p_now);

  -- The payer hears about it (both of them, when the edit moved who paid). push_dispatch swallows its own
  -- failures, so a push outage can never refuse an edit.
  select coalesce(nullif(display_name, ''), 'Someone') into v_actor_name from public.users where id = p_actor;
  for v_payer in select distinct x from unnest(array[v_e.payer_user_id, (v_after->>'payer')]) x
                  where x is not null and x <> p_actor loop
    perform public.push_dispatch(jsonb_build_object(
      'groupId', p_group_id,
      'title', v_actor_name || ' changed ' || coalesce(v_after->>'title', 'an expense'),
      'body', push_money((v_after->>'amount')::bigint, v_e.currency) || ' now',
      'onlyUserIds', jsonb_build_array(v_payer),
      'pref', 'newExpenses',
      'data', jsonb_build_object('route', 'group', 'groupId', p_group_id, 'expenseId', p_expense_id)));
  end loop;
  return jsonb_build_object('ok', true, 'changed', true, 'edit_id', v_edit_id);
end;
$$;
revoke all on function public.apply_web_expense_edit(text, text, text, bigint, text, bigint, text, text, jsonb, bigint) from public, anon, authenticated;

-- Undo a guest's expense edit, by anyone in the group. First undo wins (conditional on undone_at is
-- null). Refused with CHANGED_SINCE when the money moved again after this edit: restoring an older split
-- over a newer one is exactly the silent revert the causal version exists to prevent.
create or replace function public.undo_web_expense_edit(
  p_edit_id text,
  p_group_id text,
  p_actor text,
  p_now bigint
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_edit public.web_expense_edits%rowtype;
  v_e public.expenses%rowtype;
  v_money_changed boolean;
begin
  if not public._web_active_member(p_group_id, p_actor) then
    return jsonb_build_object('ok', false, 'reason', 'NOT_A_MEMBER');
  end if;
  select * into v_edit from public.web_expense_edits where id = p_edit_id and group_id = p_group_id for update;
  if not found then return jsonb_build_object('ok', false, 'reason', 'GONE'); end if;
  if v_edit.undone_at is not null then return jsonb_build_object('ok', true, 'changed', false); end if;
  select * into v_e from public.expenses where id = v_edit.expense_id and deleted_at is null for update;
  if not found then return jsonb_build_object('ok', false, 'reason', 'GONE'); end if;

  v_money_changed := (v_edit.before - 'title') is distinct from (v_edit.after - 'title');
  if v_money_changed and v_e.split_version <> v_edit.split_version_after then
    return jsonb_build_object('ok', false, 'reason', 'CHANGED_SINCE');
  end if;

  if (v_edit.before->>'title') is distinct from (v_edit.after->>'title') and v_e.title = v_edit.after->>'title' then
    update public.expenses set title = v_edit.before->>'title',
      title_updated_at = greatest(coalesce(title_updated_at, 0) + 1, p_now),
      updated_at = greatest(updated_at + 1, p_now), row_version = row_version + 1
     where id = v_e.id;
  end if;
  if v_money_changed then
    perform public._web_write_shares(v_e.id, v_edit.before->'shares', p_now);
    update public.expenses set
      amount_subunits = (v_edit.before->>'amount')::bigint,
      payer_user_id = v_edit.before->>'payer',
      split_mode = v_edit.before->>'split_mode',
      split_version = split_version + 1,
      split_updated_by = p_actor,
      updated_at = greatest(updated_at + 1, p_now),
      row_version = row_version + 1
     where id = v_e.id;
  end if;
  update public.web_expense_edits set undone_at = p_now, undone_by = p_actor where id = p_edit_id;
  insert into public.expense_history (id, expense_id, group_id, actor_user_id, type, detail, created_at)
  values (gen_random_uuid()::text, v_e.id, p_group_id, p_actor, 'EDITED', null, p_now);
  return jsonb_build_object('ok', true, 'changed', true);
end;
$$;
revoke all on function public.undo_web_expense_edit(text, text, text, bigint) from public, anon, authenticated;

-- Everything the web group page shows, in one read (the browser has no Supabase client, and an `in (...)`
-- over hundreds of expense ids does not fit in a PostgREST query string). `remaining` is derived exactly
-- as the client does it (`derivedRemainingForShare`): owed minus live allocations, and 0 for the payer's
-- own share. Newest 500 expenses; the page is a phone screen, not an export.
create or replace function public.web_group_snapshot(p_group_id text, p_user_id text)
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  with ex as (
    select * from public.expenses
     where group_id = p_group_id and deleted_at is null and status = 'ACTIVE'
     order by expense_date desc, created_at desc limit 500
  ), alloc as (
    select sa.share_id, sum(sa.applied_amount_subunits) as paid
      from public.settlement_allocations sa join public.settlements st on st.id = sa.settlement_id
     where sa.group_id = p_group_id and st.deleted_at is null
     group by sa.share_id
  )
  select jsonb_build_object(
    'members', coalesce((
      select jsonb_agg(jsonb_build_object('userId', u.id, 'name', coalesce(nullif(btrim(u.display_name), ''), 'Someone'),
               'isPlaceholder', u.is_placeholder) order by m.joined_at)
        from public.members m join public.users u on u.id = m.user_id
       where m.group_id = p_group_id and m.status = 'ACTIVE'), '[]'::jsonb),
    'expenses', coalesce((
      select jsonb_agg(jsonb_build_object(
        'id', e.id, 'title', e.title, 'date', e.expense_date, 'createdAt', e.created_at,
        'amountSubunits', e.amount_subunits, 'currency', e.currency, 'payerUserId', e.payer_user_id,
        'payerOutsideName', e.payer_outside_name, 'splitMode', e.split_mode, 'categoryId', e.category_id,
        'splitVersion', e.split_version, 'createdBy', e.created_by,
        'shares', coalesce((
          select jsonb_agg(jsonb_build_object('userId', s.user_id, 'owedSubunits', s.share_owed_subunits,
                   'exactSubunits', s.share_exact_subunits,
                   'remainingSubunits', case when s.user_id = e.payer_user_id then 0
                                             else s.share_owed_subunits - coalesce(a.paid, 0) end))
            from public.shares s left join alloc a on a.share_id = s.id
           where s.expense_id = e.id and s.deleted_at is null), '[]'::jsonb),
        'participantIds', coalesce((
          select jsonb_agg(bp.user_id) from public.bill_participants bp
           where bp.expense_id = e.id and bp.deleted_at is null), '[]'::jsonb),
        'lineCount', (select count(*) from public.expense_items i where i.expense_id = e.id and i.deleted_at is null),
        'iClaimed', exists (select 1 from public.item_claims c where c.expense_id = e.id and c.user_id = p_user_id and c.deleted_at is null)
                 or exists (select 1 from public.item_shares c where c.expense_id = e.id and c.user_id = p_user_id and c.deleted_at is null),
        'iAmDone', exists (select 1 from public.bill_participants bp where bp.expense_id = e.id and bp.user_id = p_user_id
                             and bp.deleted_at is null and bp.done_at is not null),
        'receipts', coalesce((
          select jsonb_agg(jsonb_build_object('id', r.id, 'url', r.url, 'mimeType', r.mime_type) order by r.created_at)
            from public.receipts r where r.expense_id = e.id and r.deleted_at is null), '[]'::jsonb),
        'comments', coalesce((
          select jsonb_agg(jsonb_build_object('id', c.id, 'userId', c.user_id, 'body', c.body, 'createdAt', c.created_at)
                   order by c.created_at)
            from public.comments c where c.expense_id = e.id and c.deleted_at is null), '[]'::jsonb)
      ) order by e.expense_date desc, e.created_at desc) from ex e), '[]'::jsonb),
    'payments', coalesce((
      select jsonb_agg(jsonb_build_object('id', st.id, 'fromUserId', st.from_user_id, 'toUserId', st.to_user_id,
               'amountSubunits', st.payment_amount_subunits, 'currency', st.payment_currency, 'settledAt', st.settled_at,
               'paymentApp', st.payment_app, 'createdBy', st.created_by,
               'chainId', coalesce(st.original_settlement_id, st.id),
               -- What this payment put on each bill, for a bill's per-person payment history.
               'applied', coalesce((
                 select jsonb_agg(jsonb_build_object('expenseId', sh.expense_id, 'subunits', sa.applied_amount_subunits))
                   from public.settlement_allocations sa join public.shares sh on sh.id = sa.share_id
                  where sa.settlement_id = st.id), '[]'::jsonb),
               'proof', (
                 select jsonb_build_object('id', pp.id, 'url', pp.url, 'uploadedBy', pp.uploaded_by)
                   from public.payment_proofs pp
                  where pp.settlement_id = coalesce(st.original_settlement_id, st.id) and pp.deleted_at is null
                  order by pp.created_at desc limit 1))
               order by st.settled_at desc)
        from (select * from public.settlements where group_id = p_group_id and deleted_at is null
               order by settled_at desc limit 300) st), '[]'::jsonb),
    'edits', coalesce((
      select jsonb_agg(jsonb_build_object('id', w.id, 'expenseId', w.expense_id, 'actorUserId', w.actor_user_id,
               'before', w.before, 'after', w.after, 'createdAt', w.created_at, 'undone', w.undone_at is not null)
               order by w.created_at desc)
        from (select * from public.web_expense_edits where group_id = p_group_id
               order by created_at desc limit 50) w), '[]'::jsonb)
  );
$$;
revoke all on function public.web_group_snapshot(text, text) from public, anon, authenticated;
 --
 -- 03:15 UTC is deliberate: 15 minutes after `purge-deleted-accounts` so the two never interleave on
 -- the same `members` rows. `cron.schedule` upserts by jobname, so running it twice is safe.

-- ─── Welcome email on sign-up ──────────────────────────────────────────────────────────────────────────
--
-- Posts to the `send-welcome-email` edge function once per account. Replaces the Studio Database Webhook
-- `sign_up_to_waitlist_webhook`, whose header embedded the legacy service-role JWT and was refused (403)
-- on every call once the function's runtime SUPABASE_SERVICE_ROLE_KEY stopped being that string.
--
-- The bearer is read from Vault (secret `welcome_email_service_key`), never written into this file, and
-- must equal the function's WELCOME_EMAIL_SECRET env var. No secret = silent no-op, so a fresh project or
-- local stack is safe. To set both to one fresh value:
--   supabase secrets set WELCOME_EMAIL_SECRET=<value>
--   select vault.create_secret('<value>', 'welcome_email_service_key');
--
-- Fires on INSERT with an email, or on the one UPDATE where an email first appears (an anonymous account
-- gaining one). The old webhook fired on every UPDATE, and auth.users is updated on every sign-in
-- (`last_sign_in_at`), while the function has no idempotency of its own: each call sends a mail. Only
-- the email and user metadata leave the database; the webhook used to post the whole row, password hash
-- included.
--
-- Errors are swallowed: this runs inside GoTrue's sign-up transaction, and a failed mail must never turn
-- into a failed sign-up.
create or replace function public.welcome_email_on_signup()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_key text;
begin
  select decrypted_secret into v_key from vault.decrypted_secrets where name = 'welcome_email_service_key';
  if v_key is null then
    return new;
  end if;
  perform net.http_post(
    url                  := 'https://wfpfgbipjmkysalfmyub.supabase.co/functions/v1/send-welcome-email',
    body                 := jsonb_build_object('record', jsonb_build_object(
                              'email', new.email,
                              'raw_user_meta_data', coalesce(new.raw_user_meta_data, '{}'::jsonb))),
    headers              := jsonb_build_object('Authorization', 'Bearer ' || v_key,
                                               'Content-Type', 'application/json'),
    timeout_milliseconds := 5000
  );
  return new;
exception when others then
  raise warning 'welcome_email_on_signup: %', sqlerrm;
  return new;
end $$;
-- Definer + reads a Vault secret: trigger-only, callable by nobody.
revoke execute on function public.welcome_email_on_signup() from public, anon, authenticated;

drop trigger if exists sign_up_to_waitlist_webhook on auth.users;
drop trigger if exists welcome_email_on_signup_insert on auth.users;
create trigger welcome_email_on_signup_insert
  after insert on auth.users
  for each row when (new.email is not null)
  execute function public.welcome_email_on_signup();
drop trigger if exists welcome_email_on_signup_email_added on auth.users;
create trigger welcome_email_on_signup_email_added
  after update of email on auth.users
  for each row when (old.email is null and new.email is not null)
  execute function public.welcome_email_on_signup();
