# Findings — `data/remote/` + `data/auth/`

**Snapshot reviewed:** `fix/security-handoff` @ `7a2740b`, **plus the uncommitted working tree**.
`SupabaseAuthSession.kt` (md5 `9496c16b`) and `SyncEngine.kt` (md5 `0d3dd1ad`) both changed *during* this
review — a parallel session is landing finding #24's fix (`SignOutWipeDao.kt`, `signOut(discardUnsynced)`,
`countPendingLocalWrites()`) and the tree does not currently compile (the two `auth.signOut()` call sites in
`ui/navigation/WiredScreens.kt:379,472` still use the old non-suspend signature). That is work in progress,
not a finding. Every line number below was re-checked against the tree at the time of writing; treat them as
strong hints, not guarantees.

---

## Summary

I read all 22 files in scope (20 under `data/remote/**`, 2 under `data/auth/**`, plus the single
`androidMain` actual that belongs to them, `AuthDeeplink.android.kt` — the other two files the brief counted
are `data/db/DatabaseBuilder.*`, which are out of scope). I ran the full-row-upsert column check
exhaustively against the **live** Supabase project rather than only `supabase/schema.sql`: 22 tables, 289
entity-field/column pairs, checked for presence *and* nullability agreement in both directions. It comes
back completely clean — no client field lacks a server column, no server-nullable column maps to a
non-nullable Kotlin field (which would blow up `decodeList` and take the whole pull down), and no NOT NULL
column maps to a nullable Kotlin field. The whole-table-outage risk in `AGENTS.md` §4.4 is currently not
present anywhere. `keepNewer`'s lost-write guard also survived every attack I could construct that did not
involve a wrong device clock; the pull-clobbers-a-local-edit vector is genuinely closed for all 22 tables.

**The one thing I would fix first is S1.** The #24 fix currently landing wipes Room inside `signOut()`, but
the wipe is not fenced against the three sync coroutines that run on `SupabaseAuthSession`'s
process-lifetime scope and do **not** gate on `currentUserId` — `init`'s restore-pull, `mirrorCurrentUser`'s
`syncNow`, and `PushController`'s pull-on-delivered-message. The `syncMutex` serializes those against the
sign-out push but does not order them against the wipe, and the wipe does not hold the mutex. A pull that
was already queued behind the sign-out push therefore runs *after* `wipeSignedOutAccount()` and re-lands the
departing account's groups, expenses, members, profile rows and `group_passes` into the cache the wipe just
emptied — with account A's session still valid, because `client.auth.signOut()` is the last statement in the
method. That reinstates exactly the cross-account exposure #24 exists to close, and it will look like the
fix works in every manual test where no pull happens to be in flight.

**Counts:** P0 × 1, P1 × 2, P2 × 5, P3 × 1 (9 total).
**Unrecoverable once it happens:** S1 (cross-account exposure), S2 (destroyed local writes), S4 (a poisoned
field stops accepting edits).

---

### S1. Sign-out's cache wipe is not fenced against the three sync coroutines that never gate on `currentUserId`, so a queued pull re-lands the departing account's data after the wipe

