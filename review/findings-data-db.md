# Findings — `data/db/` + `data/upload/`

> **STATUS 2026-08-16: all twelve findings FIXED**, on `fix/security-handoff`, both platforms compiled
> and both test suites green (`testAndroidHostTest` + `iosSimulatorArm64Test`). Tests were written
> first and pin each fix: `UserDaoTest` (B2), `CategoryDaoTest` (B10), `ExpenseShareDaoTest` (B9),
> `ItemShareDaoTest` (B6), `ReceiptUploadDaoTest` + `ReceiptUploadRetryTest` (B7/B8),
> `EntitySerializablePartitionTest` (B5), `RowFingerprintTest` (B12). Resolutions:
> B1 `schema.sql` caught up to live (users columns, three account-deletion RPCs + grants, guarded
> cron schedule, `delete_my_account` dropped from the file). B3 fixed in the preferred direction:
> `users_email_lower_uidx` applied to the live project (migration `users_email_lower_unique_index`)
> and added to `schema.sql`. B2 `UserEntity.deleted_at` added + `findByEmail` filters it (DB v29).
> B4 all seven dead methods deleted, `data/AGENTS.md` references corrected. B6 `getActiveShares`
> (list, portion-aware) replaces `getActiveShare`; dead `setShareMember` deleted. B7 auto-retry
> capped at `MAX_AUTO_RETRIES`, manual retry resets the budget (`resetForRetry`), `lastError`
> surfaced on `ReceiptUpload`. B8 deterministic `RECEIPT_ADDED` history id. B9 `row_version` bump
> added. B10 `AND deleted_at IS NULL` guard added. B11 `FxRateEntity` KDoc + `data/AGENTS.md`
> device-local list corrected. B12 64-bit fingerprint — note the first attempt
> (`hashCode` + `toString().hashCode()`) was refuted by its own test: both halves share Kotlin's
> base-31 polynomial and collide together on same-length substring swaps; the shipped version mixes
> FNV-1a, a different hash family. The §3 out-of-scope `TipSplitMode` observation remains with the
> `data/repository/` session.

**Base review, 2026-08-16.** Branch `fix/security-handoff`. Scope: the 76 files under
`code/shared/src/commonMain/kotlin/app/splitevenly/data/db/**` (30 entities, 31 DAOs, 12 projections,
the database class) and `data/upload/**` (3 files). `supabase/schema.sql` and the **live** Supabase
project `wfpfgbipjmkysalfmyub` were used as reference. No file under `code/` was edited. Every query run
against the live project was read-only (`information_schema.columns`, `pg_indexes`, `pg_proc`, and four
`count(*)`s); nothing was created, altered, dropped, truncated or deleted.

## Summary

The correspondence sweep is **complete: all 30 Room entities were checked, and 21 of the 22 synced /
pull-only ones agree with the live server column-for-column, type-for-type, null-for-null.** That is a
better result than the size of the surface suggests, and it is worth saying plainly before the findings:
`expenses` (35 columns), `pending_item_edits` (20) and `group_passes` (15) — the three most recently
churned tables — are exact. The one entity that does not correspond is `users`, and it fails three
different ways at once. Beyond correspondence, the DAO layer holds up well: every table name in every raw
`@Query` resolves to a declared entity, every multi-statement DAO method carries `@Transaction`, every
soft-deletable table's read queries filter `deleted_at`, there is no hard `DELETE` on user data outside
the two documented device-local ones plus the documented-and-tracked `UserDao.kt:29`, and the
`commonMain` sweep for JVM-only APIs came back empty. Twelve findings follow: one P0, two P1, five P2,
five P3.

**The one thing to fix first is B1**, and it is not a code change. `supabase/schema.sql` — the file
`supabase/AGENTS.md` calls canonical and the file this whole review measures against — does not describe
the live `public.users` table and does not contain the account-deletion RPCs the shipped app calls. It
still ships the `delete_my_account()` that those RPCs *replaced*, and that function no longer exists on
the server. Anyone applying the canonical file from scratch gets a database where the Play-Store-required
account deletion 404s at the first tap, and every future correspondence check on `users` — including this
one, if I had trusted the file instead of querying the server — silently measures against the wrong
shape. B2 and B3 are the client-side halves of the same `users` drift and should ship with it.

---

## 1. The correspondence sweep

Server side is the **live** database, read from `information_schema.columns` and `pg_indexes` on
2026-08-16, not from `schema.sql` (see B1 for why that distinction earned its keep). "Cols E/S" is
entity columns vs server columns.

### Synced tables (pushed through `SyncEngine.push` / `merge_expense`)

