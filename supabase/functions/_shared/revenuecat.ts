// Shared RevenueCat access + the two writes that turn a purchase into an entitlement
// (`PRO_PASS_SPEC.md` §6). `sync-subscriber`, `activate-pass` and `revenuecat-webhook` are thin HTTP
// shells over this file, which is the point: the webhook must re-run the *same* logic rather than a
// second copy that can drift, and "one code path driven by RevenueCat's answer rather than by the event
// name" is what stops an event type we have never seen from corrupting state.
//
// **v1 `/subscribers/{app_user_id}`, not v2.** The spec says "REST v2", but v2's customer endpoints
// expose subscriptions and entitlements only; the one thing `activate-pass` has to verify is a
// **consumable** purchase, and non-subscription purchases are returned by v1 and not by v2. Using two
// API versions across three functions to gain nothing would be the worse trade.
//
// The SECRET key lives only in edge-function env. It can read and modify any customer in the project, so
// it must never appear in `shared/` or either app target — the apps carry the public SDK keys, which can
// fetch offerings and start a purchase and nothing else.

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

/** One consumable purchase as RevenueCat records it. `store_transaction_id` is what the client sends. */
export interface RcNonSubscription {
  id: string;
  store: string;
  store_transaction_id?: string;
  purchase_date: string;
  is_sandbox?: boolean;
}

export interface RcEntitlement {
  expires_date: string | null;
  product_identifier: string;
  purchase_date: string;
}

export interface RcSubscriptionInfo {
  store: string;
  expires_date: string | null;
  purchase_date: string;
  unsubscribe_detected_at: string | null;
  billing_issues_detected_at: string | null;
  refunded_at?: string | null;
  period_type?: string;
}

export interface RcSubscriber {
  entitlements: Record<string, RcEntitlement>;
  subscriptions: Record<string, RcSubscriptionInfo>;
  non_subscriptions: Record<string, RcNonSubscription[]>;
  subscriber_attributes: Record<string, { value?: string }>;
}

/** The entitlement id the two subscription products are attached to. Consumables are attached to NO
 *  entitlement on purpose: a consumable on an entitlement reads as unlocked forever after one purchase,
 *  which is the exact opposite of a pass that expires (`PRO_PASS_SPEC.md` §2.3). */
export const PRO_ENTITLEMENT = "pro";

/** The subscriber attribute carrying the group a pass was bought for. Set by the client immediately
 *  before `purchase()`, and the webhook's ONLY route back to the group (§6.3). */
export const GROUP_ID_ATTRIBUTE = "evenly_group_id";

export const DAY_MILLIS = 86_400_000;

/** Our three consumables and what each one buys. Durations are OURS, not the store's: the product is a
 *  consumable, so nothing in the receipt says how long it lasts and the server stamps the expiry.
 *  30 days means 30 x 86_400_000, never a calendar month. */
export const PASS_TIERS: Record<string, { tier: string; days: number }> = {
  "app.splitevenly.pass.week1": { tier: "week_1", days: 7 },
  "app.splitevenly.pass.week2": { tier: "week_2", days: 14 },
  "app.splitevenly.pass.month1": { tier: "month_1", days: 30 },
};

/**
 * Fetch what RevenueCat believes this app user owns.
 *
 * Returns null when the key is unset (the functions are then inert, like every other unconfigured
 * integration in this project) or when RevenueCat is unreachable. Callers **fail closed** on null: this
 * is the authority on whether money changed hands, so an unreadable answer must never be read as "yes".
 */
