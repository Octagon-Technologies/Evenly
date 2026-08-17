# `data/` — persistence, sync, repositories

Governs `data/**` in `commonMain`, plus the `actual`s in `androidMain`/`iosMain` (`data/db/DatabaseBuilder.*`,
`data/remote/supabase/AuthDeeplink.android.kt`). Money *math* lives in `../domain/AGENTS.md`; screens in
`../ui/AGENTS.md`; server schema in `supabase/AGENTS.md`.

## Local-first, always

Reads stream from Room. Writes land in Room **first**; `SyncEngine`/`SyncManager` carry them to Supabase.
Never write to Supabase directly from a repository call path and hope Room catches up.

**Data-safety logic lives here, not in the UI.** `deleted_at`/`deleted_by` stamping, audit writes, version
and conflict resolution, and tombstone sync belong in `data/repository/*` and the DAO layer. If you find
yourself writing `deleted_at` or conflict logic in a Composable or a `ui/navigation/` Route wrapper, stop
and push it down.

**Optional-ctor-dep pattern for testability:** repos take new collaborators as nullable ctor params
defaulted to `null` (`fxRepository`, `historyEventDao`, `remoteGroups`, `syncManager`). Production DI passes
real instances; unit tests pass nothing and get legacy behaviour with no test churn.

## Realtime is a per-group DOORBELL, never per-table CDC

The client **ignores every realtime payload**: an event only means "something changed, pull now". The server
publishes exactly ONE table, `group_activity` (one row per group, bumped once per writing transaction by
statement-level `AFTER INSERT/UPDATE` triggers). Its own membership RLS scopes delivery to that group's
members. `SyncManager` subscribes to that one table (`DOORBELL_TABLE`, `SyncManager.kt:124`) and treats it as
the pull signal.

**NEVER re-add app tables to the `supabase_realtime` publication.** Publishing all tables fanned out one
message *per row per connected client* which — combined with the old blind full-table re-push, since fixed by
`pushDirty` hash-gating — burned 13.9M messages against a 5M quota. Triggers skip `shares` (no `group_id`;
covered by the same-transaction `expenses` bump) and `users` (no group scope).

**The sync driver is lifecycle-gated.** The whole `SyncManager` driver — realtime socket, push-on-write, the
60s `syncNow` tick — is gated by `gatedUser(currentUserId, foreground)` on **signed-in ∧ app-visible**.
Backgrounding tears the channel down after a ~5s grace (which absorbs Android activity recreation);
foregrounding restarts with a catch-up sync. `App.kt` feeds visibility into the `AppForeground` Koin single
(in the always-loaded `appModule`, default `false` so an FCM-woken headless process never loops).
`PushController` stays ungated — background FCM → pull is intended. **Never run these loops from
`onCreate`, first composition, or for the process lifetime.**

**The gate is not the fence.** Three launches sit on `SupabaseAuthSession`'s process-lifetime scope and do
*not* read `currentUserId` before working: the restore-pull in `init`, `mirrorCurrentUser`'s `syncNow`, and
`PushController`'s pull-on-delivered-message. Nulling `currentUserId` cancels `SyncManager`'s loops and none
of those, so **sign-out's cache wipe is fenced by `SyncGate`, not by the gate.** `SyncEngine.push`/`pull` run
inside `gate.withSync(userId)`, which checks a per-user "closed" latch **after** taking the sync lock, and
`signOut` runs the pending-writes count and the wipe together inside `gate.closeForSignOut(userId)`. That
ordering is the whole point: a pull queued behind sign-out's own push used to acquire the mutex the instant
that push released it and re-land account A's rows into the just-emptied cache, with A's Supabase session
still valid. The mutex alone did not prevent it, it *scheduled* it. `SyncGateTest` pins the interleaving.

## One list of synced tables

`SyncEngine.SYNCED_TABLES` is the single source: `SyncManager` drives Room invalidation off it, `push` and
`countPendingLocalWrites` walk the same `SyncTable` objects (an `init` check crashes on launch if those stop
matching the list), and `SyncedTablesTest` pins `SignOutWipeDao.WIPED_TABLES` against it. Four hand-kept
copies is what it was, and two had already drifted. **Adding a table to `push` means adding it here.**

## The client's own clock is not trusted either

