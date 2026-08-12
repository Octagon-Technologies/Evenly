// Shared RevenueCat REST access for the three Evenly Pro functions (`PRO_PASS_SPEC.md` §6).
//
// **v1 `/subscribers/{app_user_id}`, not v2.** The spec says "REST v2", but v2's customer endpoints
// expose subscriptions and entitlements only; the one thing `activate-pass` has to verify is a
// **consumable** purchase, and non-subscription purchases are returned by v1 and not by v2. Using two
// API versions across three functions to gain nothing would be the worse trade, so all three read the
// same v1 document. Re-check this if RevenueCat ever adds non-subscriptions to v2.
//
// The SECRET key lives only here, in edge-function env. It can read and modify any customer in the
// project, so it must never appear in `shared/` or either app target — the apps carry the public SDK
// keys, which can fetch offerings and start a purchase and nothing else.

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
}

/** The entitlement id the two subscription products are attached to. Consumables are attached to NO
 *  entitlement on purpose: a consumable on an entitlement reads as unlocked forever after one purchase,
 *  which is the exact opposite of a pass that expires (`PRO_PASS_SPEC.md` §2.3). */
export const PRO_ENTITLEMENT = "pro";

/**
 * Fetch what RevenueCat believes this app user owns.
 *
 * Returns null when the key is unset (the function is then inert, like every other unconfigured
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
export function mapStore(rcStore: string | undefined): "app_store" | "play_store" | "promo" | null {
  switch (rcStore) {
    case "app_store":
    case "mac_app_store":
      return "app_store";
    case "play_store":
      return "play_store";
    case "promotional":
      return "promo";
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