| # | Entity | Table | Cols E/S | Types | Nulls | PK | Unique indexes | Verdict |
|---|---|---|---|---|---|---|---|---|
| 1 | `UserEntity` | `users` | **20 / 22** | ✓ | ✓ | ✓ `id` | **✗ Room-only unique on `email`** | **✗ B1, B2, B3** |
| 2 | `GroupEntity` | `groups` | 15 / 15 | ✓ | ✓ | ✓ `id` | ✓ `invite_token` both sides | ✓ |
| 3 | `MemberEntity` | `members` | 13 / 13 | ✓ | ✓ | ✓ `id` | ✓ `(group_id, user_id)` both sides | ✓ |
| 4 | `PlaceholderClaimAnswerEntity` | `placeholder_claim_answers` | 8 / 8 | ✓ | ✓ | ✓ `id` | ✓ `(group_id, placeholder_user_id, answered_by_user_id)` both sides | ✓ |
| 5 | `ExpenseEntity` | `expenses` | 35 / 35 | ✓ | ✓ | ✓ `id` | none either side | ✓ |
| 6 | `ShareEntity` | `shares` | 11 / 12 | ✓ | ✓ | ✓ `id` | server partial-unique over active rows; Room non-unique (documented, Room cannot express partial) | ✓ (note A) |
| 7 | `SettlementEntity` | `settlements` | 16 / 16 | ✓ | ✓ | ✓ `id` | none either side | ✓ |
| 8 | `SettlementAllocationEntity` | `settlement_allocations` | 10 / 10 | ✓ | ✓ | ✓ `id` | ✓ `(settlement_id, share_id)` both sides | ✓ |
| 9 | `ConflictEntity` | `conflicts` | 8 / 8 | ✓ | ✓ | ✓ `id` | ✓ `(expense_id, added_user_id)` both sides | ✓ |
| 10 | `ExpenseEditConflictEntity` | `expense_edit_conflicts` | 13 / 13 | ✓ | ✓ | ✓ `id` | none either side | ✓ (dead table, #23) |
| 11 | `CommentEntity` | `comments` | 9 / 9 | ✓ | ✓ | ✓ `id` | none either side | ✓ |
| 12 | `ExpenseBlockedUserEntity` | `expense_blocked_users` | 10 / 10 | ✓ | ✓ | ✓ `id` | none either side | ✓ |
| 13 | `ReceiptEntity` | `receipts` | 12 / 12 | ✓ | ✓ | ✓ `id` | none either side | ✓ |
| 14 | `CategoryEntity` | `categories` | 12 / 12 | ✓ | ✓ | ✓ `id` | server partial-unique `(group_id, key)` active; Room non-unique | ✓ (note A) |
| 15 | `HistoryEventEntity` | `expense_history` | 8 / 8 | ✓ | ✓ | ✓ `id` | none either side | ✓ |
| 16 | `ExpenseItemEntity` | `expense_items` | 12 / 12 | ✓ | ✓ | ✓ `id` | none either side | ✓ |
| 17 | `ItemClaimEntity` | `item_claims` | 10 / 10 | ✓ | ✓ | ✓ `id` | server partial-unique `(item_id, user_id)` active; Room non-unique | ✓ (note A) |
| 18 | `ItemShareEntity` | `item_shares` | 12 / 12 | ✓ | ✓ | ✓ `id` | server partial-unique `(item_id, user_id, portion_id)` active; Room non-unique `(item_id, user_id)` | ✓ (note A; see **B6**) |
| 19 | `BillParticipantEntity` | `bill_participants` | 9 / 9 | ✓ | ✓ | ✓ `id` | server partial-unique `(expense_id, user_id)` active; Room has **no** `(expense_id, user_id)` index at all | ✓ (note B) |
| 20 | `PendingItemEditEntity` | `pending_item_edits` | 20 / 20 | ✓ | ✓ | ✓ `id` | none either side | ✓ |

### Pull-only tables (server-written entitlements; no `allForSync`, absent from `push`)

| # | Entity | Table | Cols E/S | Types | Nulls | PK | Unique indexes | Verdict |
|---|---|---|---|---|---|---|---|---|
| 21 | `GroupPassEntity` | `group_passes` | 15 / 15 | ✓ | ✓ | ✓ `id` | server `(store, store_txn_id)`; Room none — correct, the client never inserts | ✓ |
| 22 | `UserSubscriptionEntity` | `user_subscriptions` | 11 / 11 | ✓ | ✓ | ✓ `user_id` | server `expires_at` idx; Room none — read path is a join on `members` | ✓ |

### Device-local tables (no server counterpart by design; not in `SYNCED_TABLES`, not `@Serializable`)

| # | Entity | Table | Server table? | In `SYNCED_TABLES`? | `@Serializable`? | In `WIPED_TABLES`? | Verdict |
|---|---|---|---|---|---|---|---|
| 23 | `ExpenseSyncStateEntity` | `expense_sync_state` | no | no | no | yes | ✓ |
| 24 | `RowSyncStateEntity` | `row_sync_state` | no | no | no | yes | ✓ (see **B12**) |
| 25 | `SupersededNoticeEntity` | `superseded_notices` | no | no | no | yes | ✓ |
| 26 | `ReceiptUploadEntity` | `receipt_uploads` | no | no | no | yes | ✓ |
| 27 | `GroupScanUsageEntity` | `group_scan_usage` | no | no | no | yes | ✓ |
| 28 | `FxRateEntity` | `fx_rates` | **no** (KDoc claims otherwise) | no | no | no (deliberate) | ✓ (see **B11**) |
| 29 | `FxBakedEntity` | `fx_baked` | no | no | no | yes | ✓ |
| 30 | `FxCurrencyEntity` | `fx_currencies` | no | no | no | no (deliberate) | ✓ |

**Note A — partial unique indexes.** Room cannot express `WHERE deleted_at IS NULL`, so on four tables the
server has a partial unique index the local DB does not. This is a *deliberate, documented* asymmetry, not
a finding: every affected DAO's KDoc states it, and finding #5's deterministic-id convention is what stops
two devices minting colliding rows. I checked it rather than assuming it, and the direction is the safe
one — the local DB is permissive, the server is strict, so a violation is caught on push rather than
corrupting the cache. The unsafe direction (local strict, server permissive) occurs exactly once, on
`users.email`: that is **B3**.

**Note B — `bill_participants`.** The server carries `bill_participants_expense_user_active_uidx`; the Room
entity declares only `expense_id` and `group_id` indices, with no composite. Purely a lookup-speed gap
(`getByExpense` + `getActive` both filter on `expense_id`), no correctness consequence, and consistent with
note A's direction. Recorded for completeness, not raised as a finding.

**Server-only column, by design.** `shares.remaining_subunits` (nullable, `default 0`) exists on the server
and not on the entity. That is correct and documented — `remaining` is derived on read, the column is
vestigial, and `_share_defaults()` (`schema.sql:844`) seeds it to `0` so the omission does not become an
explicit NULL on the `merge_expense` create path. The Postgrest `Json` is configured with
`ignoreUnknownKeys = true` (`SupabaseClientFactory.kt:30`), so the column arriving on a pull is discarded
rather than throwing. Verified, clean.

---

## 2. Findings

### B1. `supabase/schema.sql` does not describe the live `public.users` table or the account-deletion RPCs the shipped app calls, and still ships the function they replaced.

- **Severity:** P0 (schema disagreement, and the file every other correspondence check is measured against)
- **Verdict:** CONFIRMED
- **Where:** `supabase/schema.sql:815-832` (`delete_my_account()`), `supabase/schema.sql:7-31` (the `users`
  create block); client callers at
  [SupabaseAuthSession.kt:346](code/shared/src/commonMain/kotlin/app/splitevenly/data/auth/SupabaseAuthSession.kt:346)
  and [:368](code/shared/src/commonMain/kotlin/app/splitevenly/data/auth/SupabaseAuthSession.kt:368)
- **Check that failed:** §3 correspondence sweep — "every column present on both sides", run against the
  live server rather than the file
- **Concrete trigger:** Three separate divergences, all verified live:
  1. Live `public.users` carries `deletion_requested_at bigint` and `deleted_at bigint`.
     `grep -c deletion_requested_at supabase/schema.sql` → **0**. Neither column appears anywhere in the
     file, and neither is added by any `alter table … add column if not exists` (the file has exactly 13
     such statements: 12 on `receipt_scan_log`, one on `pending_item_edits`).
  2. `select proname from pg_proc … where proname in (…)` returns `request_account_deletion`,
     `cancel_account_deletion`, `purge_deleted_accounts` and **not** `delete_my_account`. `schema.sql`
     contains the exact inverse: `delete_my_account()` at line 815, granted to `authenticated` at line 832,
     and none of the other three.
  3. `supabase/AGENTS.md` states the three RPCs "replace the old hard-delete `delete_my_account()`" and
     that `purge_deleted_accounts` is "scheduled in `supabase/schema.sql`". The file contains no
     `cron.schedule` call at all.
- **Blast radius:** Whole-project, two ways. (a) A from-scratch apply of the canonical file produces a
  database on which `AuthSession.requestAccountDeletion()` fails at the first RPC call — the Play Store
  "Delete account URL" requirement, live in the shipped app, dead in the reproducible schema; the 30-day
  purge job never exists, so grace periods never elapse. (b) Every correspondence check on `users`,
  including the pre-prod audit `data/AGENTS.md` schedules, silently measures the wrong shape — the file
  says 20 columns, the server has 22.
- **Why it survives refutation:** I deliberately did not settle this from the file. The column list came
  from `information_schema.columns` on `wfpfgbipjmkysalfmyub`, the function list from `pg_proc`, both
  read-only, both on 2026-08-16. The direction of the drift is also self-consistent: the client calls the
  three new RPCs and never calls `delete_my_account`, so the *server* is right and the *file* is stale —
  which is the reverse of what "canonical" is supposed to mean. `git log -p` on `schema.sql` shows the last
  five commits touching it are all `merge_expense`/clamp work; the account-deletion migration (documented
  as done 2026-08-08) was applied to the project and never folded back into the file.
- **Suggested fix:** File catch-up, not a database change — the server already has all of it, so nothing
  here needs the §4.4 server-first ordering. Append to `schema.sql`: `alter table public.users add column
  if not exists deletion_requested_at bigint;` and the same for `deleted_at`; the three `security definer`
  RPCs with their `revoke`/`grant` lines; the `purge-deleted-accounts` `cron.schedule` call; and
  `drop function if exists public.delete_my_account();` with the `-- DESTRUCTIVE OPERATION` header Rule 8
  requires (it destroys no rows, but it removes a callable grant). Dump the live definitions with
  `pg_get_functiondef` rather than retyping them, so the file matches byte-for-byte.

---

### B2. `UserEntity` has no `deleted_at`, so the Room mirror of `users` cannot represent an account the server has anonymized.

- **Severity:** P1
- **Verdict:** CONFIRMED
- **Where:** [UserEntity.kt:24-101](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/entity/UserEntity.kt:24)
  (20 columns); live `public.users` has 22
- **Check that failed:** §3 correspondence sweep — column parity
- **Concrete trigger:** `purge_deleted_accounts()` stamps `users.deleted_at` and clears the profile fields
  30 days after a deletion request. That row still pulls into Room, and no `UserDao` query has any
  `deleted_at` predicate to filter it with — `getById`, `findByEmail`, `observePlaceholdersInGroup` and
  `countPlaceholderName` all read it as a live user. The rosters are covered by accident rather than by
  design: `purge_deleted_accounts` also soft-leaves the account's `members` rows, and every roster query
  (`MemberDao.observeActiveMembersWithUser`, `PlaceholderClaimAnswerDao.observeUnansweredNames`,
  `UserDao.observePlaceholdersInGroup`) joins `members` and filters `status = 'ACTIVE'`. The two paths that
  do **not** go through `members` — `UserDao.findByEmail` (resolves a sign-in email to a user) and
  `getById` — are uncovered.
- **Blast radius:** `users` reads, not sync. Explicitly **not** a push clobber: `SyncEngine.upsertAll` is
  `client.from(table).upsert(rows)` ([SyncEngine.kt:797](code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/supabase/SyncEngine.kt:797)),
  a PostgREST `merge-duplicates` insert whose `ON CONFLICT … DO UPDATE SET` list is built from the payload
  keys only, so a column absent from the entity is never written and both server columns survive a client
  push untouched. (That is PostgREST semantics, reasoned rather than observed — I did not run a push.)
- **Why it survives refutation:** The obvious refutation is "the entity is deliberately trimmed, like
  `remaining_subunits`". It does not hold: `remaining_subunits` is documented as vestigial in three places
  and is seeded by `_share_defaults()`; `deleted_at` on `users` has no such note anywhere, and
  `data/AGENTS.md`'s own pre-prod gate item 4 lists it as *done* ("`shares` and `users` now do — done"),
  which reads as a claim that the soft-delete round trip is complete. It is complete server-side and absent
  client-side. `deletion_requested_at` is read by a hand-rolled DTO
  ([SupabaseAuthSession.kt:501](code/shared/src/commonMain/kotlin/app/splitevenly/data/auth/SupabaseAuthSession.kt:501))
  with an explicit column `select`, which is a reasonable choice for a live gate; `deleted_at` has no
  reader at all.
- **Suggested fix:** Add `@ColumnInfo(name = "deleted_at") val deletedAt: Long? = null` to `UserEntity`.
  No server migration needed — the column is already there, so §4.4's ordering is satisfied. Then decide
  the read policy explicitly (most likely: filter it in `findByEmail` and render "Deleted user" elsewhere,
  matching Rule 9's promise that others' financial history is untouched). Adding `deletion_requested_at`
  too is optional; if it stays off the entity, say so in the KDoc so the next sweep does not re-find it.

---

### B3. Room declares a UNIQUE index on `users.email` that the server does not have, so the local cache is stricter than the source of truth.

- **Severity:** P1 (silent corruption / partial pull failure, latent)
- **Verdict:** CONFIRMED
- **Where:** [UserEntity.kt:19](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/entity/UserEntity.kt:19)
  (`Index(value = ["email"], unique = true)`) and
  [UserEntity.kt:13](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/entity/UserEntity.kt:13)
  (the KDoc that asserts the server enforces it); server side: `schema.sql:11` (`email text`, no
  constraint) and live `pg_indexes`
- **Check that failed:** §3 — "unique indexes present on both sides"
- **Concrete trigger:** Two `public.users` rows sharing an email, pulled onto one device. The server
  accepts them: there is no unique constraint, no `citext` column type, and no functional index. Reachable
  paths include two auth identities for the same address (email OTP plus a Google sign-in, when identity
  linking is off), an address reused after an account was anonymized (B2's `deleted_at` rows keep their
  `email` unless the purge clears it), or a `users` row written by the `web-claim` service key. The second
  row's `@Upsert` then hits a constraint Room cannot resolve: `@Upsert` resolves conflicts by **primary
  key**, and these two rows have different primary keys. The row is either dropped silently (Room's
  `EntityUpsertAdapter` falls back to an `UPDATE … WHERE id = ?` that matches nothing) or the exception
  escapes and aborts the whole `users` upsert — and with it the pull, reported to the user as "offline"
  until finding #25's `AppError.Backend` mapping tells them otherwise. I did not run the app, so I am not
  claiming which of the two; both are defects and the fix is the same.
- **Blast radius:** One table's pull at worst, one silently-missing person at best. A missing `users` row
  means every share, settlement and roster entry pointing at that person renders with a null display name.
- **Why it survives refutation:** The refutation I tried was "the server enforces it and `schema.sql` just
  doesn't show it" — the KDoc says exactly that, citing `citext`. It is false on the live database:
  `select … from pg_indexes where tablename='users'` returns **one row**, `users_pkey`, and
  `information_schema.columns` reports `email` as plain `text`, nullable, no default. The second refutation
  is "placeholders all have null emails so nothing collides" — true today (32 rows with `email is null`,
  and SQLite permits unlimited NULLs in a UNIQUE index), and I confirmed **zero** duplicate lowercased
  emails live, which is why this is ranked as latent rather than firing. It is a constraint that exists in
  exactly one of the two places that must agree, which is the shape §3 asks for.
- **Suggested fix:** Pick a side and make both match. Preferred, and it needs a **server migration first**
  per §4.4: `create unique index if not exists users_email_lower_uidx on public.users (lower(email)) where
  email is not null;` — which is what the KDoc already believes exists, and is the right invariant for a
  sign-in identifier. Back out any duplicates before applying it (there are none today). The cheaper
  alternative is dropping `unique = true` from the Room index and correcting the KDoc; that resolves the
  disagreement but gives up a real constraint, so state it as a decision rather than a cleanup.

---

### B4. Seven DAO methods have zero callers, and one of them is still described by `data/AGENTS.md` as the live expense-adoption path. (Extends #28.)

- **Severity:** P2
- **Verdict:** CONFIRMED
- **Where:**
  | Method | Declared at | Note |
  |---|---|---|
  | `ExpenseDao.overwriteFromServer` | [ExpenseDao.kt:100](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/dao/ExpenseDao.kt:100) | superseded by `overwriteFromServerIfUnchanged` (:117); **still cited as live** by `data/AGENTS.md:139` and `:168` |
  | `SettlementDao.allocationsForShare` | [SettlementDao.kt:111](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/dao/SettlementDao.kt:111) | |
  | `ExpenseSyncStateDao.clear` | [ExpenseSyncStateDao.kt:22](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/dao/ExpenseSyncStateDao.kt:22) | `data/AGENTS.md:90` cites `ExpenseSyncStateDao.kt:21` as one of the two "legitimate hard deletes in the codebase"; it is never called |
  | `ExpenseEditConflictDao.observeUnresolvedCount` | [ExpenseEditConflictDao.kt:38](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/dao/ExpenseEditConflictDao.kt:38) | the retired bilateral-conflict badge (#23) |
  | `ExpenseBlockedUserDao.observeActive` | [ExpenseBlockedUserDao.kt:24](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/dao/ExpenseBlockedUserDao.kt:24) | KDoc says it "drives the filter and the manage sheet"; nothing calls it |
  | `MemberDao.observeActiveMembers` | [MemberDao.kt:34](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/dao/MemberDao.kt:34) | superseded by `observeActiveMembersWithUser` |
  | `PendingItemEditDao.observeLiveByGroup` | [PendingItemEditDao.kt:39](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/dao/PendingItemEditDao.kt:39) | |
- **Check that failed:** §3 DAOs — "dead methods with zero callers (#28 has one; look for more)"
- **Concrete trigger:** `ExpenseBlockedUserDao.observeActive` is the sharp one. Its KDoc names two UI
  surfaces it drives, and neither exists as a caller, so the per-expense comment filter promised by
  `CHAT_MODERATION_SPEC.md` reads as wired when the query feeding it is orphaned. Someone auditing chat
  moderation from the DAO layer will conclude the block filter is live.
- **Blast radius:** No runtime effect — dead code costs nothing at execution. The cost is entirely in the
  wrong picture it gives the next reader, which is the same cost #28 was raised for.
- **Why it survives refutation:** Method-by-method `grep -rn "\b<name>\b" code/shared/src code/androidApp/src
  code/iosApp`, excluding `/build/`, across every source set including `commonTest` and `iosTest`. Each of
  the seven returns exactly its own declaration and (for `overwriteFromServer` and
  `ExpenseSyncStateDao.clear`) prose references in `data/AGENTS.md`. The earlier mechanical pass that
  produced these candidates also correctly cleared every DAO-internal helper that only its own
  `@Transaction` default method calls (`mergeShares`, `tombstone*`, `reassign*`, `clear*`, …), so the seven
  are not an artifact of scoping. #28's own subject, `ExpenseDao.updateStatus`, is genuinely gone from the
  tree — that half is fixed.
- **Suggested fix:** Delete all seven. Then fix the two doc references in the same commit:
  `data/AGENTS.md:139` and `:168` should name `overwriteFromServerIfUnchanged`, and `:90` should stop
  citing `ExpenseSyncStateDao.kt:21` as a live hard delete. If `ExpenseBlockedUserDao.observeActive` is
  meant to be wired rather than deleted, that is a different change and belongs to the UI session — say
  which, don't leave it ambiguous.

---

### B5. `data/AGENTS.md`'s claim that the device-local entities are `@Serializable` is exactly inverted — none of them is. (Extends #28.)

- **Severity:** P2
- **Verdict:** CONFIRMED
- **Where:** [data/AGENTS.md:88-89](code/shared/src/commonMain/kotlin/app/splitevenly/data/AGENTS.md:88):
  "all three *are* `@Serializable` today, as are `row_sync_state` and `superseded_notices`, so do not reach
  for the annotation as evidence that a table is local (this file used to claim they were not, and it was
  wrong)."
- **Check that failed:** §3 converters/serialization — "`@Serializable` entity field names match the
  Postgres column names exactly", which starts with knowing which entities are `@Serializable`
- **Concrete trigger:** A mechanical check of all 30 entity files for `^import kotlinx.serialization.Serializable`
  and `^@Serializable` gives a perfectly clean partition: **22 of 22** synced and pull-only entities have
  both; **8 of 8** device-local entities (`expense_sync_state`, `row_sync_state`, `superseded_notices`,
  `receipt_uploads`, `group_scan_usage`, `fx_rates`, `fx_baked`, `fx_currencies`) have neither. The
  annotation is in fact a perfect discriminator, and the briefing tells the reader it is not.
- **Blast radius:** No runtime effect; a trap for the next writer. The concrete harm is the instruction it
  gives: an agent adding a device-local table reads "the existing local tables all carry `@Serializable`"
  and adds it for consistency, at which point the annotation stops discriminating and the doc becomes
  retroactively true in the worst possible way. The paragraph also asserts that absence from
  `SYNCED_TABLES` is "the whole protection", which discourages the belt-and-braces the codebase actually
  practises.
- **Why it survives refutation:** My first pass at this used `grep -c "@Serializable"` and returned 1 for
  each local entity, which appeared to support the doc. That count was matching the phrase *inside each
  KDoc* — `RowSyncStateEntity.kt:8` literally reads "Device-local (NOT synced, NOT @Serializable)". Anchored
  greps on the import line and on a line-initial annotation give 0/0 for all eight. The entity KDocs and the
  code agree with each other; only the briefing disagrees with both. The parenthetical "this file used to
  claim they were not, and it was wrong" says a previous session corrected this in the wrong direction,
  which is why it needs an explicit, evidenced reversal rather than a quiet edit.
- **Suggested fix:** Replace the sentence with the checked statement: no device-local entity is
  `@Serializable`, the annotation currently tracks "is a wire mirror" exactly, and the protection is both
  facts together plus the absence of `allForSync`. `SyncedTablesTest` already pins `WIPED_TABLES` against
  `SYNCED_TABLES`; a three-line test asserting the `@Serializable` partition would make this one machine-
  checked too, which is the only way a claim like this stays true.

---

### B6. `ItemShareDao.getActiveShare` still encodes the retired `(item_id, user_id)` "at most one" key, so it returns an arbitrary one of a person's portions on a line.

- **Severity:** P2 (bounded — its only live caller is guarded; see refutation)
- **Verdict:** CONFIRMED
- **Where:** [ItemShareDao.kt:32-33](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/dao/ItemShareDao.kt:32)
  — `SELECT * FROM item_shares WHERE item_id = :itemId AND user_id = :userId AND deleted_at IS NULL LIMIT 1`
- **Check that failed:** the brief's §2 instruction — "**code or schema** still assuming the old key is a
  new finding"
- **Concrete trigger:** The live uniqueness key is `(item_id, user_id, portion_id)` over active rows
  (`item_shares_item_user_portion_active_uidx`, verified live), and `ItemShareEntity`'s own KDoc states a
  person "CAN be in more than one portion of the same line at once". This query has no `portion_id`
  predicate and no `ORDER BY`, so on a line where someone holds two portions — solo on one serving, shared
  on another, which is precisely what per-serving assignment exists to express — it returns whichever row
  SQLite reaches first. `BillRepositoryImpl.setShareMember` at
  [:456](code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/BillRepositoryImpl.kt:456) uses
  it two ways, and both are wrong under the new key: leaving a line
  (`existing?.let { softDeleteByIds(listOf(it.id)) }`) tombstones one slice and leaves the person paying
  for the others, and joining a line (`existing == null -> upsert(…)`) treats "already in some portion" as
  "already in this one" and silently no-ops.
- **Blast radius:** Today, none that a user can reach — one line item's share allocation if it were called.
- **Why it survives refutation:** The refutation that nearly killed this: `setShareMember` has **zero**
  callers (`grep -rn setShareMember` returns only its `BillRepositoryImpl` implementation and its
  `BillRepository` interface declaration), and the other caller,
  [`setClaim`:438](code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/BillRepositoryImpl.kt:438),
  is gated behind `isSingleUnit(expenseId, itemId)`, where a second portion is not constructible. So no
  live path reaches the ambiguity. What survives is that the *query itself* cannot express the current key,
  it sits on a DAO with no warning attached, and the code around it reads like a working feature — the next
  person to wire a "leave this shared line" control will call it and get a half-removal, which is money.
  That is the same class of latent trap as #29, and the reason the brief asked for it by name.
- **Suggested fix:** Either give it the third key component — `getActiveShare(itemId, userId, portionId)`,
  and a separate `getActiveShares(itemId, userId): List<…>` for the "all my slices on this line" question —
  or delete it alongside `setShareMember` (B4's neighbours) if per-serving assignment has fully replaced
  that flow. No server change either way. If it is kept as-is for now, the `LIMIT 1` needs a comment saying
  it predates the portion key, so it fails loudly to the next reader instead of quietly.

---

### B7. A permanently failing receipt upload retries forever: `claimPending` re-arms every FAILED row with no cap and no backoff, and `retry_count` / `last_error` are written but never read.

- **Severity:** P2
- **Verdict:** CONFIRMED
- **Where:** [ReceiptUploadManager.kt:236-253](code/shared/src/commonMain/kotlin/app/splitevenly/data/upload/ReceiptUploadManager.kt:236)
  (`claimPending`), [:292-294](code/shared/src/commonMain/kotlin/app/splitevenly/data/upload/ReceiptUploadManager.kt:292)
  (`reportFailure`), [ReceiptUploadDao.kt:35](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/dao/ReceiptUploadDao.kt:35)
  (`markFailed`), [ReceiptUploadEntity.kt:61-65](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/entity/ReceiptUploadEntity.kt:61)
- **Check that failed:** §3 `data/upload/` — "the status state machine has no unreachable or
  terminal-but-not-final state; a failed upload is distinguishable from a pending one"
- **Concrete trigger:** A receipt whose upload fails for a non-transient reason — a Storage object policy
  rejection, an oversized file, a 403 from an expired-then-rotated key. `reportFailure` sets `FAILED` and
  increments `retry_count`. `claimPending` then selects `uploadDao.all().filter { status != UPLOADING }`,
  which includes every `FAILED` row unconditionally, marks it `UPLOADING`, and hands it back to the
  transport. `uploadAll` returns `false` (a `FAILED` row is present), so WorkManager schedules another
  attempt; `bind`'s connectivity collector schedules another on every reconnect; `retry()` schedules
  another on every user tap. Nothing anywhere reads `retryCount`, so the counter that exists to stop this
  never stops it. `FAILED` is therefore not a terminal state — it is `PENDING` with a different label.
- **Blast radius:** One device's battery, data allowance and Storage request quota, indefinitely, for one
  bad file. Not data loss: the object key is deterministic and the PUT carries `x-upsert: true`, so a
  retry that does succeed is idempotent.
- **Why it survives refutation:** The refutation worth taking seriously is "whole-file retry is deliberate
  and stated" — the class KDoc says "failures stay FAILED and retry whole", which is about *how* a retry
  works, not *whether it ever stops*. The second is "`markFailed` bumping `retry_count` implies a cap
  exists somewhere": `grep -rn "retryCount\|retry_count"` over `code/shared/src` returns exactly three
  hits — the entity property, its `@ColumnInfo`, and the `markFailed` query. No reader. `last_error` is the
  same: written by `markFailed`, and `toDomain()`
  ([:296-305](code/shared/src/commonMain/kotlin/app/splitevenly/data/upload/ReceiptUploadManager.kt:296))
  does not map it onto `ReceiptUpload`, so the KDoc's "record the error for the UI" describes a UI that
  cannot see it.
- **Suggested fix:** Filter `claimPending` on `retry_count < MAX_RETRIES` (and, better, on an
  `updated_at + backoff(retry_count) <= now` window, since the column is already there), leaving a genuinely
  terminal `FAILED` the user can retry by hand or cancel. Surface `lastError` through `ReceiptUpload` so the
  failed thumbnail can say why. Device-local table, no server change, no migration beyond the Room version
  bump the existing destructive-migration policy already absorbs.

---

### B8. `ReceiptUploadManager.reportSuccess` performs four writes across three DAOs with no transaction, so a crash mid-publish duplicates the receipt's history event on the next drain.

- **Severity:** P2
- **Verdict:** CONFIRMED
- **Where:** [ReceiptUploadManager.kt:259-290](code/shared/src/commonMain/kotlin/app/splitevenly/data/upload/ReceiptUploadManager.kt:259)
- **Check that failed:** §3 DAOs — "`@Transaction` present on any DAO method performing multiple
  statements"; and §3 `data/upload/` — "a killed process resumes rather than duplicating"
- **Concrete trigger:** `reportSuccess` runs, in order: `receiptDao.upsert(ReceiptEntity(id = row.id, …))`,
  `historyDao.upsert(HistoryEventEntity(id = newId(), …))`, `uploadDao.delete(id)`,
  `fileStore.delete(row.localPath)`. Kill the process between the second and third write — an OOM kill
  during a WorkManager drain, or an iOS background-session completion the system terminates. The outbox row
  survives. On next launch `bind` resets `UPLOADING → PENDING`
  ([:100](code/shared/src/commonMain/kotlin/app/splitevenly/data/upload/ReceiptUploadManager.kt:100)) and
  the file re-uploads to the same object key. `reportSuccess` runs again: `receiptDao.upsert` is idempotent
  (same deterministic `row.id`), but `historyDao.upsert` mints a **fresh** `newId()`, so a second
  `RECEIPT_ADDED` row is inserted and synced. The expense's activity feed shows the receipt added twice, on
  every member's device, permanently — `expense_history` is append-only with no `deleted_at`.
- **Blast radius:** One duplicated activity-feed row per interrupted upload. No money, no receipt loss.
- **Why it survives refutation:** The refutation is "these are three different DAOs, Room's `@Transaction`
  can only span one" — true, and it is why this is a design gap rather than a missed annotation; the fix is
  `db.withTransaction { }` or a single DAO owning the three writes, which is what `PlaceholderMergeDao` did
  for exactly this reason ("a Room `@Transaction` default method can only call queries on its own DAO",
  `PlaceholderMergeDao.kt:20`). The second refutation is "the window is tiny": it is, but it is the window
  the whole `data/upload/` design exists to survive — the class KDoc's contract is "a crash/network-drop
  never loses or double-publishes a file", and this is the one place it double-publishes something.
- **Suggested fix:** Wrap the three Room writes in one transaction (a `SignOutWipeDao`-style DAO that owns
  all three, or `EvenlyDatabase.withTransaction`), and keep the file delete outside it — a leaked sandbox
  file is recoverable, a rolled-back publish is not. Alternatively make the history id deterministic
  (`"${row.id}__receipt_added"`) so the re-run upserts onto the same row; that is the smaller change and
  fixes only this instance.

---

### B9. `ExpenseDao.softDeleteLocalSharesNotIn` is the only soft-delete query in the DAO layer that does not bump `row_version`.

- **Severity:** P3 (cleanup / convention drift)
- **Verdict:** CONFIRMED
- **Where:** [ExpenseDao.kt:106-107](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/dao/ExpenseDao.kt:106)
  — `UPDATE shares SET deleted_at = :now, updated_at = :now WHERE …`
- **Check that failed:** §3 DAOs — raw `@Query` correctness, read as consistency with the layer's own
  convention
- **Concrete trigger:** Every other soft-delete in the tree — `ShareDao.softDeleteByIds`,
  `ExpenseDao.softDeleteSharesByIds` twelve lines above it, `ItemClaimDao`, `ItemShareDao`,
  `BillParticipantDao`, `ExpenseItemDao`, `CategoryDao`, `CommentDao`, `ReceiptDao`, `SettlementDao`,
  `PlaceholderMergeDao`'s five tombstone queries — writes `row_version = row_version + 1`. This one does
  not. It runs on the `merge_expense` adoption path (`overwriteFromServerIfUnchanged`, and the dead
  `overwriteFromServer`), so the rows it touches are the shares the server just rejected.
- **Blast radius:** None that I can construct today. Dirty detection for `shares` is not `row_version`-based
  (they ride `merge_expense` with the parent expense, and `row_sync_state` fingerprints by `hashCode()`,
  which `deleted_at` and `updated_at` both change), and `keepNewer` compares `updated_at`, which is set. So
  the omission is currently inert.
- **Why it survives refutation:** I could not construct a failing case, and I am saying so rather than
  inventing one. What survives is that `data/AGENTS.md`'s pre-prod gate item 5 asks for exactly a
  version-based push guard ("`on conflict … where excluded.updated_at > …`" or a trigger), and a row whose
  `row_version` silently stands still while its content changes is the one row such a guard would get
  wrong. Fixing it costs six characters now and is archaeology later.
- **Suggested fix:** Add `row_version = row_version + 1` to the `SET` list, matching its eleven siblings.

---

### B10. `CategoryDao.softDelete` has no `deleted_at IS NULL` guard, so it re-stamps tombstones and re-pushes already-dead rows.

- **Severity:** P3
- **Verdict:** CONFIRMED
- **Where:** [CategoryDao.kt:36-37](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/dao/CategoryDao.kt:36)
  — `UPDATE categories SET deleted_at = :now, updated_at = :now, row_version = row_version + 1 WHERE
  group_id = :groupId AND key = :key`
- **Check that failed:** §3 DAOs — "queries with a `WHERE` clause that omits the soft-delete filter"
  (here the omission is on a *write*, not a read)
- **Concrete trigger:** `categories`' uniqueness is `categories_group_key_active_idx`, unique on
  `(group_id, key)` **only where `deleted_at is null`** — verified live. So a group that deletes a custom
  category and later creates another with the same `key` legitimately holds two rows: one tombstone, one
  live. Deleting the new one matches both, moves the old tombstone's `updated_at` to now and bumps its
  `row_version`, which changes its hash and makes `row_sync_state` re-push a row nothing changed. Every
  other member's device then pulls a "new" version of a category that died months ago.
- **Blast radius:** One redundant row per repeat-deleted key, per delete. Sync churn, not corruption — the
  row was already a tombstone and stays one.
- **Why it survives refutation:** The refutation is "deterministic ids mean there is only ever one row per
  key". True for *default*-derived categories (`CategoryRepositoryImpl.materialize` seeds
  `"<groupId>__<key>"`, so a re-materialization upserts back onto the tombstone) and false for custom ones,
  whose ids are uuids — the `key` is chosen by the user and can be reused. The partial index is what makes
  reuse legal server-side, so this is the schema working as designed and the query not keeping up with it.
- **Suggested fix:** Add `AND deleted_at IS NULL` to the `WHERE`. One line, no server change.

---

### B11. `FxRateEntity`'s KDoc calls it a "local mirror of `fx_rates`" — there is no such server table — and `data/AGENTS.md`'s device-local list omits all three FX tables. (Extends #28.)

- **Severity:** P3
- **Verdict:** CONFIRMED
- **Where:** [FxRateEntity.kt:8](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/entity/FxRateEntity.kt:8)
  ("Local mirror of `fx_rates` (02 §3.14)"); `data/AGENTS.md:85-87`
- **Check that failed:** §3 correspondence sweep — a table claimed to correspond that has nothing to
  correspond to
- **Concrete trigger:** `grep -c "fx_rates" supabase/schema.sql` → 0, and `information_schema` on the live
  project has no `fx_rates`, `fx_baked` or `fx_currencies`. `fx_rates` is not in `SyncEngine.SYNCED_TABLES`
  and `FxRateEntity` is not `@Serializable`, so it is unambiguously device-local. Its two siblings say so
  in their own KDocs — `FxBakedEntity.kt:8` ("a Room-ONLY table with no server counterpart") and
  `FxCurrencyEntity.kt:8` ("Room-ONLY, local cache … No server counterpart") — which makes `FxRateEntity`
  the odd one out rather than a house style. `data/AGENTS.md`'s "Device-local tables stay out of sync"
  paragraph names `receipt_uploads`, `expense_sync_state`, `group_scan_usage`, `row_sync_state` and
  `superseded_notices`, and none of the three FX tables.
- **Blast radius:** None at runtime. It is the third instance of the same failure mode as #28 and B5: a
  reader auditing "which Room tables must match the server" is handed a list that is wrong in both
  directions — one entity claims a mirror it does not have, and the canonical list omits three tables that
  are genuinely local.
- **Why it survives refutation:** The refutation is "'mirror' might mean the spec table in doc 02 §3.14,
  not a deployed one". Even granting that, the same paragraph's phrasing is copied verbatim from the
  genuinely-synced entities ("Local mirror of `shares` (02 §3.8)", "Local mirror of `comments` (06 §3)"),
  so the sentence carries the synced-entity meaning by construction. `SignOutWipeDao` gets this right
  already — it wipes `fx_baked` and deliberately keeps `fx_rates`/`fx_currencies` as global reference data,
  with the reasoning written out (`SignOutWipeDao.kt:27-29`). The DAO layer knows; the entity KDoc and the
  briefing do not.
- **Suggested fix:** Reword `FxRateEntity`'s first line to match its two siblings ("Room-ONLY … no server
  counterpart"), and add the three FX tables to `data/AGENTS.md`'s device-local list — same commit as B5,
  since they are the same paragraph and the same class of error.

---

### B12. `RowSyncStateEntity.syncedHash` is a 32-bit `Int`, so an edit that lands on the same hash is silently never pushed.

- **Severity:** P3
- **Verdict:** PLAUSIBLE
- **Where:** [RowSyncStateEntity.kt:24](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/entity/RowSyncStateEntity.kt:24);
  written at [SyncEngine.kt:578](code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/supabase/SyncEngine.kt:578)
  as `it.hashCode()`
- **Check that failed:** §3 converters/serialization — round-trip fidelity of a value the sync layer treats
  as identity
- **Concrete trigger:** A row is dirty iff its current `hashCode()` differs from the stored one. A data
  class `hashCode()` is a 32-bit value over the whole row, so an edit that leaves the hash unchanged
  (e.g. two fields changing in compensating ways, or plain coincidence) makes the edited row read as
  already-pushed. It never syncs, and nothing ever notices — the row is not FAILED, not pending, not
  reported by `countPendingLocalWrites`.
- **Blast radius:** One row, permanently, silently. On a money table that is a wrong balance on one device
  and not the others.
- **Why it survives refutation, and why it is PLAUSIBLE rather than CONFIRMED:** I did not construct a
  colliding pair, and per-edit probability is roughly 2⁻³² — small enough that this may never be worth
  acting on, and I would not argue with a decision to close it. What stops it being refuted outright is
  that the exposure is per-edit across the whole user base, not per-row, and the failure is undetectable
  after the fact: there is no repair path, because the next edit re-bases from the same stale fingerprint.
  The entity's KDoc explains why a hash was chosen over `updated_at` (two synced tables, `conflicts` and
  `expense_edit_conflicts`, carry no `updated_at` yet are mutated locally) and that reasoning is sound; it
  simply does not address the width.
- **Suggested fix:** Cheapest honest option is to store the fingerprint as a `String` — the row's
  serialized JSON, or a wider digest of it — for the two tables that need hashing and use `updated_at` for
  the rest. Device-local table, no server change. Alternatively record the trade-off explicitly in the
  KDoc so it is a known accepted risk rather than an unexamined one.

---

## 3. Observed outside scope — handed to the `data/repository/` session

Not numbered, because the file is out of my scope; recorded because §3 assigned me the enum-persistence
check and this is what it turned up.

**`BillMaterializer`'s `tip_split_mode` fallback disagrees with the column default it is falling back
from.** [BillMaterializer.kt:103](code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/BillMaterializer.kt:103)
reads `TipSplitMode.entries.firstOrNull { it.name == tipSplitMode } ?: TipSplitMode.EVEN`, while
`ExpenseEntity.kt:76` and the Postgres column both default to `'PROPORTIONAL'`. An unparseable value
therefore splits a bill's tip evenly rather than proportionally — a money difference, not a display one.
Nothing can trigger it today: `TipSplitMode` has exactly two entries (`PROPORTIONAL`, `EVEN`), and both UI
entry points write `EVEN` explicitly. It becomes live the moment a third mode is added by a newer client,
which is exactly the scenario §3 asks about, and the safe fallback for a value you cannot parse is the one
the schema declares.

---

## 4. Checks that came back clean

Each of these was run, not assumed.

- **Column parity** — 21 of 22 synced/pull-only entities match the live server exactly (table in §1). Only
  `users` diverges (B1, B2).
- **Type agreement** — every `*_at`/`*_subunits`/`row_version`/`*_version` column is `bigint` ↔ `Long`;
  `share_units`, `quantity`, `sort_order` (on `expense_items`), `proposed_quantity`, `previous_quantity` are
  `integer` ↔ `Int`; `categories.sort_order` and `storage_bytes_used` are `bigint` ↔ `Long`;
  `share_percentage` and `fx_rate_used` are `double precision` ↔ `Double?`; all flags are `boolean` ↔
  `Boolean`; `expense_date` and `fx_rate_date` are `text` ISO dates ↔ `String`. No `bigint`/`integer`
  crossover anywhere.
- **Nullability agreement** — every server-nullable column maps to a Kotlin nullable, in both directions,
  across all 22 tables. Checked column by column against `information_schema.columns.is_nullable`; zero
  mismatches. This is the check most likely to produce a whole-table outage and it is fully clean.
- **Default agreement, and the #16 interaction** — `_expense_defaults()` (`schema.sql:956`) covers all
  **12** of `expenses`' `not null default` columns (`kind`, `has_tax_row`, `tax_subunits`, `tip_subunits`,
  `tip_split_mode`, `gratuity_subunits`, `discount_subunits`, `other_charges_subunits`, `is_auto_refund`,
  `status`, `row_version`, `split_version`), and `_share_defaults()` (`:844`) covers both of `shares`'
  (`remaining_subunits`, `row_version`). Nothing the client omits can become an explicit NULL on either
  create path. Separately, `encodeDefaults = true` on the Postgrest `Json`
  (`SupabaseClientFactory.kt:32`) means the client does send every defaulted field, so it does not rely on
  the server default in the first place.
