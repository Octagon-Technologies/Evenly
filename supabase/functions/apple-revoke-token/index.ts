// Revokes the caller's Apple Sign In grant (Guideline 5.1.1(v)): looks up the refresh token
// apple-link-token stored for this user, calls Apple's /auth/revoke, and removes the row on success.
// Called from SupabaseAuthSession.requestAccountDeletion() right after request_account_deletion
// confirms server-side and before the local sign-out — see APPLE_SIGNIN_NATIVE_PLAN.md §5 P4. Revoking
// at request time (not at the 30-day purge) is deliberate: purge_deleted_accounts runs on a schedule
// with no outbound-HTTP path (pg_net isn't installed on this project), while request time is a live,
// authenticated, synchronous call the client is already making.
//
//   POST (no body)
//
// Auth: the caller's own Supabase session (verify_jwt = true) — only ever revokes the caller's own
// grant. Best-effort by design: a user who never linked Apple (Android, browser-OAuth Apple, email
// sign-in, or a failed apple-link-token call) has no row here, which is success
// ({revoked:false, reason:"not linked"}), not an error — account deletion must never block on this.

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

  const serviceClient = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );
  const { data: row } = await serviceClient
    .from("apple_oauth_tokens")
    .select("refresh_token")
    .eq("user_id", userId)
    .maybeSingle();
  if (!row?.refresh_token) return json({ revoked: false, reason: "not linked" }, 200);

  const clientSecret = await appleClientSecret();
  const clientId = appleClientId();
  if (!clientSecret || !clientId) {
    return json({ revoked: false, reason: "Apple credentials not configured" }, 200);
  }

  const revokeRes = await fetch("https://appleid.apple.com/auth/revoke", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: clientId,
      client_secret: clientSecret,
      token: row.refresh_token,
      token_type_hint: "refresh_token",
    }),
  });
  if (!revokeRes.ok) {
    // Leave the row: deleting it here would strand the grant live on Apple's side with nothing left to
    // retry from. Nothing currently retries automatically — see the README's manual-retry note.
    return json({ revoked: false, error: `apple revoke failed: ${revokeRes.status}` }, 200);
  }

  await serviceClient.from("apple_oauth_tokens").delete().eq("user_id", userId);
  return json({ revoked: true }, 200);
});

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