`Clock.nowEpochMillis()` — the one function every `*_at` write goes through — passes its reading through
`ServerClock.clamp`, capping it at the server's clock plus 60s. That mirrors `_clamp_client_ts` in
`supabase/schema.sql`, and the two constants move together or one side drops writes the other accepted. The
offset is learned free, from the `Date` header on Supabase responses (`SyncEngine.pull` reads it directly;
`ServerClockPlugin` covers the app's own Ktor client). With no response seen yet the clamp is the identity
function, which is also why it is inert in tests. `keepNewer` applies the same horizon on the way in: a
*local* stamp past it is a wrong clock rather than a later edit, so a row poisoned by someone else's device
accepts the next honest edit instead of being pinned forever.

## Synced Room entities double as wire DTOs

snake_case `@ColumnInfo` names mirror the Postgres columns 1:1, the entity is `@Serializable`, and the client
uses a snake_case `JsonNamingStrategy`. **No Room foreign keys** — rows sync in dependency-arbitrary order.

**Adding a column REQUIRES adding it server-side first** (additive `alter table … add column if not exists …
default …`). The full-row upsert sends every field, so a column missing on the server breaks ALL sync for
that table.

**Room schema bump = destructive migration** while pre-release (`fallbackToDestructiveMigration(dropAllTables
= true)`, `data/db/EvenlyDatabase.kt:146`). Bumping the version drops and recreates local tables; the
server rehydrates. Fine for now — see the prod gate at the bottom of this file. Schemas export to
`code/shared/schemas/`; commit them.

**Device-local tables stay out of sync.** Not every Room table is a wire mirror. Nine are local-only:
the receipt-upload outbox (`receipt_uploads`), the feedback outbox (`feedback_outbox`), the sync
bookkeeping (`expense_sync_state`, `row_sync_state`), `superseded_notices`, `group_scan_usage` (a cache of
the `my_group_scan_usage` RPC, so the free-scan meter is instant and works offline), and the three FX
tables (`fx_rates`, `fx_baked`, `fx_currencies`). All nine are **absent from
`SyncEngine.SYNCED_TABLES`** and **none is `@Serializable`** — the annotation is an exact discriminator
for "is a wire mirror", pinned by `EntitySerializablePartitionTest`, so keep it that way: a new local
table takes no annotation and a seat in that test's local list.

**Both outboxes hard-delete, and both are exempt from Rule 1 for the same reason.** `ReceiptUploadDao
.delete` holds no user data at all. `FeedbackOutboxDao.delete` *does* hold user text, so it is the more
interesting case and is flagged here rather than left to be discovered: a row is removed only once the
`feedback` edge function has **accepted** it (the ticket now lives server-side in `feedback_tickets`) or
**permanently refused** it. It is a work queue, not a record, and there is no tombstone for anyone to
sync. Do not generalize the exemption to a table that is the only home of what it holds.

**An unsent feedback ticket dies with the session.** `SignOutWipeDao` clears `feedback_outbox` with the
rest of the account's cache. That is deliberate and asymmetric with the receipt outbox: keeping it would
let the next account on the device flush a stranger's bug report under their own token, which is exactly
what the edge function refuses to do server-side. `countPendingLocalWrites` does not see it either (it
walks `SYNCED_TABLES`), so sign-out will not warn about one.

**`group_passes` and `user_subscriptions` are the PULL-ONLY synced tables.** Evenly Pro
(`PRO_PASS_SPEC.md`) has two routes and the server is the only writer of both, so the client pulls them
and never pushes. Three things enforce that, deliberately redundantly, because a client that could write
either table could grant itself unlimited paid Claude-vision calls: server RLS grants `select` only,
there is no entry in `SyncEngine.push`, and **neither DAO has an `allForSync`** — `push()` consumes
exactly that method, so its absence makes pull-only a fact of the type system rather than a comment. Do
not add one. Both land with a blind upsert and no `keepNewer` guard, which is correct here and nowhere
else: the guard protects local edits, and nothing in the app ever writes a pass or a subscription.

`user_subscriptions` is keyed by **user**, not group, so it is pulled for the roster rather than for the
group ids, and `UserSubscriptionDao.observeForGroup` joins it against the local ACTIVE `members` roster
exactly as the server's `group_pro_status` does. That join is what makes a subscriber leaving a group
drop it back to free at the next pull with no second table to keep in step.

`domain/pro/proStatusOf` mirrors the server's `group_pro_status` so the Pro badge renders offline: both
routes flatten into one `ProCandidate` list and the latest-expiring live one wins, with `source` saying
which door answered. Two implementations of one rule, accepted so a badge costs no round trip;
`ProStatusTest` pins it against the same cases the SQL side was verified with. **Enforcement is always
the server's copy** — this one only ever decides what to draw.

## Expenses sync through a ZONE-AWARE MERGE RPC

Not a blind upsert, and not a whole-expense CAS. An expense splits into concurrency zones by *invariant
boundary* (the "When Two Edits Collide" model in Notion):

- **Zone 1 — metadata** (`title`, `notes`, `category_id` + `subcategory_id`, `expense_date`): independent
  scalars, each carrying its own `*_updated_at` stamp, merged **per-field by newest timestamp**. Two people
  editing *different* fields both survive; no conflict.
- **Zone 2 — the split** (`amount_subunits`, `currency`, `split_mode`, payer, bill extras, and the per-user
  `shares`): one atomic money value guarded by a **causal `split_version`**, NOT wall-clock.
- **Zone 3 — settlements**: needs no version pointer, because `remaining` is derived on read (below).

`SyncEngine.pushExpenses` routes each *dirty* expense (local `row_version` ≠ `expense_sync_state
.synced_version`) through `merge_expense(p_expense, p_shares, p_base_split_version, p_actor)`, where
`p_base_split_version` is the device's last server-confirmed split version. The client sets its local
`split_version` to `synced_split_version + 1` **only when it actually changed the split** (see
`ExpenseRepositoryImpl.edit*` / `BillRepositoryImpl.editBill`), so the server can tell a real split edit from
a metadata-only one:

- base matches ⇒ apply and advance the split;
- **base is stale** (a split edit made against an older version than canonical) ⇒ the server keeps its
  advanced split and the loser is APPENDED to the append-only `superseded_split_edits` audit — never
  applied, never lost.

That is the fix for the offline-for-weeks device eating a dozen newer edits: it loses **because it is
causally behind**, not because of who reached the server last. `merge_expense` returns the merged canonical
`{expense, shares}` and the client adopts it directly (`ExpenseDao.overwriteFromServerIfUnchanged`) — no re-pull. Shares
ride *with* the expense (the RPC soft-deletes removed ones server-side); soft-deleted expenses are tombstones
(LWW by `updated_at`).

Do **not** reintroduce a blind bulk `expenses`/`shares` push, a whole-expense CAS, or field-level merging of
the split — that last one is what produces a $35 split of a $30 dinner.

**There are NO bilateral "you both edited this" conflict cards.** A superseded split edit surfaces to its
author ALONE as a one-sided, dismissible "your change was superseded — review?" notice (device-local
`superseded_notices` → `ExpenseRepository.observeSupersededNotice` → a banner on the expense detail).
`merge_expense` never writes `expense_edit_conflicts`, so `observeEditConflicts` is hard-wired to empty and
the Conflicts-tab edit half is dead. The old `commit_expense` RPC, the `expense_edit_conflicts` parking
table, and the `ExpenseEditConflict*` client code are **inert** — nothing populates them; they get removed
once the branch settles.

## Settlement state is DERIVED on read, never stored

A share's `remaining` is `owed − Σ(applied allocations of non-voided settlements)`; the payer's own share is
always 0. Balance and "settled" follow from it (an expense is settled iff every share's derived remaining is
0). Consequences you must preserve:

- `settlement_allocations` is a **synced** table (the payment ground truth; carries `group_id` +
  `row_version`).
- Settlements never mutate a stored remaining. `shares.remaining_subunits` is **vestigial** — read it nowhere.
- `expenses.status` only ever stores ACTIVE/DELETED.
- **An expense's `currency` is fixed once a payment has been applied to it.** `editExpense` refuses the
  change (`ShareDao.appliedAllocationCount`), because the derived remaining subtracts an allocation with no
  currency predicate: re-denominating would read a $25.00 payment as settling a EUR 25,00 debt, drop the
  line out of every outstanding filter, and leave the debtor unable to pay the difference. Voiding the
  payment frees the currency again.
- **Editing a split no longer wipes payments:** `ExpenseDao.replaceWithShares` + `mergeShares` preserve each
  surviving participant's share `id` (matched by `user_id`) so allocations stay linked.
- `shares` has `deleted_at` (removed participants soft-delete) plus a partial unique index over active rows.
  The old hard-delete `deleteSharesForExpense` is **gone** — do not reintroduce a `DELETE FROM shares`. The
  conflict-revert path (`ExpenseDao.overwriteFromServerIfUnchanged`) tombstones rejected local shares too.

Do not reintroduce a stored remaining or a stored SETTLED status; that is the staleness bug this design
removes. A payment scopes three ways in `NewSettlement`: `expenseId` (one expense), `expenseIds` (a chosen
subset), or neither (all outstanding to that creditor). Allocation runs oldest-first *within* the chosen set.

## Itemized bills: shares are a derived materialization

An itemized expense (`split_mode = "ITEMIZED"`) keeps line items in the synced `expense_items` table and
who-had-what in the synced `item_claims` / `item_shares` tables; bill-level extras ride on the expense row.
**Claims are partitioned by user** — each device only ever writes its *own* claim — so live multi-device
claiming is conflict-free and needs no CAS, unlike an expense edit.

`BillRepositoryImpl.materializeShares` runs the pure `splitBill` engine (see `../domain/AGENTS.md`) and
writes the resulting `shares` with **deterministic ids** (`"<expenseId>__<userId>"`), so every device
converges on identical rows and editing the menu re-derives *in place* — a price fix never disturbs a
recorded claim or its settlement allocations. Shares for an itemized expense are a **local derived
materialization**, not independently pushed. `BillRepositoryImpl.setClaim` (solo) and `.setPortion(portionId,
members, quantity)` (shared slice) are the assignment writes. Item shares soft-delete (opt-out), and
`added_by` records who assigned a claim — useful when someone assigned on another person's behalf.

A bill carries a synced participant set (`bill_participants` — who it is *for*). `editBill` reconciles the
set, but an empty selection must never silently wipe it. Each participant carries a per-person `done_at`
"I'm done" stamp that is a **nudge-silencer, not a resolution** — `BillRepository.observeUnresolvedBills`
treats a bill as unresolved when a line still needs someone *or* a participant hasn't marked done, because
claiming ≠ paying.

**Taking someone off a bill must take their claims with them.** The roster row carries no money — owed
amounts derive from `item_claims`/`item_shares` — so tombstoning it alone leaves the person off the bill and
still paying for their dishes, and the next pull re-derives that debt rather than healing it. "The same
write" is literal: **`BillWriteDao` owns both bill writes as Room transactions** (`createBill`,
`applyBillEdit`), because these ran as loose sequences from a navigation-scoped coroutine and a back-tap
committed the first and dropped the rest. Units a removed person held return to UNCLAIMED; a slice they
shared survives for its remaining members. The
reverse case is the deterministic row id: putting the same person back **revives their tombstoned row**, it
does not insert a second one. `BillRepositoryTest` pins both directions.

