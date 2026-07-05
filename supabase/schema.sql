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
  last_editor text,                              -- who wrote the canonical version (set by commit_expense); names the "winner" of a parked edit conflict
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  deleted_at bigint
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
-- Additive auto-union set — overlapping "shared with" declarations merge for free. added_by records who
-- put a member in; the member has final say (they can leave). Soft-delete (Rule 1).
create table if not exists public.item_shares (
  id text primary key,
  item_id text not null,
  expense_id text not null,
  group_id text not null,
  user_id text not null,
  added_by text not null,
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  deleted_at bigint
);
create index if not exists item_shares_expense_idx on public.item_shares (expense_id);
create index if not exists item_shares_group_idx on public.item_shares (group_id);
create unique index if not exists item_shares_item_user_active_uidx
  on public.item_shares (item_id, user_id) where deleted_at is null;

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

-- ── Realtime (F7): enable Postgres CDC on the synced tables ──────────────────────────────────────
-- The client subscribes to `public` changes via Supabase Realtime to pull in near-real-time. Tables
-- must be in the `supabase_realtime` publication for change events to be emitted. Idempotent: skip a
-- table that's already published.
do $$
declare t text;
begin
  foreach t in array array['groups','members','expenses','shares','settlements','settlement_allocations','conflicts','expense_edit_conflicts','comments','receipts','categories','expense_history','expense_items','item_claims','item_shares','bill_participants']
  loop
    begin
      execute format('alter publication supabase_realtime add table public.%I;', t);
    exception
      when duplicate_object then null;
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
