# RLS hardening — launch blocker

**Hand this file to a fresh Claude Code session as its entire kickoff prompt.** Use high reasoning
effort. Read `AGENTS.md` and `supabase/AGENTS.md` before your first edit.

---

## Mission

Replace Evenly's permissive RLS with membership-scoped policies, so that an authenticated user can
read and write **only the groups they are a member of**. This is the last thing standing between the
app and launch.

Today every app table carries `for all to authenticated using (true) with check (true)`. Any signed-in
account can read and write every other group's ledger. Verified live on project `wfpfgbipjmkysalfmyub`
via the security advisor on 2026-08-17.

## Scope

**In scope:** RLS policies, the grants and helper functions they need, the indexes that keep them
fast, and `supabase/schema.sql` + `supabase/AGENTS.md` staying truthful about both.

**Out of scope — do not touch, even if you spot a problem:** app logic in `code/`, the destructive
Room migration, any open item in `review/`, the realtime publication, UI, and Pro. If a policy change
*forces* a client change, stop and report it rather than widening the work yourself.

---

## Ground truth (verified 2026-08-17 — treat as a strong hint, re-verify before relying on it)

### The 20 tables that are wide open

Nineteen come from the loop at [`schema.sql:630`](supabase/schema.sql), one is a copy of the same
shape declared separately:

```
users  groups  members  expenses  shares  settlements  settlement_allocations
conflicts  expense_edit_conflicts  comments  expense_blocked_users  receipts
categories  expense_history  device_tokens  expense_items  item_claims
item_shares  bill_participants
```

plus `pending_item_edits` (`pending_item_edits_rw`, [`schema.sql:1528`](supabase/schema.sql)).

### Six tables already scoped correctly — do not clobber

`placeholder_claim_answers` (:102), `superseded_split_edits` (:374), `receipt_scan_log` (:503),
`group_activity` (:660), `group_passes` (:2550), `user_subscriptions` (:2636). Read them first: they
are the house style for what you are about to write, and `group_activity`'s is load-bearing for
realtime delivery.

### Eight tables with RLS on and zero policies — leave exactly as they are

`apple_oauth_tokens`, `ops_alerts`, `pro_orphan_purchases`, `receipt_opus_escalations`,
`waitlist_signups`, `web_bill_links`, `web_claim_write_log`, `web_sessions`. Service-role only by
design. **This is also why the web claim flow is not at risk from your change** — it runs through edge
functions on the service key, which bypasses RLS. Confirm that before you rely on it.

### How the client actually touches these tables

- **Reads:** direct PostgREST `select`, scoped by `group_id` or by parent id, in `SyncEngine.pull`
  ([`SyncEngine.kt:280-394`](code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/supabase/SyncEngine.kt:280)).
- **Writes:** direct upserts for 18 tables; **`expenses` and `shares` are the exception** — they move
  as one unit through the `merge_expense` RPC.
- **`merge_expense` and `commit_expense` are SECURITY INVOKER**, not DEFINER. They run as the calling
  user, so your policies apply *inside* them. A write policy that forgets this breaks every expense
  write in the app. Check each RPC's declaration yourself before assuming which way it goes.

---

## The seven traps

1. **`members` recursion.** A membership policy on `members` that selects from `members` recurses
   infinitely. Write a `security definer` helper with a pinned `search_path` — e.g.
   `public.is_group_member(p_group_id text)` — and call it from every policy, including `members`' own.
2. **A single permissive policy defeats every other one.** Postgres OR's permissive policies together.
   You must **delete the `_rw` loop and the `pending_item_edits_rw` block from `schema.sql`**, not
   leave them and add stricter policies alongside. If the loop survives, a from-scratch apply silently
   restores `using (true)`.
3. **Tombstones must stay readable.** Deletions propagate as soft-deleted rows with `deleted_at` set.
   A policy that filters `deleted_at is null` stops deletions reaching other devices and resurrects
   data — `AGENTS.md` §4.5.
