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
// The work itself is `syncSubscription` in `_shared/revenuecat.ts`, shared with `revenuecat-webhook` so
// there is exactly one implementation of "what does RevenueCat say this person owns".

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { syncSubscription } from "../_shared/revenuecat.ts";

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
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

  // Service role for the write: `user_subscriptions` grants the client SELECT and nothing else, which is
  // the point — a client that could write it could hand every group it joins unlimited paid vision calls.
  const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const result = await syncSubscription(db, callerId);
  return json({ ok: result.ok, isPro: result.isPro ?? false, reason: result.reason }, result.status);
});
