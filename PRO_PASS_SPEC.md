# Evenly Pro — group passes (RevenueCat)

**Status:** spec, not built. Written 2026-08-09 for the RevenueCat Shipaton.
**Owner decisions already taken** (do not re-open without saying so): pass is bound to ONE group chosen
at checkout; 5 free scans per group, lifetime; consumable product on both stores; Pro = unlimited scans
+ group data export.

---

## 1. What we are selling, in one paragraph

A group gets **5 receipt scans, free, ever**. After that, any member can buy the group a **Pro pass** —
one week, two weeks, or one month — and for that window the whole group gets **unlimited scans** and can
**export the group's data**. One person buys; everyone benefits; the app shows who paid for it. The pass
**ends on its own**. There is no renewal, no card on file, nothing to cancel.

That last sentence is the product. We are deliberately choosing the model that makes less money per user
than a subscription, because the failure mode of a subscription — a friend buys a $1 pass for a ski trip
and gets billed monthly for two years — is exactly the kind of thing that ends up in a 1-star review of a
money app. This mirrors the reasoning already recorded for data loss in `data/AGENTS.md`.

### Why a group pass and not a personal one

Evenly's unit of value is the group, not the person. A trip has one receipt-heavy weekend and then goes
quiet. Charging each of six friends separately to split one dinner is absurd; charging the trip $2 once
is not. It also removes the worst freemium moment in a social app: five people staring at a bill while one
of them hits a paywall.

---

## 2. Tiers and pricing

| Pass | Duration | Target price | Store product id (proposed) | RevenueCat package |
| --- | --- | --- | --- | --- |
| Pro, 1 week | 7 days | $0.99 | `app.splitevenly.pro.week1` | `pro_week_1` |
| Pro, 2 weeks | 14 days | $1.99 | `app.splitevenly.pro.week2` | `pro_week_2` |
| Pro, 1 month | 30 days | $3.99 | `app.splitevenly.pro.month1` | `pro_month_1` |

**Open decision (needs the owner):** the brief said $1 / $2 / $4. Both stores now support exact $1.00 /
$2.00 / $4.00 custom price points, but $0.99 / $1.99 / $3.99 are the conventional tiers and read as
cheaper. Pick one before creating the products; changing a price point later is a store-console chore, not
a code change. The table above assumes the `.99` ladder.

**Never hardcode a price string in the app.** Always render RevenueCat's localized `StoreProduct`
price. App Review rejects hardcoded prices, and a Kenyan or European user must see their own currency.
The Kotlin side treats price purely as display text.

Duration is measured **server-side in milliseconds from the moment the server activates the pass**, not
from the client clock, and not in calendar months. 30 days means 30 × 86_400_000.

### Product type

**Consumable on both stores.** It cannot auto-renew by construction, which is the whole point, and it
behaves identically on Play and the App Store. The tradeoff is that there is no store-level "restore
purchases" for consumables — which costs us nothing here, because the entitlement lives on **our** server
attached to a **group**, not on the buyer's store account. See §5.

Do not use an auto-renewing subscription with renewal disabled: the store's own UI still calls it a
subscription and users will go hunting for a cancel button that does not exist. That is the opposite of
what we are promising.

---

## 3. What Pro unlocks

| Capability | Free | Pro |
| --- | --- | --- |
| Receipt scans (`extract-receipt`) | 5 per group, lifetime | Unlimited during the window |
| Group data export (`export_group`) | No | Yes |
| Everything else in Evenly | Yes | Yes |

**Everything else stays free forever** — unlimited groups, unlimited expenses, unlimited members,
itemized splits, settle-up, the web claim flow, manual bill entry. We gate the thing that costs us real
money per use (Claude vision) plus one end-of-trip convenience. Nothing that a friend needs in order to
be paid back is ever behind a paywall.

**Manual bill entry is never gated, and that is load-bearing.** A group at 0 free scans can still itemize
a bill by typing it. The paywall must therefore always read as "want the fast way?" and never as "you
cannot use the app." This is the §7 "never leave a silent dead end" rule from `AGENTS.md` applied to money.

**`export_group` has no Kotlin caller today** (verified: no match for `export_group` in
`code/shared/src`). The edge function exists but nothing in the app invokes it. So "gate export behind
Pro" is really "build the export entry point, gated" — a real chunk of scope, tracked separately in §11
so it can be dropped if the Shipaton clock runs out without touching the scans work.

---

## 4. The free allowance

**5 successful scans per group, for the life of the group. It never resets.**

