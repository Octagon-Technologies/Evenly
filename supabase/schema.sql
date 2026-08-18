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
  notify_new_expenses boolean not null default true,
  notify_payments boolean not null default true,
  notify_conflict_reminders boolean not null default false,
  theme_mode text not null default 'System',
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1
);

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
    'bill_participants'
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
    'expense_items','item_claims','item_shares','bill_participants'
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
    'expense_items','item_claims','item_shares','bill_participants'
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
alter table public.web_sessions enable row level security;
-- Deliberately no policies: anon and authenticated get zero access, matching spec §5.1.

-- The 72h, revocable, bill-scoped authorisation link (spec §2.2, §2.9, §4.2). The token itself is
-- 128-bit random base62, stored ONLY hashed; the plaintext lives in the URL/QR alone. RLS is enabled
-- with no policies for now — the web-claim edge function (service key) is the only writer/reader.
-- The payer's in-app share/revoke screen (build-order step 6) adds a membership-scoped read/write
-- policy in its own migration; this one deliberately does not pre-grant broader access than step 1
-- needs, since a token_hash column is more sensitive than the rest of this permissive-RLS schema.
create table if not exists public.web_bill_links (
  id text primary key,
  expense_id text not null,
  group_id text not null,
  token_hash text not null unique,
  created_by text not null,
  created_at bigint not null,
  expires_at bigint not null,
  revoked_at bigint,
  extended_count integer not null default 0,
  updated_at bigint not null,
  row_version bigint not null default 1
);
create index if not exists web_bill_links_expense_idx on public.web_bill_links (expense_id);
create index if not exists web_bill_links_group_idx on public.web_bill_links (group_id);
alter table public.web_bill_links enable row level security;

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

  insert into public.bill_participants (id, expense_id, group_id, user_id, created_at, updated_at)
  values (p_participant_id, p_expense_id, p_group_id, p_user_id, p_now, p_now);

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
-- `web_bill_links` still carries RLS-with-no-policies, deliberately, and this section is the reason the
-- earlier comment's "step 6 adds a membership-scoped table policy" plan was NOT taken. A table policy
-- would make `token_hash` readable by every authenticated user (RLS is still `using (true)` app-wide),
-- which is exactly the column that must never leave the server: the hash is the whole authorisation
-- check the edge function performs, and a leaked hash column is a leaked bill for 72 hours. So the app
-- reaches the table only through these `security definer` RPCs, each of which re-derives membership
-- itself and never returns the hash.
--
-- The plaintext token is minted HERE and returned exactly once (spec §4.2: it exists only in the URL
-- and the QR). The creating device keeps it in its own SecureStorage so it can re-render the QR;
-- nothing server-side can hand it back. A payer on a second device makes a new link, which rotates.

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

-- Mint a link for a bill. Any live link on the same expense is revoked first, so a bill has at most one
-- usable token at a time and "make a new link" is genuinely a rotation rather than an extra open door.
-- Entropy comes from `gen_random_uuid()` (a CSPRNG, 122 bits) rather than the client: Kotlin/Native has
-- no crypto-grade RNG in commonMain, and a guessable bill token is a public read of someone's bill.
create or replace function public.create_web_bill_link(
  p_expense_id text,
  p_actor text,
  p_now bigint,
  p_ttl_ms bigint default 259200000   -- 72 hours (spec §2.2)
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_group_id text := public.web_bill_link_guard(p_expense_id, p_actor);
  v_token text := public.base62_encode(uuid_send(gen_random_uuid()));
  v_id text := gen_random_uuid()::text;
  v_expires bigint := p_now + p_ttl_ms;
begin
  update public.web_bill_links set
      revoked_at = p_now, updated_at = p_now, row_version = row_version + 1
    where expense_id = p_expense_id and revoked_at is null;

  insert into public.web_bill_links (
    id, expense_id, group_id, token_hash, created_by, created_at, expires_at, updated_at
  ) values (
    v_id, p_expense_id, v_group_id,
    encode(sha256(convert_to(v_token, 'utf8')), 'hex'),
    p_actor, p_now, v_expires, p_now
  );

  -- The only time the plaintext is ever readable. The caller stores it device-locally or loses it.
  return jsonb_build_object('id', v_id, 'token', v_token, 'expires_at', v_expires);
end;
$$;
revoke all on function public.create_web_bill_link(text, text, bigint, bigint) from public, anon;
grant execute on function public.create_web_bill_link(text, text, bigint, bigint) to authenticated;

-- Extend the live link's expiry without changing the token (spec E27: "new expiry on the same token,
-- no new QR"). No-op when there is nothing live to extend — the caller then offers "make a new link".
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
  v_expires bigint;
begin
  update public.web_bill_links set
      expires_at = p_now + p_ttl_ms,
      extended_count = extended_count + 1,
      updated_at = p_now,
      row_version = row_version + 1
    where expense_id = p_expense_id and revoked_at is null
    returning expires_at into v_expires;
  return jsonb_build_object('ok', v_expires is not null, 'expires_at', v_expires);
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
  kind           text not null,  -- 'waitlist' | 'feedback'
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
