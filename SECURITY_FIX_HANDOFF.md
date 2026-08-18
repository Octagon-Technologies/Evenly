# Security & correctness fix handoff

**Written 2026-08-15.** Supersedes the open half of `ARCH_SECURITY_REVIEW.md`, which was generated
2026-07-19 against `feat/expense-versioning-conflicts` and has since gone partly stale. Every finding
below was **re-verified against the working tree on `feat/pro-passes` @ `daef743`** — the line numbers
are real as of that commit, not copied forward from the old tracker.

Read `AGENTS.md` first; its §4 non-negotiables and §6 commit rules bind everything here. Read the
nested `AGENTS.md` for whichever tree you are about to touch (`data/`, `domain/`, `supabase/`).

---

## 0. What this document is for

`ARCH_SECURITY_REVIEW.md` had 14 open findings. Re-triage moved three of them:

| Was | Now | Why |
| --- | --- | --- |
| #26 open | **Closed — already fixed** | `categories` is present in `SYNC_TABLES` |
| #27 open | **Closed — won't fix, the finding is wrong** | It asks you to violate AGENTS.md §4.3 |
| #29 open | **Downgraded to cleanup** | The index name is gone from `schema.sql` entirely |

The remaining 11 are ordered below by blast radius, not by the old P-labels. Three of them
(#25, #16, #19) are ranked higher here than the old tracker had them, for reasons given inline.

**Ground rule for this session:** verify each finding still reproduces before fixing it. This document
was accurate on 2026-08-15; concurrent sessions run on this tree (see `AGENTS.md`), so treat every line
reference as a strong hint, not a guarantee.

---

## 1. Do not "fix" these — they are closed

### #27 — do NOT add `users` to the `supabase_realtime` publication

The old tracker asks for this. **It is wrong and acting on it re-creates a production outage.**
`AGENTS.md` §4.3 and [`supabase/schema.sql:642`](supabase/schema.sql) both forbid adding app tables to
that publication; doing it once fanned out one message per row per client and burned 13.9M messages
against a 5M quota. Realtime is a one-table doorbell (`group_activity`) and stays that way. Profile
renames riding the 60s fallback tick is the intended trade, not a defect.

If you touch `ARCH_SECURITY_REVIEW.md`, mark #27 `[x]` with the reason, so the next agent does not
rediscover it as an actionable task.

### #26 — already done

`SYNC_TABLES` at [`SyncManager.kt:162`](code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/supabase/SyncManager.kt)
already contains `"categories"`. Tick it and move on.

---

## 2. Tier 1 — fix before Pro can take money

These five are the reason this document exists. #24 and #18 should not survive the week.

### 2.1 — #24 · Sign-out clears nothing, so account A's rows push under account B

**Severity: highest on this list.** A cross-account data leak in a money app.

**Verified state.** [`SupabaseAuthSession.kt:235`](code/shared/src/commonMain/kotlin/app/splitevenly/data/auth/SupabaseAuthSession.kt)
is the whole of `signOut()`: capture an analytics event, `analytics.reset()`, launch
`client.auth.signOut()`, null out `_currentUserId`. No Room write of any kind. `grep -rn
"clearAllTables\|wipeLocal"` over `code/shared/src` returns only a *prohibition* in
`data/AGENTS.md`. So: sign out, sign in as a different account on the same device, and the next push
sends account A's rows under account B's session.

Pro raises the stakes — entitlements and `group_passes` are per-user, so a stale local pass row now
rides along too.

**The two constraints that make this non-trivial.** Read the comment block on
`requestAccountDeletion()` directly below `signOut()` before writing anything; it encodes both:

1. **`data/AGENTS.md` Rule 9 — never wipe local state ahead of the server.** The account-deletion path
   deliberately leaves Room intact, because a deletion can be cancelled within the grace period. Your
   fix must not wipe in the path that runs when the delete RPC *failed*.
2. **Unsynced local writes are real user data.** Wiping unconditionally destroys them silently.

**Shape of the fix.** On sign-out, dirty-check first (`row_sync_state` / `expense_sync_state` for
pending pushes). If clean, clear the synced tables plus both sync-state tables. If dirty, warn
("unsynced changes will be lost") and let the user decide. Do not reuse this path for
`deleteAccount`'s failure branch.

**KMP trap.** `RoomDatabase.clearAllTables()` resolves on Android/JVM and **fails on Kotlin/Native**
(`AGENTS.md` §4.1). Clear via explicit DAO deletes or a `@Query`-per-table, and compile the iOS target
before you believe it works.

**Test it.** A `commonTest` that seeds rows for user A, signs out, signs in as B, and asserts the push
payload contains nothing of A's.

---

### 2.2 — #18 · `extract-receipt` lets the caller choose the bucket for a service-role download

**Verified state.** [`supabase/functions/extract-receipt/index.ts:276-281`](supabase/functions/extract-receipt/index.ts):

```ts
const slash = payload.storagePath.indexOf("/");
const bucket = slash > 0 ? payload.storagePath.slice(0, slash) : "receipts";
const path   = slash > 0 ? payload.storagePath.slice(slash + 1) : payload.storagePath;
const { data, error } = await supabase.storage.from(bucket).download(path);
```

The caller names the bucket, and the download runs with service-role credentials. Today there may be
no private bucket worth stealing; the day there is, this is a read primitive for it. Receipts are
photographs of people's lives.

**Fix.** Hardcode `from("receipts")`. Treat the entire `storagePath` as an object path, stripping at
most a leading `receipts/`. Reject any path containing `..`. Three lines.

Update the request-shape comment at the top of the file (line ~15) in the same edit, since it currently
documents the bucket-prefixed form as legitimate input.

---

### 2.3 — #25 · Every sync failure is reported as "airplane mode"

**Ranked up from P2.** This is not just a bug, it is the reason every other bug on this list is
invisible to you in production. Fix it early and everything after it gets easier to diagnose.

**Verified state.** [`SyncEngine.kt:443-451`](code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/supabase/SyncEngine.kt)
— `runCatchingSync` catches `Exception` and maps it wholesale to
`AppError.Network(Kind.Unreachable)`. An RLS denial, a serialization drift, a unique-index wedge and a
dead radio are indistinguishable. `SyncManager` then swallows the result entirely.

**Fix.** Map Postgrest/HTTP-status exceptions to a new `AppError.Backend(status, code)`; keep
`Network.Unreachable` for genuine transport failure only. Add a `MutableStateFlow<SyncHealth>`
(consecutive-failure count + last error kind) observable from Settings or a debug screen. Keep the
`CancellationException` rethrow exactly as it is.

---

### 2.4 — #22 · Sync dies wholesale at ~800–1000 expenses, reported as offline

**Verified state.** [`SyncEngine.kt:436`](code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/supabase/SyncEngine.kt):

```kotlin
private suspend inline fun <reified T : Any> selectIn(table: String, column: String, values: List<String>): List<T> =
    client.from(table).select(Columns.ALL) { filter { isIn(column, values) } }.decodeList<T>()
```

One `id=in.(…)` GET carrying every expense id. Past the gateway URL limit every pull fails, for every
table, permanently — and thanks to #25 the user is told they are offline. It lands first on your most
engaged group, which is the worst possible sample.

**Fix.** Chunk `values` (100 is a reasonable batch) inside the helper and concatenate. Every call site
at `SyncEngine.kt:117-135` benefits with no change. **Do not** work around it with unfiltered
full-table selects — that breaks the moment RLS is tightened.

---

### 2.5 — #21 · A bill can total zero or negative

**Verified state.** `validate()` at
[`BillRepositoryImpl.kt:787`](code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/BillRepositoryImpl.kt)
takes only `(title, lines)` and checks per-line quantity/total. The `total()` helper at line 802 —
which subtracts `discountSubunits` — is never called from it. Both call sites (lines 153 and 227) pass
only title and items, so `extras` never reaches validation. An oversized discount yields a negative
expense, violating the always-positive entity invariant, hidden by the `> 0` outstanding filters.

**Fix.** Widen `validate` to take `extras`, compute `total(...)`, reject `<= 0` with a field error on
`discount`. Update both call sites.

**Do it as a vector, not just code** — see §5.

---

## 3. Tier 2 — real, not launch-blocking

### #16 · `jsonb_populate_record` writes explicit NULLs for absent columns

[`schema.sql:892`](supabase/schema.sql) (`commit_expense`) and [`schema.sql:1003`](supabase/schema.sql)
(`merge_expense`). Any column absent from the client payload is inserted as an explicit NULL, bypassing
its default — which contradicts the additive-migration rule the rest of the system leans on.

**Ranked up from P2** because Pro is actively adding columns; this is the failure mode that bites while
the schema is moving.

Two acceptable resolutions, pick one and update `AGENTS.md` in the same commit: an explicit column-list
insert wrapping post-baseline columns in `coalesce((p_expense->>'col')::type, <default>)`, or amend the
migration rule to require new expense columns be nullable.

### #19 · Unbounded client clocks permanently poison field merges

`v_now` is taken straight from the client payload at [`schema.sql:880`](supabase/schema.sql) and
[`schema.sql:986`](supabase/schema.sql) (`coalesce((p_expense->>'updated_at')::bigint, 0)`), and
`p_actor` plus every timestamp are client-supplied and unauthenticated by the RPC. A future-clocked or
crafted payload wins `updated_at = greatest(...)` forever — the field never accepts another edit.

**Fix.** Clamp `v_now` and every incoming `*_updated_at` to `min(value, server_now + 60s)` before use.
Note this needs the same treatment in both `commit_expense` and `merge_expense`.

### #17 · `editSettlement` can un-pay a settlement on crash

[`SettlementRepositoryImpl.kt:172`](code/shared/src/commonMain/kotlin/app/splitevenly/data/repository/SettlementRepositoryImpl.kt)
voids and re-records across two separate transactions. A crash between them leaves the payment voided
with no replacement. No `replaceSettlement` exists anywhere in the tree.

**Fix.** One `SettlementDao.replaceSettlement(...)` marked `@Transaction`, combining void + apply; call
it once. **Do not** reorder to write-then-void — that double-counts on crash instead of un-paying,
which is not an improvement.

### #20 · Even-split tip slices of non-claiming participants are dropped

[`BillSplit.kt:246-262`](code/shared/src/commonMain/kotlin/app/splitevenly/domain/expense/BillSplit.kt).
`tipShares` under `TipSplitMode.EVEN` allocates across `participants`, but `breakdown` is built with
`subtotals.associate { … }` — so anyone with no item subtotal gets a tip slice that is then silently
discarded, and the bill's shares sum below `amount_subunits`.

**Read the comment at lines 248-250 before changing anything.** It states that non-claimers
deliberately do not pick up their slice until they claim. There are two distinct cases tangled here —
*not yet claimed* (intentional, and the `UNCLAIMED_BUCKET` machinery handles it) and *a participant who
genuinely owes tip but no items* (the actual bug). Separate them first, then fix only the second.
`BillSplitTest` expectations will need updating.

### #23 · Four stranded conflict rows, and a dead table still in sync

Half of this finding is now **intentional**: `observeEditConflicts` returning empty is documented as
deliberate in `data/AGENTS.md:118` and asserted by
`EditConflictResolutionTest.observeEditConflicts_isEmpty_bilateralCardsRetired`. Do not "fix" that.

What is still open:
- Four unresolved `expense_edit_conflicts` rows on the live server, invisible in-app but synced to
  every device forever. Export them (they carry the full rejected payloads) for the owner's manual
  keep/apply decision, then mark resolved via SQL.
- `expense_edit_conflicts` is still in `SYNC_TABLES` ([`SyncManager.kt:164`](code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/supabase/SyncManager.kt))
  and in `SyncEngine` pull/push. Remove it once the concurrent `RowSyncState` work settles — check with
  the owner before doing this half, per the existing deferral note.

---

## 4. Tier 3 — cleanup

### #28 · Dead code and documentation that describes things which do not exist

Verified: `computeExpenseStatus` has **no definition anywhere** in the tree, yet is referenced as if it
exists by [`ExpenseEntity.kt:17`](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/entity/ExpenseEntity.kt)
and [`Expense.kt:11`](code/shared/src/commonMain/kotlin/app/splitevenly/domain/expense/Expense.kt).
`ExpenseDao.updateStatus` at [`ExpenseDao.kt:51`](code/shared/src/commonMain/kotlin/app/splitevenly/data/db/dao/ExpenseDao.kt)
has zero callers (the `updateStatus` hits in `ReceiptUploadManager` are `ReceiptUploadDao`'s, a
different method).

Delete the dead DAO method and both stale doc references. Also correct the item-share uniqueness
sentence — schema and entity key on `(item_id, user_id, portion_id)` and allow multiple slices, while
the docs still describe `(item_id, user_id)` "at most one shared slice per line."

### #29 · Defensive index drop

`item_shares_item_user_active_uidx` no longer appears in `schema.sql` at all, so a from-scratch apply
is already clean and the original finding overstates the risk. Still worth one defensive line —
`drop index if exists item_shares_item_user_active_uidx;` immediately before the current index
definition — so a legacy environment reapplying the file converges.

---

## 5. Convert two of these into permanent guards

**#20 and #21 are money invariants, not bugs.** Fixing the code alone leaves nothing that stops them
recurring. Both belong in `test-vectors/bill-split.json`, enforced forever by
`.github/workflows/money-vectors.yml`:

- *A bill's shares always sum to `amount_subunits`* — catches #20 and every future variant of it.
- *A bill's total is always positive* — catches #21, including discount/tax/tip combinations nobody
  thought to enumerate.

`test-vectors/README.md` documents the vector format and the record command. This is the highest-value
half hour in this document: a markdown checkbox decays, a vector does not.

---

## 6. Suggested order

1. **#18** — three lines, pure win, no design questions.
2. **#25** — makes everything after it diagnosable.
3. **#24** — the big one; needs the dirty-check design decided first.
4. **#22** — mechanical once #25 is in.
5. **#21 + #20** — together, as vectors first then code.
6. **#16, #19** — schema pair, one commit each.
7. **#17**, then **#23**, then **#28 / #29** as cleanup.
8. Update `ARCH_SECURITY_REVIEW.md` and tick #26/#27 as closed.

#24, #20 and #23's second half each have a real design decision in them. Per `AGENTS.md` §7, surface
the decision rather than picking silently — especially #24's dirty-check behaviour, which is
user-visible.

---

## 7. Build, verify, commit

`gradlew` lives in `code/`, not the repo root. JDK 17. **Android-green is not green** — several APIs
resolve on JVM and fail on Kotlin/Native, which is exactly the trap in #24's fix.

```bash
cd /Users/DaChelimo/Documents/TechWork/ShareCost/code
./gradlew :shared:compileAndroidMain
./gradlew :shared:compileKotlinIosSimulatorArm64
./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test
```

Manual verification defaults to the iOS simulator:

```bash
code/iosApp/run-ios-sim.sh
```

Commit rules from `AGENTS.md` §6 apply in full: one coherent fix per commit, both platforms green
before each, conventional message with a scope, the Claude `Co-Authored-By:` trailer, and never on
`main`. Branch from wherever this work starts; do not pile these onto `feat/pro-passes` unless the
owner says so, since that branch is already 168 files ahead.

Tick each finding `[x]` in `ARCH_SECURITY_REVIEW.md` **only once the fix is implemented and verified**
— that file's own rule, and it is the thing that lets a future session trust "review the diff only."
