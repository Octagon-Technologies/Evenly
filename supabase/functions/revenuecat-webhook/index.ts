// Evenly Pro: RevenueCat's webhook (PRO_PASS_SPEC.md §6.3).
//
//   POST /revenuecat-webhook   (Authorization: <REVENUECAT_WEBHOOK_SECRET>)  ->  200 { ok }
//
// `verify_jwt = false` and authenticated by a shared secret in the Authorization header, the same
// pattern as `web-claim`: the caller is RevenueCat, which holds no Supabase JWT.
//
// Three jobs, and the first one is deliberately not a switch over event types:
//
//  - **Subscription lifecycle.** INITIAL_PURCHASE, RENEWAL, PRODUCT_CHANGE, CANCELLATION, EXPIRATION,
//    BILLING_ISSUE, UNCANCELLATION all resolve to the same thing: re-run §6.1's logic for that
//    `app_user_id`. One code path, driven by RevenueCat's *answer* rather than by the event name, so an
//    event type we have never seen cannot corrupt state — the worst it can do is trigger a re-read that
//    writes what was already true.
//  - **Pass backstop.** A NON_RENEWING_PURCHASE for a transaction we never activated (the client died
//    between the charge and its activate call) gets activated here. The group id rides along as a
//    RevenueCat **subscriber attribute**, set immediately before `purchase()`. **If it is absent the
//    event is parked and never guessed** — picking a group for someone hands a different set of people a
//    paid entitlement.
//  - **Refunds.** CANCELLATION with `cancel_reason = CUSTOMER_SUPPORT`, and any REFUND-shaped event,
//    stamp `revoked_at`. The group drops back to free at the next pull. Already-scanned bills are
//    untouched: we never claw back data.
//
// **Always 200 on anything we have understood and handled, including "nothing to do".** RevenueCat
// retries non-2xx for hours, and a retry storm over an event we deliberately ignore is worse than
// silence. A 5xx is reserved for "we could not do the work and want the retry".

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import {
  activatePass,
  fetchSubscriber,
  GROUP_ID_ATTRIBUTE,
  mapStore,
  syncSubscription,
} from "../_shared/revenuecat.ts";

interface RcEvent {
  type?: string;
  app_user_id?: string;
  original_app_user_id?: string;
  product_id?: string;
  store?: string;
  transaction_id?: string;
  cancel_reason?: string;
  subscriber_attributes?: Record<string, { value?: string }>;
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/**
 * Constant-time-ish comparison of the shared secret.
 *
 * Length is compared first and the loop always runs to completion, so a wrong secret cannot be narrowed
 * down a character at a time. Not the strongest defence in the world, but free.
 */
function secretMatches(provided: string, expected: string): boolean {
  if (provided.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < provided.length; i++) diff |= provided.charCodeAt(i) ^ expected.charCodeAt(i);
  return diff === 0;
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const expected = Deno.env.get("REVENUECAT_WEBHOOK_SECRET");
  // Inert until configured, and inert means REFUSING, not accepting. An unauthenticated writer here
  // could hand any account a subscription.
  if (!expected) return json({ error: "webhook not configured" }, 503);
  const provided = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!secretMatches(provided, expected)) return json({ error: "unauthorized" }, 401);

