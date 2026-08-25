// Evenly welcome email. Fires once, the moment a real account exists — sends the "Welcome to Evenly"
// email (the Bloom-direction mockup, `_shared/emailTemplates.ts`), never the waitlist one (that's sent
// inline from `waitlist/index.ts`, since a waitlist join has no `auth.users` row to hang a trigger off).
//
// Not wired to anything yet, same as `push-notify`. Wire it with a Supabase Database Webhook (Studio →
// Database → Webhooks → New): table `auth.users`, event **Insert**, type **HTTP Request**, method POST,
// URL `https://<project-ref>.functions.supabase.co/send-welcome-email`, header
// `Authorization: Bearer <service_role_key>` (Studio's "Supabase Auth" header preset fills this in
// without ever putting the key in a file this repo tracks). That is deliberate, not a shortcut: the key
// only ever lives in the dashboard and in edge-function env, never in `schema.sql` or here — see
// `AGENTS.md` rule 6.
//
// Auth: same shape as `push-notify` — the caller MUST present the service-role key as the Bearer.
// Legitimate callers are the DB webhook above, or a manual/admin call, both of which hold it. Without
// this check, anyone who discovered the function's URL could fire arbitrary "welcome" mail at any
// address they typed into the request body.

import { sendWelcomeEmail } from "../_shared/email.ts";

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** Accepts either a direct `{ email, name? }` call or the standard Database Webhook payload shape
 *  (`{ type: "INSERT", table: "users", record: {...} }`) so it works from the dashboard-configured
 *  webhook above without a translation layer in front of it. */
function extractTarget(payload: unknown): { email: string; name: string | null } | null {
  if (!payload || typeof payload !== "object") return null;
  const body = payload as Record<string, unknown>;
  const record = (body.record && typeof body.record === "object" ? body.record : body) as Record<
    string,
    unknown
  >;

  const email = typeof record.email === "string" ? record.email : null;
  if (!email) return null;

  // `auth.users.raw_user_meta_data` is where Supabase Auth puts whatever the sign-up flow supplied
  // (Google's `full_name`, Apple's `name`, or nothing for email/OTP). A direct test call can also just
  // pass `name` at the top level.
  const meta = (record.raw_user_meta_data && typeof record.raw_user_meta_data === "object"
    ? record.raw_user_meta_data
    : {}) as Record<string, unknown>;
  const name =
    (typeof body.name === "string" && body.name) ||
    (typeof meta.full_name === "string" && meta.full_name) ||
    (typeof meta.name === "string" && meta.name) ||
    null;

  return { email, name };
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const bearer = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  if (!serviceRoleKey || bearer !== serviceRoleKey) {
    return json({ error: "forbidden" }, 403);
  }

  let payload: unknown;
  try {
    payload = await req.json();
  } catch {
    return json({ error: "invalid JSON body" }, 400);
  }

  const target = extractTarget(payload);
  if (!target) return json({ error: "email required" }, 400);

  // Never allowed to fail the caller (the auth webhook's own signup transaction has already
  // committed): `sendWelcomeEmail` is best-effort and inert if `RESEND_API_KEY` is unset, same as
  // every other optional integration in `_shared/`.
  await sendWelcomeEmail(target.email, target.name);

  return json({ ok: true }, 200);
});