Counted server-side from the existing `receipt_scan_log` table, which already carries `group_id` (added
for the Plan A cost ledger, `schema.sql:509`). No new counting table.

```sql
select count(*) from public.receipt_scan_log
where group_id = $1 and outcome = 'ok';
```

Only `outcome = 'ok'` counts. A blurry photo, a non-receipt, a server failure, or a rate-limited attempt
must **not** burn someone's free scan — being charged for our own failure is a support ticket and a bad
review. That does mean a determined abuser could spend our Anthropic budget on garbage photos without
consuming their allowance; the existing per-user 20-scans-per-hour limit and the Opus circuit breaker are
what bound that, and they already exist.

**Groups created before this ships start at their real historical count**, because we are counting rows
that already exist. A test group with 40 scans is instantly out of free scans. Acceptable — there are no
real users yet. If that changes before launch, add a `groups.free_scans_grant` int column defaulting to 5
and grandfather old groups with a larger grant rather than special-casing dates.

---

## 5. Entitlement model — the server owns it

The single most important structural decision: **RevenueCat tells us a purchase happened; our database
decides who is Pro.** The client never asserts entitlement, and `CustomerInfo` on the buyer's device is
not the source of truth. It cannot be — five other people in the group need to be Pro on their own
devices, and they made no purchase.

### 5.1 New synced table

```sql
create table if not exists public.group_passes (
  id                text primary key,           -- uuid, client-visible
  group_id          text not null references public.groups(id),
  purchased_by      uuid not null references auth.users(id),
  tier              text not null check (tier in ('week_1','week_2','month_1')),
  store             text not null check (store in ('app_store','play_store','promo')),
  store_txn_id      text not null,              -- RevenueCat's store transaction id
  rc_app_user_id    text not null,
  starts_at         bigint not null,            -- epoch millis, server clock
  expires_at        bigint not null,            -- epoch millis, server clock
  revoked_at        bigint,                     -- refund / chargeback; null normally
  deleted_at        bigint,
  deleted_by        uuid,
  created_at        bigint not null,
  updated_at        bigint not null
);
create unique index if not exists group_passes_txn_idx on public.group_passes (store, store_txn_id);
create index if not exists group_passes_group_idx on public.group_passes (group_id, expires_at);
```

`store_txn_id` unique per store is the **idempotency key**. A webhook retry, a client retry, and a
reconciliation sweep all try to insert the same row; exactly one wins and the rest are no-ops. Without
this, one $1 purchase becomes three stacked passes.

Rows are **synced** (snake_case `@ColumnInfo`, `@Serializable`, in `SyncEngine`'s table list, in the
doorbell trigger loop) so every member's device learns the group went Pro through the normal pull, with
no push notification and no special-casing. It carries `deleted_at`/`deleted_by` like every other
user-data table, per `data/AGENTS.md` Rule 1. It is **append-mostly**: the client never writes it. Client
writes are refused by RLS (see §5.4).

**A pass is never transferable and never refundable by us.** It is bound to `group_id` at insert. If
someone buys for the wrong group, that is a support conversation and a manual row, not a feature.

### 5.2 Derived state — one function, used everywhere

```sql
create or replace function public.group_pro_status(p_group_id text, p_now bigint)
returns table (is_pro boolean, expires_at bigint, purchased_by uuid, tier text)
```

Returns the **latest-expiring live pass**: `deleted_at is null and revoked_at is null and expires_at >
p_now`. The client mirrors this as a pure Kotlin function over its local `group_passes` rows so the badge
and the paywall work offline; the *enforcement* copy is the SQL one, in the edge function. Two
implementations of one rule is a wart, but the alternative is a network round trip to render a badge.
Pin both with the same test vectors.

### 5.3 Stacking

Buying while a pass is live **extends from the current expiry, not from now**. `starts_at` of the new
pass = `max(now, current_max_expires_at)`. Two friends who both buy a week get the group two weeks, and
nobody feels robbed. `group_pro_status` picking the max expiry makes this fall out for free.

The paywall must say so *before* the purchase when the group is already Pro: "Ski Trip is Pro until
Aug 16. Buying now adds to the end, it does not start over."

### 5.4 RLS

`group_passes` is exempt from the permissive `_rw` loop at `schema.sql:591`, alongside
`superseded_split_edits` and `placeholder_claim_answers`:

- **read**: `authenticated`, where the caller has an ACTIVE `members` row in `group_id`.
- **insert/update/delete**: revoked from `anon` and `authenticated` entirely. Only the service key writes
  here.

