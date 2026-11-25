-- Payment proof (docs/features/payment-proof.md): proof that a screenshot follows its payment through an
-- edit (it hangs off the chain id), that the web snapshot carries it with each payment's per-bill amounts,
-- and that it also carries the bill's receipts and comments.
--
-- Same contract as web_group_page.sql: it writes fixtures, asserts, and then ALWAYS raises, so every write
-- rolls back. "PAYMENT PROOFS: ALL PASSED" is green; any other message names the first failed check.
do $$
declare
  g text := 'test-pp-' || gen_random_uuid()::text;
  andrew text := 'a-' || gen_random_uuid()::text;
  maya text := 'm-' || gen_random_uuid()::text;
  e1 text := gen_random_uuid()::text;
  sh_maya text := gen_random_uuid()::text;
  sh_andrew text := gen_random_uuid()::text;
  s1 text := gen_random_uuid()::text;
  s2 text := gen_random_uuid()::text;
  res jsonb;
  pay jsonb;
  now_ms bigint := 1800000000000;
begin
  insert into public.groups (id, name, invite_token, created_at, created_by, updated_at)
  values (g, 'Orlando', 'inv-' || g, now_ms, andrew, now_ms);
  insert into public.users (id, is_placeholder, display_name, placeholder_group_id, created_by, created_at, updated_at) values
    (andrew, false, 'Andrew', null, andrew, now_ms, now_ms), (maya, true, 'Maya', g, maya, now_ms, now_ms);
  insert into public.members (id, group_id, user_id, joined_at, created_at, updated_at) values
    (gen_random_uuid()::text, g, andrew, now_ms, now_ms, now_ms), (gen_random_uuid()::text, g, maya, now_ms, now_ms, now_ms);
  insert into public.expenses (id, group_id, title, amount_subunits, currency, expense_date, payer_user_id, split_mode,
    created_by, created_at, updated_at)
  values (e1, g, 'Uber to Disney Springs', 4800, 'USD', '2026-11-18', andrew, 'EVEN', andrew, now_ms, now_ms);
  insert into public.shares (id, expense_id, user_id, share_owed_subunits, created_at, updated_at) values
    (sh_andrew, e1, andrew, 2400, now_ms, now_ms), (sh_maya, e1, maya, 2400, now_ms, now_ms);

  -- Maya pays $20, attaches a screenshot, then edits the payment to $24 (void + replacement).
  insert into public.settlements (id, group_id, from_user_id, to_user_id, payment_currency, payment_amount_subunits,
    payment_app, settled_at, created_by, created_at, updated_at)
  values (s1, g, maya, andrew, 'USD', 2000, 'ZELLE', now_ms, maya, now_ms, now_ms);
  insert into public.settlement_allocations (id, settlement_id, group_id, share_id, applied_amount_subunits, applied_currency, created_at)
  values (gen_random_uuid()::text, s1, g, sh_maya, 2000, 'USD', now_ms);
  insert into public.payment_proofs (id, settlement_id, group_id, uploaded_by, storage_path, url, mime_type, size_bytes, created_at, updated_at)
  values ('pf-1', s1, g, maya, g || '/payments/' || s1 || '/pf-1.jpg', 'https://x/pf-1.jpg', 'image/jpeg', 1000, now_ms, now_ms);

  update public.settlements set deleted_at = now_ms + 1 where id = s1;
  insert into public.settlements (id, group_id, from_user_id, to_user_id, payment_currency, payment_amount_subunits,
    payment_app, settled_at, created_by, created_at, updated_at, original_settlement_id)
  values (s2, g, maya, andrew, 'USD', 2400, 'ZELLE', now_ms, maya, now_ms + 1, now_ms + 1, s1);
  insert into public.settlement_allocations (id, settlement_id, group_id, share_id, applied_amount_subunits, applied_currency, created_at)
  values (gen_random_uuid()::text, s2, g, sh_maya, 2400, 'USD', now_ms + 1);

  insert into public.receipts (id, expense_id, group_id, uploaded_by, storage_path, url, mime_type, size_bytes, created_at, updated_at)
  values ('rc-1', e1, g, andrew, g || '/' || e1 || '/rc-1.jpg', 'https://x/rc-1.jpg', 'image/jpeg', 2000, now_ms, now_ms);
  insert into public.comments (id, expense_id, group_id, user_id, body, created_at, updated_at)
  values ('cm-1', e1, g, andrew, 'Surge pricing, sorry', now_ms, now_ms);

  res := public.web_group_snapshot(g, maya);

  if jsonb_array_length(res->'payments') <> 1 then raise exception 'snapshot: voided payment listed: %', res->'payments'; end if;
  pay := res->'payments'->0;
  if pay->>'id' <> s2 or pay->>'chainId' <> s1 then raise exception 'snapshot: chain id %', pay; end if;
  if pay->'proof'->>'id' is distinct from 'pf-1' then raise exception 'snapshot: proof lost on edit: %', pay; end if;
  if (pay->'applied'->0->>'subunits')::bigint <> 2400 or pay->'applied'->0->>'expenseId' <> e1 then
    raise exception 'snapshot: applied %', pay->'applied';
  end if;
  if pay->>'paymentApp' <> 'ZELLE' then raise exception 'snapshot: app %', pay; end if;
  if (res->'expenses'->0->'receipts'->0->>'id') is distinct from 'rc-1' then raise exception 'snapshot: receipts %', res->'expenses'->0; end if;
  if (res->'expenses'->0->'comments'->0->>'body') is distinct from 'Surge pricing, sorry' then
    raise exception 'snapshot: comments %', res->'expenses'->0;
  end if;

  -- A replaced screenshot: the newest live one wins, the old tombstone is ignored.
  update public.payment_proofs set deleted_at = now_ms + 2 where id = 'pf-1';
  insert into public.payment_proofs (id, settlement_id, group_id, uploaded_by, storage_path, url, mime_type, size_bytes, created_at, updated_at)
  values ('pf-2', s1, g, andrew, g || '/payments/' || s1 || '/pf-2.jpg', 'https://x/pf-2.jpg', 'image/jpeg', 1000, now_ms + 2, now_ms + 2);
  res := public.web_group_snapshot(g, maya);
  if res->'payments'->0->'proof'->>'id' is distinct from 'pf-2' then raise exception 'snapshot: replacement %', res->'payments'; end if;

  -- Removed entirely: no proof.
  update public.payment_proofs set deleted_at = now_ms + 3 where id = 'pf-2';
  res := public.web_group_snapshot(g, maya);
  if res->'payments'->0->'proof' <> 'null'::jsonb then raise exception 'snapshot: removed proof still shown %', res->'payments'; end if;

  raise exception 'PAYMENT PROOFS: ALL PASSED';
end $$;