Don't reintroduce stored itemized share input, a stored per-unit truth, or a blind items/shares push.

**Joining someone else's claim goes through `join_item_portion`, never a local cross-user write.**
`BillRepository.joinItem` calls the atomic server RPC (`JoinItemPortionGateway`, bound only when
Supabase is configured — same optional-ctor-dep pattern as `PlaceholderClaimGateway`) and mirrors its
canonical `{portion_id, quantity, members}` result into Room, including retiring any local solo
`item_claims` row for a member the server just folded into the portion. `setPortion` calls the same
gateway internally whenever a newly-added target already holds an active solo claim held by someone
else — writing that person's `item_claims` tombstone from this device would violate the "claims are
partitioned by user" invariant above. A fresh assignment with no colliding solo claim still writes
locally, same as before; there's nothing to race against. No gateway (offline/stub/tests) means that
specific add is skipped rather than risking a half-converted claim (WEB_CLAIM_SPEC.md §5.3).

**A portion membership replaces a solo claim; the two must never both be live.** Whichever path writes
the membership retires the claim in the same breath — the RPC for someone else's claim, `setPortion`
itself for the target's *own* claim (a same-user write, no RPC needed). Leaving both counts that person
twice in `assignedQuantityByItem` and in the money. `BillJoinPortionTest` pins all four paths.

