// Debug-only OTP relay for `@sharecost.test` accounts. Lets the owner walk the real "Sign in with
// email" -> OTP -> onboarding path as a seeded test persona (e.g. steve@sharecost.test), whose inbox
// does not exist.
//
// Supabase Auth would send the code to the undeliverable `.test` address, so the debug client
// (`SupabaseAuthSession.sendEmailOtp`) calls this instead for that one domain. The function mints the
// same OTP Supabase would have mailed (`auth.admin.generateLink`, which also creates the user on first
// use, exactly like `signInWithOtp`), then emails it to DEBUG_OTP_INBOX via Resend. The client then
// verifies it through the normal `verifyEmailOtp`, so everything after the send is the production path.
//
// Why a relay and not an Auth "Send Email" hook: a hook replaces delivery for *every* auth email in
// the project, so a bug there breaks real users' sign-in. This touches only `.test` addresses.
//
// Safety: the domain allowlist is the boundary. The code is only ever mailed to DEBUG_OTP_INBOX (an env
// secret, never the request), so a caller can't obtain a code for anyone, and can't reach a real
// user's account because real accounts are never on `sharecost.test`. Inert (503) until
// DEBUG_OTP_INBOX and RESEND_API_KEY are set. Rate-limited per IP like the other public endpoints.
//
//   DEBUG_OTP_INBOX   where relayed codes go (the owner's inbox)

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { callerIp, isRateLimited, recordSubmission } from "../_shared/rateLimit.ts";
import { sendDebugOtpEmail } from "../_shared/email.ts";

const TEST_DOMAIN = "@sharecost.test";
const RATE_KIND = "debug_otp";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const inbox = Deno.env.get("DEBUG_OTP_INBOX");
  if (!inbox || !Deno.env.get("RESEND_API_KEY")) return json({ error: "relay not configured" }, 503);

  let email: string;
  try {
    const body = await req.json();
    email = String(body?.email ?? "").trim().toLowerCase();
  } catch {
    return json({ error: "bad json" }, 400);
  }
  const local = email.endsWith(TEST_DOMAIN) ? email.slice(0, -TEST_DOMAIN.length) : "";
  if (!local || local.includes("@")) return json({ error: "only sharecost.test addresses" }, 400);

  const sb = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false },
  });

  const ip = callerIp(req);
  if (await isRateLimited(sb, RATE_KIND, ip)) return json({ error: "rate limited" }, 429);

  const { data, error } = await sb.auth.admin.generateLink({ type: "magiclink", email });
  const otp = data?.properties?.email_otp;
  if (error || !otp) {
    console.error(`debug-otp-relay: generateLink failed: ${error?.message ?? "no otp"}`);
    return json({ error: "could not mint code" }, 502);
  }

  const sent = await sendDebugOtpEmail(inbox, email, otp);
  if (!sent) return json({ error: "send failed" }, 502);

  await recordSubmission(sb, RATE_KIND, ip);
  return json({ ok: true });
});