Getting this wrong is free Pro for everyone: under the current `using (true)` loop, any authenticated user
could insert themselves a pass with `expires_at` in 2099. This is the one table that cannot wait for the
pre-prod RLS tightening.

**RLS does not apply to TRUNCATE.** Revoking insert/update/delete still leaves a signed-in user able to
wipe the table in one statement, because Supabase grants ALL on a new public table to
`anon`/`authenticated`. `group_passes` revokes it explicitly. A live grants query says **all 32 tables**
in this schema currently grant TRUNCATE to `authenticated`, including the zero-policy ones
(`web_bill_links`, `web_sessions`, `apple_oauth_tokens`) — that is a project-wide P0 for the pre-prod
RLS work, recorded in `data/AGENTS.md`, and deliberately not fixed inside this feature's migration.

---

## 6. Purchase flow

```
  [paywall] --purchase--> RevenueCat SDK --> store
                                              |
                      success (CustomerInfo)  v
  [client] --POST /activate-pass { groupId, rcAppUserId, storeTxnId }-->
                                              |
                              verifies against RevenueCat REST API v2
                              (server-side secret key, never in the app)
                                              |
                                     insert group_passes (idempotent)
                                              |
                              <-- { isPro, expiresAt, tier, purchasedBy }
                                              |
                                     bump group_activity (doorbell)
                                              |
                        every other member pulls and sees Pro
```

### 6.1 `activate-pass` edge function (new)

Verifies **server-side** that the purchase is real before granting anything. It calls the RevenueCat REST
API with a secret key held in an edge-function env var, confirms the transaction exists for that
`rcAppUserId`, confirms it is one of our three product ids, then inserts.

Reasons this exists rather than trusting the client's `CustomerInfo`:

1. A modified client can claim any purchase. The entitlement grants unlimited paid Claude vision calls to
   six people, so the incentive is real.
2. Duration must be measured on the server clock. A device clock set back is otherwise a free month.
3. The group binding lives nowhere in the store's data model. Only we know which group this was for.

Its failure mode must be **safe and recoverable**: if the store charged the user and this call fails
(offline, 500), the client retries with the same `storeTxnId` on next launch and on the next paywall open.
Idempotency (§5.1) makes retrying free. The buyer sees "Payment went through. Turning on Pro..." with a
retry, never a silent loss.

### 6.2 `revenuecat-webhook` edge function (new)

`verify_jwt = false`, authenticated by a shared secret in the `Authorization` header, same pattern as
`web-claim` doing its own token auth. Two jobs:

- **Backstop**: `NON_RENEWING_PURCHASE` for a transaction we never activated (client died between the
  store charge and our call) gets activated from the webhook. The group id rides along as a RevenueCat
  **subscriber attribute** set immediately before `purchase()`, and is also in the purchase metadata. If
  neither is present the event is parked in an ops table and alerted, not guessed.
- **Refunds**: `CANCELLATION` / `REFUND` / chargeback stamps `revoked_at`. The group silently drops back
  to free at the next pull. Already-scanned bills are untouched. We do not claw back or delete anything.

### 6.3 Restore

There is no store-side restore for a consumable, and we do not need one. The pass belongs to the group and
syncs to every member on every device. A buyer who reinstalls signs in and their groups are Pro because
the server says so. The paywall carries no "Restore purchases" button for the pass itself, and instead
says "Your group's pass follows the group, not this phone."

The one real gap: a purchase that reached the store but never reached us and whose webhook also lacked the
group attribute. That is what the ops alert in §6.2 is for; recovery is a manual row.

---

## 7. Enforcement — `extract-receipt`

Enforced **in the edge function, before the paid vision call**, in the same place the existing rate limit
lives (`extract-receipt/index.ts:296`). Never in the client. The client's job is only to render the
outcome well.

Order of checks, cheapest and most-user-hostile-if-wrong first:

1. Resolve the user (already there, 401 otherwise).
2. **`groupId` is now REQUIRED.** Today it is documented as analytics-only and nullable. A nullable
   quota key is a bypass: omit the field, get unlimited scans. Both existing call sites already pass a
   real group id (`LedgerRoutes.kt:172`, `BillRoutes.kt:151`), so requiring it costs nothing. Missing or
   non-member `groupId` ⇒ 400.