**A web guest's menu edit APPLIES, server-side, and the app only ever undoes it.** `pending_item_edits`
is a synced Room mirror of the bill's change log; the name predates the decision and the table is
synced, so it stays. The `web-claim` edge function calls `apply_web_bill_edit`, which writes
`expense_items` and the log row (stamped `APPLIED`, with `item_id` filled in even for an ADD) in one
transaction. It cannot use `merge_expense`: it holds a service key and has no `auth.uid()`. See
`supabase/AGENTS.md`.

`BillRepository.undoPendingEdit` takes ONE change back (no bulk variant), restores the line,
**advances the causal `split_version`**, and re-derives. Skipping that bump would let `merge_expense`
treat the undo as causally stale and silently drop it. Two rules that look like details and are not:

- **Restore the recorded LINE TOTAL, never per-unit × quantity.** `previous_line_total_subunits` exists
  for exactly this — per-unit is a rounded view of the line total (`../domain/AGENTS.md`), so rebuilding
  a $10.00 line over 3 units hands back $9.99.
- **First-undo-wins is the conditional `UPDATE` in `PendingItemEditDao.markUndone`,** not a
  read-then-write. Anyone on the bill may undo, so two simultaneous taps must produce one undo and one
  no-op. Losing the race is an `Ok`: the change is undone, which is what the caller wanted. That stamp is
  the one thing an undo can spend and not get back, so it and the restore are ONE transaction
  (`PendingItemEditDao.undoAndRestore`) — split, an interruption between them left the line at the price
  the payer rejected while every device rendered it as undone, unrepairable because the retry correctly
  no-ops.
- **An undo that would leave an illegal bill is refused before anything is written.** A discount entered
  while a guest's inflated line was live can exceed the bill without it, so the undo would store a negative
  expense that falls out of every `remaining > 0` filter. The rule is `domain/expense/billTotalProblem`,
  the same one `createBill`/`editBill` enforce; only a *newly* illegal total is refused, so a bill that
  arrived broken can still have its cosmetic changes taken back.

Undoing a REMOVE in-app restores the line but **not** the claims that removal killed — the app cannot
tell them apart from claims their owners dropped at the same moment. The server's `undo_web_bill_edit`
can, by matching the removal's exact `deleted_at` stamp, so a guest's undo revives them. Restoring the
line and leaving the claiming to the table is the safe direction to be wrong in.