- **Primary keys** — all 30 entities' `@PrimaryKey` / `primaryKeys` match the server's (or, for the eight
  local tables, are self-consistent). `user_subscriptions` is correctly keyed on `user_id`, `fx_rates` on
  the composite `(rate_date, quote_currency)`, `row_sync_state` on `(table_name, row_id)`.
- **Unique indexes** — enumerated from live `pg_indexes` and compared entity by entity (§1 table). Four
  deliberate partial-index asymmetries (note A), one gap in the safe direction (note B), and one in the
  unsafe direction (B3).
- **Timestamp unit and epoch** — one unit everywhere: epoch millis, through the single writer
  `Clock.nowEpochMillis()` (`Time.kt:28`). No seconds/millis mixing found. `ServerClock.SKEW_TOLERANCE_MS`
  is `60_000` (`ServerClock.kt:46`) and `_clamp_client_ts` is `+ 60000` (`schema.sql:909`) — the two
  constants `data/AGENTS.md` says must move together do currently match.
- **Field-name ↔ column-name agreement** — the client serializes with `JsonNamingStrategy.SnakeCase`
  (`SupabaseClientFactory.kt:30`). Every entity property's snake_case form equals its `@ColumnInfo` name,
  which equals the live Postgres column, for all 22 wire entities. The awkward ones (`rcAppUserId` →
  `rc_app_user_id`, `cashappHandle` → `cashapp_handle`, `otherChargesSubunits` →
  `other_charges_subunits`, `previousUnitPriceSubunits` → `previous_unit_price_subunits`) all resolve
  correctly. No finding-#25-style "offline" sync failure lurking here.
