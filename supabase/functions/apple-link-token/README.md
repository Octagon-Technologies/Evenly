# `apple-link-token` / `apple-revoke-token` (Apple Sign In native plan §5 P4)

Together these implement Guideline 5.1.1(v): an app offering account deletion and Sign In with Apple
must also revoke the Apple token server-side when the account is deleted.

- **`apple-link-token`** — called once, right after a successful native Apple sign-in
  (`SupabaseAuthSession.signInWithAppleIdToken`), with the `authorizationCode` Apple's native sheet
  returned alongside the identity token. Exchanges it at Apple's `/auth/token` for a refresh token and
  stores it in `apple_oauth_tokens`, keyed by the signed-in user.
- **`apple-revoke-token`** — called from `SupabaseAuthSession.requestAccountDeletion()`, right after
  the 30-day deletion countdown is confirmed and before local sign-out. Looks up that refresh token and
  calls Apple's `/auth/revoke`.

Both are **inert until configured** — without the four `APPLE_*` secrets they return 200 with
`{linked:false}` / `{revoked:false}` rather than erroring, so native sign-in and account deletion never
break because of missing Apple config.

## To turn it on

1. Apple Developer Portal → **Keys** → create a key with "Sign In with Apple" enabled, download the
   `.p8` once (Apple will not let you download it again). Note the **Key ID** and your **Team ID**
   (Membership details, top right of the portal).
2. Set the four secrets:
   ```bash
   supabase secrets set \
     APPLE_TEAM_ID=<team id> \
     APPLE_KEY_ID=<key id> \
     APPLE_CLIENT_ID=app.splitevenly \
     APPLE_PRIVATE_KEY="$(cat AuthKey_XXXXXXXXXX.p8)" \
     --project-ref wfpfgbipjmkysalfmyub
   ```
   `APPLE_CLIENT_ID` is the bundle id, not a Services ID — native Sign In with Apple's authorization
   code carries `aud`/aligns to the App ID directly (see `APPLE_SIGNIN_NATIVE_PLAN.md` §3).

## Manual test

```bash
# apple-link-token (needs a real authorizationCode from a live native sign-in — one-time-use, so this
# only works right after capturing one on-device; there's no way to fabricate a valid one)
curl -X POST "https://wfpfgbipjmkysalfmyub.supabase.co/functions/v1/apple-link-token" \
  -H "Authorization: Bearer <user JWT>" -H "Content-Type: application/json" \
  -d '{"authorizationCode":"<code from ASAuthorizationAppleIDCredential>"}'

# apple-revoke-token (safe to call any time — reports {revoked:false, reason:"not linked"} if the user
# never linked one)
curl -X POST "https://wfpfgbipjmkysalfmyub.supabase.co/functions/v1/apple-revoke-token" \
  -H "Authorization: Bearer <user JWT>"
```

## Known gap: no automatic retry

If Apple's `/auth/revoke` call fails (network blip, Apple outage), `apple-revoke-token` leaves the
`apple_oauth_tokens` row in place rather than deleting it, but nothing currently re-attempts the call —
the row just sits there until someone notices (query `select * from apple_oauth_tokens` for stale rows)
and re-invokes the function for that user, or a future scheduled retry job is built. Low priority: the
attached Supabase account is already 30 days into deletion by the time this can happen, so the residual
Apple grant outlives it by, at most, until someone notices.