`assignRemainder` is the sibling write for
"three people never claimed" (spec E17): one NEW `item_shares` portion per line carrying only that
line's leftover units, under a deterministic `"<item>__remainder"` id, so it needs no `join_item_portion`
(nobody's existing claim is being rewritten) and running it twice converges instead of double-billing.
It computes the leftover **excluding that portion**, and drops former members who are not in the new set,
so a second call with different people re-assigns rather than reading zero left and silently doing
nothing.

**`WebBillLinkRepository` is deliberately NOT local-first**, the only repository here that isn't. A
bill link is a server-side authorisation: minting one offline hands out a QR nothing can validate, and
revoking one offline tells the payer a link is dead while guests keep writing to it. Every call is a
live round trip that reports its own failure. Its one piece of local state is the **plaintext token**,
in `SecureStorage` (not Room) keyed by expense — it is a bearer credential for one bill, so it must
never ride the sync push and must die on sign-out. The server stores only the hash and cannot hand the
plaintext back, so losing that cache is a normal state (`url` goes null, `exists` stays true) and the
screen offers a rotation rather than an error.

**A conversion that could not be made is an `AppResult.Err`, and the claim screen shows it.** Adding
someone to a line another person claimed needs the server; if that call fails there is no local
fallback, so `setPortion` reports rather than returning `Ok` with a target silently missing. Every
target that *did* apply is still saved — partial success, reported as failure, because the person who
tapped did not get what they asked for.

## Placeholders, members, and names

A placeholder is a `users` row (`is_placeholder=1` + `placeholder_group_id`) **plus** a `members` row —
`addPlaceholder` creates both in ONE `UserDao.createPlaceholder` transaction, because every read that
matters JOINs them and a `users` row alone is invisible everywhere with no way to remove it. When merged into a real user (reconcile, *or* a joiner picking it on the Join
sheet), soft-leave it **and** stamp `placeholder_claim_completed_at`.
`UserDao.observePlaceholdersInGroup` (the source for both the Reconcile picker and
the Join-sheet identity picker) JOINs `members` and filters `status='ACTIVE' AND placeholder_claim_completed_at
IS NULL`, so a claimed placeholder stops appearing as a pickable identity. **Do not revert it to a users-only
query** — that resurrects the merged placeholder.

**A claim in flight is parked durably before the server is asked.** `claim_placeholder` retires the
placeholder server-side, so dying between that RPC and the local merge strands the name's whole history
where no picker will offer it again. `PlaceholderClaimCoordinator` writes a `PendingClaimStore` record
first and clears it only once `reconcilePlaceholder` returns; `resumePending()` (called when a group screen
opens) finishes anything left over. Both halves are idempotent, so a resume that was not needed is free.

**The merge is ONE transaction, in `PlaceholderMergeDao`.** Every table the retired name appears in moves
together: `shares`, the parent `expenses` (payer + causal `split_version`), `settlements`,
`settlement_allocations`, `item_claims`, `item_shares`, `bill_participants`, then the `members` stamp. A
half-applied merge is silently wrong money across several people's balances. Never reintroduce a blind
`UPDATE … SET user_id = :toUserId` on any of them: where the claimer already holds a row for the same
parent it produces two active rows per person, which Room's non-unique index accepts and the server's
partial unique index rejects — taking that expense's whole sync down. Collisions **fold** (shares and item
claims sum; portion memberships and bill participants drop the duplicate) and the loser is tombstoned with
its allocations re-pointed at the survivor.

Member display names always resolve from the global `users` JOIN (`MemberWithUserRow.displayName`). There is
**no per-group name copy**, so a Settings rename (`ProfileRepositoryImpl.updateDisplayName`) propagates to
every roster reactively.

`SyncEngine.pull()` guards `members`, `users`, and every other synced table carrying `updated_at` with the
`keepNewer` last-write-wins filter, so a stale server row cannot re-resurrect a local soft-delete or rename.
`conflicts` and `expense_history` are exempt (no `updated_at`).

## Categories are per-group with copy-on-write defaults

The built-in set lives in app code (`CategoryDefaults`, mirroring the legacy `ExpenseCategory` ids/colors). A
group has **zero** rows in the synced `categories` table until it first edits categories; until then
`CategoryRepository.observeCategories` emits the defaults. The first mutating call
(`CategoryRepositoryImpl.materialize`) seeds the **full** default set as rows (deterministic ids
`"<groupId>__<key>"`, `is_default=1`) then applies the change — so a never-customized group costs no rows.

An expense persists a category's `key` in `expenses.category_id` (`"food"…` for a default, a uuid for
custom); `key` is unique per group among live rows. Icons are a UI concern: the entity stores an opaque
`icon` token resolved by `ui/screen/group/CategoryCatalog`. Soft-delete only.

The expense editor picker **and** the Balances "Spending by category" donut and history
(`GroupBalancesMapping.build*`, via `resolveCategory`) read the group's *effective* categories, so custom
categories render with their own label, icon, and color.