- **Severity:** P0 (leak across accounts) — **extends #24**, whose fix as written does not close this
- **Verdict:** CONFIRMED (the structure is certain; the outcome requires a pull in flight or queued)
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/auth/SupabaseAuthSession.kt:276-286`
  (the wipe, and the comment claiming the gate prevents this), with the three ungated launchers at
  `SupabaseAuthSession.kt:107`, `SupabaseAuthSession.kt:383`, and
  `code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/supabase/PushController.kt:53`
- **Property broken:** Identity and session ("no path can push data authored under session A using session
  B's credentials … look for others"); Deletion safety / Rule 9 by inversion (state that should be gone
  comes back)
- **Failing sequence:**
  1. Account A is signed in. A push notification for one of A's groups is delivered. `PushController.kt:53`
     reads `currentUserId.value` (still A) and launches `syncEngine.pull(A)` **on `SupabaseAuthSession`'s
     own scope**, not on the gate's `supervisorScope`.
  2. That pull blocks on `syncMutex` (`SyncEngine.kt:132`).
  3. The user taps Sign out. `signOut()` runs `engine.push(A)` (`SupabaseAuthSession.kt:268`), which takes
     the mutex, finishes, and releases it.
  4. The queued `pull(A)` immediately acquires the mutex and starts fetching A's groups, members, expenses,
     `users` rows and `group_passes`. A's Supabase session is still fully valid — `client.auth.signOut()` is
     not called until line 286.
  5. `signOut()` continues on its own coroutine: `_currentUserId.value = null` (line 278), then
     `wipeSignedOutAccount()` (line 282). The wipe does **not** hold `syncMutex`, and setting
     `_currentUserId` to null cancels only `SyncManager`'s children — nothing cancels the pull.
  6. The pull completes and writes A's rows back into the just-emptied Room, `stampSynced`-ing them clean.
  7. Account B signs in on the same device and sees A's groups, A's expenses, A's members' payment handles,
     and inherits A's `group_passes` Pro entitlement.
- **Consequence:** Account B reads account A's financial data and identity on a device A believes they wiped.
  Unrecoverable — the exposure has already happened by the time anyone notices. Rows are stamped synced, so B
  will not *push* them back up (that half of #24 does hold), but the on-device read is the leak.
  `secureStorage.clear()` (line 285) is not undone by this, so the bill-link bearer tokens do stay dead.
- **Why it survives refutation:** The KDoc at lines 276-277 asserts the opposite — "Stops the sync loops
  (they gate on this) before anything is deleted, so no in-flight pull can re-land the rows we are about to
  drop." That is true only of `SyncManager`, whose three loops are children of `collectLatest(gatedUser(...))`
  (`SyncManager.kt:64-115`) and are genuinely cancelled. It is false for the three launches above: all three
  use the ctor scope `CoroutineScope(SupervisorJob() + Dispatchers.Default)` (`SupabaseAuthSession.kt:80`),
  which is created per singleton and never cancelled anywhere in the tree. I checked whether `syncMutex`
  saves it: it does not — it guarantees mutual exclusion between `push` and `pull`, but the wipe is outside
  it, so the mutex actively *schedules* the queued pull into the wipe window rather than preventing it.
  I also checked whether the pull would fail for lack of a session: it would not, because `client.auth
  .signOut()` is deliberately last. Finally I checked the new `iosTest/…/SignOutWipeTest.kt` that ships with
  the in-flight fix: its four tests assert the DAO clears every table `push` reads from, is idempotent, and
  drops the Pro mirror and the sync bookkeeping. All correct, and none of them involves a concurrent pull —
  they call `wipeSignedOutAccount()` directly, so nothing in the test suite would fail if this race fired.
- **Suggested fix:** Two parts, and the second is the load-bearing one. (a) Give `SyncEngine` a
  "sync is closed" latch that `signOut` sets before the wipe and that `pull`/`push` check *after* acquiring
  the mutex, returning immediately; or run the wipe inside `syncMutex.withLock` and have every entry point
  re-read the current user id under the lock. (b) Make the three ungated launches cancellable as a unit — a
  child `Job` of the auth scope that `signOut` cancels and joins before wiping. **Needs an owner decision**
  on whether `PushController`'s deliberately-ungated background pull (`data/AGENTS.md`: "PushController
  stays ungated — background FCM → pull is intended") should be exempted from the gate but *not* from the
  sign-out fence; I think it must be fenced, since the intent is "pull while backgrounded", not "pull for an
  account that just left".

---

### S2. `push()` returning `Ok` does not mean every local row reached the server, and the pending-writes check fails open — so sign-out can wipe unsynced user data while believing the cache is a pure mirror

- **Severity:** P1 (silent local data loss) — **extends #24**
- **Verdict:** CONFIRMED (both code properties); the loss needs one of the two named preconditions
- **Where:** `SupabaseAuthSession.kt:267-272` (the precondition), `SupabaseAuthSession.kt:269`
  (`.getOrDefault(0)`), `SyncEngine.kt:485-493` (`if (adopted)`)
- **Property broken:** No lost writes ("the dirty-tracking state machine cannot mark a row clean before the
  server has durably accepted it" — here, the inverse: a still-dirty row is treated as clean by the caller)
- **Failing sequence:** Two independent routes to the same wipe.
  - **(a) `Ok` with a row still dirty.** `pushExpenses` (`SyncEngine.kt:437`) applies #8's in-flight-edit
    guard: if the local expense's `row_version` changed between the pre-RPC snapshot and adoption,
    `adopted` is false, the sync-state stamp at `SyncEngine.kt:486` is skipped, and the expense stays dirty
    *on purpose*. The loop then continues normally, `step` sees no exception, `firstError` stays null, and
    `push()` returns `AppResult.Ok`. Sign-out reads that `Ok` as "the cache is a pure mirror" (KDoc line 253)
    and wipes. Precondition: a concurrent local expense write landing during sign-out's push.
  - **(b) The safety check fails open.** `runCatching { engine.countPendingLocalWrites() }.getOrDefault(0)`
    — if the count itself throws, it defaults to **0, meaning "nothing pending, safe to wipe"**. The one
    read that stands between a failed push and destroying the user's only copy of their writes defaults to
    the permissive answer.
- **Consequence:** Expenses, settlements or bill claims that exist only on this device are deleted with no
  tombstone, no warning, and no `SignOutOutcome.UnsyncedChanges` dialog. Unrecoverable — this is precisely
  the Tricount failure mode `data/AGENTS.md`'s prod gate is written against.
- **Why it survives refutation:** I checked whether the `UnsyncedChanges` branch covers route (a): it does
  not, because it is reached only when `push()` returns `Err`. I checked whether `countPendingLocalWrites()`
  itself would catch the dirty expense: it would — its expense check (`SyncEngine.kt:394`) is exactly the
  right comparison — but it is never called on the `Ok` path. I checked whether `push()`'s per-table `step`
  isolation (`SyncEngine.kt:339-347`) already forces `Err` here: it does not, because nothing throws. And I
  checked the fix's own new test: `iosTest/…/SignOutWipeTest.kt` exercises the wipe DAO directly and never
  goes through `signOut`, so neither route is covered by it.
- **Suggested fix:** Call `countPendingLocalWrites()` **unconditionally** after the push, not only on `Err`,
  and gate the wipe on it being zero; change `.getOrDefault(0)` to fail closed (any non-zero sentinel), so a
  DB error blocks the wipe rather than authorising it. The KDoc's step 1 should say "a successful push *and*
  a zero pending count means the cache is a mirror".

---

### S3. `SyncHealth` keeps one counter for two independent channels, so a permanently failing push is erased by the next successful pull

- **Severity:** P1 (the diagnostic every other sync defect depends on does not work)
- **Verdict:** CONFIRMED
- **Where:** `SyncEngine.kt:122-129` (`recordHealth` called from both `push` and `pull`),
  `code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/supabase/SyncHealth.kt:23`
  (`recordSuccess` returns a fresh `SyncHealth`, zeroing `consecutiveFailures` and `lastError`),
  `SyncManager.kt:77` (push-only loop) vs `SyncManager.kt:93` (pull-only loop)
- **Property broken:** Failure reporting (a permanent failure must be distinguishable from a transient one)
- **Failing sequence:**
  1. A local `item_claims` row wedges on a unique-index violation (finding #5's shape), or push starts
     getting 403s once RLS is tightened. Every `push()` now fails, forever.
  2. `SyncManager`'s push loop (`SyncManager.kt:77`) calls `syncEngine.push(userId)` on its own — not
     `syncNow` — so it records a failure: `consecutiveFailures = 1`, `lastError = Backend(...)`.
  3. Any doorbell event fires the pull loop (`SyncManager.kt:93`), which calls `syncEngine.pull(userId)` on
     its own. The pull succeeds (pulls are unaffected by a wedged push).
  4. `recordSuccess` replaces the whole value: `consecutiveFailures = 0`, `lastError = null`,
     `looksOffline = false`.
  5. Repeat forever. `consecutiveFailures` never exceeds 1 and is usually 0. The health flow reports a
     healthy sync while not one local write has reached the server since step 1.
- **Consequence:** The user's edits stop syncing permanently and every observable signal says everything is
  fine. Recoverable once noticed, but the whole point of `SyncHealth` — per `SECURITY_FIX_HANDOFF.md` §2.3,
  "the reason every other bug on this list is invisible to you in production" — is defeated in the exact
  scenario it was built for, because push failures and pull successes are the *normal* interleaving.
- **Why it survives refutation:** I checked `syncNow` (`SyncEngine.kt:502`): it short-circuits on a failed
  push and never pulls, so the fallback tick alone would not mask anything. The masking comes from
  `SyncManager`'s two *separate* loops, which call `push` and `pull` independently — that is the design, not
  an accident. I checked for a consumer that compensates: `grep` over `code/shared/src` finds **zero**
  readers of `SyncEngine.health`, `looksOffline`, or `consecutiveFailures` outside `SyncHealth.kt` itself,
  and no test covers the interleaving (`SyncEngineTest` pins `classifySyncError` and `keepNewer` only). So
  nothing downstream corrects for it, and nothing would catch a regression.
- **Suggested fix:** Track push and pull health separately (two `SyncHealth` values, or a `channel` field on
  the record), and let `recordSuccess` clear only its own channel. Keep the surfaced "you're offline" copy
  gated on both being `Network`. While in there: `recordSuccess` also silently drops `lastError` even for a
  reader that wants "last thing that went wrong" — worth keeping.

---

### S4. Client-side last-write-wins trusts the writing device's wall clock with no bound, so one badly-clocked device can pin a row on every other device permanently

- **Severity:** P2 (silent divergence; unrecoverable per affected row until someone out-clocks it)
- **Verdict:** CONFIRMED — **extends #19**, which covers the same unbounded clock inside `merge_expense`
  server-side but not the client's own LWW
- **Where:** `SyncEngine.kt:584-591` (`keepNewer`), applied by `land()` (`SyncEngine.kt:306-320`) to every
  synced table carrying `updated_at`
- **Property broken:** Convergence (two devices applying the same operations reach the same state)
- **Failing sequence:**
  1. Device A's clock is wrong — set forward manually, or a bad RTC after a battery-dead boot. A is a real
     scenario on Android; the app never reads a server clock.
  2. A edits the group name. Room stamps `groups.updated_at = now + 1 year`. The row pushes.
  3. Device B renames the group correctly, stamping `now`. B's push lands (push is blind — Rule 5 gap (a)),
     so the server row now carries B's name with B's timestamp.
  4. B pulls. `keepNewer` compares B's local `now` against… nothing yet, so B keeps its own.
  5. Device C pulls. It has A's `now + 1 year` row locally from step 2. `keepNewer` drops B's incoming row
     (`localTs <= updatedAt` is false). C keeps A's name.
  6. Every subsequent honest edit by anyone is dropped on C the same way, for a year.
- **Consequence:** A field silently stops accepting edits on any device that saw the future-stamped row, and
  the group's members disagree about it with no conflict surfaced anywhere. Recoverable only by an edit
  timestamped past the poisoned value, i.e. not by any honest device.
- **Why it survives refutation:** I checked whether anything clamps the client clock: `nowEpochMillis()` is
  read raw at every write site, and neither `SyncEngine` nor `SyncHealth` validates it. I checked whether
  #19's proposed server-side clamp (`min(value, server_now + 60s)` in `merge_expense`) would cover it: it
  would not — `keepNewer` runs entirely on the client, over rows fetched by plain `select`, and `expenses`
  is the *only* table that goes through `merge_expense` at all. The other 21 tables never see the RPC.
- **Suggested fix:** Have the client learn a clock offset (the `Date` response header on any Postgrest call
  is free and sufficient), clamp its own `updated_at` stamps to `min(local, server + skew tolerance)`, and
  apply the same clamp to incoming rows inside `keepNewer` before comparing. Landing it alongside #19's
  server clamp keeps the two halves consistent. **Needs an owner decision** on tolerance and on what to do
  with rows already poisoned.

---

### S5. A member who has left a group keeps pulling and pushing that group's entire ledger, forever

- **Severity:** P2
- **Verdict:** CONFIRMED (5 live rows on the production project meet the precondition today)
- **Where:** `SyncEngine.kt:135-138` — the memberships query filters `user_id` only, with no `status` filter
- **Property broken:** Identity and session (the device holds and continues to fetch data for an account
  relationship that has ended)
- **Failing sequence:**
  1. Sam is a member of "Iceland". Sam leaves; `members.status` becomes `'LEFT'` (soft-leave by design —
     the row must survive so their historical shares still resolve).
  2. Sam's next pull runs `select … from members where user_id = sam` (`SyncEngine.kt:136`). The `LEFT` row
     comes back, so `groupIds` (`SyncEngine.kt:138`) still contains "Iceland".
  3. Every subsequent pull hydrates Iceland's `expenses`, `shares`, `settlements`, `comments`, `receipts`
     and `users` rows — including every expense added *after* Sam left — onto Sam's device, and `push()`
     keeps offering Iceland's rows back up.
- **Consequence:** An ex-member's phone keeps a live, growing copy of a group's financial history and the
  other members' payment handles. Not recoverable by leaving again. I verified the precondition is live
  rather than theoretical: `select m.status, u.is_placeholder, count(*) … where m.status='LEFT'` on the
  production project returns 5 rows for real (non-placeholder) users and 4 for placeholders.
- **Why it survives refutation:** I checked for a downstream filter: the UI does hide left groups, but the
  rows are on the device regardless, which is the leak. I checked whether this is the known permissive-RLS
  item: it is not the same defect — permissive RLS is what makes the *server* answer, but the client is the
  thing asking for a group it has left, and once RLS is membership-scoped this query starts silently
  returning nothing rather than being correct by design. The client-side filter is missing either way.
- **Suggested fix:** Filter the memberships query to `status = 'ACTIVE'` for the purpose of computing
  `groupIds` (keep landing the full membership set, including `LEFT` rows, so historical names still
  resolve). Separately worth deciding whether leaving a group should drop its cached rows locally — that is
  the same design question as S1/S2's wipe and should be answered once.

---

### S6. The device's push token is never unregistered on sign-out, so account A's notifications keep arriving on a device where B is now signed in

- **Severity:** P2 (cross-account leak, latent — push delivery is inert today)
- **Verdict:** CONFIRMED
- **Where:** `PushController.kt:64-70` (the only write to `device_tokens` in the client); no delete anywhere
  in `code/shared/src` — the sole delete in the system is `delete_my_account()` at `supabase/schema.sql:822`
- **Property broken:** Identity and session
- **Failing sequence:**
  1. A signs in. `registerCurrentToken` upserts `device_tokens{id = <fcm token>, user_id = A}`.
  2. A signs out. `signOut()` (`SupabaseAuthSession.kt:264-288`) clears Room, `SecureStorage` and the
     Supabase session. The server row still says this token belongs to A.
  3. Someone adds an expense to one of A's groups. The sender resolves A's `device_tokens` rows and pushes
     to this handset.
  4. The notification renders on the lock screen of a device A no longer has a session on — and, if B has
     signed in but the token has not re-registered yet (registration is driven by
     `currentUserId.collect`, so it needs B's sign-in to complete), on B's device.
- **Consequence:** A's group activity (title, amount, group name) is displayed to whoever holds the device.
  It self-heals once B signs in and the token re-upserts under B, but the window is the whole sign-out
  interval, and any push delivered in it also triggers `PushController.kt:53`'s pull — which is S1's vector.
- **Why it survives refutation:** I checked `SignOutWipeDao`: `device_tokens` has no Room table to wipe, so
  it is not in the list and could not be. I checked `data/AGENTS.md`'s note that `device_tokens` is
  "intentionally hard-deleted, ephemeral non-financial data" — that sanctions deleting the row, it does not
  say anyone does it on sign-out, and nothing does. Mitigating and stated honestly: `platform/AGENTS.md`
  records that push delivery is currently inert (no `FCM_SERVICE_ACCOUNT`, nothing invokes `push-notify`),
  so this cannot fire today. It becomes live the moment that secret is configured.
- **Suggested fix:** Delete this device's `device_tokens` row in `signOut()` (before `client.auth.signOut()`,
  while the session can still authenticate the delete), best-effort like the other steps there.

---

### S7. `ReceiptOcrHttp` and `GroupExportHttp` catch `Throwable`, so a cancelled scan finishes by writing a failure to the screen and firing a false `scan_failed` event

- **Severity:** P2 (bounded; user-visible + telemetry)
- **Verdict:** CONFIRMED
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/supabase/ReceiptOcrHttp.kt:105`
  and `code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/supabase/GroupExportHttp.kt:51`