- **Raw `@Query` table names** — every `FROM` / `JOIN` / `UPDATE` / `DELETE FROM` target across all 31 DAO
  files resolves to one of the 30 tables declared in `EvenlyDatabase`. No typos, no references to dropped
  tables.
- **Projection column names** — all 12 `projection/` row classes' `@ColumnInfo` names match the `AS`
  aliases in the queries that return them, including the derived `remaining_subunits`,
  `overpaid_subunits`, `expense_count`, `currency_count`, and `expense_title`. Nullability matches too:
  `MemberWithUserRow.isPlaceholder` is non-null and fed by `COALESCE(u.is_placeholder, 0)` behind a LEFT
  JOIN; `UnclaimedNameRow.currency` is nullable and fed by a `MIN()` that can return NULL.
- **Soft-delete filters on reads** — every read that should hide tombstones does. `ShareDao`'s six
  balance/outstanding queries all carry `e.deleted_at IS NULL AND s.deleted_at IS NULL`;
  `ExpenseDao.observeByGroup`/`getActiveByGroup`, `ItemClaimDao`, `ItemShareDao`, `ExpenseItemDao`,
  `BillParticipantDao`, `CategoryDao`, `CommentDao`, `ReceiptDao`, `SettlementDao.observeByGroup`, and
  `GroupDao.observeById`/`findByInviteToken` all filter. Every `allForSync()` deliberately does **not**,
  which is correct — tombstones must propagate. The two omissions I found on write paths are B9 and B10.
