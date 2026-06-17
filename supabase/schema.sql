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
  placeholder_group_id text,
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
  category_id text,
  subcategory_id text,
  refund_of_expense_id text,
  is_auto_refund boolean not null default false,
  status text not null default 'ACTIVE',
  created_by text not null,
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
  remaining_subunits bigint not null,
  share_units integer,
  share_percentage double precision,
  share_exact_subunits bigint,
  created_at bigint not null,
  updated_at bigint not null,
  row_version bigint not null default 1,
  unique (expense_id, user_id)
);
create index if not exists shares_expense_idx on public.shares (expense_id);

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
  foreach t in array array['users','groups','members','expenses','shares','settlements','conflicts','comments','receipts','expense_history','device_tokens']
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
  foreach t in array array['groups','members','expenses','shares','settlements','conflicts','comments','receipts','expense_history']
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
