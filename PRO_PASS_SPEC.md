# Evenly Pro — subscription + group passes (RevenueCat)

**Status:** steps 1-4 and 7 built (`e6d75f0`, `fcca341`, `e0f5109`, `887fc87`, `1ae6651`). Steps 5-6 not
started, which is why **no user can become Pro today**: nothing in the app or the edge functions ever
writes a `group_passes` row. Every gate currently ends in a wall with no door.

**Revised 2026-08-11.** v1 sold a consumable group pass only, and §13 forbade an auto-renewing
subscription outright. The owner reversed that after the finding in §2.1: RevenueCat's paywall editor and
its Experiments cannot touch consumables, so a consumable-only product could never have a
remotely-designed, A/B-testable paywall. Evenly now sells **both**, and this file is rewritten in place
rather than corrected in an appendix.

---

## 1. What we are selling, in one paragraph

A group gets **5 receipt scans, free, ever**. Past that there are two doors, and the app shows both:

- **Evenly Pro** — a personal subscription, $2.99/month or $19.99/year. Every group you are an active
  member of gets unlimited scans and export while it is live. One friend subscribes and the whole trip
  benefits.
- **A group pass** — one week, two weeks, or one month, bought once for **one** group. It **ends on its
  own**: no renewal, no card on file, nothing to cancel.

The pass exists because of the failure mode that made v1 subscription-free: a friend buys a $1 unlock for
a ski weekend and gets billed for two years. That is a 1-star review of a money app. We did not remove
that risk by adding a subscription; we **kept the escape hatch next to it** and made the recurring option
say its renewal date out loud before the charge (§8.2). Anyone who only came for one trip can take the
door that cannot bill them twice.

### Why a group pass and not only a personal one

Evenly's unit of value is the group, not the person. A trip has one receipt-heavy weekend and then goes
quiet. Charging each of six friends separately to split one dinner is absurd; charging the trip $2 once is
not. It also removes the worst freemium moment in a social app: five people staring at a bill while one
of them hits a paywall.

---

## 2. Tiers and pricing

### 2.1 The finding that shaped this

RevenueCat's own docs: *"Paywalls are only compatible with packages associated with subscription or
non-consumable products like 'lifetime' purchases."* Consumables may sit in an **Offering** and be
purchased normally, but the paywall editor cannot render them, and Experiments are built on paywalls and
offerings. A consumable-only Evenly could therefore never run a paywall experiment or change a paywall
without an app release.

Non-consumable is not a way out: a store account can buy one exactly once, ever, which kills repeat
passes. So the recurring tier is what unlocks remote design and experiments, and it is the **subscription
alone** that is sold through RevenueCat's editor.

Cost is not a reason to hesitate: Experiments sits on RevenueCat's Pro plan, which is **free up to
$2,500/month tracked revenue**.

### 2.2 Evenly Pro — auto-renewing, personal

| Package | Period | Price | Store product id | RevenueCat package |
| --- | --- | --- | --- | --- |
| Monthly | 1 month | $2.99 | `app.splitevenly.pro.monthly` | `$rc_monthly` |
| Annual | 1 year | $19.99 | `app.splitevenly.pro.annual` | `$rc_annual` |

One Apple **subscription group** ("Evenly Pro") holding both, so a subscriber can move between monthly and
annual and can only ever hold one at a time. One Play **subscription** with two base plans. Entitlement id
`pro`. Offering `default`, which is what the paywall and every experiment reads.

Annual is ~44% off the monthly run rate and is the anchor. Do not add a weekly: weekly auto-renew is the
highest-regret shape on the App Store and it competes directly with the one-week pass, which is the
honest product for that buyer.

### 2.3 Group pass — one-off, consumable

