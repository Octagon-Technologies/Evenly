# Spec — block & report on expense comments

Status: **§8 steps 1–2 done.** `expense_blocked_users` is live on `wfpfgbipjmkysalfmyub`, mirrored in
`supabase/schema.sql`, and has a Room mirror (`ExpenseBlockedUserEntity` + `ExpenseBlockedUserDao`,
db version 24) wired into `SyncEngine`'s pull/push. Green on Android + iOS Native. Next: §8 step 3
(`CommentRepository` filter + block/unblock writes).
Follows the long-press mockup (Artifact `f11931bb-…`, "Report & Block from Chat"). Touches `comments`
(`data/AGENTS.md`, `supabase/AGENTS.md` — flagged, see §2 note) plus `ui/screen/expense/ExpenseDetailScreen.kt`.

---

## 1. What this is

The expense comment thread (`ExpenseDetailScreen`'s "Comments" section, F5) has no safety controls today.
Long-pressing a message should offer **Reply** (unchanged — drops the sender's name into the composer,
no embedded-reply UI exists to point at), **Report message**, and **Block [name]**.

## 2. Decisions locked

| # | Decision | Consequence |
| --- | --- | --- |
| 1 | Block is **scoped to one expense's chat**, not the group or any other expense | A blocks C in "Dinner at Kavi"; C's messages still show in "Groceries" |
| 2 | Block is a **read-side filter for the blocker**, not a send-side restriction | The blocked person can still post; nobody's composer is disabled. Matches "don't build report escalation" — there's no moderation authority backing a hard mute yet |
| 3 | Blocking hides **all** of that person's messages in that expense, past and future, **including the one just reported** | Filter is by `(expense_id, blocked_user_id)`, not per-message |
| 4 | Unblock is available and **reveals everything immediately** | Same filter, just gated off |
| 5 | **Report ≠ escalation yet.** Tapping Report performs the same block write, with copy that says so | See §5 non-goal and the TODO in §6 |
| 6 | Unblock lives **on the expense**, not group settings | Scope is per-expense (decision 1); a group-settings list would misrepresent that |
| 7 | New table `expense_blocked_users`, synced, following the `comments` pattern (`group_id` denormalized for RLS scoping) | **Flagging per `data/AGENTS.md`: this is a new synced table** — needs the Postgres column added first, before the Room entity |

## 3. Data model

New table, additive migration, `supabase/schema.sql` first:

```sql
create table if not exists expense_blocked_users (
  id text primary key,                         -- "<expense_id>__<blocker_user_id>__<blocked_user_id>"
  expense_id text not null,
  group_id text not null,                       -- denormalized, same reason as comments.group_id
  blocker_user_id text not null,                -- who blocked
  blocked_user_id text not null,                -- who got blocked
  reason text not null default 'block',         -- 'block' | 'report' — see §6 TODO, not read anywhere yet
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint,                            -- soft-delete = unblock
  row_version bigint not null default 1
);
```

Room mirror: `ExpenseBlockedUserEntity` (snake_case `@ColumnInfo`, `@Serializable`), same shape as
`CommentEntity`. Deterministic id, same convention as `shares` (`"<expenseId>__<userId>"`) and
`BillRepositoryImpl.materializeShares`, so re-blocking after a stale local write converges instead of
duplicating.

`ExpenseBlockedUserDao`:

```kotlin
@Upsert suspend fun upsert(row: ExpenseBlockedUserEntity)

/** Active blocks the current user holds on this expense — drives both the filter and the manage sheet. */
@Query("SELECT * FROM expense_blocked_users WHERE expense_id = :expenseId AND blocker_user_id = :blockerId AND deleted_at IS NULL")
fun observeActive(expenseId: String, blockerId: String): Flow<List<ExpenseBlockedUserEntity>>

@Query("UPDATE expense_blocked_users SET deleted_at = :now, updated_at = :now, row_version = row_version + 1 WHERE id = :id")
suspend fun unblock(id: String, now: Long)

@Query("SELECT * FROM expense_blocked_users") suspend fun allForSync(): List<ExpenseBlockedUserEntity>
```

## 4. Filtering behavior

`CommentDao.observeByExpense` gets a `currentUserId` param and excludes authors that user has actively
blocked on that expense:

```sql
SELECT c.* FROM comments c
WHERE c.expense_id = :expenseId AND c.deleted_at IS NULL
  AND c.user_id NOT IN (
    SELECT blocked_user_id FROM expense_blocked_users
    WHERE expense_id = :expenseId AND blocker_user_id = :currentUserId AND deleted_at IS NULL
  )
ORDER BY c.created_at ASC
```

This is a **live** filter (same query re-runs on every emission), not a one-time redaction — so unblocking
makes everything reappear without a re-fetch, and blocking makes the reported message vanish along with
the rest of that person's history in the thread, satisfying decisions 3 and 4 in one query. No per-comment
flag needed.

## 5. UI

**Long-press menu** (already mocked): Reply / Report message / Block [name], the latter two in the
destructive red, grouped at the bottom.

**Confirmation**, one shared alert-driven flow for both destructive items, copy differs:

- Block: *"Block Jordan? They won't be able to post in this expense chat for you. Jordan keeps their
  share of the bill and isn't notified."*
- Report: *"Report & block Jordan? Reports aren't reviewed by our team yet, so for now this only blocks
  them here for you. Jordan keeps their share of the bill and isn't notified."*

Both call the same `onBlockSender(expenseId, userId, reason)` callback; `reason` is `"report"` vs
`"block"` purely for the TODO in §6, no behavioral difference today. Being upfront that Report currently
= Block is a copy decision, not a technicality — silently blocking under a "Report" label without saying
so would read as broken once someone reports the same person twice and nothing escalates.

**Manage/unblock surface**: a text row under the Comments section header, shown only when the current
user has ≥1 active block on this expense — `"1 person blocked · Manage"` — opening a sheet listing
blocked senders with an **Unblock** action each. Mirrors the existing collapsible-section pattern already
in `ExpenseDetailScreen.kt:410-460`; no new sheet primitive needed (`EvSheetScaffold`).

## 6. Non-goals (explicit)

- **No send-side restriction.** The blocked person's composer, other expenses, and ability to post
  everywhere else are untouched.
- **No cross-expense or group-level block.** Blocking is local to the one conversation.
- **No notification to the blocked person**, on block or on report.
- **No report review surface.** Nothing routes to an admin/support queue. Leave a TODO where
  `onReportMessage` is wired (`ExpenseDetailScreen.kt` / its Route wrapper):

  ```
  // TODO(report-escalation): Report currently just blocks (reason="report" on
  // expense_blocked_users). To actually escalate: a `moderation_reports` table
  // (report_id, expense_id, comment_id, reporter_user_id, reported_user_id,
  // reason, status, reviewed_by, reviewed_at), an admin-facing review surface,
  // and a decision on who counts as "admin" for a group with no formal roles today.
  // Not scoped here — ask before building.
  ```

## 7. Acceptance criteria

1. A posts a comment in Expense X's chat. B (another group member) long-presses A's message and taps
   Block → confirms. A's message, including the one B acted on, disappears from B's view of X's thread
   immediately.
2. A's messages in every other expense, and A's ability to post new messages in X, are unaffected.
3. B opens "Manage" under Comments on X, sees A listed, taps Unblock. All of A's messages in X reappear
   for B immediately.
4. Tapping Report instead of Block on step 1 produces the same visible result as step 1 (message and
   sender's history hidden for B), with the Report-specific confirmation copy from §5.

## 8. Build order

1. ✅ **Server**: `expense_blocked_users` added to `schema.sql` and applied via `apply_migration`
   (`add_expense_blocked_users`, project `wfpfgbipjmkysalfmyub`). `get_advisors` shows only the
   pre-existing permissive-RLS pattern already shared by every synced table — no new warnings.
2. ✅ **Data**: `ExpenseBlockedUserEntity` + `ExpenseBlockedUserDao` (`data/db/entity`, `data/db/dao`),
   added to `EvenlyDatabase` (v23 → v24, destructive migration per that file's gating notice, schema
   exported to `code/shared/schemas/…/24.json`) and to `SyncEngine.pull`/`push`, scoped by `expense_id`
   like `comments`.
3. **Repository**: `CommentRepository` gets `blockSender` / `unblockSender` / `observeBlocked`, and
   `observeByExpense` takes `currentUserId` and applies the filter query from §4.
4. **UI**: wire the three long-press actions and the manage sheet in `ExpenseDetailScreen.kt` + its
   Route wrapper (DI stays out of the screen per `ui/AGENTS.md`).
5. **Verify**: green on `:shared:compileAndroidMain` and `:shared:compileKotlinIosSimulatorArm64`, run
   AC 1–4 on the iOS simulator per root `AGENTS.md` §5, then `ux-firsttimer` on the long-press flow before
   calling it done.
