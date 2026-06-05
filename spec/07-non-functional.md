# 07 — Non-Functional Requirements

> Quality bars the app MUST meet. These are not features but constraints. Every section is enforceable in CI or via a measurable runtime check.

---

## 1. Performance

### 1.1 Startup

- **Cold start to interactive Home** (network-warm cache, p50): ≤ 1.5s on a 2022 mid-range device (Pixel 6a class) / iPhone 12.
- **Cold start to interactive Home** (offline cache only, p95): ≤ 2.5s.

Cold start is measured from `Application.onCreate` (Android) or `application(_:didFinishLaunchingWithOptions:)` (iOS) to first frame with at least one expense row visible.

### 1.2 Screen transitions

- Tap-to-frame for any navigation: p95 ≤ 250ms on a Pixel 6a / iPhone 12.
- Add-expense form first frame after tap: p95 ≤ 300ms (includes loading members, categories from Room).

### 1.3 List rendering

- Expense list of 1,000 rows: scroll at 60 fps (90 fps on devices with high-refresh-rate displays). Validated with `androidx.benchmark` macrobenchmarks.

### 1.4 Sync latency

- Local-only writes return to UI in ≤ 50ms (Room write).
- Remote sync of a single mutation: p95 ≤ 1.5s on a good network.
- Realtime delivery of a remote write to another client: p95 ≤ 3s end-to-end.

### 1.5 Budget enforcement

CI runs benchmark tests on every PR; regressions > 10% from main are flagged. Track:

- `BENCH_addExpense_localWrite_ms`
- `BENCH_renderExpenseList_1000_fps`
- `BENCH_settleSheet_open_ms`

## 2. Security

### 2.1 Authentication & sessions

- Tokens are stored in platform secure storage (`SecureStorage` actual — `06 §5.2`). Never in `SharedPreferences` plain, never in `UserDefaults` plain.
- All Supabase calls are HTTPS (TLS 1.3 preferred).
- Refresh tokens rotated by Supabase; client never logs them.
- Sign-out wipes Room (`05 §13.4`) AND `SecureStorage` AND deregisters push token.

### 2.2 Row-level security

Every table has RLS enabled (`02 §5`). Default deny; explicit allow per role and predicate. Implementing agents MUST add a CI check that runs a smoke suite hitting every RPC from a "non-member" identity to confirm denials.

### 2.3 Storage signed URLs

- Receipts: signed URLs minted by `mint_receipt_url` RPC, TTL 1 hour, audience = caller's `user_id`.
- The Storage bucket is private (`02 §8`).
- No public CDN caching.

### 2.4 Input validation

- All RPC payloads validated server-side (Postgres function bodies).
- Display name: 1–80 chars; reject control characters; allow Unicode.
- Email: validated via Supabase Auth.
- Currency code: must exist in `currencies` table (FK).
- Amounts: positive; ≤ 9_999_999_999 subunits (~ $99M per expense — sanity guard).
- Receipt size: ≤ 30 MB (compressed); mime-type allowed list enforced by RPC AND a SQL CHECK.
- **Magic-link rate limiting (OQ-10):** use Supabase Auth's built-in default (one magic link per 60 s per email); no override. Documented here so it is not re-litigated during implementation.

### 2.5 No money custody

This is a security AND ethics principle:

- The app NEVER asks for routing/account numbers.
- The app NEVER stores credit card data.
- The app NEVER acts as an intermediary in any transfer.
- All settlement is by deep link to the user's own app — no in-app payment flow exists.

CI lint MUST fail if any code or string contains a pattern matching a Plaid SDK, Stripe SDK, ACH-related term, or bank-account form field name. (A simple regex check is enough.)

### 2.6 Sensitive logging

