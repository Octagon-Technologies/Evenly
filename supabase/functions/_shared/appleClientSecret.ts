// Builds Apple's "client_secret" — a short-lived ES256 JWT signed with the Sign In with Apple private
// key, required by both /auth/token (code exchange) and /auth/revoke. Shared by apple-link-token and
// apple-revoke-token so the ES256 signing logic lives in one place: WebCrypto's ECDSA signature is
// already raw r||s bytes (IEEE P1363), which is exactly the JOSE signature format ES256 needs — no
// DER conversion required, unlike RSA/Node's crypto module (compare push-notify's RS256 FCM signer,
// which needs no such conversion either but for a different reason: RSASSA-PKCS1-v1_5 has no DER step).
//
// Needs four secrets (Apple Developer Portal, APPLE_SIGNIN_NATIVE_PLAN.md §3.3):
//   APPLE_TEAM_ID     — Developer Portal, top right, "Membership details"
//   APPLE_KEY_ID      — the Key ID shown when the "Sign In with Apple" key was created
//   APPLE_CLIENT_ID   — "app.splitevenly" (the bundle id; native auth's aud, not a Services ID)
//   APPLE_PRIVATE_KEY — the downloaded .p8 file's contents, verbatim (PEM, including BEGIN/END lines)
// Set with:
//   supabase secrets set APPLE_TEAM_ID=... APPLE_KEY_ID=... APPLE_CLIENT_ID=app.splitevenly \
//     APPLE_PRIVATE_KEY="$(cat AuthKey_XXXXXXXXXX.p8)" --project-ref wfpfgbipjmkysalfmyub

export async function appleClientSecret(): Promise<string | null> {
  const teamId = Deno.env.get("APPLE_TEAM_ID");
  const keyId = Deno.env.get("APPLE_KEY_ID");
  const clientId = Deno.env.get("APPLE_CLIENT_ID");
  const privateKeyPem = Deno.env.get("APPLE_PRIVATE_KEY");
  if (!teamId || !keyId || !clientId || !privateKeyPem) return null;

  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "ES256", kid: keyId, typ: "JWT" };
  const claims = {
    iss: teamId,
    iat: now,
    exp: now + 3600, // Apple allows up to 6 months; minted fresh per call, so an hour is plenty
    aud: "https://appleid.apple.com",
    sub: clientId,
  };
  const unsigned = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
  const key = await importEcPrivateKey(privateKeyPem);
  const sig = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    new TextEncoder().encode(unsigned),
  );
  return `${unsigned}.${b64urlBytes(new Uint8Array(sig))}`;
}

export function appleClientId(): string | null {
  return Deno.env.get("APPLE_CLIENT_ID") ?? null;
}

async function importEcPrivateKey(pem: string): Promise<CryptoKey> {
  const der = pemToDer(pem);
  return await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "ECDSA", namedCurve: "P-256" },
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
