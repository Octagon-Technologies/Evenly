// Exchanges a native Sign In with Apple authorization code for an Apple refresh token and stores it
// server-side, keyed by the signed-in user. That refresh token is the only thing apple-revoke-token can
// later hand to Apple's /auth/revoke — the native flow's identity token alone cannot be revoked
// (APPLE_SIGNIN_NATIVE_PLAN.md §5 P4 / Guideline 5.1.1(v)).
//
//   POST { "authorizationCode": "..." }
//
// Auth: the caller's own Supabase session (verify_jwt = true, the default — not listed in
// supabase/config.toml). This links only the caller's own Apple grant, never someone else's.
//
// Best-effort by design, on both ends: SupabaseAuthSession.signInWithAppleIdToken calls this after a
// sign-in has already succeeded and ignores its outcome, and this function itself never 4xx/5xxs for a
// missing secret or a failed Apple exchange — it always returns 200 with {linked: boolean} so a config
// gap or a flaky Apple endpoint can never turn into a broken sign-in. The tradeoff: if this silently
// fails to link, that account's later account-deletion revoke is a no-op (apple-revoke-token reports
// {revoked:false, reason:"not linked"}) rather than an error anyone sees. Acceptable for a Guideline
// 5.1.1(v) best-effort revoke; check function logs if native Apple accounts show up unlinked.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { appleClientId, appleClientSecret } from "../_shared/appleClientSecret.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const authHeader = req.headers.get("Authorization") ?? "";
  const callerClient = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader } } },
  );
  const { data: userData, error: userErr } = await callerClient.auth.getUser();
  if (userErr || !userData.user) return json({ error: "unauthorized" }, 401);
  const userId = userData.user.id;

  let payload: { authorizationCode?: string };
  try {
    payload = await req.json();
  } catch {
    return json({ error: "invalid JSON body" }, 400);
  }
  const code = payload.authorizationCode;
  if (!code) return json({ error: "authorizationCode required" }, 400);

  const clientSecret = await appleClientSecret();
  const clientId = appleClientId();
  if (!clientSecret || !clientId) {
    return json({ linked: false, skipped: "Apple credentials not configured" }, 200);
  }

  const tokenRes = await fetch("https://appleid.apple.com/auth/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: clientId,
      client_secret: clientSecret,
      code,
      grant_type: "authorization_code",
    }),
  });
  if (!tokenRes.ok) {
    return json({ linked: false, error: `apple token exchange failed: ${tokenRes.status}` }, 200);
  }
  const tokenBody = await tokenRes.json();
  const refreshToken = tokenBody.refresh_token as string | undefined;
  if (!refreshToken) return json({ linked: false, error: "no refresh_token in Apple response" }, 200);

  const serviceClient = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );
  const { error: upsertErr } = await serviceClient
    .from("apple_oauth_tokens")
    .upsert({ user_id: userId, refresh_token: refreshToken, updated_at: new Date().toISOString() });
  if (upsertErr) return json({ linked: false, error: upsertErr.message }, 200);

  return json({ linked: true }, 200);
});

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