| Pass | Duration | Price | Store product id | RevenueCat package |
| --- | --- | --- | --- | --- |
| 1 week | 7 days | $0.99 | `app.splitevenly.pass.week1` | `pass_week_1` |
| 2 weeks | 14 days | $1.99 | `app.splitevenly.pass.week2` | `pass_week_2` |
| 1 month | 30 days | $3.99 | `app.splitevenly.pass.month1` | `pass_month_1` |

Consumable on both stores. Offering `group_pass`, **attached to no entitlement** — a consumable on an
entitlement makes RevenueCat report it unlocked forever after one purchase, which is the exact opposite of
a pass that expires.

The 1-month pass at $3.99 sits **above** the $2.99 monthly subscription on purpose. That is a commitment
discount, the standard shape, and it is honest: you are paying a little more for the version that cannot
bill you again. The paywall must not hide the comparison; someone who does the arithmetic and subscribes
is a better outcome than someone who feels tricked.

Durations here are ours, not the store's, because the product is consumable and the server stamps the
expiry. The v1 ladder survives intact. (Contrast the subscription: **Apple has no 2-week auto-renew
period** — 1 week, 1 month, 2, 3, 6 months, 1 year are the only options — which is why §2.2 has no
fortnightly tier.)

**Never hardcode a price string.** Always render RevenueCat's localized `StoreProduct` price. App Review
rejects hardcoded prices, and a Kenyan or European user must see their own currency. Every price above is
a target for the console, not a literal for Kotlin.

Duration is measured **server-side in milliseconds from the moment the server activates the pass**, not
from the client clock and not in calendar months. 30 days means 30 × 86_400_000.

---

## 3. What Pro unlocks

| Capability | Free | Pro (either route) |
| --- | --- | --- |
| Receipt scans (`extract-receipt`) | 5 per group, lifetime | Unlimited |
| Group data export (`export_group`) | No | Yes |
| Everything else in Evenly | Yes | Yes |

**Everything else stays free forever** — unlimited groups, expenses, members, itemized splits, settle-up,
the web claim flow, manual bill entry. We gate the thing that costs real money per use (Claude vision)
plus one end-of-trip convenience. Nothing a friend needs in order to be paid back is ever behind a
paywall.

**Manual bill entry is never gated, and that is load-bearing.** A group at 0 free scans can still itemize
a bill by typing it. The paywall must read as "want the fast way?" and never as "you cannot use the app."

---

## 4. The free allowance

**5 successful scans per group, for the life of the group. It never resets.** Counted server-side from
`receipt_scan_log`, which already carries `group_id`. No new counting table.

Only `outcome = 'ok'` counts. A blurry photo, a non-receipt, a server failure or a rate-limited attempt
must **not** burn a free scan — being charged for our own failure is a support ticket and a bad review.
Built and enforced in `fcca341`; unchanged by this revision.

---

## 5. Entitlement model — the server owns it

**RevenueCat tells us a purchase happened; our database decides who is Pro.** The client never asserts
entitlement, and `CustomerInfo` on the buyer's device is not the source of truth. It cannot be: five other
people in the group need to be Pro on their own devices and they bought nothing.

### 5.1 `group_passes` — built, unchanged

`schema.sql:2212`. Server-owned, pull-only, `(store, store_txn_id)` unique as the idempotency key, RLS
readable by ACTIVE members and writable by nobody but the service key. See the comments in the schema;
none of it changes.

### 5.2 `user_subscriptions` — new

```sql
create table if not exists public.user_subscriptions (
  user_id         text primary key,       -- users.id; one live subscription per person by construction
  product_id      text not null,
  store           text not null,          -- app_store | play_store | promo
  rc_app_user_id  text not null,
  period          text not null,          -- monthly | annual
  started_at      bigint not null,
  expires_at      bigint not null,        -- store-provided period end, server clock
  will_renew      boolean not null,
  revoked_at      bigint,                 -- refund / chargeback
  updated_at      bigint not null,
  row_version     bigint not null default 1
);
create index if not exists user_subscriptions_live_idx on public.user_subscriptions (expires_at desc);
```