**Known gap:** the expense-LIST rows (`GroupExpensesMapping`) and the filter sheet still resolve icon/color
from the legacy `categoryIcon(ExpenseCategory)` / `categoryColor(ExpenseCategory)` enum helpers, so a
*custom* category's rows there fall back to the OTHER glyph/slate. Thread `observeCategories` into those to
finish.

## Receipts and uploads

Resilient receipt upload lives in `data/upload/` (`ReceiptUploadManager` = the shared
`ReceiptUploadDriver`) with a platform `ReceiptUploadScheduler` actual — Android `WorkManager`, iOS
background `URLSession`. Progress is written to Room and observed by the UI; there is no WorkManager↔UI
plumbing. The synced `receipts` row is created only once the bytes land in Storage. The iOS host
`AppDelegate` still needs `handleEventsForBackgroundURLSession` for suspended-app completion (session id
`app.splitevenly.receiptUpload`).

**A picked receipt is staged before it has an expense to belong to.** `enqueue` is `stage` + `attach`:
`stage` compresses and writes the bytes to the sandbox (on `Dispatchers.Default` — every caller invokes it
from a UI scope) and records *nothing*; `attach` writes the outbox rows once an expense id exists. That
split is what lets the add-expense editor show the real photo while the user is still typing, instead of a
placeholder and a promise. Do not re-merge them, and do not pre-generate an expense id to enqueue early:
an abandoned editor would leave a Storage object pointing at an expense that never existed.

Staged bytes are the editor's to clean up until `attach` runs. **These editors have no `BackHandler`,** so
system back and swipe-back never reach `onBack` — cleanup hangs off `DisposableEffect`/`onDispose` and
calls `discardStagedDetached`, which runs on the manager's app-lifetime scope because the editor's own
scope is already cancelled by then.

Receipt OCR is the `extract-receipt` edge function (Claude vision → structured draft) reached via the
`ReceiptOcr` gateway. It accepts **multiple pages (images and/or PDFs) read as one bill** and only ever
*pre-fills* the editable item list — a human verifies before any money is computed.

**The pages someone scanned are a receipt no matter what the OCR returns.** Stage them when they are
picked, not inside the `ScanOutcome.Success` branch, and on a job separate from `scanJob` — cancelling the
scan must not cancel the staging. Attaching only on success silently threw the photo away on every
failed, blocked, offline or cancelled scan.

## RevenueCat wiring — unconfigured must be INERT

`ProConfig` mirrors `SupabaseConfig`: two **public** SDK keys (one per store) and one `isConfigured`
flag the whole feature hangs off. The **secret** key lives only in edge-function env and must never
appear in `shared` or either app target.

`ProBilling` is bound **unconditionally** in `appModule` — `RevenueCatBilling` when configured,
`NoProBilling` otherwise — so no caller ever asks whether monetization is switched on. That is what
keeps the promise that with no keys there is no paywall, no pass sheet, no meter change, and scans
behave exactly as they do today; same contract as the app being fully usable with Supabase
unconfigured. A `getOrNull` here would move that question into every call site.

The parked pass activation in `ProPurchaseCoordinator` is **keyed by group**. One un-scoped record meant a
stuck charge in group A answered the pass sheet opened in group B, activating A and dismissing itself as
though B were Pro. `hasPendingActivation`/`retryPendingActivation` take a `groupId`; the others are
retried in the background and never reported on a sheet that does not own them.

`Purchases.logIn` is bound to `currentUserId` in `SupabaseAuthSession.init`, beside the sync and push
binds, so the RevenueCat app user id **is** our user id — that is what makes webhook attribution and
support lookups possible. The sign-out half is not optional: without `logOut`, one device's
subscription follows the next person who signs in on it.

**Never hardcode a price.** Always render the `StoreProduct`'s formatted price; App Review rejects
hardcoded prices and a non-US buyer must see their own currency. Offerings drive both surfaces, so
nothing about the package mix belongs in Kotlin beyond a fallback ordering, or a pricing experiment
stops being a dashboard change.

## Supabase client wiring

Plugins bind only when `SupabaseConfig.isConfigured` (real creds): Auth + Postgrest + Storage + Realtime.
Otherwise `StubAuthSession` keeps the app fully offline-usable. `SupabaseConfig` ships the **anon** key
(public, RLS-gated). The service-role key **never** appears in shared or client code — edge functions only,
via env vars.

---

# ⛔️ PRODUCTION DATA-SAFETY RULES — inert until the app is live

> **GATING NOTICE.** As of the last edit the app is in **development**, where these rules do **not** bind: a
> wiped local cache rehydrates from the server, there are no real users, and destructive shortcuts
> (destructive Room migration, permissive RLS) are acceptable. **The owner will explicitly say "the app is
> live / in prod" in this repo.** Until that sentence exists, treat this section as inert. The moment it
> does, **every rule below is non-negotiable and overrides any convenience, urgency, or "clean shortcut"
> framing** — including anything above that assumes a rebuildable cache.
>
> **Why this exists:** our competitor Tricount is buried in 1-star reviews for *losing people's expense
> data*. For a money-splitting app a lost expense is a real-world dispute between friends — unrecoverable
> trust, not just a row. This is the single highest-leverage defense of our reviews.

