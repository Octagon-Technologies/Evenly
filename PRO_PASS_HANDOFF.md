# Evenly Pro — handoff

**Written 2026-08-12.** Companion to `PRO_PASS_SPEC.md`, which stays the design authority. This file is
the *state* document: what is built, what is not, and who has to do the rest.

Branch **`feat/pro-passes`**, 8 commits ahead of `main` for this work (`484f96f`…`9d2bf68`).

---

## 0. The one-paragraph summary

**All nine spec steps are written, committed, green on both platforms, and deployed.** Nothing in the
feature is verifiable end-to-end yet, because **no store product exists**. RevenueCat is unconfigured, so
the app is in its designed inert state: no paywall, no pass sheet, no Pro row, scans behave exactly as
they did before this work. The remaining engineering is small; the remaining *owner* work (§10 of the
spec) is the actual critical path, and until it lands nobody can buy anything and no purchase code path
has ever executed.

---

## 1. Where we are, step by step

| Step | What it is | State |
| --- | --- | --- |
| 1 | Schema, enforcement, sync, meter, badge | **Done** (pre-existing) |
| 2 | Export gating | **Done** (pre-existing) |
| 3 | `user_subscriptions` + widened `group_pro_status` | **Done, applied to the live project, vector-verified** |
| 4 | Client sync of subscriptions | **Done, tests pass both platforms** |
| 5 | RevenueCat SDK + `ProConfig` | **Done, inert.** iOS host links it (full `xcodebuild` verified) |
| 6 | Entry points, paywall, pass sheet, picker | **Done.** Renders only once §3 below is configured |
| 7 | `sync-subscriber` + `activate-pass` | **Written + deployed.** Inert without the secret key |
| 8 | `revenuecat-webhook` | **Written + deployed.** *Refuses* every request without its secret |
| 9 | Analytics + `ux-firsttimer` | **Done.** 2 P0s + 5 P1s found cold and fixed |
| — | PostHog funnel + experiment (added after step 9) | **Done** — see §5 |

### Verified with commands, not assumed

- `group_pro_status` §13 vectors, run transactionally against the live DB and rolled back: empty means
  not Pro, `expires_at == now` is expired, revoked ignored on both routes, latest expiry wins across
  routes with the right `source`, subscriber leaving drops the group back to free.
- All three edge functions `ACTIVE` on project `wfpfgbipjmkysalfmyub`. `revenuecat-webhook` has
  `verify_jwt = false`; the other two `true`.
- Endpoint behaviour: no auth → 401; non-user bearer → 401; `GET` → 405; missing body fields → 400;
  webhook with no secret configured → **503 (refuses)**; wrong secret → 401; `TEST` event → 200 with no
  write; unreachable RevenueCat → 503 so the retry happens.
- Android APK builds; iOS host app builds, links, installs, launches, **0 uncaught Kotlin exceptions**.
- Play Billing's `com.android.vending.BILLING` permission merges into the Android manifest
  automatically — nothing to add.
- iOS needs **no** entitlement for In-App Purchase; `iosApp.entitlements` correctly carries only Apple
  Sign In.

### Never executed by anybody

Every code path below has been reviewed and compiled and has **never run**:
a real `purchase()`, `activate-pass` with a real transaction, `sync-subscriber` with a real subscriber,
any webhook event from RevenueCat, restore, and cancel.

---

## 2. What is left on the DEVELOPMENT side

Ordered by what blocks the most. Items 2.1–2.3 cannot start until §3 is done.

### 2.1 — Sandbox the purchase round trip *(blocked on §3; this is the real "step 7/8 done" bar)*
Spec §13's last bullet. On **both** stores, verify a real purchase, a restore, and a cancel:
- a pass purchase → `activate-pass` → a `group_passes` row → the group goes Pro on a *second* member's
  device through the normal pull;
- a subscription purchase → `sync-subscriber` → a `user_subscriptions` row → every group that person is
  in goes Pro;
- kill the app between the charge and activation, relaunch, confirm the parked transaction retries and
  yields **one** pass;