`user_id` as the primary key, not a row per purchase: a store account holds at most one live subscription
in a group, so renewals **update** one row rather than accumulating history. Billing history lives in
RevenueCat, which is better at it than we are.

`expires_at` is the paid-through date, so a cancelled-but-not-yet-expired subscriber stays Pro to the end
of the period they paid for. `will_renew` exists only to render "renews Aug 16" vs "ends Aug 16" — it
never decides entitlement.

**RLS.** Same shape as `group_passes` and for the same reason: insert/update/delete revoked from `anon`
and `authenticated`, TRUNCATE revoked separately (RLS does not cover TRUNCATE), service key only. Read is
scoped to *people you share a group with*:

```sql
create policy user_subscriptions_shared_group_read on public.user_subscriptions
  for select to authenticated
  using (
    exists (
      select 1 from public.members me
      join public.members them on them.group_id = me.group_id
      where me.user_id = (select auth.uid())::text and me.status = 'ACTIVE'
        and them.user_id = user_subscriptions.user_id and them.status = 'ACTIVE'
    )
  );
```

That is deliberately narrow. The badge names the person paying (§8.4), which is the social half of the
design, but it must not leak one stranger's billing state to another. Nothing beyond "this person has Pro
and it runs until X" is readable, ever: no price, no store, no product id in the client's view of it.

### 5.3 `group_pro_status` — one function, extended

The single change point for this whole revision. Every gate already calls it (`extract-receipt:359`,
`export_group:108`), so **neither edge function changes at all**.

```sql
create or replace function public.group_pro_status(p_group_id text, p_now bigint)
returns table (is_pro boolean, expires_at bigint, purchased_by text, tier text, source text)
```

Returns at most one row; **no row means not Pro**. It now takes the later of two candidates:

1. the latest-expiring live pass for the group (`source = 'pass'`), and
2. the latest-expiring live subscription held by any ACTIVE member of the group (`source = 'subscription'`),

live meaning `deleted_at is null and revoked_at is null and expires_at > p_now`. `purchased_by` is the
member in both cases, which keeps "who paid for this?" answerable without the caller knowing which route
it came from.

Adding a return column is a **breaking signature change** for a Postgres function: `create or replace`
fails and it must be `drop function` then create. It ships safely on its own even so, and that was
checked rather than assumed: both callers test only whether a row came back
(`extract-receipt:361`, `export_group:110`), never a column, so neither needs redeploying.

The client mirrors this in Kotlin over local rows so the badge works offline; enforcement is always the
SQL copy. Two implementations of one rule is a real wart, taken on so that drawing a badge costs no round
trip. Pin both with the same vectors.

### 5.4 Stacking, and the two routes overlapping

A pass bought while the group is already Pro **extends from the current expiry, not from now**:
`starts_at = max(now, current_max_expires_at)`. Two friends who each buy a week give the group two weeks.
`group_pro_status` picking the later expiry makes this fall out for free.

A subscriber buying a pass for a group they are already in is the one purchase we actively talk someone
**out of**. The pass sheet checks `source` first: if this group is already Pro because of your own
subscription, it says so and offers no purchase. Selling someone something they already have is how a
money app loses trust.

### 5.5 Client sync

`user_subscriptions` is pulled like any other synced table (Room mirror, `SyncEngine` table list,
doorbell). Its doorbell trigger must bump **every group the subscriber is an active member of**, not one
group, because one renewal changes the badge in all of them. The Kotlin mirror joins the local
`user_subscriptions` rows against the local `members` roster, which the app already holds.

---

## 6. Purchase flows

### 6.1 Subscription — `sync-subscriber` (new edge function)

