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
// **Idempotent by construction.** `(store, store_txn_id)` is unique on `group_passes`, so this call, a
// webhook retry and the §6.3 backstop all race to insert the same purchase and exactly one wins.
// Submitting the same transaction three times yields one pass and three identical successes. That is
// what lets the buyer see "Payment went through, tap again" instead of losing a charge.
//
// The write itself is `activatePass` in `_shared/revenuecat.ts`, shared with the webhook backstop so
// both produce identical rows and identical stacking.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { activatePass, fetchSubscriber } from "../_shared/revenuecat.ts";

interface ActivateRequest {
  groupId?: string;
  storeTxnId?: string;
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

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

  const subscriber = await fetchSubscriber(callerId);
  // Fail closed. RevenueCat is the authority on whether money changed hands, so an unreadable answer is
  // never read as "yes"; the client's retry with the same transaction id costs nothing.
  if (!subscriber) return json({ error: "purchase check unavailable" }, 503);

  // Service role from here: `group_passes` grants the client SELECT only, and the membership check
  // inside activatePass is what authorises this write. RLS cannot express it, because the rule is "the
  // caller paid for a group they belong to" and the proof of payment lives at RevenueCat.
  const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const result = await activatePass(db, { appUserId: callerId, groupId, storeTxnId, subscriber });
  return json(result, result.status);
});