- **`@Transaction` coverage** — present on all nine multi-statement DAO default methods
  (`GroupDao.createGroupWithAdmin`, `applyLeave`; `ExpenseDao.insertWithShares`, `replaceWithShares`,
  `overwriteFromServer`, `overwriteFromServerIfUnchanged`, `upsertFromServerIfUnchanged`;
  `SettlementDao.applySettlement`, `voidSettlement`, `replaceSettlement`; `ItemShareDao.setServings`;
  `PlaceholderMergeDao.mergePlaceholder`; `SignOutWipeDao.wipeSignedOutAccount`). No DAO returns a
  `@Relation`, so the other half of that rule does not apply. The one cross-DAO sequence that needs a
  transaction and cannot have a Room one is B8.
- **Hard deletes** — `grep` for `@Delete` and `DELETE FROM` across the DAO layer returns exactly: the 28
  `SignOutWipeDao` statements (a documented cache wipe of server-owned or device-local rows, correctly
  refusing `clearAllTables()`), `ReceiptUploadDao.kt:45` and `ExpenseSyncStateDao.kt:21` (device-local,
  documented exempt), and `UserDao.kt:29`. That last one is the known, tracked pre-prod item
  (`data/AGENTS.md` Rule 1: "the one hard delete to convert before prod"), and its **only** caller is
  `StubAuthSession.kt:102` — the offline no-Supabase stub deleting its own stub row, which Rule 9
  explicitly sanctions. No new hard delete on user data anywhere. **No P0 on this axis.**
