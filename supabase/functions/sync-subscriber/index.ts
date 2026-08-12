// Evenly Pro, route 2: mirror a person's subscription into our database (PRO_PASS_SPEC.md §6.1).
//
//   POST /sync-subscriber   (no body, Authorization: Bearer <user access token>)  ->  200 { ok, isPro }
//
// **It takes no body, and that is the design.** It resolves the caller from their JWT, asks RevenueCat
// what that subscriber owns, and writes what it is told. With no client-supplied transaction id there is
// nothing to forge and nothing to make idempotent, which is what makes it safe to call on every launch
// and what lets it double as **Restore** with no separate code path.
//
// Deliberately different from `activate-pass`, which cannot work this way: a group pass's binding to a
// group exists nowhere in the store's data model, so that one has to accept a client-supplied group id.
//
// RevenueCat says a purchase happened; **this database decides who is Pro.** It has to work that way:
// the other five people in the group bought nothing and still need Pro, so the buyer's device-side
// CustomerInfo cannot be the source of truth for them.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { fetchSubscriber, mapStore, PRO_ENTITLEMENT, toMillis } from "../_shared/revenuecat.ts";

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/**
 * `monthly` or `annual`, from the product id.
 *
 * RevenueCat's v1 subscriber document carries no duration field (`period_type` is trial/intro/normal,
 * not the billing period), so the product id is the only signal available here. It is a soft mapping on
 * purpose: `period` renders "renews 16 Aug" against "ends 16 Aug" and nothing else, and **never decides
 * entitlement** — `expires_at` alone does. A product id we cannot classify falls back to monthly, which
 * is a wrong word on one row rather than a wrongly-granted month.
 */
function periodOf(productId: string): "monthly" | "annual" {
  const id = productId.toLowerCase();
  return id.includes("annual") || id.includes("year") ? "annual" : "monthly";
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const authHeader = req.headers.get("Authorization") ?? "";
  const callerClient = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader } } },
  );
  const { data: userData } = await callerClient.auth.getUser();
  const callerId = userData?.user?.id;
  if (!callerId) return json({ error: "unauthorized" }, 401);

  const subscriber = await fetchSubscriber(callerId);
  // Inert when unconfigured, and fail-closed when unreachable. Never "no entitlement found, so expire
  // them": a RevenueCat outage would then quietly un-Pro every paying customer who opened the app.
  if (!subscriber) return json({ ok: false, reason: "revenuecat_unavailable" }, 503);

  // Service role for the write: `user_subscriptions` grants the client SELECT and nothing else, which is
  // the point — a client that could write it could hand every group it joins unlimited paid vision calls.
  const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const now = Date.now();

  const entitlement = subscriber.entitlements[PRO_ENTITLEMENT];
  const productId = entitlement?.product_identifier;
  const info = productId ? subscriber.subscriptions[productId] : undefined;

  if (!entitlement || !productId || !info) {
    // No `pro` entitlement at all. Expire whatever row exists rather than deleting it: the row is also
    // how the Profile screen says "ended 16 Aug", and a hard delete would take that with it. No row and
    // nothing to write is a perfectly normal state (someone who has never subscribed).
    const { data: existing } = await db
      .from("user_subscriptions").select("user_id").eq("user_id", callerId).maybeSingle();
    if (existing) {
      await db.from("user_subscriptions")
        .update({ expires_at: now, will_renew: false, updated_at: now })
        .eq("user_id", callerId);
    }
    return json({ ok: true, isPro: false }, 200);
  }

  const store = mapStore(info.store);
  // An unrecognised store would fail the CHECK constraint on insert; refusing here says so plainly
  // instead of surfacing as a database error with no explanation.
  if (!store) return json({ ok: false, reason: "unknown_store" }, 422);

  const expiresAt = toMillis(entitlement.expires_date);
  // A `pro` entitlement with no expiry would be a lifetime product, which Evenly does not sell. Treating
  // it as Pro-forever off an unexpected payload is exactly the kind of guess this table exists to avoid.
  if (expiresAt === null) return json({ ok: false, reason: "no_expiry" }, 422);

  const row = {
    user_id: callerId,
    product_id: productId,
    store,
    rc_app_user_id: callerId,
    period: periodOf(productId),
    started_at: toMillis(entitlement.purchase_date) ?? now,
    // The store's own paid-through date. Someone who cancels stays Pro to the end of the period they
    // already paid for, which is both correct and the difference between a lapse and a support ticket.
    expires_at: expiresAt,
    // Renders the verb on the Profile row and nothing else. A billing issue is NOT a revocation: the
    // grace period is exactly when a subscriber must not lose what they paid for.
    will_renew: !info.unsubscribe_detected_at,
    revoked_at: toMillis(info.refunded_at ?? null),
    updated_at: now,
  };

  const { error } = await db.from("user_subscriptions").upsert(row, { onConflict: "user_id" });
  if (error) return json({ ok: false, reason: "write_failed" }, 503);

  // The doorbell trigger on this table wakes every group the subscriber is active in, so the other five
  // phones learn the group went Pro through the normal pull. Nothing to do here.
  return json({ ok: true, isPro: expiresAt > now && !row.revoked_at }, 200);
});
