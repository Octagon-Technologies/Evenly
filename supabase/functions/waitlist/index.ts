// Evenly waitlist signup. One action, one table: the pre-launch page at `/waitlist` posts an email
// here and this function writes it to `waitlist_signups`.
//
// This is the entire security boundary for that table, exactly as `web-claim` is for the claim flow.
// `waitlist_signups` has RLS on with no policies, so anon and authenticated are denied outright and
// only this function's service key can reach it. That is deliberate: the browser bundle ships no
// Supabase client and no anon key (WEB_CLAIM_SPEC §4.1), and a marketing page is not a reason to
// break that rule. If a future change has the page inserting through PostgREST directly, the
// waitlist becomes publicly enumerable.
//
// `verify_jwt` is off, and has to be: the caller is an anonymous visitor on a marketing page and the
// bundle ships no anon key to present. The function therefore accepts one shape of request from
// anyone, which is why it writes exactly one row to exactly one table and returns no data.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { callerIp, isRateLimited, recordSubmission } from "../_shared/rateLimit.ts";

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "content-type, apikey, authorization",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
  });
}

/** Deliberately permissive: one `@`, a dot in the domain, no spaces, sane length. Anything stricter
 *  rejects real addresses (plus-tags, new TLDs, unicode locals) and the only cost of letting a typo
 *  through is one undeliverable mail. Validation that blocks a real signup is worse than no
 *  validation. */
const EMAIL = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/;

/** Lowercase and trim only. Not stripping plus-tags or dots: `sam+evenly@gmail.com` is how careful
 *  people track senders, and folding it would merge two addresses the user considers distinct. */
function normalize(raw: string): string {
  return raw.trim().toLowerCase();
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS_HEADERS });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  let body: { email?: unknown; source?: unknown };
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad_json" }, 400);
  }

  const raw = typeof body.email === "string" ? body.email : "";
  const email = raw.trim().slice(0, 254); // RFC 5321 ceiling, so a megabyte of text can't be stored
  if (!EMAIL.test(email)) return json({ error: "bad_email" }, 400);

  const source = typeof body.source === "string" ? body.source.slice(0, 40) : null;

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false } },
  );

  // 30 signups per hour per IP (ADMIN_FEEDBACK_SPEC.md §8). Sits after validation and before the
  // write, so a burst of malformed requests is refused on shape rather than eating someone's quota.
  const principal = callerIp(req);
  if (await isRateLimited(supabase, "waitlist", principal)) {
    return json({ error: "rate_limited" }, 429);
  }

  // `ignoreDuplicates` makes a repeat signup succeed silently rather than 409. The page cannot tell
  // the difference and should not: telling a visitor "you are already on the list" turns the form
  // into an account-existence oracle for any address someone wants to test.
  const { error } = await supabase
    .from("waitlist_signups")
    .upsert(
      { email, email_norm: normalize(email), source },
      { onConflict: "email_norm", ignoreDuplicates: true },
    );

  if (error) {
    console.error("waitlist insert failed", error.message);
    return json({ error: "server_error" }, 500);
  }

  // Best-effort, after the row is safe: a failed log must not undo a signup that already succeeded.
  await recordSubmission(supabase, "waitlist", principal);

  return json({ ok: true });
});