- **`SignOutWipeDao` completeness** — 28 explicit `DELETE`s covering 28 of the 30 entities; the two
  omissions (`fx_rates`, `fx_currencies`) are global reference data with the reasoning written out. Every
  table in `SYNCED_TABLES` and both pull-only tables are covered. `WIPED_TABLES` is a faithful hand-mirror
  of the transaction body, and `SyncedTablesTest` pins it.
- **`SYNCED_TABLES` integrity** — the `init` block at `SyncEngine.kt:226-235` crashes on launch if the
  declared list and the actual push tables disagree; they currently agree (19 entries). `group_passes` and
  `user_subscriptions` are absent from it and neither DAO has an `allForSync`, so pull-only is enforced by
  the type system exactly as documented.
- **Enum persistence** — enums are stored as TEXT and parsed at the boundary. `ReceiptUploadStatus.fromName`
  (`ReceiptUpload.kt:22-23`) falls back to `PENDING` on an unknown value, which is benign here (the object
  key is deterministic and the PUT is `x-upsert`, so a spurious re-upload is idempotent). The one fallback
  that disagrees with its own column default is the out-of-scope `TipSplitMode` case in §3.
- **KMP parity** — swept `data/db/**` and `data/upload/**` in `commonMain` for JVM-only APIs
  (`java.`, `javax.`, `clearAllTables`, `System.currentTimeMillis`, `UUID.randomUUID`, `String.format`,
  `Locale`, `SimpleDateFormat`, `Thread.`, `synchronized`). **Zero hits** — the only match is the
  *prohibition* on `clearAllTables()` in `SignOutWipeDao`'s KDoc. Clean.