4. **Placeholder users have no `auth.uid()`.** `users` rows for un-claimed people have no auth
   account. Scoping `users` to `id = auth.uid()` makes co-members and placeholders invisible and
   breaks every name in the app. `users` needs: myself, **or** anyone I share a group with.
5. **Child tables have no `group_id`.** `shares`, `comments`, `receipts`, `expense_items`,
   `item_claims`, `item_shares`, `bill_participants`, `pending_item_edits`, `expense_history` reach
   the group through their parent expense. A nested `exists` chain per row is where this gets slow —
   prefer a second definer helper (`can_access_expense(p_expense_id text)`) over hand-rolling the join
   into 9 policies.
6. **Performance is a correctness issue here.** Wrap `auth.uid()` as `(select auth.uid())` so it is
   evaluated once per statement rather than once per row, and add an index on
   `members (user_id, group_id)` if one does not exist. A membership subquery per row on `expenses`
   will make sync unusable on a real group.
7. **`device_tokens` is not group-scoped.** It is per-user: `user_id = auth.uid()`. Do not sweep it
   into the membership pattern.

---

## Three decisions to surface, not settle alone

Per `AGENTS.md` §7, put these to the owner with a recommendation and wait. Each one changes shipped
behavior.

1. **Ex-members.** `members` carries `left_at` and `status`. Does membership mean *ever joined* or
   *currently active*? Scoping to active revokes a departed member's access to their own settled
   history. Note this interacts with review finding S5, which was deliberately left open.
2. **The `receipts` storage bucket is `public = true`** ([`schema.sql:797`](supabase/schema.sql)), so
   its four `storage.objects` policies are decorative — anyone with a URL reads any receipt photo.
   Closing it means signed URLs, which is a client change and therefore outside your scope. Recommend,
   do not implement.
3. **Write scope.** Membership gates *which group* a user may write to, not *which rows within it*.
   Tighter per-actor write rules (only the payer may edit their settlement, say) are a larger design.
   Recommend membership-level writes now and say plainly what that still permits.

---

## Verification — isolation must be proven, not assumed

A green build proves nothing about RLS. Do all four:

1. **Prove isolation.** Two real accounts, A and B, in different groups. As B, query every one of the
   20 tables for A's rows. **Expect zero rows on all 20.** Paste the results. A test that cannot fail
   is not evidence.
2. **Prove the app still works.** Run the iOS simulator (`code/iosApp/run-ios-sim.sh`, per `AGENTS.md`
   §5) and complete one full round trip: create a group, add an expense, split it, settle it, sign out,
   sign back in, confirm it all pulls. Watch for `Backend` errors — finding #25's fix means RLS denials
   now surface with a real status instead of reading as "offline", so use that.
3. **Prove `merge_expense` survives.** It is SECURITY INVOKER. Edit an existing expense end to end.
4. **Re-run the advisor** (`get_advisors`, type `security`) and confirm the `auth_allow_anonymous_sign_ins`
   entries for the 20 tables are gone.

If any of the four fails, roll back rather than shipping a partial tightening. Half-scoped RLS is worse
than none: it fails asymmetrically and the failures look like sync bugs.

## Rollback

Apply as an **additive, idempotent migration** via the Supabase MCP `apply_migration`, per
`supabase/AGENTS.md`. Before applying, write the exact inverse migration that restores the `_rw`
policies, and keep it to hand. You are editing the live project that the owner is about to launch on.

## Commit

`AGENTS.md` §6 in full: branch (do not commit to `main`), one coherent commit, conventional message
scoped `fix(rls)`, the Claude `Co-Authored-By:` trailer. `schema.sql` and `supabase/AGENTS.md` change
in the **same** commit as the migration — §6.7 makes that mandatory, and a schema file that still shows
`using (true)` after this lands is how the next session reintroduces the hole.

**Note the tree state:** the working tree currently carries ~113 modified and ~70 untracked files from
the review-fix pass. Stage your paths explicitly (`git add supabase/...`); never `git add -A` here.