3. Per-user rate limit, unchanged (429).
4. **Pro check**: `group_pro_status(groupId, now).is_pro` ⇒ proceed, log the scan with the pass id.
5. **Free check**: `count(receipt_scan_log where group_id and outcome='ok') < 5` ⇒ proceed.
6. Otherwise **402 Payment Required**, `{ error, reason: "quota_exhausted", scansUsed, freeLimit }`,
   logged as `outcome = 'quota_exhausted'` (new value on the existing check constraint) **before** any
   Anthropic call.

402 rather than 429 because the client must tell "wait a bit" apart from "buy a pass" — they are
different screens.

### Client mapping

`ScanOutcome.Blocked(reason: String)` already exists for exactly this
(`ReceiptOcrHttp.kt:86` maps 429 to `reason = "rate_limited"`), and both route files already branch on
`ScanOutcome.Blocked`. So the client change is:

- map 402 to `Blocked(reason = "quota_exhausted")`,
- in `LedgerRoutes.kt:189` / `BillRoutes.kt:168`, branch that reason to the paywall sheet instead of the
  generic error.

No new outcome type, no new plumbing through the scan state machine.

### The race we accept

Six people on one dinner, one free scan left, two scan at once: both pass the check, both scans run, the
group ends at 6 of 5. We eat one scan. The alternative is a lock on a hot path to protect a sub-cent cost.
Do not add one. (Contrast: `join_item_portion`, where the same race corrupts money and therefore *is*
serialized.)

---

## 8. UI

Mock these before writing Compose, per `AGENTS.md` §7. Every string below is draft copy, subject to the
`ui/AGENTS.md` rules, and **contains no em dashes** — the `em-dash-guard` hook blocks them in Kotlin
string literals.

### 8.1 The scan meter (free groups only)

Above the scan CTA, appearing **only from the 3rd scan onward** so a new group is never nagged:

> 2 of 5 free scans left

At 0 left, the scan button **stays enabled**. Tapping it opens the paywall. Never grey it out with no
explanation (`AGENTS.md` §7, and the guide-when-blocked rule).

### 8.2 The paywall sheet

Dimmed `ScSheetScaffold` (this is a decision, not a browse), opened from: the scan CTA at 0 free scans,
the Pro badge in Group Settings, and the export row.

```
                 Unlimited scans for Ski Trip

  Ski Trip has used all 5 free scans. Get a pass and
  everyone in the group can scan as many receipts as
  they want.

   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
   │   1 week     │ │   2 weeks    │ │   1 month    │
   │    $0.99     │ │    $1.99     │ │    $3.99     │
   │              │ │  most picked │ │              │
   └──────────────┘ └──────────────┘ └──────────────┘

            [  Get Pro for Ski Trip  ]

   One time. It does not renew. Nothing to cancel.
   You will not be charged again.

   The pass covers this group only, and everyone in
   it. You can still add bills by hand for free.
```

Non-negotiable copy points:

- **"One time. It does not renew. Nothing to cancel."** sits above the buy button, not buried in fine
  print. This is the differentiator; hiding it wastes it.
- The **group name** appears in the headline and on the button, because the pass is group-bound and the
  #1 support risk is buying for the wrong group.
- **"You can still add bills by hand for free"** keeps the exit visible.
- Prices come from RevenueCat, localized. The `$0.99` above is a mock, not a literal.

### 8.3 Group is already Pro

Group Settings row and a small badge on Group Home:

> **Pro** until Aug 16
> Sam got this for the group

Showing the buyer by name is the social half of the design. It makes the purchase a small favour to
friends rather than an invisible tax, and it is the honest answer to "wait, who paid for this?"

Reopening the paywall while Pro shows the extend framing from §5.3.

### 8.4 Expiry

No countdown pressure, no interstitial. On the day it ends, the badge changes to:

> Pro ended. Your bills and receipts are all still here.
> [ Get another pass ]

**Nothing already scanned is ever taken away.** Expiry only stops *new* scans. Retroactively hiding
someone's receipts would be indistinguishable from data loss in a money app.

### 8.5 Run `ux-firsttimer` on all of it

Build-time gate, not an audit. Walk it cold as Sam the invited friend who did not buy anything, and as
the person who tapped scan at 0 remaining. Clear every P0/P1 before this is done.

---

## 9. RevenueCat wiring

- Official **`purchases-kmp`** SDK in `commonMain`. Pin the version at implementation time and check its
  Kotlin requirement against the **2.3.21** anchor before adding it; a KMP SDK that drags a different
  Kotlin or Ktor is a build break, not a dependency bump.