- **Migrations** — the destructive `fallbackToDestructiveMigration(dropAllTables = true)`
  (`EvenlyDatabase.kt:204`) is the known, accepted pre-prod risk and is not re-reported. What the brief
  asked instead: nothing in the tree assumes a `Migration` object that does not exist (`grep` for
  `Migration(` in `commonMain` returns nothing), `exportSchema = true` is set, and `version = 28` is
  consistent with a single `@Database` declaration. The entity set is internally consistent — all 30
  entities are registered, all 31 DAOs are exposed, and `SignOutWipeDao`'s KDoc correctly states the
  add-an-entity-means-add-a-line rule that keeps them in step.
- **`data/upload/` resumability** — a killed process does resume rather than strand: `bind`
  (`ReceiptUploadManager.kt:95-108`) resets `UPLOADING → PENDING` on launch and re-drains on reconnect;
  the outbox row plus the sandbox copy outlive the process; the object key is fixed at `attach` and the PUT
  carries `x-upsert: true`, so a re-send overwrites cleanly. A missing local file is handled explicitly
  rather than looping (`:214-218`). Progress writes are throttled to one per 16 KB
  (`PROGRESS_STEP_BYTES`, `:311`) plus one at completion, so a 1 MB receipt costs ~64 updates on a
  device-local table that no sync loop watches — no thrash. The two defects on this path are B7 (retry is
  not bounded) and B8 (publish is not atomic); the resumability contract itself holds.

## 5. Counts

| Severity | Count | Findings |
|---|---|---|
| P0 | 1 | B1 |
| P1 | 2 | B2, B3 |
| P2 | 5 | B4, B5, B6, B7, B8 |
| P3 | 4 | B9, B10, B11, B12 |
| **Total** | **12** | 11 CONFIRMED, 1 PLAUSIBLE (B12) |

Plus one out-of-scope observation handed to the `data/repository/` session (§3).

The sweep is **complete** — all 30 entities covered, every check in the brief's §3 has a verdict, and no
entity was skipped. Four of the twelve findings (B4, B5, B6, B11) extend known finding #28's "docs and
code describe different things" theme; each names that lineage inline. Findings #16, #23, #29 and the
destructive-migration and permissive-RLS items were re-verified as still-accurate descriptions of the
current tree and are not re-reported.