```
[RC paywall] --purchase--> RevenueCat SDK --> store
                                              |
                       success (CustomerInfo)  v
[client] --POST /sync-subscriber (no body, auth header)-->
                                              |
                     GET RevenueCat REST v2 /subscribers/{rc_app_user_id}
                          (secret key, edge-function env only)
                                              |
                     upsert user_subscriptions from the authoritative answer
                                              |
                     bump every group this user is active in (doorbell)
```

It takes **no body**. It resolves the caller from their JWT, asks RevenueCat what that subscriber owns,
and writes what it is told. Deliberately different from `activate-pass` below: with no client-supplied
transaction id there is nothing to forge and nothing to make idempotent, it is safe to call on every
launch, and it doubles as **restore** with no separate code path.

Called: after a successful purchase, on app launch when a subscription is known or suspected, and from
the Restore control (§8.6).

### 6.2 Group pass — `activate-pass` (new edge function)

Unchanged from v1. Verifies the transaction against the RevenueCat REST API server-side, confirms it is
one of our three pass product ids, then inserts with `(store, store_txn_id)` as the idempotency key.
It needs the client's `{ groupId, storeTxnId }` because **the group binding exists nowhere in the store's
data model** — only we know which group the money was for.

Reasons it exists rather than trusting client `CustomerInfo`: a modified client can claim any purchase and
the reward is unlimited paid vision calls for six people; duration must be measured on the server clock, or
a wound-back device clock is a free month.

Failure must be **safe and recoverable**: if the store charged and this call fails, the client retries the
same `storeTxnId` on next launch and on next paywall open. Idempotency makes retrying free. The buyer sees
"Payment went through. Turning on Pro…" with a retry, never a silent loss.

### 6.3 `revenuecat-webhook` (new edge function)

`verify_jwt = false`, authenticated by a shared secret in the `Authorization` header, same pattern as
`web-claim`. Three jobs:

- **Subscription lifecycle**: `INITIAL_PURCHASE`, `RENEWAL`, `PRODUCT_CHANGE`, `CANCELLATION`,
  `EXPIRATION`, `BILLING_ISSUE` all resolve to the same thing — re-run §6.1's logic for that
  `app_user_id`. One code path, driven by RevenueCat's answer rather than by the event name, so an event
  type we have not seen before cannot corrupt state.
- **Pass backstop**: `NON_RENEWING_PURCHASE` for a transaction we never activated (the client died
  between the charge and our call) gets activated here. The group id rides along as a RevenueCat
  **subscriber attribute** set immediately before `purchase()`. If it is absent the event is parked in an
  ops table and alerted, never guessed.
- **Refunds**: stamps `revoked_at`. The group drops back to free at the next pull. Already-scanned bills
  are untouched. We never claw back data.

### 6.4 Restore

The subscription needs a real **Restore purchases** control — App Review requires one and a reinstalling
subscriber needs it. It calls `Purchases.restorePurchases()` then `sync-subscriber`.

The **pass** has no store-side restore, and needs none: it belongs to the group and syncs to every
member's device. The pass sheet says "Your group's pass follows the group, not this phone."

---

## 7. Enforcement

Unchanged and already shipped. `extract-receipt` checks, in order: caller resolved (401), `groupId`
required (400), per-user rate limit (429), `group_pro_status` (proceed), free count < 5 (proceed),
otherwise **402** `reason: "quota_exhausted"` logged before any Anthropic call. `export_group` returns
**402** `reason: "pro_required"`. Because §5.3 widens `group_pro_status` underneath them, both gates gain
the subscription route for free.

402 rather than 429 because the client must tell "wait a bit" apart from "buy something" — different
screens.

**The race we accept:** two people scan simultaneously with one free scan left, both pass, the group ends
at 6 of 5. We eat one scan rather than putting a lock on a hot path to protect a sub-cent cost.

---

## 8. UI

Mock before writing Compose (`AGENTS.md` §7). Every string below is draft copy under `ui/AGENTS.md`, and
contains no em dashes (the `em-dash-guard` hook blocks them in Kotlin literals).

