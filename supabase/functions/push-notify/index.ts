// Evenly push-sender (F7). Looks up the device tokens of a group's members and sends each an FCM
// notification via the FCM HTTP v1 API. Invoke from a DB webhook (on expense/settlement insert) or
// directly with a service key.
//
// Inert until configured: set the `FCM_SERVICE_ACCOUNT` secret (the JSON of a Firebase service account
// with the Cloud Messaging role). Without it the function returns 200 + {sent:0, skipped:"..."} so a
// trigger that calls it never errors.
//
//   POST  { "groupId": "...", "title": "...", "body": "...",
//           "data"?: {..}, "excludeUserId"?: "...", "pref"?: "newExpenses"|"payments"|"conflictReminders" }
//
// Auth: the caller MUST present the project's service-role key as the Bearer. Title/body/data are fully
// caller-controlled and this function fans a push out to every device in a group, so an anon-key caller
// (the shipped app's key) must NOT be able to reach it — that would be arbitrary-content push spam to any
// group (P1 #14). Legitimate callers are server-side only: a DB webhook/trigger or an admin task, both of
// which hold the service role. This check MUST stay in place before the FCM secret is ever configured.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

interface NotifyRequest {
  groupId: string;
  title: string;
  body: string;
  data?: Record<string, string>;
  excludeUserId?: string;
  pref?: "newExpenses" | "payments" | "conflictReminders";
}

const PREF_COLUMN: Record<string, string> = {
  newExpenses: "notify_new_expenses",
  payments: "notify_payments",
  conflictReminders: "notify_conflict_reminders",
};

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  // Require the service-role key explicitly (P1 #14). Anything else — anon key, a user JWT, nothing — is
  // rejected before any work, so the shipped app's anon key can't drive arbitrary push. Constant-time-ish
  // compare on the raw Bearer against the service-role secret.
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const bearer = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  if (!serviceRoleKey || bearer !== serviceRoleKey) {
    return json({ error: "forbidden" }, 403);
  }

  let payload: NotifyRequest;
  try {
    payload = await req.json();
  } catch {
    return json({ error: "invalid JSON body" }, 400);
  }
  if (!payload.groupId || !payload.title) return json({ error: "groupId + title required" }, 400);

  const serviceAccountRaw = Deno.env.get("FCM_SERVICE_ACCOUNT");
  if (!serviceAccountRaw) {
    // Not yet configured — succeed without sending so triggers don't fail.
    return json({ sent: 0, skipped: "FCM_SERVICE_ACCOUNT not set" }, 200);
  }

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );

  // 1. Members of the group (optionally excluding the actor).
  const { data: members, error: memErr } = await supabase
    .from("members")
    .select("user_id")
    .eq("group_id", payload.groupId)
    .eq("status", "ACTIVE");
  if (memErr) return json({ error: memErr.message }, 500);

  let userIds = (members ?? []).map((m) => m.user_id as string);
  if (payload.excludeUserId) userIds = userIds.filter((id) => id !== payload.excludeUserId);
  if (userIds.length === 0) return json({ sent: 0, skipped: "no recipients" }, 200);

  // 2. Honour each recipient's per-event preference, if one was named.
  const prefColumn = payload.pref ? PREF_COLUMN[payload.pref] : null;
  if (prefColumn) {
    const { data: optedIn } = await supabase
      .from("users")
      .select("id")
      .in("id", userIds)
      .eq(prefColumn, true);
    const allowed = new Set((optedIn ?? []).map((u) => u.id as string));
    userIds = userIds.filter((id) => allowed.has(id));
  }
  if (userIds.length === 0) return json({ sent: 0, skipped: "all recipients opted out" }, 200);

  // 3. Their device tokens.
  const { data: tokenRows, error: tokErr } = await supabase
    .from("device_tokens")
    .select("id")
    .in("user_id", userIds);
  if (tokErr) return json({ error: tokErr.message }, 500);
  const tokens = (tokenRows ?? []).map((t) => t.id as string);
  if (tokens.length === 0) return json({ sent: 0, skipped: "no device tokens" }, 200);

  // 4. Send via FCM HTTP v1.
  const serviceAccount = JSON.parse(serviceAccountRaw);
  const accessToken = await getAccessToken(serviceAccount);
  const projectId = serviceAccount.project_id as string;

  let sent = 0;
  const failures: string[] = [];
  for (const token of tokens) {
    const res = await fetch(
      `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          message: {
            token,
            notification: { title: payload.title, body: payload.body },
            data: payload.data ?? {},
          },
        }),
      },
    );
    if (res.ok) sent++;
    else failures.push(`${res.status}: ${await res.text()}`);
  }

  return json({ sent, attempted: tokens.length, failures }, 200);
});

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** Exchange the service-account JWT for an OAuth2 access token scoped to FCM. */
async function getAccessToken(serviceAccount: { client_email: string; private_key: string }): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const claims = {
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };
  const header = { alg: "RS256", typ: "JWT" };
  const unsigned = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
  const key = await importPrivateKey(serviceAccount.private_key);
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  const jwt = `${unsigned}.${b64urlBytes(new Uint8Array(sig))}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  if (!res.ok) throw new Error(`token exchange failed: ${res.status} ${await res.text()}`);
  return (await res.json()).access_token as string;
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const der = pemToDer(pem);
  return await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

function pemToDer(pem: string): ArrayBuffer {
  const base64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const bin = atob(base64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

function b64url(s: string): string {
  return b64urlBytes(new TextEncoder().encode(s));
}

function b64urlBytes(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
