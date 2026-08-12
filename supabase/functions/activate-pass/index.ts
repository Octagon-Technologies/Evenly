// Evenly Pro, route 1: turn a paid group pass into an entitlement (PRO_PASS_SPEC.md §6.2).
//
//   POST { "groupId": "...", "storeTxnId": "..." }  ->  200 { ok, expiresAt, stacked }
//
// It needs the client's `groupId` because **the group binding exists nowhere in the store's data
// model** — only we know which group the money was for. Everything else is verified server-side.
//
// Why this function exists at all, rather than trusting the client's `CustomerInfo`:
//   - a modified client can claim any purchase, and the reward is unlimited paid Claude-vision calls
//     for six people;
//   - duration must be measured on the SERVER clock, or a wound-back device clock is a free month.
//
// **Idempotent by construction.** `(store, store_txn_id)` is unique on `group_passes`, so the client's
// call, a webhook retry and a reconciliation sweep all race to insert the same purchase and exactly one
// wins. Submitting the same transaction three times yields one pass and three identical successes.
// That is what lets the buyer see "Payment went through, tap again" instead of losing a charge.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { fetchSubscriber, mapStore } from "../_shared/revenuecat.ts";

interface ActivateRequest {
  groupId?: string;
  storeTxnId?: string;
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const DAY_MILLIS = 86_400_000;

/** Our three consumables and what each one buys. Durations are OURS, not the store's: the product is a
 *  consumable, so nothing in the receipt says how long it lasts and the server stamps the expiry.
 *  30 days means 30 x 86_400_000, never a calendar month. */
const PASS_TIERS: Record<string, { tier: string; days: number }> = {
  "app.splitevenly.pass.week1": { tier: "week_1", days: 7 },
  "app.splitevenly.pass.week2": { tier: "week_2", days: 14 },
  "app.splitevenly.pass.month1": { tier: "month_1", days: 30 },
};

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  let payload: ActivateRequest;
  try {
    payload = await req.json();
  } catch {
    return json({ error: "invalid JSON body" }, 400);
  }
  const groupId = payload.groupId;
  const storeTxnId = payload.storeTxnId;
  if (!groupId || !storeTxnId) return json({ error: "groupId and storeTxnId required" }, 400);

  const authHeader = req.headers.get("Authorization") ?? "";
  const callerClient = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader } } },
  );
  const { data: userData } = await callerClient.auth.getUser();
  const callerId = userData?.user?.id;
  if (!callerId) return json({ error: "unauthorized" }, 401);

  // Service role from here: `group_passes` grants the client SELECT only, and the membership check
  // immediately below is what authorises this write. RLS cannot express it, because the rule is "the
  // caller paid for a group they belong to" and the proof of payment lives at RevenueCat.
  const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

  const { data: membership, error: memberError } = await db
    .from("members").select("id")
    .eq("group_id", groupId).eq("user_id", callerId).eq("status", "ACTIVE")
    .maybeSingle();
  // Fail closed: an unreadable membership means we cannot say this purchase belongs to this group, and
  // the client's retry is free.
  if (memberError) return json({ error: "membership check unavailable" }, 503);
  if (!membership) return json({ error: "not a member of this group" }, 403);

  // Already activated? Answer success before spending a RevenueCat call. This is the common path on a
  // retry, and it is what makes retrying free rather than merely harmless.
  const { data: existing } = await db
    .from("group_passes").select("id, group_id, expires_at")
    .eq("store_txn_id", storeTxnId).maybeSingle();
  if (existing) {
    // A transaction id is globally unique, so a mismatch here means someone is trying to attach one
    // group's purchase to another group. Refused, and deliberately not treated as a retry.
    if (existing.group_id !== groupId) return json({ error: "transaction belongs to another group" }, 409);
    return json({ ok: true, expiresAt: existing.expires_at, stacked: false, alreadyActive: true }, 200);
  }

  const subscriber = await fetchSubscriber(callerId);
  if (!subscriber) return json({ error: "purchase check unavailable" }, 503);

  // Find the consumable RevenueCat recorded under THIS app user with THIS transaction id. Scoping the
  // search to the caller's own subscriber document is what stops one person activating another's
  // purchase: a transaction id alone is not proof of ownership.
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
  if (!productId) return json({ error: "no such purchase for this account", reason: "not_found" }, 404);

  const tier = PASS_TIERS[productId];
  // A real purchase of something that is not one of our passes (a subscription, a product from another
  // app on the same account). Not an error the buyer can fix, and not something to guess a duration for.
  if (!tier) return json({ error: "not a group pass product", reason: "wrong_product" }, 422);

  const store = mapStore(rcStore);
  if (!store) return json({ error: "unknown store", reason: "unknown_store" }, 422);

  const now = Date.now();
  // Stacking (PRO_PASS_SPEC.md §5.4): a pass bought while the group is already Pro starts at the CURRENT
  // expiry, not at now, so two friends who each buy a week give the group two weeks instead of one
  // wasted. Reads passes only, not subscriptions: a subscription is not extended by a pass, and starting
  // a pass after someone else's renewal date would be selling dead time.
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
    purchased_by: callerId,
    tier: tier.tier,
    store,
    store_txn_id: storeTxnId,
    rc_app_user_id: callerId,
    starts_at: startsAt,
    expires_at: expiresAt,
    created_at: now,
    updated_at: now,
  });
  if (insertError) {
    // 23505 is the idempotency key doing its job: the webhook backstop (or a racing retry) inserted the
    // same purchase between our read above and this write. That is a success, not a failure.
    const { data: raced } = await db
      .from("group_passes").select("expires_at").eq("store_txn_id", storeTxnId).maybeSingle();
    if (raced) return json({ ok: true, expiresAt: raced.expires_at, stacked: false, alreadyActive: true }, 200);
    return json({ error: "could not activate", reason: "write_failed" }, 503);
  }

  return json({ ok: true, expiresAt, stacked: currentExpiry !== undefined }, 200);
});