### 8.1 The four entry points

Today there are none, which is the bug this revision exists to fix.

| Where | State | What it does |
| --- | --- | --- |
| Profile / app settings | Always | "Evenly Pro" row. Subscribed: status + manage + restore. Not: opens the paywall. |
| Group Settings | Always | Pro row. Free groups currently render **nothing** here; they must show "Get Pro for this group". |
| Scan card at 0 free | On tap | Opens the paywall. The card stays enabled. |
| Export CSV row | On tap | Opens the paywall instead of the current dead end. |

The Group Settings row is a genuine bug beyond the missing paywall: `GroupSettingsRoute.kt:70` renders the
row only when `everHadPass`, so a group that never bought one sees no Pro surface at all.

### 8.2 The paywall — RevenueCat's, not ours

The subscription paywall is **built in the RevenueCat dashboard** and rendered by the `Paywall()`
composable from `purchases-kmp-ui`. That is the whole point of §2.1: layout, copy and price mix become a
dashboard change, and Experiments can test them without an app release.

Requirements that are non-negotiable and that the RC template already has fields for (App Review rejects
paywalls missing any of them): price and period stated plainly, an explicit auto-renew disclosure, links
to Terms and Privacy, and Restore.

On top of the template's own copy, the offering's configuration must carry:

- **The renewal date said out loud before the charge**, not after. This is the mitigation for the risk
  that kept v1 subscription-free, and it is not optional.
- **The one-off escape hatch**: "Only need it for one trip?" leading to §8.3. Preferred as a paywall
  footer link so the whole surface stays remotely editable; if the v2 editor cannot express the action, an
  Evenly-owned footer row sits directly beneath the `Paywall()` composable instead. Verify which at
  implementation time and take the dashboard-side option if it exists.

### 8.3 The group-pass sheet — ours

A dimmed `ScSheetScaffold` (a decision, not a browse), because RevenueCat cannot render consumables.

```
                 A pass for Ski Trip

  Ski Trip has used all 5 free scans. One pass and
  everyone in the group can scan as many receipts
  as they want.

   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
   │   1 week     │ │   2 weeks    │ │   1 month    │
   │    $0.99     │ │    $1.99     │ │    $3.99     │
   │              │ │  most picked │ │              │
   └──────────────┘ └──────────────┘ └──────────────┘

            [  Get the pass for Ski Trip  ]

   One time. It does not renew. Nothing to cancel.
   You will not be charged again.

   Covers this group only, and everyone in it. You
   can still add bills by hand for free.
```

Non-negotiable copy points: **"One time. It does not renew. Nothing to cancel."** above the button, not in
fine print, since it is the whole differentiator; the **group name** in the headline and on the button,
because buying for the wrong group is the #1 support risk; **"you can still add bills by hand for free"**
keeping the exit visible. Prices come from the `group_pass` offering, localized.

Already Pro via a pass: show the extend framing from §5.4. Already Pro via your own subscription: no
purchase offered (§5.4).

### 8.4 Choosing a group

Reached only from the Profile entry point, and only for the pass — a subscription needs no group, which is
the thing that makes it simpler to explain. The picker lists the user's active groups with each one's Pro
state inline, so a group already covered is visibly not worth buying for.

### 8.5 Group is already Pro

```
Pro until Aug 16
Sam got this for the group          (pass)
Pro because Sam subscribes          (subscription)
```

Naming the buyer is the design, not decoration: it turns a charge into a visible favour to friends rather
than an invisible tax. Built already in `ProStatusRow.kt`; it needs the subscription wording and the
free-group state added.

### 8.6 Expiry, and managing a subscription

No countdown pressure and no interstitial. On the day it ends: "Pro ended. Your bills and receipts are all
still here." with a way back.

**Nothing already scanned is ever taken away.** Expiry stops new scans only. Retroactively hiding
someone's receipts would be indistinguishable from data loss in a money app.

