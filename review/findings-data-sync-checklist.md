# Fix checklist — `review/findings-data-sync.md`

Nine findings, grouped by the mechanism each one breaks rather than by severity, because the fixes inside
a group share code and must land coherently.

Legend: `[x]` fixed **and verified** · verification named per item.

**Status: 9 / 9 closed.** Green on Android + iOS; 197 tests pass (56 of them new or extended for these
findings). Both mutation checks below were run: reverting the fix makes the intended test fail.

---

## Group A — the sign-out fence (S1, S2, S6)

All three are the same moment: the instant account A leaves a device. They share `signOut()` and were
designed together, because S1's fix (fencing the wipe) is what makes S2's fix (counting pending writes)
atomic with it.

- [x] **S1 — P0.** The cache wipe was not fenced against the three sync coroutines that never gate on
      `currentUserId`. **Fix:** new `SyncGate` owns the sync mutex plus a per-user "closed" latch.
      `push`/`pull` run inside `gate.withSync(userId)`, which checks the latch **after** taking the lock, so
      an operation queued while sync was open still sees it closed when its turn comes; the wipe runs inside
      `gate.closeForSignOut(userId)`, holding the same lock. The latch is keyed by user id so a *different*
      account's sign-in cannot unblock work queued for the one that left.
      **Verified:** `SyncGateTest` (6 tests) reproduces the exact interleaving from the finding —
      in-flight pull → queued pull → sign-out → wipe — and asserts the queued pull is refused and the wipe
      is ordered last. Mutation check: moving the latch check outside the lock (the pre-fix ordering) fails
      `pullQueuedBehindSignOutsPush_isRefused_andTheWipeRunsAfterTheInFlightWork`.
- [x] **S2 — P1.** `push() == Ok` is not "everything reached the server", and the pending-count failed open.
      **Fix:** the count is taken on **every** sign-out (not only after an `Err`), inside the fence, and
      `pendingWritesOrUnknown` fails **closed** — a throwing count returns `PENDING_UNKNOWN`, which becomes
      `UnsyncedChanges(null)` and stops the wipe. `SignOutOutcome.UnsyncedChanges.pendingWrites` is now
      nullable and the dialog says "some of what you did" rather than inventing a number.
      **Verified:** `SignOutGateTest` (5 tests) on the fail-closed mapping, and `PendingLocalWritesTest`
      (5 tests, real Room DB on Native) on route (a) — an expense left dirty by #8's in-flight-edit guard,
      exactly the state a successful push hides, is counted.
- [x] **S6 — P2.** The device's `device_tokens` row was never removed on sign-out.
      **Fix:** `PushController.unregisterCurrentToken(userId)`, called before `client.auth.signOut()` while
      A's session can still authenticate the delete, scoped to `(token, user_id)` so a row already claimed
      by the next account is never touched.
      **Verified:** compile + review. No unit-testable seam — it is a single Postgrest `delete` and every
      collaborator on the path (`PushService`, `SupabaseClient`) is a platform/expect type with no fake.

## Group B — failure reporting: permanent vs transient (S3, S8)

- [x] **S3 — P1.** One `SyncHealth` counter for two independent channels, so a successful pull erased a
      permanently failing push. **Fix:** `SyncHealth` now holds a `ChannelHealth` per direction;
      `recordSuccess` clears only its own channel and **keeps** `lastError`/`lastFailureAt`; `looksOffline`
      requires *both* directions to be down, so a wedged push is never blamed on the user's wifi.
      **Verified:** `SyncHealthTest` (6 tests), including the exact push-fails/pull-succeeds interleaving
      `SyncManager` produces — ten rounds must read as ten failures, not zero.
- [x] **S8 — P2.** Activation and subscriber sync collapsed every outcome into `false`.
      **Fix:** `ActivationOutcome` (Activated / Retryable / Refused) splitting statuses the way
      `classifySyncError` splits exceptions (4xx that is not 408/429 ⇒ permanent); a third
      `PassPurchaseResult.ChargedActivationRefused`; a `PassSheetPhase.ChargedRefused` that drops the retry
      button, says the payment is recorded and that trying again will not change it, and offers "Get help
      with this"; and its own `reason = "activation_refused"` so the funnel can separate the two.
      **Verified:** `ActivationOutcomeTest` (6 tests) pins the status → outcome → purchase-result →
      sheet-phase chain, including that no outcome is ever a plain failure (the money already moved).