## P0 — the pre-prod readiness gate

None of these may ship to a real user. Refuse to mark the app prod-ready while any remain open.

1. **Kill destructive Room migration** (`EvenlyDatabase.kt:146`). In prod one schema bump drops every
   local table and every unsynced offline write with it. Replace with hand-written, tested `Migration`
   objects for every version step, keep `exportSchema = true`, and test each against a *populated* DB.
2. ~~**Tighten RLS to membership-scoped.**~~ **Done (2026-08-17).** All 20 app tables are scoped to ACTIVE
   members of the row's group, and the TRUNCATE sweep ran alongside (RLS does not apply to TRUNCATE, and
   Supabase grants ALL on a new public table, so policies alone would not have closed it). Rules for
   anything you add — never a permissive policy, always via `is_group_member`, never filter `deleted_at`
   in a policy — are in `supabase/AGENTS.md`. **Still open, and not closed by this:** the `receipts`
   Storage bucket is `public = true`, so receipt *bytes* are readable by anyone with the URL. That needs
   signed URLs, which is a client change.
3. **Add the audit log + triggers** (Rule 4) — there is none today. `expense_history` logs *events*, not
   before-images, so a bad mutation is currently unrecoverable from app data alone.
4. **Close the soft-delete gaps** (Rule 1): `conflicts` has no `deleted_at`. (`shares` and `users` now
   do — done. `members` uses `status = 'LEFT'` by design; `device_tokens` is intentionally hard-deleted,
   ephemeral non-financial data.)
5. **Add concurrency guards to push** (Rule 5). Pull is guarded by `keepNewer`; push is still blind.
6. **Turn on Supabase PITR + scheduled backups, and rehearse a restore** (Rule 12).

## The rules

### 1. Never hard-delete user data. Soft-delete only.

Every table holding user-generated or financial data deletes by setting `deleted_at` (epoch millis, matching
our `updated_at` convention) and `deleted_by` (actor `user_id`) — never by removing the row. The synced
round-trip depends on it: tombstones must reach the server, and `allForSync()` deliberately includes
soft-deleted rows so the deletion *propagates* instead of a hard delete silently resurrecting on the next
pull.

**Already correct:** `expenses`, `groups`, `settlements`, `comments`, `receipts`, `shares` carry
`deleted_at`; `members` uses `status = 'LEFT'` + `left_at`. Copy that pattern.

**Forbidden — flag before writing, never add silently:** `@Delete` or `@Query("DELETE FROM …")` on a
user-data DAO; a Postgrest `.delete()` from client code on a user-data table.

**The one hard delete to convert before prod:** `UserDao.delete` (`DELETE FROM users`; sole caller is the
offline `StubAuthSession`) — see Rule 11. The other one (`ReceiptUploadDao.delete`) is a device-local
outbox holding no user data and is legitimately exempt.

If a table lacks `deleted_at`/`deleted_by` and you're asked to delete from it: **stop and flag it.** Adding a
hard delete as a workaround is itself a violation.

### 2. Never DROP, TRUNCATE, or unscoped-DELETE. Ever.

Forbidden in any migration, RPC, edge function, or client query — Postgres *and* Room. `clearAllTables()` and
any wipe/reset/nuke helper are forbidden against a prod DB. If a task seems to need one, there is a safer
path — ask. (Today the schema is clean: zero `DROP`/`TRUNCATE`; the only `DELETE` is the per-user
`delete_my_account()` RPC. Keep it that way.)

### 3. Migrations are additive, backward-compatible, and tested against real data.

Add columns with a sensible `DEFAULT` so existing rows and older app versions keep working (and remember:
server-side first). **Never rename in one step** — add the new column, backfill, deprecate the old one in a
*later* migration once no in-the-wild version reads it. **Never drop a column** without a deprecation period
and a real recovery trail. **Test every migration against a populated copy of prod**, both the Postgres
migration and the Room `Migration`. A migration only ever run on an empty DB is untested.

### 4. Every financial mutation writes to the audit log.

Any repository method, RPC, trigger, or edge function that creates, edits, or soft-deletes an `expense`,
`share`, `settlement`, or `settlement_allocation` must append a before+after record. **Not built yet — P0.**

```
expense_audit_log (
  id uuid pk default gen_random_uuid(),
  entity text not null,            -- 'expense' | 'share' | 'settlement' | ...
  entity_id text not null,
  action text not null,            -- 'created' | 'updated' | 'deleted'
  old_data jsonb,                  -- before-image (null on create)
  new_data jsonb,                  -- after-image
  changed_by uuid references auth.users(id),
  changed_at timestamptz default now()
)
```