- NEVER log: emails, full names, payment-app handles, access/refresh tokens, push tokens, IP addresses (the client doesn't need to log these; Supabase logs them server-side under their own retention policy).
- The structured Logger interface in `core` has a `redactedHandle(handle)` helper that returns first 2 + `***` + last 2 chars.

### 2.7 Third-party SDKs

- Google SDKs used in v1: **Firebase Messaging** (push), **Firebase Analytics** (anonymous usage events), and **Firebase Crashlytics** (crash reporting) — D-23, OQ-08. No other Google SDKs.
- Analytics and Crashlytics collection is **consent-gated** by the user's privacy toggle (default ON, set in onboarding, changeable in Profile → Privacy; D-25, OQ-07). When consent is off, both SDKs have collection disabled at runtime.
- **No advertising identifier** is ever collected: Firebase Analytics is configured with ad-ID collection disabled (`06 §2.2`). The "no ad ID, ever" guarantee in `§9` holds.
- Payloads MUST never include PII (names, emails, amounts, expense titles, handles) — see `§4`.
- Apple/Facebook OAuth go through Supabase; no native FB SDK linked.

## 3. Accessibility

### 3.1 WCAG 2.1 AA conformance

- Color contrast: text on background ≥ 4.5:1 (3:1 for ≥18pt or bold ≥14pt). Verified in design tokens (`06 §4.2`) for both dark and light themes; CI test computes contrast for every defined color pair and fails if below threshold.
- Color is not the sole indicator of state (e.g., reconcile selection uses outline + checkmark).
- Focus indicators on every focusable element (outline using `border.focus`).

### 3.2 Screen readers

- Every `Composable` that renders a tappable affordance MUST set `Modifier.semantics { contentDescription = ... }` or expose a `text` for the system to infer.
- Expense rows: composite description "Title, paid by Payer, your share X remaining of Y owed, date" (per `05 §15`).
- Custom components MUST set `role` semantically (`Role.Button`, `Role.Checkbox`, etc.).

### 3.3 Touch targets

- All tap targets ≥ 44×44 dp.
- Reconcile cards: full row tappable, not just the chip.

### 3.4 Dynamic type

- Use Compose's `MaterialTheme.typography` scale and never hardcode `fontSize`. Test at the largest accessibility size on both platforms; rows MUST wrap rather than truncate critical information.

### 3.5 Reduced motion

- Respect `Settings.Global.ANIMATOR_DURATION_SCALE` (Android) and `UIAccessibility.isReduceMotionEnabled` (iOS). When reduced:
  - Skeleton shimmer pauses.
  - Settle confirmation sheet uses opacity-only transition.
  - Bar chart on Overview disables animated entry.

### 3.6 Right-to-left

- Layout MUST support RTL via `LayoutDirection` even though v1 is English-only. This is forward compat for v2 multi-language.

## 4. Observability

### 4.1 Client telemetry

The client emits structured analytics events to **Firebase Analytics** and non-fatal/fatal crashes to **Firebase Crashlytics** (D-23, OQ-08), both via the `Analytics`/`CrashReporter` facades (`06 §5.10`). Collection is **gated by the user's privacy consent** (default ON; `05 §1.3`, `§10`); when consent is off, nothing is sent. All payloads are anonymous: keyed by Firebase's app-instance id (never `user_id`), no display names, no expense titles, no amounts, no handles. Advertising-ID collection is disabled.

| Event | When |
|---|---|
| `app_start` | On cold start. Carries `app_version`, `platform`, `os_version`. |
| `screen_view` | On navigation. Carries route name only. |
| `rpc_call` | On every RPC completion. `name`, `latency_ms`, `outcome: ok | error`, `error_code?`. |
| `sync_pull` | Per pull. `since_age_seconds`, `rows_returned`, `latency_ms`. |
| `realtime_event` | Per event. `channel`, `table`, `op`. |
| `mutation_enqueued` | When a mutation hits the queue. `op_kind`. |
| `mutation_committed` | When a mutation succeeds server-side. `op_kind`, `attempts`. |
| `deep_link_settle_outcome` | When the user resolves the confirm sheet. `app: VENMO|ZELLE|CASH_APP|PAYPAL|MANUAL`, `outcome: yes|not_yet`. |
| `feedback_submitted` | On in-app feedback. |

### 4.2 Server telemetry

- Supabase logs every RPC invocation. We add a `pg_audit` extension or a wrapper trigger on `idempotency` inserts to count RPC calls per user per hour for abuse detection.
- Edge Functions log structured JSON; aggregated via Supabase log dashboards.

### 4.3 Privacy

- Telemetry is **opt-in with a default-ON toggle presented during onboarding** (D-25, OQ-07) and changeable any time in Profile → Privacy. Turning it off disables Firebase Analytics and Crashlytics collection at runtime.
- Firebase retains analytics/crash data per Google's configured retention; we set the shortest practical Analytics data-retention window. No PII is ever included. The privacy policy explicitly documents the Firebase Analytics + Crashlytics surface and the opt-out.

### 4.4 Error reporting

- Crashes (fatal) and `AppError.Unknown` cases (non-fatal) are reported via **Firebase Crashlytics**, gated by consent. Stack traces are symbolicated by Crashlytics; the client adds NO user data to crash keys/logs (no emails, names, amounts, titles, handles).
- A separate "Send feedback" surface in Profile allows the user to attach a diagnostics blob (last 100 log lines + device info) only with their explicit consent on a per-submission basis.

## 5. Reliability

### 5.1 Sync correctness

- Tests in `commonTest/sync/` simulate offline writes, concurrent edits, and reconciliation. The test set covers:
  - Add expense offline → reconnect → server has the expense exactly once (idempotency).
  - Two clients edit the same expense; LWW wins; the loser sees a `STALE_ROW` and recovers via the dialog (`04 §6.4`).
  - Add expense + apply settlement against it both offline → both replay in order on reconnect.
  - Cap-exceeded receipt upload offline → on reconnect, the upload fails with `STORAGE_CAP_EXCEEDED` and the queue item is moved to a "failed" state; user is notified.

### 5.2 Crash recovery

- App must survive process kill mid-write. Room is the source of truth for the mutation queue.
- On every cold start, the sync worker resumes the queue.

### 5.3 Migrations

- Room migrations are versioned and tested with `Room.migration` test harness for forward upgrades.
- Postgres migrations are forward-only. No destructive `DROP COLUMN` in a single migration; do `ADD column-rename → backfill → DROP` over two releases.

### 5.4 FX failure modes

- Network failure: silently fall back through the chain (`03 §6.2`).
- Frankfurter returns a malformed payload: discard, do not update the cache, log to Edge Function logs.

## 6. Privacy

### 6.1 Data minimization

- Email is the only required PII.
- Display name is user-chosen; can be a pseudonym.
- Payment-app handles are optional.
- No phone numbers, no address, no DOB.

### 6.2 User data export & delete

- "Delete my account" (`04 §1.4`) is real deletion (subject to legal retention windows for receipts and history per shared-group context).
- Per-group "Export" (CSV/JSON/PDF) is always available, free, and unrestricted.
- Account-level export (all groups + all profile data) is available from Profile → Account → "Download my data."

### 6.3 Cross-member visibility

- A member's email is NEVER visible to other members.
- Display name and avatar ARE visible to other members in shared groups.
- Payment-app handles ARE visible to other members in shared groups (to enable settle deep links).

## 7. Testing strategy

### 7.1 Unit tests (`commonTest`)

- All use cases in `domain/usecase/` have unit tests. Coverage target: 95% line, 90% branch.
- `allocate` algorithm has property-based tests using `kotest-property`:
  - For any positive `totalSubunits` and any non-empty `weights`, the sum of allocations equals `totalSubunits`.
  - Determinism: same inputs in the same order produce the same outputs.
  - Largest-remainder property: leftover subunits go to the participants with the largest fractional shares.
- FX lookup chain tests covering: present in live cache; absent → baked; both absent → unavailable; stale > 7 days → flag.
- Auto-refund tests: settling members get refunded the exact overpayment after retro adds.
- Conflict resolution tests: INCLUDE math redistributes correctly; DISMISS leaves shares unchanged.
- Placeholder merge tests: idempotent; all references rewritten; history events appended.
- Itemized tax+tip tests (D-21): `share = subtotal + tax_share + tip_share`; PROPORTIONAL vs EVEN tip distribution; `SUM(shares) == amount_subunits == SUM(subtotals)+tax+tip`.
- Draft tests (D-24): auto-save offline then sync; convert reuses draft id and re-points receipts; discard cascades to draft receipts; cross-device LWW keeps newest save.
- Receipt pipeline tests (D-22): compression keeps quality ≥ 0.80 and bounds size; thumbnail generated (incl. PDF page-1); author-only delete denies non-authors; 14-day cache eviction triggers re-fetch.

### 7.2 Integration tests

- Room database tests covering all DAO operations and invariants in `02 §9`.
- Sync engine tests with a fake Supabase using an in-process HTTP server (`MockEngine` for Ktor).
- RPC contract tests: schema validation of every RPC response shape against a Kotlin DTO. Generated from `supabase/migrations/` using `tools/gen_rpc_dtos.kts`.

### 7.3 Postgres tests

- `supabase/migrations/` are tested via `pgTAP` or a Kotlin harness that:
  - Asserts every RLS policy denies cross-group access.
  - Asserts triggers fire (storage cap, single admin, history append-only).
  - Asserts `idempotency` correctly returns cached result on retry.

### 7.4 UI / screenshot tests

- Compose Multiplatform supports `runComposeUiTest`. Smoke screenshot tests on:
  - Home (empty, populated, archived)
  - Group home (each tab)
  - Expense detail (active, settled, refund, with comments)
  - Add expense (each split mode)
  - Reconcile (selected vs. unselected cards — verifies the green outline contract)
  - Settle sheet (deep-link variant, fallback variant)
- Screenshot baselines are checked into the repo per platform.

### 7.5 E2E tests

- Limited E2E suite that runs against a local Supabase via the Supabase CLI. Tests:
  - Sign in → create group → invite → second account joins → reconcile → see expenses.
  - Add expense → settle via deep link → marked paid → balance updates on the other client.
  - Retro add → conflicts created → resolve INCLUDE → auto-refund visible to overpayer.

### 7.6 Manual checklist (release gate)

A short manual smoke list per release:

- Dark mode visual pass on both platforms.
- VoiceOver / TalkBack pass on Add expense + Reconcile screens.
- Deep link from FCM payload opens the right screen.
- Universal link from email opens the right screen.
- Offline → online sync of 5+ queued writes.

## 8. Internationalization (forward compat)

- All user-visible strings live in `composeResources/values/strings.xml` (or JSON) keyed by `R.string.xxx` equivalent for KMP (`compose-multiplatform-resources`).
- Number formatting uses `kotlinx.datetime`/`NumberFormat`-equivalent that respects locale (forward-compat for v2). For v1, English `en-US` only is rendered; locale is forced.
- Dates are formatted via `kotlinx.datetime.LocalDate.format(Format)` patterns; no string concatenation of date parts.

## 9. Compliance & legal

- Privacy policy + Terms are linked from sign-in and Profile.
- App Store / Play Store data-collection disclosures match the actual data flow:
  - Email (account linkage).
  - Payment-app handles (functional, not for advertising).
  - Anonymous Firebase Analytics usage data + Firebase Crashlytics crash data (analytics/diagnostics, not for advertising; consent-gated, default-on, user can opt out).
- No advertising ID is collected, ever (Firebase Analytics ad-ID collection disabled).
- COPPA: app is for ages 13+; sign-in flow includes a "Are you 13 or older?" confirmation gate before account creation (Supabase Auth is allowed to enforce this via a custom claim).

## 10. Resource budgets

### 10.1 Storage

- Room DB target footprint for a heavy 5-year household group: ≤ 50 MB. (Receipts are NOT stored in Room; only metadata rows are. The files live in Storage and are lazily fetched into Coil's disk cache; see `03 §15.2`.)
- Coil disk cache: ≤ 200 MB; LRU-evicted, with a **14-day TTL** applied to receipt images and thumbnails (D-22, OQ-11) so unviewed-for-two-weeks receipts are dropped and re-fetched on demand.
- Receipts are uploaded **compressed** (≥ 80% quality, long edge ≤ 2048 px) with a separate ≤ 320 px thumbnail to bound both Storage and cache footprint.
- Per-group receipt storage on Supabase: soft 500 MB / hard 1 GB (`02 §8`, D-09), counting full image + thumbnail.

### 10.2 Memory

- Steady-state memory on group home with 200 expenses: ≤ 150 MB RSS (Android) / ≤ 180 MB resident (iOS).

### 10.3 Network

- Cold start total bytes (warm cache): ≤ 30 KB of JSON.
- Pull-group-delta with 50 new rows: ≤ 80 KB of JSON.

---

**Read next:** [`08-acceptance-criteria.md`](08-acceptance-criteria.md).