export async function fetchSubscriber(appUserId: string): Promise<RcSubscriber | null> {
  const key = Deno.env.get("REVENUECAT_SECRET_KEY");
  if (!key) return null;
  try {
    const res = await fetch(
      `https://api.revenuecat.com/v1/subscribers/${encodeURIComponent(appUserId)}`,
      { headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" } },
    );
    if (!res.ok) return null;
    const body = await res.json();
    const s = body?.subscriber;
    if (!s) return null;
    return {
      entitlements: s.entitlements ?? {},
      subscriptions: s.subscriptions ?? {},
      non_subscriptions: s.non_subscriptions ?? {},
      subscriber_attributes: s.subscriber_attributes ?? {},
    };
  } catch {
    return null;
  }
}

/**
 * RevenueCat's store name to ours. Anything we do not recognise returns null and the caller refuses:
 * `group_passes.store` and `user_subscriptions.store` both carry a CHECK constraint, so guessing here
 * would turn an unknown store into a failed insert further down where it is harder to explain.
 */
export function mapStore(
  rcStore: string | undefined,
): "app_store" | "play_store" | "promo" | "test_store" | null {
  switch (rcStore?.toLowerCase()) {
    case "app_store":
    case "mac_app_store":
      return "app_store";
    case "play_store":
      return "play_store";
    case "promotional":
      return "promo";
    case "test_store":
    case "rc_test_store":
      // RevenueCat's Test Store: a real purchase flow over simulated money, which is what lets the pass
      // and subscription round trips be verified before a store product exists. Kept as its own value
      // rather than folded into `promo` so a test row is never mistaken for revenue, and **gated on
      // `PRO_ALLOW_TEST_STORE`**: the test SDK key ships inside every dev build, so an ungated path
      // would let anyone holding one mint themselves unlimited paid vision calls. Unset means refuse,
      // matching every other unconfigured integration here.
      return Deno.env.get("PRO_ALLOW_TEST_STORE") === "true" ? "test_store" : null;
    default:
      return null;
  }
}

/** ISO-8601 to epoch millis, or null. RevenueCat dates are UTC strings; our columns are bigint millis. */
export function toMillis(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
}

/**
 * `monthly` or `annual`, from the product id.
 *
 * RevenueCat's v1 subscriber document carries no duration field (`period_type` is trial/intro/normal,
 * not the billing period), so the product id is the only signal available. It is a soft mapping on
 * purpose: `period` renders "renews 16 Aug" against "ends 16 Aug" and nothing else, and **never decides
 * entitlement** — `expires_at` alone does. A product id we cannot classify falls back to monthly, which
 * is a wrong word on one row rather than a wrongly-granted month.
 */
export function periodOf(productId: string): "monthly" | "annual" {
  const id = productId.toLowerCase();
  return id.includes("annual") || id.includes("year") ? "annual" : "monthly";
}

export interface SyncResult {
  ok: boolean;
  isPro?: boolean;
  reason?: string;
  status: number;
}

/**
 * Mirror one person's subscription state into `user_subscriptions` (§6.1).
 *
 * The single code path for the subscription half of Evenly Pro. `sync-subscriber` calls it for the
 * signed-in caller; the webhook calls it for the event's `app_user_id`, for **every** lifecycle event
 * (initial purchase, renewal, product change, cancellation, expiration, billing issue), because the
 * answer always comes from re-reading RevenueCat rather than from interpreting the event name.
 */
export async function syncSubscription(
  db: SupabaseClient,
  appUserId: string,
  prefetched?: RcSubscriber | null,
): Promise<SyncResult> {
  const subscriber = prefetched ?? await fetchSubscriber(appUserId);
  // Inert when unconfigured, and fail-closed when unreachable. Never "no entitlement found, so expire
  // them": a RevenueCat outage would then quietly un-Pro every paying customer who opened the app.
  if (!subscriber) return { ok: false, reason: "revenuecat_unavailable", status: 503 };

  const now = Date.now();
  const entitlement = subscriber.entitlements[PRO_ENTITLEMENT];
  const productId = entitlement?.product_identifier;
  const info = productId ? subscriber.subscriptions[productId] : undefined;

  if (!entitlement || !productId || !info) {
    // No `pro` entitlement at all. Expire whatever row exists rather than deleting it: the row is also
    // how the app says "ended 16 Aug", and a hard delete would take that with it. No row and nothing to
    // write is a perfectly normal state (someone who has never subscribed).
    const { data: existing } = await db
      .from("user_subscriptions").select("user_id").eq("user_id", appUserId).maybeSingle();
    if (existing) {
      await db.from("user_subscriptions")
        .update({ expires_at: now, will_renew: false, updated_at: now })
        .eq("user_id", appUserId);
    }
    return { ok: true, isPro: false, status: 200 };
  }

  const store = mapStore(info.store);
  // An unrecognised store would fail the CHECK constraint on insert; refusing here says so plainly
  // instead of surfacing as a database error with no explanation.
  if (!store) return { ok: false, reason: "unknown_store", status: 422 };

  const expiresAt = toMillis(entitlement.expires_date);
  // A `pro` entitlement with no expiry would be a lifetime product, which Evenly does not sell. Treating
  // it as Pro-forever off an unexpected payload is exactly the kind of guess this table exists to avoid.
  if (expiresAt === null) return { ok: false, reason: "no_expiry", status: 422 };

  const revokedAt = toMillis(info.refunded_at ?? null);
  const { error } = await db.from("user_subscriptions").upsert({
    user_id: appUserId,
    product_id: productId,
    store,
    rc_app_user_id: appUserId,
    period: periodOf(productId),
    started_at: toMillis(entitlement.purchase_date) ?? now,
    // The store's own paid-through date. Someone who cancels stays Pro to the end of the period they
    // already paid for, which is both correct and the difference between a lapse and a support ticket.
    expires_at: expiresAt,
    // Renders the verb on the Profile row and nothing else. A billing issue is NOT a revocation: the
    // grace period is exactly when a subscriber must not lose what they paid for.
    will_renew: !info.unsubscribe_detected_at,
    revoked_at: revokedAt,
    updated_at: now,
  }, { onConflict: "user_id" });
  if (error) return { ok: false, reason: "write_failed", status: 503 };

  // The doorbell trigger on this table wakes every group the subscriber is active in, so the other five
  // phones learn the group went Pro through the normal pull. Nothing to do here.
  return { ok: true, isPro: expiresAt > now && !revokedAt, status: 200 };
}

export interface ActivateResult {
  ok: boolean;
  expiresAt?: number;
  stacked?: boolean;
  alreadyActive?: boolean;
  reason?: string;
  status: number;
}

/**
 * Turn one verified consumable purchase into a `group_passes` row (§6.2).
 *
 * Shared by `activate-pass` (the client's own call, right after the charge) and the webhook backstop
 * (the client died between the charge and that call). Both must produce exactly one pass for one
 * transaction, which `(store, store_txn_id)` guarantees; a unique violation here is **success**, not a
 * failure, and that is what makes the client's retry free rather than merely harmless.
 */
export async function activatePass(
  db: SupabaseClient,
  args: { appUserId: string; groupId: string; storeTxnId: string; subscriber: RcSubscriber },
): Promise<ActivateResult> {
  const { appUserId, groupId, storeTxnId, subscriber } = args;

  // The purchaser must actually be in the group the pass is for. Without this, anyone could bind their
  // purchase to any group id, and six strangers would get paid vision calls on it.
  const { data: membership, error: memberError } = await db
    .from("members").select("id")
    .eq("group_id", groupId).eq("user_id", appUserId).eq("status", "ACTIVE")
    .maybeSingle();
  if (memberError) return { ok: false, reason: "membership_unavailable", status: 503 };
  if (!membership) return { ok: false, reason: "not_a_member", status: 403 };

  // Already activated? Answer success without touching anything. This is the common path on a retry.
  const { data: existing } = await db
    .from("group_passes").select("group_id, expires_at").eq("store_txn_id", storeTxnId).maybeSingle();
  if (existing) {
    // A transaction id is globally unique, so a mismatch means someone is trying to attach one group's
    // purchase to another group. Refused, and deliberately not treated as a retry.
    if (existing.group_id !== groupId) return { ok: false, reason: "wrong_group", status: 409 };
    return { ok: true, expiresAt: existing.expires_at, alreadyActive: true, status: 200 };
  }

  // Find the consumable RevenueCat recorded under THIS app user with THIS transaction id. Scoping the
  // search to that subscriber's own document is what stops one person activating another's purchase: a
  // transaction id alone is not proof of ownership.
  let productId: string | null = null;
  let rcStore: string | undefined;
  for (const [product, purchases] of Object.entries(subscriber.non_subscriptions)) {
    const hit = purchases.find((p) => p.store_transaction_id === storeTxnId || p.id === storeTxnId);
    if (hit) {
      productId = product;
      rcStore = hit.store;
      break;
    }
  }
  if (!productId) return { ok: false, reason: "not_found", status: 404 };

  const tier = PASS_TIERS[productId];
  // A real purchase of something that is not one of our passes (a subscription, or a product from
  // another app on the same account). Not something to guess a duration for.
  if (!tier) return { ok: false, reason: "wrong_product", status: 422 };

  const store = mapStore(rcStore);
  if (!store) return { ok: false, reason: "unknown_store", status: 422 };

  const now = Date.now();
  // Stacking (§5.4): a pass bought while the group is already Pro starts at the CURRENT expiry, not at
  // now, so two friends who each buy a week give the group two weeks instead of one wasted. Reads passes
  // only, not subscriptions: a subscription is not extended by a pass, and starting a pass after
  // someone else's renewal date would be selling dead time.
  const { data: liveRows } = await db
    .from("group_passes").select("expires_at")
    .eq("group_id", groupId).is("deleted_at", null).is("revoked_at", null)
    .gt("expires_at", now).order("expires_at", { ascending: false }).limit(1);
  const currentExpiry = liveRows?.[0]?.expires_at as number | undefined;
  const startsAt = Math.max(now, currentExpiry ?? now);
  const expiresAt = startsAt + tier.days * DAY_MILLIS;

  const { error: insertError } = await db.from("group_passes").insert({
    id: crypto.randomUUID(),
    group_id: groupId,
    purchased_by: appUserId,
    tier: tier.tier,
    store,
    store_txn_id: storeTxnId,
    rc_app_user_id: appUserId,
    starts_at: startsAt,
    expires_at: expiresAt,
    created_at: now,
    updated_at: now,
  });
  if (insertError) {
    // 23505 is the idempotency key doing its job: the client's call (or a racing webhook retry) inserted
    // the same purchase between the read above and this write. That is a success.
    const { data: raced } = await db
      .from("group_passes").select("expires_at").eq("store_txn_id", storeTxnId).maybeSingle();
    if (raced) return { ok: true, expiresAt: raced.expires_at, alreadyActive: true, status: 200 };
    return { ok: false, reason: "write_failed", status: 503 };
  }

  return { ok: true, expiresAt, stacked: currentExpiry !== undefined, status: 200 };
}
