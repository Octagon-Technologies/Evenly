-- ShareCost — Supabase schema for the synced tables.
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
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  unique (group_id, user_id)
);
create index if not exists members_group_idx on public.members (group_id);
create index if not exists members_user_idx on public.members (user_id);

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
  -- ON DELETE CASCADE so delete_my_account() (which deletes the auth.users row) doesn't FK-violate once
  -- scan rows exist. A rate-limit log is ephemeral operational data, not financial history, so a hard
  -- cascade here is correct (P1 #12). The migration below re-adds the FK with the cascade on live DBs.
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

-- ── Row-Level Security ──────────────────────────────────────────────────────────────────────────
-- PERMISSIVE policies so sync works immediately for testing: any authenticated (incl. anonymous)
-- user can read/write every row. NOT safe for real multi-user data — see the membership-scoped sketch
-- at the bottom before going further than a personal test.
do $$
declare t text;
begin
  foreach t in array array['users','groups','members','expenses','shares','settlements','settlement_allocations','conflicts','expense_edit_conflicts','comments','receipts','categories','expense_history','device_tokens','expense_items','item_claims','item_shares','bill_participants']
  loop
    execute format('alter table public.%I enable row level security;', t);
    execute format('drop policy if exists %I on public.%I;', t || '_rw', t);
    execute format(
      'create policy %I on public.%I for all to authenticated using (true) with check (true);',
      t || '_rw', t
    );
  end loop;
end $$;

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

-- ── Tightening RLS later (sketch — do NOT ship the permissive policies above) ────────────────────
-- A row should be visible only to members of its group. e.g. for expenses:
--   create policy expenses_member_read on public.expenses for select to authenticated
--   using (exists (select 1 from public.members m
--                  where m.group_id = expenses.group_id and m.user_id = auth.uid()::text));
-- Mirror per table (members/shares/settlements/conflicts key off group_id; shares via its expense).
-- `users` is trickier (you may expose only co-members' profiles). See spec/04 for the full model.

-- ── Storage: the `receipts` bucket (F5) ──────────────────────────────────────────────────────────
-- Receipt bytes live in a PUBLIC bucket so the client's `publicUrl(path)` renders without signing.
-- (Permissive, like the table policies above — tighten to membership-scoped before real multi-user.)
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

-- ── Account deletion (F8): caller deletes their own profile + device tokens + auth record ────────
-- security definer so it can touch auth.users; conservative scope (leaves shared group data — a full
-- cascade / admin-ownership-transfer is a separate product decision).
create or replace function public.delete_my_account()
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
  delete from public.device_tokens where user_id = uid;
  delete from public.users where id = uid;
  delete from auth.users where id = uid::uuid;
end;
$$;
revoke all on function public.delete_my_account() from public;
grant execute on function public.delete_my_account() to authenticated;

-- ── Optimistic-concurrency commit for expenses (versioning + parked conflicts) ───────────────────
-- The client routes every expense create/edit through commit_expense() instead of a blind upsert.
-- It compares the caller's base_version against the canonical row_version under a row lock, so the
-- FIRST writer to advance base->base+1 wins; a stale writer's payload is PARKED in
-- expense_edit_conflicts and the canonical row is left untouched (no silent last-write-wins clobber).
-- Outcome is order-independent: swap who commits first and you still get one canonical row + one
-- parked conflict. Shares are replaced atomically with the expense — removed participants are
-- soft-deleted (Rule 1), never hard-deleted, so the tombstone propagates on the next pull.
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

  insert into public.shares as sh
    select * from jsonb_populate_recordset(null::public.shares, p_shares)
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
  v_id text := p_expense->>'id';
  v_group_id text := p_expense->>'group_id';
  v_now bigint := coalesce((p_expense->>'updated_at')::bigint, 0);
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
      select * from jsonb_populate_record(null::public.expenses, p_expense);
    update public.expenses set last_editor = p_actor where id = v_id;
    perform public._replace_expense_shares(v_id, p_shares, v_now);
    return jsonb_build_object('status', 'created', 'version', coalesce((p_expense->>'row_version')::bigint, 1));
  end if;

  if v_current.row_version = p_base_version and v_current.deleted_at is null then
    v_new_version := p_base_version + 1;
    update public.expenses set
      title              = p_expense->>'title',
      notes              = p_expense->>'notes',
      amount_subunits    = (p_expense->>'amount_subunits')::bigint,
      currency           = p_expense->>'currency',
      expense_date       = p_expense->>'expense_date',
      payer_user_id      = p_expense->>'payer_user_id',
      payer_outside_name = p_expense->>'payer_outside_name',
      split_mode         = p_expense->>'split_mode',
      category_id        = p_expense->>'category_id',
      subcategory_id     = p_expense->>'subcategory_id',
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
    and v_current.title              is not distinct from p_expense->>'title'
    and v_current.notes              is not distinct from p_expense->>'notes'
    and v_current.amount_subunits    is not distinct from (p_expense->>'amount_subunits')::bigint
    and v_current.currency           is not distinct from p_expense->>'currency'
    and v_current.expense_date       is not distinct from p_expense->>'expense_date'
    and v_current.payer_user_id      is not distinct from p_expense->>'payer_user_id'
    and v_current.payer_outside_name is not distinct from p_expense->>'payer_outside_name'
    and v_current.split_mode         is not distinct from p_expense->>'split_mode'
    and v_current.category_id        is not distinct from p_expense->>'category_id'
    and v_current.subcategory_id     is not distinct from p_expense->>'subcategory_id'
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
    p_expense::text, p_shares::text, v_now)
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
  v_id text := p_expense->>'id';
  v_group_id text := p_expense->>'group_id';
  v_now bigint := coalesce((p_expense->>'updated_at')::bigint, 0);
  v_cur public.expenses%rowtype;
  v_client_split_ver bigint := coalesce((p_expense->>'split_version')::bigint, 1);
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
    insert into public.expenses select * from jsonb_populate_record(null::public.expenses, p_expense);
    update public.expenses set split_updated_by = p_actor where id = v_id;
    perform public._replace_expense_shares(v_id, p_shares, v_now);
    return jsonb_build_object(
      'status', 'created',
      'split_version', coalesce((p_expense->>'split_version')::bigint, 1),
      'expense', (select to_jsonb(e) from public.expenses e where e.id = v_id),
      'shares',  (select coalesce(jsonb_agg(to_jsonb(s)), '[]'::jsonb) from public.shares s where s.expense_id = v_id));
  end if;

  if (p_expense->>'deleted_at') is not null then
    if v_now >= v_cur.updated_at then
      update public.expenses
         set deleted_at = (p_expense->>'deleted_at')::bigint, status = 'DELETED',
             updated_at = v_now, row_version = v_cur.row_version + 1
       where id = v_id;
    end if;
    return jsonb_build_object(
      'status', 'deleted',
      'split_version', v_cur.split_version,
      'expense', (select to_jsonb(e) from public.expenses e where e.id = v_id),
      'shares',  (select coalesce(jsonb_agg(to_jsonb(s)), '[]'::jsonb) from public.shares s where s.expense_id = v_id and s.deleted_at is null));
  end if;

  if coalesce((p_expense->>'title_updated_at')::bigint, 0) > coalesce(v_cur.title_updated_at, 0) then
    v_title := p_expense->>'title'; v_title_at := (p_expense->>'title_updated_at')::bigint;
  else v_title := v_cur.title; v_title_at := v_cur.title_updated_at; end if;

  if coalesce((p_expense->>'notes_updated_at')::bigint, 0) > coalesce(v_cur.notes_updated_at, 0) then
    v_notes := p_expense->>'notes'; v_notes_at := (p_expense->>'notes_updated_at')::bigint;
  else v_notes := v_cur.notes; v_notes_at := v_cur.notes_updated_at; end if;

  if coalesce((p_expense->>'category_updated_at')::bigint, 0) > coalesce(v_cur.category_updated_at, 0) then
    v_cat := p_expense->>'category_id'; v_subcat := p_expense->>'subcategory_id'; v_cat_at := (p_expense->>'category_updated_at')::bigint;
  else v_cat := v_cur.category_id; v_subcat := v_cur.subcategory_id; v_cat_at := v_cur.category_updated_at; end if;

  if coalesce((p_expense->>'date_updated_at')::bigint, 0) > coalesce(v_cur.date_updated_at, 0) then
    v_date := p_expense->>'expense_date'; v_date_at := (p_expense->>'date_updated_at')::bigint;
  else v_date := v_cur.expense_date; v_date_at := v_cur.date_updated_at; end if;

  v_client_changed := v_client_split_ver > p_base_split_version;
  v_server_advanced := v_cur.split_version > p_base_split_version;

  if v_client_changed and not v_server_advanced then
    v_status := 'merged';
    update public.expenses set
      amount_subunits    = (p_expense->>'amount_subunits')::bigint,
      currency           = p_expense->>'currency',
      split_mode         = p_expense->>'split_mode',
      payer_user_id      = p_expense->>'payer_user_id',
      payer_outside_name = p_expense->>'payer_outside_name',
      has_tax_row        = coalesce((p_expense->>'has_tax_row')::boolean, false),
      tax_subunits       = coalesce((p_expense->>'tax_subunits')::bigint, 0),
      tip_subunits       = coalesce((p_expense->>'tip_subunits')::bigint, 0),
      tip_split_mode     = coalesce(p_expense->>'tip_split_mode', 'PROPORTIONAL'),
      gratuity_subunits  = coalesce((p_expense->>'gratuity_subunits')::bigint, 0),
      discount_subunits  = coalesce((p_expense->>'discount_subunits')::bigint, 0),
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
    v_same := v_cur.amount_subunits    is not distinct from (p_expense->>'amount_subunits')::bigint
      and v_cur.currency           is not distinct from p_expense->>'currency'
      and v_cur.split_mode         is not distinct from p_expense->>'split_mode'
      and v_cur.payer_user_id      is not distinct from p_expense->>'payer_user_id'
      and v_cur.payer_outside_name is not distinct from p_expense->>'payer_outside_name'
      and v_cur.tax_subunits       is not distinct from coalesce((p_expense->>'tax_subunits')::bigint, 0)
      and v_cur.tip_subunits       is not distinct from coalesce((p_expense->>'tip_subunits')::bigint, 0)
      and v_cur.tip_split_mode     is not distinct from coalesce(p_expense->>'tip_split_mode', 'PROPORTIONAL')
      and v_cur.gratuity_subunits  is not distinct from coalesce((p_expense->>'gratuity_subunits')::bigint, 0)
      and v_cur.discount_subunits  is not distinct from coalesce((p_expense->>'discount_subunits')::bigint, 0)
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
        p_expense::text, p_shares::text, v_now)
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