The subscription's manage control opens the **store's** management screen (`showManageSubscriptions`),
never an in-app imitation. Cancelling has to be as easy as buying, and a subscriber who cannot find the
cancel button leaves a 1-star review instead.

### 8.7 Run `ux-firsttimer` on all of it

Build-time gate, not an audit. Walk it cold as Sam the invited friend who bought nothing, as the person who
tapped scan at 0, and as someone who wants to cancel. Clear every P0/P1.

---

## 9. RevenueCat wiring

- **`purchases-kmp`** in `commonMain`, plus **`purchases-kmp-ui`** for the paywall composable. Requires
  Kotlin **2.3.20+** against our **2.3.21** anchor, and paywalls need `purchases-kmp` **1.8.2+**. Pin the
  exact version at implementation time and re-check what it drags in: a KMP SDK pulling a different Ktor is
  a build break, not a dependency bump.
- Paywall platform floors are iOS 15+ / Android 7.0 (API 24)+. Evenly is iOS 16 / minSdk 24, so both clear.
- Public SDK keys (separate Apple and Google) configured at `App.kt` init behind a `ProConfig`, following
  the `SupabaseConfig.isConfigured` pattern. **Unconfigured must be inert**: no paywall, no meter, scans
  behave exactly as today, nothing crashes. Same contract as the app being fully usable with Supabase
  unconfigured.
- The RevenueCat **secret** key lives only in edge-function env. Never in `shared`, never in either app
  target.
- `logIn(supabaseUserId)` so the RC app user id is our user id, which is what makes webhook attribution and
  support lookups possible. Call it on sign-in and `logOut()` on sign-out, or one device's subscription
  follows the next person who signs in on it.
- Offerings drive both surfaces. Do not hardcode packages in Kotlin beyond a fallback ordering, or pricing
  experiments stop being a dashboard change.
- Set the `group_id` subscriber attribute immediately before a **pass** `purchase()`; it is the webhook's
  only route back to the group (§6.3).

---

## 10. Console checklist (owner, outside this repo)

Nothing in §11 past step 3 can be verified without this. In progress as of 2026-08-11.

**App Store Connect** — subscription group "Evenly Pro" containing `app.splitevenly.pro.monthly` ($2.99,
1 month) and `app.splitevenly.pro.annual` ($19.99, 1 year); three **consumables**
`app.splitevenly.pass.week1/week2/month1` ($0.99/$1.99/$3.99). Localized display name and description on
each, a review screenshot, and the Terms of Use (EULA) and Privacy Policy URLs set on the app — Apple
rejects subscriptions without them. Paid Applications agreement active or nothing loads.

**Play Console** — one subscription `app.splitevenly.pro` with base plans `monthly` and `annual`; three
in-app products for the passes. A license-tester account for sandbox purchases.

**RevenueCat** — one project, two apps (App Store + Play, with the Play service-account JSON and the App
Store In-App Purchase key uploaded, or nothing verifies server-side). Entitlement `pro` attached to the
**two subscription products only, never the consumables** (§2.3). Offerings: `default` with `$rc_monthly`
+ `$rc_annual`, and `group_pass` with the three passes. A paywall on `default` built in the editor. A
webhook pointed at `revenuecat-webhook` with the shared secret. Copy the two public SDK keys and the secret
key back for §9.

---

## 11. Build order

Each step ends green on **both** platforms (`AGENTS.md` §5) and is its own commit.

1. ~~Schema, enforcement, sync, meter and badge~~ — **done** (`e6d75f0`…`887fc87`).
2. ~~Export gating~~ — **done** (`1ae6651`), currently a dead end pending step 6.
3. **Schema for the second route.** `user_subscriptions` + its RLS + the widened `group_pro_status` +
   the multi-group doorbell. Server first, always (`AGENTS.md` §4). No client change; both gates pick up
   the new route the moment it exists.
