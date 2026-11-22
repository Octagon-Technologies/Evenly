// Evenly welcome email. Fires once, the moment a real account exists — sends the "Welcome to Evenly"
// email (the Bloom-direction mockup, `_shared/emailTemplates.ts`), never the waitlist one (that's sent
// inline from `waitlist/index.ts`, since a waitlist join has no `auth.users` row to hang a trigger off).
//
// Wired by the SQL trigger `welcome_email_on_signup` on `auth.users` (`supabase/schema.sql`), which posts
// `{ record: { email, raw_user_meta_data } }` through `net.http_post`. It replaced a Studio Database
// Webhook whose header embedded the legacy service-role JWT: once the project's runtime
// SUPABASE_SERVICE_ROLE_KEY stopped being that string, every call was refused with 403.
//
// Auth: the caller MUST present WELCOME_EMAIL_SECRET (what the trigger sends, read from Vault as
// `welcome_email_service_key`) or the runtime service-role key (a manual/admin call). That secret is not
// a JWT, so the function deploys with `verify_jwt = false` (`config.toml`). Without this check, anyone who
// found the URL could fire "welcome" mail at any address they typed into the body.
//
// Not idempotent: every accepted call sends a mail. The trigger is what makes it fire once per account.

import { sendWelcomeEmail } from "../_shared/email.ts";

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** Accepts either a direct `{ email, name? }` call or the `{ record: {...} }` shape the `auth.users`
 *  trigger sends. */
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

  const accepted = [Deno.env.get("WELCOME_EMAIL_SECRET"), Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")]
    .filter((k): k is string => !!k);
  const bearer = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  if (!bearer || !accepted.includes(bearer)) {
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

  // Never allowed to fail the caller (the signup transaction that fired the trigger has
  // already committed): `sendWelcomeEmail` is best-effort and inert if `RESEND_API_KEY` is unset, same as
  // every other optional integration in `_shared/`.
  await sendWelcomeEmail(target.email, target.name);

  return json({ ok: true }, 200);
});