- API keys: separate Apple and Google public SDK keys, configured at `App.kt` init behind a small
  `ProConfig`, following the `SupabaseConfig.isConfigured` pattern. **Unconfigured must be inert**: no
  paywall, no meter, scans behave exactly as they do today. Every screen must survive the SDK being
  absent, the same way the app is fully usable with Supabase unconfigured.
- The RevenueCat **secret** API key lives only in the `activate-pass` edge function env. Never in
  `shared`, never in either app target.
- `logIn(supabaseUserId)` so the RevenueCat app user id is our user id, which makes the webhook's
  attribution and any support lookup possible.
- Offerings drive the paywall. Do not hardcode the three packages in Kotlin beyond a fallback ordering, so
  pricing experiments are a dashboard change.
- Set the `group_id` subscriber attribute immediately before `purchase()` — it is the webhook's only
  route back to the group (§6.2).

---

## 10. Analytics

Reuse `platform/Analytics.kt`. Minimum set to answer "is 5 the right number":

| Event | Properties |
| --- | --- |
| `free_scan_used` | `group_id`, `scans_used`, `scans_remaining` |
| `paywall_shown` | `group_id`, `trigger` (scan / export / badge), `scans_used` |
| `paywall_dismissed` | `group_id`, `trigger` |
| `pass_purchase_started` | `group_id`, `tier` |
| `pass_activated` | `group_id`, `tier`, `store`, `stacked` (bool) |
| `pass_activation_failed` | `group_id`, `tier`, `reason` |
| `pass_expired` | `group_id`, `tier`, `scans_during_pass` |
| `manual_entry_after_paywall` | `group_id` |

`scans_during_pass` is the one that tells us whether the pass was worth it to *them*, and
`manual_entry_after_paywall` is the honest measure of how many people we pushed into the slow path. Both
are more useful than conversion rate alone.

---

## 11. Build order

Each step ends green on **both** platforms (`AGENTS.md` §5) and is its own commit.

1. **Schema.** `group_passes` + its RLS + `group_pro_status` + the new `quota_exhausted` outcome value.
   Server first, always (`AGENTS.md` §4).
2. **Enforcement, no paywall.** Require `groupId`; add the Pro and free checks to `extract-receipt`;
   return 402. Client maps it to `Blocked("quota_exhausted")` and shows a plain "no scans left" message.
   *At this point the product is honest but unsellable, and every later step is additive.*
3. **Sync.** `GroupPassEntity` + DAO + `SyncEngine` table + doorbell trigger + the Kotlin mirror of
   `group_pro_status`. Pro state is now readable offline on every member's device.
4. **Meter and badge.** Free-scans-left row, Pro badge with buyer name, expiry state. No purchase yet.
5. **RevenueCat SDK + paywall + `activate-pass`.** The purchase round trip. Sandbox on both stores.
6. **`revenuecat-webhook`.** Backstop activation and refund revocation.
7. **Export gating** (§3) — build the `export_group` entry point in Group Settings, Pro-gated. **Droppable
   if the clock runs out**; nothing above depends on it, and cutting it does not make the pass dishonest,
   it only makes it thinner. If cut, remove export from the paywall copy in the same commit rather than
   promising it.
8. **`ux-firsttimer` pass**, both personas, all P0/P1 cleared.

---

## 12. Testing

- **Money math**: `group_pro_status` stacking, expiry boundary (`expires_at == now` is expired), revoked
  passes ignored, latest-expiring wins. Same vectors against the SQL and the Kotlin mirror.
- **Idempotency**: the same `store_txn_id` submitted three times yields one pass row and three identical
  success responses.
- **Enforcement**: 5 ok scans then a 6th ⇒ 402 with no Anthropic call made (assert on the absence of the
  call, not just the status). Failed / not-receipt / rate-limited scans do not decrement.
- **Free-tier fallback**: pass expires mid-session ⇒ next scan is 402, existing bills fully intact.
- **Unconfigured RevenueCat**: whole app behaves as it does today, no paywall, no crash.
- **Store sandbox**: iOS StoreKit config file for simulator, Play license testers for Android. Verify on a
  real device on both platforms before calling it done, and leave the sim running (`AGENTS.md` §4.2).

---

## 13. Non-goals

- No auto-renewing subscription, now or later, under any framing.
- No per-person Pro, no account-level Pro, no cross-group pass.
- No transfer of a pass between groups.
- No free-scan reset, monthly or otherwise.
- No gating of anything a friend needs in order to be paid back.
- No ads.
- No countdown timers, no "your pass expires in 2 days!" pressure. It ends, we say so once, they buy again
  or they do not.