- submit one `store_txn_id` three times, confirm one row and three identical successes;
- a refund → `revoked_at` → the group drops back at the next pull, with bills and receipts intact.

### 2.2 — Confirm the webhook end to end *(blocked on §3)*
Fire RevenueCat's dashboard **Send test event**, then a real `NON_RENEWING_PURCHASE`. Specifically
confirm the `evenly_group_id` subscriber attribute arrives on the event — the backstop has no other
route back to the group, and if it is missing the purchase lands in `pro_orphan_purchases` instead.

### 2.3 — Decide the StoreKit-testing shortcut *(optional, could unblock 2.1 earlier)*
There is **no `.storekit` configuration file** in `code/iosApp`. Adding one lets the iOS purchase UI be
exercised on the simulator without App Store Connect. Caveat before investing in it: RevenueCat's
server-side verification does not treat Xcode-local StoreKit transactions the same as sandbox ones, so
this proves the *UI and client* half only, never `activate-pass`. Worth ~30 minutes if §3 is going to
take a while; worthless if §3 lands this week.

### 2.4 — `pro_expired` analytics *(spec §12, deliberately not built)*
The reason is recorded in `AnalyticsEvents.kt`: no client-side moment observes an expiry, and
`scans_during` needs a server-side count over `receipt_scan_log`. It belongs in a scheduled Postgres job
(there is already a `pg_cron` precedent in `purge_deleted_accounts`). Small, self-contained, do it any
time.

### 2.5 — `pro_orphan_purchases` has no resolution tooling
An unattributable purchase is parked and Slack-alerted, and a human then fixes it with hand-written SQL.
That is acceptable at zero volume and will not stay acceptable. A tiny admin RPC
(`resolve_orphan_purchase(txn_id, group_id)` that calls the same activation path and stamps
`resolved_at`) is the obvious follow-up.

### 2.6 — `manageSubscriptionsUrl(productId)` is always called with `null`
`ProRoutes.kt` passes `null`, so on Android "Manage or cancel" opens the general subscriptions list
rather than deep-linking to Evenly's own SKU. One-line polish; pass the live subscription's `product_id`.

### 2.7 — Not mine, but blocks App Store submission
`code/iosApp/iosApp/PrivacyInfo.xcprivacy` exists on disk, is **untracked in git**, and is **not
referenced anywhere in `project.pbxproj`** — so it is not bundled into the app. Apple requires the
privacy manifest at submission, and both RevenueCat and PostHog collect data that belongs in it. Someone
started this and did not finish it. Also uncommitted and untouched by this work: `project.pbxproj`,
`PRO_PASS_SPEC.md`, `design/pro-pass-mockup.html`.

### 2.8 — Pre-existing P0s this feature now leans on harder
Both are in `data/AGENTS.md` and predate this work, but Pro raises the stakes:
- **RLS is still permissive app-wide** (`using (true)`). `group_passes` and `user_subscriptions` are
  deliberately exempt and safe, but every other table is not.
- **Room still uses destructive migration.** Fine now; a schema bump in production drops unsynced local
  writes.

---

## 3. What is left on YOUR side — the critical path

Nothing in §2.1–2.3 can begin until this is finished. Spec §10 is the source; this section adds the
**exact identifiers the code matches on**, which §10 does not spell out.

### ⚠️ Read this first: three identifiers are load-bearing

Get these wrong and the failure is silent or confusing, not a compile error.

| Thing | Must be exactly | Where the code matches it | If it's wrong |
| --- | --- | --- | --- |
| Entitlement id | `pro` | `supabase/functions/_shared/revenuecat.ts` | Subscriptions never grant Pro. `sync-subscriber` writes nothing |
| Pass **product** ids | `app.splitevenly.pass.week1` / `.week2` / `.month1` | `PASS_TIERS` in the same file | `activate-pass` returns **422 `wrong_product`** and the buyer is charged with no pass |
| Pass **package** ids | `pass_week_1` / `pass_week_2` / `pass_month_1` | `PassTier` in `domain/pro/PassTier.kt` | Sheet still works, but loses the "best value" flag and the "Extend to 23 Aug" date |

