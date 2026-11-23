-- Web group page (docs/features/web-group-page.md): proof that a guest's group payment pays oldest-first
-- and never above what is owed, that a web-created expense splits penny-exact like Kotlin's `allocate`,
-- that an edit keeps share ids, refuses a stale base, and that Undo restores the old split exactly.
--
-- Same contract as payment_edits.sql: it writes fixtures, asserts, and then ALWAYS raises, so every write
-- rolls back. "WEB GROUP PAGE: ALL PASSED" is green; any other message names the first failed check.
do $$
declare
  g text := 'test-wgp-' || gen_random_uuid()::text;
  alice text := 'a-' || gen_random_uuid()::text;
  bob text := 'b-' || gen_random_uuid()::text;
  joe text := 'c-' || gen_random_uuid()::text;
  e1 text := gen_random_uuid()::text;
  e2 text := gen_random_uuid()::text;
  e3 text := gen_random_uuid()::text;
  e4 text := gen_random_uuid()::text;
  res jsonb;
  v bigint;
  v_ids text[];
  v_share_alice text;
  v_edit text;
  now_ms bigint := 1800000000000;
begin
  insert into public.groups (id, name, invite_token, created_at, created_by, updated_at)
  values (g, 'Tulum', 'inv-' || g, now_ms, joe, now_ms);
  insert into public.users (id, is_placeholder, display_name, placeholder_group_id, created_by, created_at, updated_at) values
    (alice, true, 'Alice', g, alice, now_ms, now_ms), (bob, true, 'Bob', g, bob, now_ms, now_ms),
    (joe, false, 'Joe', null, joe, now_ms, now_ms);
  insert into public.members (id, group_id, user_id, joined_at, created_at, updated_at) values
    (gen_random_uuid()::text, g, alice, now_ms, now_ms, now_ms),
    (gen_random_uuid()::text, g, bob, now_ms, now_ms, now_ms),
    (gen_random_uuid()::text, g, joe, now_ms, now_ms, now_ms);

  -- 1. Create: $10.00 even over three people ⇒ 334/333/333, the extra penny to the lowest id ("a-…").
  res := public.create_web_expense(g, alice, e1, 'Groceries', 1000, 'USD', '2026-11-01', joe, 'EVEN',
    jsonb_build_array(jsonb_build_object('user_id', alice), jsonb_build_object('user_id', bob), jsonb_build_object('user_id', joe)),
    null, null, now_ms);
  if not (res->>'ok')::boolean then raise exception 'create: %', res; end if;
  select share_owed_subunits into v from public.shares where expense_id = e1 and user_id = alice;
  if v <> 334 then raise exception 'even split: alice owes % not 334', v; end if;
  select sum(share_owed_subunits) into v from public.shares where expense_id = e1;
  if v <> 1000 then raise exception 'even split sums to % not 1000', v; end if;
  if not exists (select 1 from public.expense_history where expense_id = e1 and type = 'CREATED' and actor_user_id = alice) then
    raise exception 'create: no CREATED history';
  end if;

  -- 2. EXACT must add up, and everyone must be in the group.
  begin
    perform public.create_web_expense(g, alice, e2, 'Bad', 1000, 'USD', '2026-11-02', joe, 'EXACT',
      jsonb_build_array(jsonb_build_object('user_id', alice, 'exact', 400), jsonb_build_object('user_id', bob, 'exact', 500)),
      null, null, now_ms);
    raise exception 'exact: a short split was accepted';
  exception when others then
    if sqlerrm not like 'web expense: exact amounts%' then raise; end if;
  end;
  begin
    perform public.create_web_expense(g, alice, e2, 'Bad', 1000, 'USD', '2026-11-02', joe, 'EVEN',
      jsonb_build_array(jsonb_build_object('user_id', 'stranger')), null, null, now_ms);
    raise exception 'create: a stranger was put in a split';
  exception when others then
    if sqlerrm not like 'web expense: everyone in the split%' then raise; end if;
  end;
  res := public.create_web_expense(g, alice, e2, 'Taxi', 600, 'USD', '2026-11-02', joe, 'EXACT',
    jsonb_build_array(jsonb_build_object('user_id', alice, 'exact', 400), jsonb_build_object('user_id', joe, 'exact', 200)),
    null, null, now_ms);

  -- 3. Itemized: amount is the lines' sum, no shares, participants include the payer.
  res := public.create_web_expense(g, bob, e3, 'Ramen', 0, 'USD', '2026-11-03', joe, 'ITEMIZED',
    jsonb_build_array(jsonb_build_object('user_id', alice)),
    jsonb_build_array(jsonb_build_object('label', 'Katsu', 'quantity', 2, 'line_total', 2401),
                      jsonb_build_object('label', 'Beer', 'quantity', 1, 'line_total', 700)),
    null, now_ms);
  if (res->>'amount_subunits')::bigint <> 3101 then raise exception 'itemized amount %', res; end if;
  if exists (select 1 from public.shares where expense_id = e3) then raise exception 'itemized wrote shares'; end if;
  if (select count(*) from public.bill_participants where expense_id = e3) <> 2 then raise exception 'itemized participants'; end if;

  -- 4. Group payment: Alice owes Joe 334 (e1) + 400 (e2) = 734. Above that is refused with the ceiling.
  res := public.record_web_group_payment(g, alice, joe, 'USD', 800, null, now_ms);
  if (res->>'ok')::boolean or (res->>'ceiling')::bigint <> 734 then raise exception 'ceiling: %', res; end if;
  -- 500 pays e1 in full first (oldest), then 166 of e2.
  res := public.record_web_group_payment(g, alice, joe, 'USD', 500, 'cash', now_ms);
  if not (res->>'ok')::boolean then raise exception 'pay: %', res; end if;
  select id into v_share_alice from public.shares where expense_id = e1 and user_id = alice;
  if public._share_remaining(v_share_alice) <> 0 then raise exception 'oldest-first: e1 not cleared'; end if;
  select public._share_remaining(id) into v from public.shares where expense_id = e2 and user_id = alice;
  if v <> 234 then raise exception 'oldest-first: e2 remaining % not 234', v; end if;
  -- Scoped to one bill, the ceiling is that bill's remainder only.
  res := public.record_web_group_payment(g, alice, joe, 'USD', 300, null, now_ms, e2);
  if (res->>'ok')::boolean or (res->>'ceiling')::bigint <> 234 then raise exception 'scoped ceiling: %', res; end if;
  res := public.record_web_group_payment(g, alice, alice, 'USD', 1, null, now_ms);
  if (res->>'ok')::boolean then raise exception 'paid myself'; end if;

  -- 5. Edit: a stale base is refused and changes nothing.
  res := public.apply_web_expense_edit(e1, g, bob, 99, null, 1200, null, null, null, now_ms + 1);
  if res->>'reason' <> 'STALE' then raise exception 'stale edit: %', res; end if;
  -- Drop Bob, raise to $12.00: Alice keeps her share id (her payment still applies to it), Bob is tombstoned.
  res := public.apply_web_expense_edit(e1, g, bob, 1, 'Groceries + wine', 1200, null, 'EVEN',
    jsonb_build_array(jsonb_build_object('user_id', alice), jsonb_build_object('user_id', joe)), now_ms + 2);
  if not (res->>'ok')::boolean then raise exception 'edit: %', res; end if;
  v_edit := res->>'edit_id';
  if not exists (select 1 from public.shares where id = v_share_alice and deleted_at is null and share_owed_subunits = 600) then
    raise exception 'edit: alice lost her share id or owes the wrong amount';
  end if;
  if not exists (select 1 from public.shares where expense_id = e1 and user_id = bob and deleted_at is not null) then
    raise exception 'edit: bob not tombstoned';
  end if;
  if (select split_version from public.expenses where id = e1) <> 2 then raise exception 'edit: split_version not advanced'; end if;
  if (select title from public.expenses where id = e1) <> 'Groceries + wine' then raise exception 'edit: title'; end if;
  -- Locally push_dispatch is a stub that records into _pushes; on the live project it is the real sender.
  if to_regclass('pg_temp._pushes') is not null then
    execute 'select count(*) from _pushes where p->''onlyUserIds'' ? $1' into v using joe;
    if v = 0 then raise exception 'edit: payer not notified'; end if;
  end if;
  -- Editing an itemized bill here is refused; it is edited line by line on its own link.
  res := public.apply_web_expense_edit(e3, g, bob, 1, 'x', null, null, null, null, now_ms + 3);
  if res->>'reason' <> 'UNSUPPORTED' then raise exception 'itemized edit: %', res; end if;

  -- 6. Undo restores the exact old split, Bob's old row included, and only once.
  res := public.undo_web_expense_edit(v_edit, g, joe, now_ms + 4);
  if not (res->>'ok')::boolean or not (res->>'changed')::boolean then raise exception 'undo: %', res; end if;
  select array_agg(user_id || ':' || share_owed_subunits order by user_id collate "C") into v_ids
    from public.shares where expense_id = e1 and deleted_at is null;
  if v_ids <> array[alice || ':334', bob || ':333', joe || ':333'] then raise exception 'undo split: %', v_ids; end if;
  if (select amount_subunits from public.expenses where id = e1) <> 1000 then raise exception 'undo amount'; end if;
  if (select title from public.expenses where id = e1) <> 'Groceries' then raise exception 'undo title'; end if;
  res := public.undo_web_expense_edit(v_edit, g, joe, now_ms + 5);
  if (res->>'changed')::boolean then raise exception 'undo twice'; end if;

  -- 7. Undo after a later money change is refused rather than reverting it.
  res := public.apply_web_expense_edit(e2, g, bob, 1, null, 700, null, 'EXACT',
    jsonb_build_array(jsonb_build_object('user_id', alice, 'exact', 500), jsonb_build_object('user_id', joe, 'exact', 200)), now_ms + 6);
  v_edit := res->>'edit_id';
  res := public.apply_web_expense_edit(e2, g, alice, 2, null, 800, null, 'EXACT',
    jsonb_build_array(jsonb_build_object('user_id', alice, 'exact', 600), jsonb_build_object('user_id', joe, 'exact', 200)), now_ms + 7);
  res := public.undo_web_expense_edit(v_edit, g, joe, now_ms + 8);
  if res->>'reason' <> 'CHANGED_SINCE' then raise exception 'undo over newer: %', res; end if;

  -- 8. A typed-in name: blocked when it clashes, otherwise a placeholder created by the guest.
  res := public.add_web_group_name(g, ' alice ', gen_random_uuid()::text, gen_random_uuid()::text, bob, now_ms);
  if (res->>'created')::boolean or res->>'user_id' <> alice then raise exception 'name clash: %', res; end if;
  res := public.add_web_group_name(g, 'Steve', 's-x', gen_random_uuid()::text, bob, now_ms);
  if not (res->>'created')::boolean then raise exception 'new name: %', res; end if;
  if (select created_by from public.users where id = 's-x') <> bob then raise exception 'new name created_by'; end if;

  -- 9. The snapshot derives remaining like the client: owed minus live payments, 0 on the payer's own share.
  res := public.web_group_snapshot(g, alice);
  if jsonb_array_length(res->'members') <> 4 or jsonb_array_length(res->'expenses') <> 3 then
    raise exception 'snapshot sizes: %', res;
  end if;
  if (select (s->>'remainingSubunits')::bigint from jsonb_array_elements(res->'expenses') x,
        jsonb_array_elements(x->'shares') s where x->>'id' = e1 and s->>'userId' = alice) <> 0 then
    raise exception 'snapshot: alice still owes on e1';
  end if;
  if (select (s->>'remainingSubunits')::bigint from jsonb_array_elements(res->'expenses') x,
        jsonb_array_elements(x->'shares') s where x->>'id' = e1 and s->>'userId' = joe) <> 0 then
    raise exception 'snapshot: payer share not zero';
  end if;
  if (select (x->>'iClaimed')::boolean from jsonb_array_elements(res->'expenses') x where x->>'id' = e3) then
    raise exception 'snapshot: claimed flag';
  end if;
  if jsonb_array_length(res->'edits') <> 3 then raise exception 'snapshot edits: %', res->'edits'; end if;

  raise exception 'WEB GROUP PAGE: ALL PASSED';
end $$;