- **Property broken:** Failure reporting ("No `catch (e: Exception)` that swallows `CancellationException`
  — that breaks structured concurrency and turns a cancelled scope into a silent success")
- **Failing sequence:**
  1. The user starts a receipt scan. `BillRoutes.kt:158` stores the job in `scanJob`; the suspension point
     is `ocr.extract(...)` at `BillRoutes.kt:160`.
  2. The user taps Cancel. `BillRoutes.kt:279` calls `scanJob?.cancel()` and then synchronously sets
     `scanState = ScanUiState.Idle`.
  3. `extract`'s in-flight HTTP call throws `CancellationException`. `catch (t: Throwable)` at
     `ReceiptOcrHttp.kt:105` catches it and returns `ScanOutcome.Failed(t.message)`.
  4. The cancelled coroutine is now running ordinary non-suspending code, so it continues: it computes
     `durationMs`, fires `AnalyticsEvents.SCAN_FAILED` with `kind = Error`, and assigns
     `scanState = ScanUiState.Failed(ScanErrorKind.Error)`.
  5. The user, who just cancelled, watches the sheet flip from Idle to an error.
- **Consequence:** A cancelled scan is presented as a failure, and every cancellation is counted as a scan
  error in the funnel — which quietly corrupts the metric the OCR escalation work is tuned against.
  Recoverable per-instance (dismiss), but the telemetry damage is cumulative. `LedgerRoutes.kt:180,297` is
  the same pair of sites for the non-bill scan path. `GroupExportHttp:51` has the identical shape.
- **Why it survives refutation:** I checked whether the codebase treats this as acceptable: it does not —
  `FrankfurterFxFetcher.kt:32-33` and `:46-47` carry an explicit `catch (e: CancellationException) { throw e
  } // never swallow structured-concurrency cancellation`, and `SyncEngine.runCatchingSync`
  (`SyncEngine.kt:523-531`) and `ReceiptStorage.runCatchingStorage` (`ReceiptStorage.kt:47-54`) both rethrow
  it too. These two files are the outliers. I checked whether cancellation is actually reachable: it is —
  `BillRoutes.kt:279` and `LedgerRoutes.kt:297` cancel `scanJob` explicitly, and `data/AGENTS.md:307-309`
  documents cancelling a scan as a supported flow. I checked whether the staging path is affected: it is
  not, and deliberately so (staging runs on a separate job, per the same AGENTS.md note) — so the receipt
  itself is safe; only the reported outcome is wrong.
- **Suggested fix:** Add `catch (e: CancellationException) { throw e }` ahead of the `Throwable` catch in
  both files, matching `FrankfurterFxFetcher`. One line each.

---

### S8. Pass activation and subscriber sync collapse every outcome into `false`, so a permanent server refusal is shown to a paying user as "payment went through, tap again" — forever

- **Severity:** P2 (money; recoverable server-side)
- **Verdict:** CONFIRMED — **extends #25** into the RevenueCat path, which §3 of the brief names explicitly
- **Where:** `code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/revenuecat/PassActivationGateway.kt:42-50`
  (`activate`) and `:72-80` (`sync`) — both `runCatching { … .status.isSuccess() }.getOrDefault(false)`
- **Property broken:** Failure reporting (permanent vs transient)
- **Failing sequence:**
  1. The user buys a group pass. `RevenueCatBilling.buyPass` returns `Bought(storeTxnId)`; the money has
     moved.
  2. `ProPurchaseCoordinator.buyPass` parks the txn id in `SecureStorage` and calls
     `activation.activate(groupId, txnId)`.
  3. The `activate-pass` edge function rejects the transaction permanently — a 403 because RevenueCat's
     server-side verification fails, a 400 because the product id is not one it recognises, or a 500
     because the function is misconfigured after a deploy.
  4. `activate` returns `false`. It returns exactly the same `false` for airplane mode.
  5. `ProRoutes.kt:414` sets `PassSheetPhase.Charged`, whose contract (per `PassPurchaseResult`'s own KDoc)
     is "Charged, but activation did not land … the retry costs nothing". The user taps Retry
     (`ProRoutes.kt:439`), which calls `retryPendingActivation()` → the same permanent rejection → `false`
     → `Charged` again. `retryPendingActivation()` also runs on every launch and every paywall open
     (`ProRoutes.kt:284`), so the state is sticky.
- **Consequence:** A paying customer is told, repeatedly and indefinitely, that a retry will fix something a
  retry cannot fix, with no route to support and nothing on screen distinguishing it from a network problem.
  Recoverable in the money sense — the RevenueCat webhook plus `pro_orphan_purchases` is the server-side
  backstop, and `ProConfig.GROUP_ID_ATTRIBUTE` is set before `purchase()` precisely so this is attributable
  — but not recoverable in the app, and the user has no way to know that.
- **Why it survives refutation:** I checked whether the UI already distinguishes the cases: it does not —
  `ProRoutes.kt:394-431` has exactly one non-cancel failure branch fed by the boolean, and
  `PURCHASE_ACTIVATION_FAILED` is tagged `reason = "activation"` for every one of them, so the analytics
  cannot separate them either. I checked whether the money-safety design compensates: it does, and that is
  why this is P2 rather than P1 — the charge is never lost, only the explanation. I checked whether
  `getOrDefault(false)` also swallows `CancellationException`: it does, same class as S7, though the
  consequence there is only a spurious `Charged`.
- **Suggested fix:** Return the same typed shape the rest of the codebase now uses — reuse
  `SyncEngine.classifySyncError`'s split (4xx that is not 408/429 ⇒ permanent, transport/timeout/5xx ⇒
  transient) and give `PassPurchaseResult` a third state for "this will not resolve by retrying, here is how
  to reach support". `HttpSubscriberSyncGateway.sync()` wants the same treatment for the same reason.

---

### S9. "The synced table list" is maintained by hand in four places, and two of them already disagree

- **Severity:** P3 (cleanup, but it is the #26 class recurring)
- **Verdict:** CONFIRMED (no live impact today — see refutation)
- **Where:** `SyncManager.kt:162-167` (`SYNC_TABLES`), `SyncEngine.kt:348-369` (`push`),
  `SyncEngine.kt:386-419` (`countPendingLocalWrites`), `data/db/dao/SignOutWipeDao.kt:35-69` (out of scope
  to fix, listed for completeness)
- **Property broken:** No lost writes (a table missing from the invalidation list never gets a prompt push)
- **Failing sequence:** `push()` sends 19 tables. `SYNC_TABLES` — whose comment at `SyncManager.kt:161`
  claims it "mirrors [SyncEngine.push]" — lists 18, and omits **`expense_blocked_users`** and
  **`pending_item_edits`**. A local write to either therefore does not trigger the 1.2s debounced push
  (`SyncManager.kt:75-77`); it waits for the 60s fallback tick, and if the app is backgrounded inside the
  5s grace, for the next foreground `syncNow`. `SYNC_TABLES` also lists `shares`, which `push()` never sends
  directly (shares ride with `merge_expense`), so every `materializeShares` run triggers a full push cycle
  that carries no share data.
- **Consequence:** Today, none — which is why this is P3 and not higher. Tomorrow, a table added to `push()`
  and forgotten here is an unbounded sync delay that nothing tests.
- **Why it survives refutation:** I checked both omitted tables for live impact and found none, which is the
  honest result. `expense_blocked_users` has **no writer anywhere in the app** — `grep` for
  `ExpenseBlockedUserDao`/`blockedUser` outside `data/db` returns only `SyncEngine` and `SignOutWipeDao`, so
  the chat-moderation write path is not wired yet. `pending_item_edits` is written by
  `BillPendingEdits.undo` (`BillPendingEdits.kt:80`), but `restore()` writes `expense_items` and bumps the
  expense in the same call, and both of those *are* in `SYNC_TABLES`, so the push fires anyway; the two
  paths that write the log row alone are `markUndone` returning 0 (which wrote nothing) and the `itemId ==
  null` branch its own comment says is unreachable. I also verified `countPendingLocalWrites` against
  `push`: those two match exactly, table for table. So the disagreement is `SYNC_TABLES` alone.
- **Suggested fix:** Derive one list. A single `internal val SYNCED_TABLES` in `SyncEngine`'s companion,
  consumed by `push`, `countPendingLocalWrites` and `SYNC_TABLES`, turns three hand-maintained copies into
  one and makes the fourth (`SignOutWipeDao`) checkable by a test that asserts every name in the list has a
  clear. `countPendingLocalWrites`'s own KDoc already anticipates the failure ("A table added to `push` must
  be added here too, or its unsynced rows vanish without a warning") — that warning wants to be a compiler
  or a test, not a comment.

---

## Property verdicts (§3)

Every property has a verdict. Nothing below was skipped.

| Property | Verdict |
| --- | --- |
| **No lost writes** — offline write survives backgrounding, process death, failed push | **CLEAN.** Local writes land in Room first, `pushDirty` (`SyncEngine.kt:418-424`) stamps the fingerprint *after* a successful upsert, and per-table `step` isolation (`SyncEngine.kt:339-347`) keeps one table's failure from stranding the rest. A failed row stays dirty and retries. |
| **No lost writes** — dirty-tracking can't mark a row clean before the server accepted it | **CLEAN inside `SyncEngine`; broken at the sign-out caller → S2.** I specifically chased `pushExpenses`' `canonical == null` branch (`SyncEngine.kt:493-497`), which stamps sync state without server confirmation: **refuted** — `merge_expense` (`supabase/schema.sql:975-1119`) returns a non-null `expense` on all four of its return paths (created / deleted / merged / superseded), and any RPC error throws rather than decoding, so the branch is unreachable defensive code as its comment says. |
| **No lost writes** — partial push leaves rows clean with no retry | **CLEAN.** `pushDirty` stamps only the rows it upserted, from the same snapshot it filtered; a row mutated after the snapshot keeps a different hash and stays dirty. |
| **Convergence** — two devices, different orders, same state | **BROKEN → S4** (clock-driven, per-row). Otherwise the zone-aware `merge_expense` path is sound for expenses; the other tables are whole-row LWW by design. |
| **Convergence** — a pull mid-push clobbering an in-flight local write | **CLEAN.** `keepNewer` (`SyncEngine.kt:584-591`) covers every table carrying `updated_at`; `expenses` additionally gets the dirty-version guard (`SyncEngine.kt:212-222`); `conflicts`/`expense_edit_conflicts` get the resolution-monotonic filter (`SyncEngine.kt:262-275`); a bill's shares are excluded and re-derived (`SyncEngine.kt:226-230, 287-289`). I attacked this hard and could not construct a skew-free sequence that loses a write: for a pulled row to win, the server row must be genuinely newer, which is LWW behaving as specified. |
| **Convergence** — full-row upsert column check | **CLEAN, verified exhaustively against the live DB.** 22 tables / 289 field-column pairs. Tables verified: `users`, `groups`, `members`, `expenses`, `shares`, `settlements`, `settlement_allocations`, `conflicts`, `expense_edit_conflicts`, `comments`, `expense_blocked_users`, `receipts`, `categories`, `expense_history`, `expense_items`, `item_claims`, `item_shares`, `bill_participants`, `pending_item_edits`, `placeholder_claim_answers`, `group_passes`, `user_subscriptions`. No client field lacks a server column; no server-nullable column maps to a non-nullable Kotlin field (which would throw in `decodeList` and take the entire pull down, not just that table); no NOT NULL column maps to a nullable field. Two intentional server-only columns confirmed harmless: `shares.remaining_subunits` (vestigial, nullable, defaulted) and `users.deleted_at`/`users.deletion_requested_at` (server-written, dropped by `ignoreUnknownKeys`; both nullable, so the omission cannot break an upsert). See "Method" below. |
| **Deletion safety** — soft-delete only, no hard delete / `clearAllTables()` / `TRUNCATE` reachable | **CLEAN in `data/remote/`.** `grep` over `code/shared/src` finds `clearAllTables`/`TRUNCATE` only inside `data/AGENTS.md`'s prohibitions. `SupabaseReceiptStorage.delete` (`ReceiptStorage.kt:39-45`) is a single-object delete, which Rule 10 sanctions; there is no bucket- or prefix-wide delete. `StubAuthSession.requestAccountDeletion` (`StubAuthSession.kt:92`) does hard-delete the local `users` row via `UserDao.delete` — that is the already-tracked `UserDao.kt:29` item in `data/AGENTS.md` Rule 1, not re-reported. `SignOutWipeDao`'s 28 `DELETE`s are a cache wipe in `data/db` (out of scope); its *caller* is S1/S2. |
| **Deletion safety** — tombstones actually reach the server, failure path included | **CLEAN.** A tombstone is an ordinary dirty row: it retries on every cycle, `step` isolation stops a sibling table wedging it, and `keepNewer` stops a stale ACTIVE server row un-deleting it (pinned by `SyncEngineTest.claimedPlaceholder_isNotResurrected_byStaleActiveServerRow`). The one way a tombstone dies before reaching the server is S2's wipe. |
| **Deletion safety** — Rule 9, never wipe local state ahead of the server | **BROKEN → S2** (the wipe's precondition is unsound). `requestAccountDeletion` itself is correct and deliberately does not reuse the sign-out path — I read the load-bearing comment block at `SupabaseAuthSession.kt:291-297` and it holds: local state survives, and the failure branch wipes nothing. |
| **Identity/session** — no path pushes A's data under B's credentials | **BROKEN → S1** (and S6 for the push-token half). The Room half of #24 is addressed by the in-flight fix; the fence around it is not. |
| **Identity/session** — session restore on cold start can't transiently expose another account's rows | **CLEAN.** `mirrorCurrentUser` (`SyncEngine`-adjacent, `SupabaseAuthSession.kt:347-385`) selects the server `users` row before seeding a default, so P0 #4's profile-wipe is closed; `_currentUserId` is seeded from `client.auth.currentUserOrNull()` at construction, so there is no window where the id is wrong. |
| **Identity/session** — auth state observed, not polled | **CLEAN.** `client.auth.sessionStatus.collect` (`SupabaseAuthSession.kt:101-105`) is the single source of truth; nothing polls. |
| **Failure reporting** — permanent vs transient | **BROKEN → S8** (RevenueCat path) and **S3** (health masking). `SyncEngine.classifySyncError` (`SyncEngine.kt:563-575`) itself is good and tested. I also checked the upload path the brief names: `ReceiptUploadManager` is in `data/upload/`, out of scope, and I did not review it. |
| **Failure reporting** — no `catch` swallowing `CancellationException` | **BROKEN → S7** (2 sites). Everything else in scope rethrows correctly. The `runCatching` uses in `SupabaseAuthSession` (11 sites) and `SyncManager.kt:73,84,101` also technically swallow it; I chased each and none produces a wrong outcome — `SyncManager`'s two wrap `collect` calls whose job is already being cancelled by the gate, and the auth ones sit at the top of user-initiated one-shot calls where the caller is the thing being cancelled. Not reported, but they are the same smell and worth a sweep if S7 is fixed. |
| **Concurrency** — work that must survive navigation isn't tied to a composable scope | **CLEAN for `data/remote`/`data/auth`.** `SyncManager` binds to the auth-session scope, not a composable's. (The known counter-example, #15's `onSetServings`, is in `ui/navigation/`.) |
| **Concurrency** — shared mutable state across sync loop / doorbell / foreground gate | **BROKEN → S3** (`_health` is the shared mutable state, and it is the one that is wrong). `syncMutex` is sound: I verified `SyncEngine` is a Koin `single` (`di/UiModule.kt:51`), so the mutex really is process-global rather than per-injection. |
| **Concurrency** — a doorbell burst can't stampede into overlapping syncs | **CLEAN.** Triple-guarded: `debounce(600ms)` coalesces the burst (`SyncManager.kt:93`), `collect` with a suspending body applies backpressure, and `syncMutex` serialises whatever gets through. |
| **KMP parity** — no JVM-only API reachable from `commonMain` | **CLEAN.** `grep` for `import java.`/`javax.`/`java.util.`/`java.time`/`Locale.`/`String.format(` across both scope trees returns nothing. Everything used is multiplatform: `kotlin.time.Clock`, `kotlinx.io.IOException`, `kotlin.io.encoding.Base64`, Ktor, `kotlinx.coroutines.sync.Mutex`. `row_sync_state` stores `hashCode()`, whose value differs between JVM and Native — harmless, because it is a device-local table never compared across platforms. The new `SignOutWipeDao` correctly avoids `clearAllTables()` for exactly this reason. |

---

## Method (so the column check is reproducible)

The full-row-upsert check was done mechanically, not by eye, and against the deployed database rather than
`supabase/schema.sql` alone (they drift — the live `users` table has `deleted_at` and
`deletion_requested_at`, which the checked-in schema file does not declare). The scripts are in the session
scratchpad, not in the repo:

1. Parse every `@Entity` under `data/db/entity/` for its `tableName`, its property names and their Kotlin
   nullability.
2. Convert each property name with the same transform `JsonNamingStrategy.SnakeCase` applies, since that —
   not `@ColumnInfo` — decides the wire key. (Checked: the two agree on all 289 fields, so a divergence
   between the Room column and the wire name is not currently possible either.)
3. Read `information_schema.columns` from the live project (read-only `select`) for name, `is_nullable` and
   `column_default`.
4. Diff in three directions: client field with no server column; server-nullable vs Kotlin-non-null; server
   NOT NULL vs Kotlin-nullable.

All four Supabase queries I ran were read-only `select`s against `information_schema` and two `count(*)`
aggregates over `members`. Nothing destructive, no migration, no branch.

---

## Gaps — what I did not get to

- **The `androidMain`/`iosMain` actual count.** The brief expects 3 files / ~64 lines; only
  `AuthDeeplink.android.kt` (18 lines) actually belongs to `data/remote`/`data/auth`. The other two files
  matching that line count are `data/db/DatabaseBuilder.android.kt` and `.ios.kt` (20 + 26), which are
  `data/db` and out of scope. I read them for context but reviewed and reported nothing there.
- **`ReceiptUploadManager` / the upload path**, which §3 names as a place to look for the same
  permanent-vs-transient conflation. It lives in `data/upload/`, explicitly out of scope, and another
  session owns it. S8 covers the RevenueCat half of that same instruction.
- **Dynamic verification.** I did not run a `commonTest` in the scratchpad or exercise the sync loop on a
  simulator: the working tree does not compile right now (the in-flight `signOut` signature change has not
  reached its two UI call sites), so nothing I built would have been meaningful. Everything above is
  therefore settled by reading, by `grep`, or by a read-only query — never by argument alone, but not by
  execution either. S1's interleaving in particular is the one I would most want a test for once the tree
  builds, and it is testable: a fake `SyncEngine` whose `pull` blocks on a latch, released after
  `wipeSignedOutAccount()` returns, asserting Room is empty afterwards.
- **One documentation divergence, noted rather than filed** because the file it concerns is out of scope:
  `data/AGENTS.md:57-60` states that `receipt_uploads`, `expense_sync_state` and `group_scan_usage` are
  local-only and "*not* `@Serializable`". All three are `@Serializable` today, as are `row_sync_state` and
  `superseded_notices`. The protection that actually holds — absence from `SyncEngine`'s table lists — is
  real and I verified it; the type-system half the doc claims is not.