### 3.1 App Store Connect
- [ ] **Paid Applications agreement active.** Nothing loads without it, including in sandbox.
- [ ] Subscription group **"Evenly Pro"** containing:
  - [ ] `app.splitevenly.pro.monthly` — $2.99, 1 month
  - [ ] `app.splitevenly.pro.annual` — $19.99, 1 year
- [ ] Three **consumables** (not non-consumables — a non-consumable can be bought once ever, which kills
      repeat passes):
  - [ ] `app.splitevenly.pass.week1` — $0.99
  - [ ] `app.splitevenly.pass.week2` — $1.99
  - [ ] `app.splitevenly.pass.month1` — $3.99
- [ ] Localized display name + description on **each** of the five, plus a review screenshot.
- [ ] **Terms of Use (EULA) and Privacy Policy URLs set on the app.** Apple rejects subscriptions
      without them. The app already links to `split-evenly.app/terms` and `/privacy` — confirm both
      pages are actually live.
- [ ] A sandbox tester account.

### 3.2 Play Console
- [ ] One subscription `app.splitevenly.pro` with base plans **`monthly`** and **`annual`**.
- [ ] Three in-app products using the same three `app.splitevenly.pass.*` ids as above.
- [ ] License-tester accounts.
- [ ] A build uploaded to a testing track — Play Billing will not return products otherwise.

### 3.3 RevenueCat
- [ ] One project, two apps (App Store + Play).
- [ ] **Upload the Play service-account JSON and the App Store In-App Purchase key.** Without these
      nothing verifies server-side, which means `activate-pass` refuses every purchase.
- [ ] Entitlement **`pro`** attached to the **two subscription products only**. *Never attach the
      consumables* — a consumable on an entitlement reads as unlocked forever after one purchase, the
      exact opposite of a pass that expires.
- [ ] Offering **`default`** → packages `$rc_monthly` + `$rc_annual`.
- [ ] Offering **`group_pass`** → packages `pass_week_1`, `pass_week_2`, `pass_month_1`, attached to
      **no entitlement**.
- [ ] A paywall on `default`, built in the editor. Non-negotiable content (App Review rejects paywalls
      missing any of it): price and period stated plainly, explicit auto-renew disclosure, Terms and
      Privacy links, Restore. Plus, from spec §8.2, **the renewal date said out loud before the charge**.
- [ ] Webhook → `https://wfpfgbipjmkysalfmyub.supabase.co/functions/v1/revenuecat-webhook`, with your
      chosen shared secret in the **Authorization** header.
- [ ] Copy back four values: the **two public SDK keys**, the **secret key**, and the **webhook secret**.

### 3.4 Hand the four values over
Two go in code, two go in Supabase.

**In code** — `code/shared/src/commonMain/kotlin/app/splitevenly/data/remote/revenuecat/ProConfig.kt`,
replacing the `YOUR_…` placeholders. This is the switch that turns the whole feature on; nothing renders
until it flips.

```kotlin
private const val APPLE_SDK_KEY: String = "appl_…"
private const val GOOGLE_SDK_KEY: String = "goog_…"
```

**In Supabase** — neither is currently set (verified 2026-08-12):

```bash
supabase secrets set REVENUECAT_SECRET_KEY=sk_… --project-ref wfpfgbipjmkysalfmyub
```

```bash
supabase secrets set REVENUECAT_WEBHOOK_SECRET=<the same secret you put in RevenueCat> --project-ref wfpfgbipjmkysalfmyub
```

### 3.5 PostHog *(optional, but it is why the funnel was built)*
- [ ] Create feature flag **`pro_pass_sheet`** to run pass-sheet experiments. JSON payload keys:
      `order` (list of package ids), `preselect` (`best_value` | `cheapest` | a package id),
      `best_value_flag` (bool). Every field defaults to today's behaviour, so an absent flag changes
      nothing.