Prefer a Postgres `AFTER INSERT/UPDATE` trigger so the before-image is captured server-side and cannot be
skipped by a client path. The log is **append-only**: never updated, soft-deleted, hard-deleted, or
truncated. If asked to delete from it, refuse. (`expense_history` is a user-facing activity feed, **not** a
substitute — it stores no `old_data`.)

### 5. Sync must not silently clobber data.

Our biggest *latent* loss vector, invisible until two devices collide. `pull()` already applies `keepNewer`.
Two gaps remain: (a) `push()` sends every local row with **no version check**, so a stale local row can
overwrite a newer server row — add a server-side guard (a trigger, or `on conflict … where excluded
.updated_at > <table>.updated_at`) that rejects an upsert older than the stored row; (b) the pull guard
silently keeps the local winner instead of **surfacing a real conflict** to the user — we still have the
`conflicts` table and the `GroupConflictsTab` UX to surface it with.

The Realtime handler ignores action type and just re-pulls. That is only safe *because we soft-delete* — if a
hard delete ever reaches the server, a re-pull won't remove it locally. Keep deletes soft so a pull always
carries the tombstone. Treat a partially-failed push as recoverable: the periodic full sync re-pushes, but
only if the local write is still intact, which is the whole point of Rules 1 and 4.

### 6. Never bypass RLS without flagging it loudly.

See `supabase/AGENTS.md`. Client code uses the anon/authenticated key only. Reaching for the service-role
client is never the default; if a task genuinely needs it, say so in a comment explaining *why RLS can't
express the rule*, and keep it read-only or tightly scoped.

### 7. Cascade deletes require explicit confirmation, and are soft.

We deliberately have **no FK constraints and no `ON DELETE CASCADE`** (rows sync in dependency-arbitrary
order). Keep it that way — **never add `ON DELETE CASCADE` to a user-data table.** Deleting a parent (group,
expense) cascades to children in application/trigger logic as a **soft**-delete cascade. Before writing any
cascade, state in your response: (a) exactly which tables and roughly how many rows are affected, and (b)
that it is a soft cascade. If you can't confirm both, don't write it.

### 8. Irreversible operations need a confirmation header with real pre-checks.

Any migration or function that can't be cleanly undone carries a top-of-file `-- DESTRUCTIVE OPERATION`
comment: what it does, **pre-checks filled with true answers** (which app versions still read this column?
since when unused? is the data in the audit log or a PITR snapshot?), and the **recovery path**. If you
cannot fill the pre-checks with true statements, **do not write it — ask for the missing facts.**

### 9. Account deletion is reversible and never erases others' financial history. — Done (2026-08-08)

Implemented for the Play Store "Delete account URL" requirement. `AuthSession.requestAccountDeletion()`
(`SupabaseAuthSession.kt`) calls the `request_account_deletion` RPC, which only stamps
`users.deletion_requested_at` — nothing is deleted yet. Only signs out and clears `currentUserId` on RPC
**success**; a network failure surfaces `AppError` with the account and local state untouched. A daily
pg_cron job (`purge_deleted_accounts`, scheduled in `supabase/schema.sql`) anonymizes any account whose
30-day grace period has elapsed: profile fields cleared and `deleted_at` stamped (never a row delete),
every active `members` row soft-left, admin handed off where the deleted user was sole admin, then
`auth.users` removed (the login credential — not shared data, unlike everything else). Expenses, shares,
settlements, and receipts are untouched and read as "Deleted user" everywhere.

`AuthSession.cancelAccountDeletion()` clears the pending request within the grace window; a returning
sign-in is gated behind `ui/screen/auth/PendingDeletionScreen.kt` (via `HomeGateRoute` in
`ui/navigation/WiredScreens.kt`) until the user cancels or signs out. `StubAuthSession` (offline, no
server) has no grace period to preserve, so it still deletes the local row immediately on request.

### 10. Storage: a soft-deleted row must not orphan its file — and never bulk-purge.

Soft-deleting a `receipts` row already best-effort deletes the Storage object
(`ActivityRepositoryImpl.deleteReceipt`). In prod make that cleanup reliable (reconcile/retry) so we neither
leak files nor delete a file another row still references. **Never** issue a bucket-wide or prefix-wide
delete against the prod `receipts` bucket.

### 11. Backups are a feature: enable PITR and rehearse restores.

Before prod, enable Supabase Point-in-Time Recovery and scheduled backups, set a retention window, and
**actually perform a test restore** to a scratch project so "restore from PITR" in Rule 8 is a proven path,
not a hope. Re-test after any major schema change.

### General

- **Flag before you act** on anything touching schema, deletes, auth, RLS, or sync — name the change at the
  top of your response. Never silently alter a column, RLS policy, sync rule, or function signature.
- **When in doubt on any data operation, ask.** In prod the cost of asking is a message; the cost of a wrong
  guess is unrecoverable user data and the reviews that follow.
- **Prefer explicit, readable data-safety code over clever or terse code.**