4. **Sync.** `UserSubscriptionEntity` + DAO + `SyncEngine` + the Kotlin mirror joining subscriptions
   against the local roster. Pro state readable offline through either route.
5. **RevenueCat SDK + `ProConfig`.** Inert when unconfigured. No UI yet.
6. **Entry points and the paywall.** The four surfaces in §8.1, the `Paywall()` composable, the pass
   sheet, the group picker, restore and manage. **This is the step that closes the dead ends the owner
   reported**, and it is the first step with any user-visible effect.
7. **`sync-subscriber` + `activate-pass`.** The purchase round trips. Sandbox on both stores.
8. **`revenuecat-webhook`.** Lifecycle, backstop activation, refund revocation.
9. **Analytics** (§12), then **`ux-firsttimer`**, both personas, all P0/P1 cleared.

Steps 3-5 can be built and merged before the console work in §10 finishes. Steps 6-8 can be *written*
but not *verified* until it does, and `AGENTS.md` §4.2 means unverified is not done.

---

## 12. Analytics

Reuse `platform/Analytics.kt`. RevenueCat's own dashboard covers revenue; these answer the questions it
cannot.

| Event | Properties |
| --- | --- |
| `free_scan_used` | `group_id`, `scans_used`, `scans_remaining` |
| `paywall_shown` | `group_id`, `trigger` (scan / export / group_settings / profile), `scans_used`, `offering_id` |
| `paywall_dismissed` | `group_id`, `trigger` |
| `pass_sheet_shown` | `group_id`, `from_paywall` (bool) |
| `purchase_started` | `group_id`, `product_id`, `kind` (subscription / pass) |
| `purchase_activated` | `group_id`, `product_id`, `kind`, `store`, `stacked` (bool) |
| `purchase_activation_failed` | `group_id`, `product_id`, `reason` |
| `pro_expired` | `group_id`, `kind`, `scans_during` |
| `manual_entry_after_paywall` | `group_id` |

`scans_during` says whether it was worth it to *them*; `manual_entry_after_paywall` is the honest measure
of how many people we pushed onto the slow path. Both beat conversion rate alone. `offering_id` is what
makes an experiment readable on our side as well as RevenueCat's.

---

## 13. Testing

- **Money math**: stacking, expiry boundary (`expires_at == now` is expired), revoked ignored,
  latest-expiring wins, and the new one — subscription and pass both live, later wins, correct `source`.
  Same vectors against the SQL and the Kotlin mirror.
- **Idempotency**: one `store_txn_id` submitted three times yields one pass row and three identical
  success responses. `sync-subscriber` called ten times yields one subscription row.
- **Enforcement**: 5 ok scans then a 6th ⇒ 402 with **no Anthropic call made** (assert on the absence of
  the call, not just the status). Failed / not-receipt / rate-limited scans do not decrement.
- **The second route**: a group with 0 free scans and no pass, one member subscribes ⇒ every member can
  scan. That member leaves ⇒ the group is free again at the next pull.
- **Lapse**: subscription expires mid-session ⇒ next scan is 402, existing bills fully intact.
- **Unconfigured RevenueCat**: the whole app behaves exactly as today. No paywall, no crash.
- **Store sandbox**: StoreKit config file on the simulator, Play license testers on Android. Verify a real
  purchase, a restore, and a cancel on both platforms before calling it done, and leave the sim running
  (`AGENTS.md` §4.2).

---

## 14. Non-goals

- No per-group subscription. A store account holds one subscription per group at a time, so "subscribe
  for this group" cannot be built — that is what the pass is for.
- No transfer of a pass between groups.
- No free-scan reset, monthly or otherwise.
- No gating of anything a friend needs in order to be paid back.
- No ads.
- No countdown timers and no "your pass expires in 2 days!" pressure. It ends, we say so once, they buy
  again or they do not.
- No in-app imitation of the store's cancel flow (§8.6).
