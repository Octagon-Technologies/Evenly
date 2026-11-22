-- Payment edits (docs/features/one-time-splits.md, B1 + B4): proof that only the two parties can change
-- a payment, that the later of two concurrent edits wins, and that the web guest RPCs keep to their
-- own payment and their own bill.
--
-- Run it against a project that already has the schema (SQL editor or the Supabase MCP execute_sql).
-- It writes fixtures, asserts, and then ALWAYS raises: the final exception is the report and rolls
-- every write back, so it is safe to run on the live project. "PAYMENT EDITS: ALL PASSED" is green;
-- any other message names the first check that failed.
do $$
declare
  g text := 'test-pe-' || gen_random_uuid()::text;
  alice text := gen_random_uuid()::text;
  bob text := gen_random_uuid()::text;
  steve text := gen_random_uuid()::text;
  ann text := gen_random_uuid()::text;  -- a typed-in name (web guest)
  e1 text := gen_random_uuid()::text;
  e2 text := gen_random_uuid()::text;
  sh_a1 text := gen_random_uuid()::text;
  sh_a2 text := gen_random_uuid()::text;
  sh_ann text := gen_random_uuid()::text;
  s1 text := gen_random_uuid()::text;
  r jsonb;
  v_count int;
  v_amount bigint;
  v_refused boolean;
  v_web text;