## Group C — structured concurrency (S7)

- [x] **S7 — P2.** `ReceiptOcrHttp` and `GroupExportHttp` caught `Throwable`, so a cancelled scan wrote a
      failure to the screen and fired a false `scan_failed`. **Fix:** `catch (e: CancellationException) {
      throw e }` ahead of the `Throwable` catch in both, matching `FrankfurterFxFetcher`. The same rethrow
      was applied to the new RevenueCat gateway path while it was being rewritten for S8.
      **Verified:** compile + review against the four sites that already do this correctly. **No test** —
      reaching the `try` block needs a live `ConnectivityObserver`, which is an `expect class` with no
      common-source seam, so any test would have been an environment-dependent simulator test asserting a
      one-line idiom. Called out rather than quietly skipped.

## Group D — what the device syncs, and whose clock decides (S4, S5)

- [x] **S4 — P2.** Client LWW trusted the writing device's wall clock unbounded.
      **Fix, two halves.** *Write:* `ServerClock` learns the offset from the `Date` header on Supabase
      responses (free — `SyncEngine.pull` reads it off the response it already holds; `ServerClockPlugin`
      covers the app's own Ktor client), and `Clock.nowEpochMillis()` clamps every `*_at` stamp to
      `min(local, server + 60s)`, matching `_clamp_client_ts` exactly. *Read:* `keepNewer` takes a trust
      horizon — an incoming stamp past it is compared as if it were the horizon, and a **local** stamp past
      it is treated as a wrong clock rather than a later edit, so a poisoned row accepts the next honest
      edit instead of being pinned forever.
      **Verified:** `ServerClockTest` (7 tests) and 4 new `SyncEngineTest` cases, including one pinning that
      the horizon does **not** resurrect a claimed placeholder. Mutation check: restoring the unbounded
      comparison fails `aPoisonedLocalRow_acceptsTheNextHonestEdit_insteadOfBeingPinnedForever`.
- [x] **S5 — P2.** A member who left a group kept pulling and pushing its whole ledger.
      **Fix:** `activeGroupIds` computes the pull scope from ACTIVE memberships only, while the full
      membership set (LEFT rows included) still lands — through `keepNewer`, not the blind upsert that
      branch used to do — so historical shares keep resolving to a name.
      **Verified:** 4 new `SyncEngineTest` cases, including rejoining a group you left.

## Group E — one list instead of four (S9)

- [x] **S9 — P3.** **Fix:** `SyncEngine.SYNCED_TABLES` is the single source. `push` and
      `countPendingLocalWrites` walk the same `SyncTable` objects (so those two cannot drift at all), an
      `init` check crashes on the next launch if those objects stop matching the list, `SyncManager` drives
      Room invalidation off it, and `SignOutWipeDao.WIPED_TABLES` is pinned against it by a test. The two
      drifts the finding named are gone: `expense_blocked_users` and `pending_item_edits` are now watched,
      and `shares` is not.
      **Verified:** `SyncedTablesTest` (5 tests). The `init` check is additionally exercised for real by
      `PendingLocalWritesTest`, which constructs a `SyncEngine`.

---

## Deliberately not done (named, not skipped silently)

Both are flagged in the findings themselves as owner decisions, not oversights.

- **Dropping the local cache of a group you have left** (S5's closing paragraph). The filter stops an
  ex-member's copy growing and stops them pushing it back up; rows already on the device stay. Doing more is
  the same design question as the sign-out wipe and should be answered once, for both.
- **Repairing rows already poisoned on the server** (S4's closing line). `keepNewer`'s horizon un-pins a
  poisoned row locally so the field starts accepting edits again, and the write clamp stops new ones being
  created. Nothing rewrites the `updated_at` already stored server-side.

## Docs updated in step (AGENTS.md rule 7)

`data/AGENTS.md` gained three sections — the gate-is-not-the-fence rule, the one-synced-table-list rule, and
the client-clock rule — and its claim that the device-local tables are "*not* `@Serializable`" was corrected
in place: all three are, and absence from `SYNCED_TABLES` is the protection that actually holds.