  let body: { event?: RcEvent };
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid JSON body" }, 400);
  }
  const event = body?.event;
  const type = event?.type ?? "";
  // RevenueCat aliases identities; `app_user_id` is the current one and is what we logged in with.
  const appUserId = event?.app_user_id ?? event?.original_app_user_id;
  if (!appUserId) return json({ ok: true, skipped: "no_app_user_id" }, 200);

  const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

  // TEST events exist to prove the endpoint is reachable from the dashboard. Acknowledged and nothing
  // else: running a real sync off a synthetic user id would write a row for an account that isn't ours.
  if (type === "TEST") return json({ ok: true, test: true }, 200);

  const subscriber = await fetchSubscriber(appUserId);
  // 503 so RevenueCat retries. This is the one case where we *want* the retry: the event was real and we
  // could not do the work.
  if (!subscriber) return json({ ok: false, reason: "revenuecat_unavailable" }, 503);

  if (type === "NON_RENEWING_PURCHASE") {
    const storeTxnId = event?.transaction_id;
    if (!storeTxnId) return json({ ok: true, skipped: "no_transaction_id" }, 200);

    // Prefer the attribute on the event itself (what was true at purchase time) over the subscriber's
    // current attribute, which a later purchase for a different group would have overwritten.
    const groupId = event?.subscriber_attributes?.[GROUP_ID_ATTRIBUTE]?.value
      ?? subscriber.subscriber_attributes[GROUP_ID_ATTRIBUTE]?.value;

    if (groupId) {
      const result = await activatePass(db, { appUserId, groupId, storeTxnId, subscriber });
      if (result.ok) return json({ ok: true, activated: !result.alreadyActive }, 200);
      // A membership or product problem is not something a retry fixes, so it is parked for a human
      // rather than bounced back at RevenueCat forever.
      await park(db, event, appUserId, storeTxnId, result.reason ?? "activation_failed");
      return json({ ok: true, parked: result.reason }, 200);
    }

    // No group id anywhere. This is the case the parking table exists for: nothing on the server can
    // derive which group the money was for, and guessing would give a paid entitlement to the wrong
    // people. A human resolves it with the receipt in front of them.
    await park(db, event, appUserId, storeTxnId, "missing_group_id");
    return json({ ok: true, parked: "missing_group_id" }, 200);
  }

  // Everything else is a subscription lifecycle event, including the refund-shaped ones: `refunded_at`
  // comes back on the subscriber document, and syncSubscription copies it into `revoked_at`. That is
  // why refunds need no branch of their own here.
  const result = await syncSubscription(db, appUserId, subscriber);
  // A write failure gets the retry; a payload we refused (unknown store, no expiry) does not, because
  // the next attempt would refuse it identically.
  if (!result.ok && result.status >= 500) return json(result, 503);
  return json({ ok: true, isPro: result.isPro ?? false, reason: result.reason }, 200);
});

/**
 * Park an unattributable purchase for a human, and alert once.
 *
 * Deliberately best-effort and never fatal to the response: failing to park is not a reason to make
 * RevenueCat retry an event we have already decided we cannot act on automatically.
 */
async function park(
  db: ReturnType<typeof createClient>,
  event: RcEvent,
  appUserId: string,
  storeTxnId: string,
  reason: string,
): Promise<void> {
  const store = mapStore(event.store) ?? "app_store";
  // The unique index on (store, store_txn_id) makes a webhook retry a no-op instead of five rows for a
  // human to read, which is the same reason group_passes has one.
  await db.from("pro_orphan_purchases").upsert({
    store,
    store_txn_id: storeTxnId,
    app_user_id: appUserId,
    product_id: event.product_id ?? null,
    reason,
    payload: event,
    created_at: Date.now(),
  }, { onConflict: "store,store_txn_id", ignoreDuplicates: true });
  await alertSlack(db, `Evenly Pro: unattributed purchase (${reason}) txn ${storeTxnId}`);
}

/**
 * One Slack post per hour per kind, using the same `ops_alerts` cooldown as `extract-receipt`.
 *
 * Someone paid and did not get what they paid for, so this cannot be a row nobody reads. Inert with no
 * webhook URL configured, like every other optional integration here.
 */
async function alertSlack(db: ReturnType<typeof createClient>, text: string): Promise<void> {
  const url = Deno.env.get("SLACK_ALERT_WEBHOOK_URL");
  if (!url) return;
  const kind = "pro_orphan_purchase";
  const hourAgo = new Date(Date.now() - 3_600_000).toISOString();
  const { data: recent } = await db
    .from("ops_alerts").select("id").eq("kind", kind).gt("sent_at", hourAgo).limit(1);
  if (recent && recent.length > 0) return;
  await db.from("ops_alerts").insert({ kind });
  try {
    await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text }),
    });
  } catch {
    // An unreachable Slack must never turn into a webhook retry storm.
  }
}