begin
  -- Fixtures, as the table owner. Bob paid both bills; Alice owes $9 + $6, Ann owes $21.05 on e1.
  insert into public.users (id, display_name, created_at, updated_at) values
    (alice, 'Alice', 1, 1), (bob, 'Bob', 1, 1), (steve, 'Steve', 1, 1);
  insert into public.users (id, display_name, is_placeholder, created_at, updated_at) values (ann, 'Ann', true, 1, 1);
  insert into public.groups (id, name, invite_token, created_at, created_by, updated_at)
    values (g, 'Apartment 4B', g, 1, bob, 1);
  insert into public.members (id, group_id, user_id, joined_at, created_at, updated_at) values
    (gen_random_uuid()::text, g, alice, 1, 1, 1), (gen_random_uuid()::text, g, bob, 1, 1, 1),
    (gen_random_uuid()::text, g, steve, 1, 1, 1), (gen_random_uuid()::text, g, ann, 1, 1, 1);
  insert into public.expenses (id, group_id, title, amount_subunits, currency, expense_date, split_mode,
      payer_user_id, created_by, created_at, updated_at) values
    (e1, g, 'Groceries', 5400, 'USD', '2026-11-12', 'EXACT', bob, bob, 1, 1),
    (e2, g, 'Internet', 1200, 'USD', '2026-11-15', 'EXACT', bob, bob, 1, 1);
  insert into public.shares (id, expense_id, user_id, share_owed_subunits, created_at, updated_at) values
    (sh_a1, e1, alice, 900, 1, 1), (sh_a2, e2, alice, 600, 1, 1), (sh_ann, e1, ann, 2105, 1, 1);
  insert into public.settlements (id, group_id, from_user_id, to_user_id, payment_currency,
      payment_amount_subunits, settled_at, created_by, created_at, updated_at)
    values (s1, g, alice, bob, 'USD', 1500, 2, alice, 2, 2);
  insert into public.settlement_allocations (id, settlement_id, group_id, share_id, applied_amount_subunits,
      applied_currency, created_at) values
    (gen_random_uuid()::text, s1, g, sh_a1, 900, 'USD', 2), (gen_random_uuid()::text, s1, g, sh_a2, 600, 'USD', 2);

  -- 1 · Steve (a member, not a party) cannot void or re-amount Alice -> Bob.
  perform set_config('request.jwt.claims', json_build_object('sub', steve, 'role', 'authenticated')::text, true);
  set local role authenticated;
  v_refused := false;
  begin
    update public.settlements set deleted_at = 3, updated_at = 3 where id = s1;
  exception when insufficient_privilege then v_refused := true;
  end;
  if not v_refused then raise exception 'FAIL 1a: a third member voided someone else''s payment'; end if;
  v_refused := false;
  begin
    update public.settlements set payment_amount_subunits = 1 where id = s1;
  exception when insufficient_privilege then v_refused := true;
  end;
  if not v_refused then raise exception 'FAIL 1b: a third member changed someone else''s amount'; end if;
  v_refused := false;
  begin
    insert into public.settlements (id, group_id, from_user_id, to_user_id, payment_currency,
        payment_amount_subunits, settled_at, created_by, created_at, updated_at, original_settlement_id)
      values (gen_random_uuid()::text, g, alice, bob, 'USD', 100, 3, steve, 3, 3, s1);
  exception when insufficient_privilege then v_refused := true;
  end;
  if not v_refused then raise exception 'FAIL 1c: a third member wrote a replacement for someone else''s payment'; end if;
  -- An unchanged full-row re-send from Steve's device is NOT refused (the push is a full-row upsert).
  update public.settlements set updated_at = updated_at where id = s1;
  reset role;

  -- 2 · Bob (the one owed) edits $15 -> $5: void + replacement. Allowed.
  perform set_config('request.jwt.claims', json_build_object('sub', bob, 'role', 'authenticated')::text, true);
  set local role authenticated;
  update public.settlements set deleted_at = 4, updated_at = 4 where id = s1;
  insert into public.settlements (id, group_id, from_user_id, to_user_id, payment_currency,
      payment_amount_subunits, settled_at, created_by, created_at, updated_at, original_settlement_id)
    values ('bob-edit-' || s1, g, alice, bob, 'USD', 500, 4, bob, 4, 4, s1);
  reset role;

  -- 3 · Alice edits the same payment at the same time, from the original. Her replacement arrives last
  --     and wins: Bob's replacement is voided, exactly one payment of the chain stays live.
  perform set_config('request.jwt.claims', json_build_object('sub', alice, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.settlements (id, group_id, from_user_id, to_user_id, payment_currency,
      payment_amount_subunits, settled_at, created_by, created_at, updated_at, original_settlement_id)
    values ('alice-edit-' || s1, g, alice, bob, 'USD', 1000, 5, alice, 5, 5, s1);
  reset role;
  select count(*), max(payment_amount_subunits) into v_count, v_amount from public.settlements
   where (id = s1 or original_settlement_id = s1) and deleted_at is null;
  if v_count <> 1 or v_amount <> 1000 then
    raise exception 'FAIL 3: expected one live payment of $10.00 in the chain, got % (max %)', v_count, v_amount;
  end if;

  -- 4 · Web guest Ann records $210.50 by mistake: refused with the ceiling ($21.05), then $20.00.
  --     `web-claim` calls these with the service key, so there is no JWT: clear the test's.
  perform set_config('request.jwt.claims', '', true);
  r :=public.record_web_payment(e1, ann, 21050, 'venmo', 10);
  if (r->>'ok')::boolean or (r->>'ceiling')::bigint <> 2105 then raise exception 'FAIL 4a: %', r; end if;
  r := public.record_web_payment(e1, ann, 2000, 'venmo', 10);
  if not (r->>'ok')::boolean then raise exception 'FAIL 4b: %', r; end if;
  v_web := r->>'settlement_id';

  -- 5 · Ann cannot edit Alice's payment; she can change her own to $21.05, then remove it.
  r := public.edit_web_payment(e1, 'alice-edit-' || s1, ann, 100, 11);
  if (r->>'ok')::boolean or r->>'reason' <> 'NOT_YOURS' then raise exception 'FAIL 5a: %', r; end if;
  r := public.edit_web_payment(e1, v_web, ann, 2200, 11);
  if (r->>'ok')::boolean or (r->>'ceiling')::bigint <> 2105 then raise exception 'FAIL 5b: %', r; end if;
  r := public.edit_web_payment(e1, v_web, ann, 2105, 11);
  if not (r->>'ok')::boolean then raise exception 'FAIL 5c: %', r; end if;
  v_web := r->>'settlement_id';
  if (select payment_amount_subunits from public.settlements where id = v_web) <> 2105 then
    raise exception 'FAIL 5d: replacement amount wrong';
  end if;
  if (select original_settlement_id from public.settlements where id = v_web) is null then
    raise exception 'FAIL 5e: replacement does not name its chain';
  end if;
  r := public.edit_web_payment(e1, v_web, ann, 0, 12);
  if not (r->>'ok')::boolean then raise exception 'FAIL 5f: %', r; end if;
  if exists (select 1 from public.settlements where from_user_id = ann and deleted_at is null) then
    raise exception 'FAIL 5g: removed payment is still live';
  end if;

  -- 6 · History: one row per web write, with the app's tokens.
  select count(*) into v_count from public.expense_history where expense_id = e1 and actor_user_id = ann;
  if v_count <> 3 then raise exception 'FAIL 6a: expected 3 history rows for Ann, got %', v_count; end if;
  if not exists (select 1 from public.expense_history where expense_id = e1 and type = 'SETTLEMENT_EDITED'
                  and detail = 'edit:2000:2105:' || ann || ':2105') then
    raise exception 'FAIL 6b: edit history token missing';
  end if;
  if not exists (select 1 from public.expense_history where expense_id = e1 and type = 'SETTLEMENT_REMOVED'
                  and detail = 'removed:2105:' || ann) then
    raise exception 'FAIL 6c: removal history token missing';
  end if;

  raise exception 'PAYMENT EDITS: ALL PASSED';
end;
$$;