- [ ] Suggested first two arms: `preselect: cheapest` against the default, and `best_value_flag: false`
      — the flag may be steering people to a tier they regret.

---

## 4. How to confirm it's working, once §3 is done

```bash
supabase secrets list --project-ref wfpfgbipjmkysalfmyub
```

```bash
cd code && ./gradlew :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64 :shared:testAndroidHostTest :shared:iosSimulatorArm64Test
```

```bash
code/iosApp/run-ios-sim.sh
```

Then, in the app: Settings should grow an **Evenly Pro** row showing a real localized price. A group at
0 free scans should show "Get Pro for <group>". Tapping either must reach a sheet with three real prices
from the store. If prices are missing but the row appears, the SDK key is right and the `group_pass`
offering is wrong.

Server-side, after a sandbox purchase:

```sql
select * from public.group_passes order by created_at desc limit 5;
```

```sql
select * from public.user_subscriptions;
```

```sql
select * from public.pro_orphan_purchases where resolved_at is null;
```

That third one should be **empty**. Rows in it mean purchases arrived with no `evenly_group_id`
attribute and nobody got what they paid for.

---

## 5. Decisions taken during the build that differ from the spec

Anyone continuing should know these were deliberate, and why.

1. **purchases-kmp 3.4.0, not the 1.8.x line the spec assumed.** 1.8.x reaches iOS through a
   `PurchasesHybridCommon` cinterop whose `linkerOpts` demand the framework be added to the Xcode host
   via SPM or CocoaPods. 3.x bundles `libRevenueCat.a` inside the klib, so the iOS host needs **no**
   package reference. Verified by a full `xcodebuild`, not just the Kotlin compile.
2. **RevenueCat REST v1, not v2** (`/subscribers/{app_user_id}`). v2's customer endpoints expose
   subscriptions and entitlements only; `activate-pass` has to verify a **consumable**, which appears
   only in v1's `non_subscriptions`. Re-check if RevenueCat ever adds them to v2.
3. **The group-named doors open the pass sheet, not the paywall** (spec §8.1 says paywall for the scan
   card). A control reading "Get Pro for Ski Trip" that opens a $19.99/yr all-groups plan is wrong on
   both axes that distinguish the products. Each sheet now names the other as an exit. **This is the one
   decision most worth revisiting** — it costs the remotely-designed paywall its highest-intent traffic,
   which was §2.1's whole reason for existing. Alternative, ~20 lines: keep spec routing and thread the
   group into the paywall so its footer reads "Only need it for **Ski Trip**?".
4. **§12's `paywall_shown` + `pass_sheet_shown` collapsed into `pro_offer_shown` with a `surface`
   property.** Follows from 3: with two event names, "does the pass door convert better than the
   subscription door?" needs two reports you cannot lay over each other.
5. **PostHog `group()` was tried and backed out.** `AnalyticsEvents.kt` already records that the wrapper
   binds one *current* group per user, which does not fit a member in several groups. Group facts ride
   on events instead, following the existing `group_snapshot` precedent.
6. **A store product price is allowed in analytics**, as an explicit carve-out written next to the rule
   it bends in `platform/Analytics.kt`. Catalogue data, not a user's money. Ledger amounts stay banned.
7. **`pro_expired` deliberately not emitted** — see §2.4.

---

## 6. Where the rules live

Do not re-derive these; they are already written down.

- Design authority: `PRO_PASS_SPEC.md`; visual authority: `design/pro-pass-mockup.html`.
- Server: `supabase/AGENTS.md` → "The three Evenly Pro functions", "`group_passes` — the Pro entitlement".
- Client data: `.../app/splitevenly/data/AGENTS.md` → "RevenueCat wiring — unconfigured must be INERT",
  and the pull-only tables note.
- UI: `.../app/splitevenly/ui/AGENTS.md` → the Pro block (which door opens what, never sell a pass to a
  covered group, a money button carries the amount, one event spine).
- Every new analytics event must also be added to `.posthog-events.json`.
