# Plan — native Sign In with Apple (iOS)

Status: **ready to build.** Written for a fresh session with no memory of how this was scoped — read
this file fully before touching code, don't assume extra context exists.

Branch: create `feat/apple-signin-native` off `main` (or ask the owner which branch to base on — check
`git status`/`git branch` first, do not assume `main` is checked out).

---

## 1. What already exists (verified facts, not assumptions)

- Google, Apple, and Facebook sign-in buttons are **already wired and working**, all going through
  **browser-based Supabase OAuth** (`ASWebAuthenticationSession` via the `splitevenly://login-callback`
  deep link):
  - Provider enum: `enum class OAuthProvider { GOOGLE, APPLE, FACEBOOK }` —
    `code/shared/src/commonMain/kotlin/app/splitevenly/domain/auth/AuthSession.kt:63`
  - Interface method: `suspend fun signInWithProvider(provider: OAuthProvider): AppResult<Unit>` —
    same file, line 26
  - Implementation: `SupabaseAuthSession.signInWithProvider` —
    `code/shared/src/commonMain/kotlin/app/splitevenly/data/auth/SupabaseAuthSession.kt:98-101`,
    routes `OAuthProvider.APPLE -> client.auth.signInWith(Apple)`
  - UI wiring: `code/shared/src/commonMain/kotlin/app/splitevenly/ui/navigation/WiredScreens.kt:73-75`
- **No `.entitlements` file exists** in the iOS project. No `CODE_SIGN_ENTITLEMENTS` build setting in
  `code/iosApp/iosApp.xcodeproj/project.pbxproj`. No `com.apple.developer.applesignin` key anywhere.
- The only Swift files are `code/iosApp/iosApp/iOSApp.swift` (SwiftUI `App` entry point, no
  `AppDelegate`) and `code/iosApp/iosApp/ContentView.swift`.
- Bundle ID is `app.splitevenly`. `GoogleService-Info.plist` is present (Firebase, unrelated to this
  work — don't confuse with Apple Sign In config).
- `deleteAccount()` in `SupabaseAuthSession.kt:170-186` calls a Postgres RPC `delete_my_account` and
  signs out locally. **It does not revoke the Apple token.** See §5 P4 — this is Apple Guideline
  5.1.1(v) territory and is a real gap, not a nice-to-have, once native Apple auth ships.
- This is why native Apple Sign In is being added at all: Google + Facebook social login already exist,
  so Guideline 4.8 requires Apple as an equivalent option — the browser-OAuth Apple button already
  technically satisfies 4.8, but native gives the Face-ID one-tap flow and lower review risk. Confirm
  with the owner that the goal is still "replace the Apple button's browser flow with native," not "add
  a second button" — this plan assumes replace.

Read `code/shared/src/commonMain/kotlin/app/splitevenly/platform/AGENTS.md` before writing any
`expect`/`actual` code — it owns the rules for this layer, including the "a runtime permission is EARNED,
never sprung" rule, which doesn't strictly apply here (Sign In with Apple isn't a permission prompt) but
the surrounding conventions (Bridge pattern, StateFlow exposure) do.

---

## 2. Architecture decision

Follow the existing `IosPushTokenHolder` bridge pattern
(`code/shared/src/iosMain/kotlin/app/splitevenly/platform/PushService.ios.kt`) — Swift owns the
`AuthenticationServices` call (Kotlin/Native can't call it directly), and hands the result to Kotlin
through a small holder object.

- **iOS only.** Android has no native "Sign In with Apple" API — Android's Apple button stays exactly
  as-is (browser OAuth). Do not touch `androidMain` for this feature.
- **New method, not a new provider value.** Add `signInWithAppleIdToken(idToken: String, rawNonce:
  String): AppResult<Unit>` to `AuthSession` rather than overloading `signInWithProvider` — the browser
  flow and the native flow have different inputs (URL callback vs. ID token + nonce), and forcing them
  through one signature would leak iOS-only concepts into `commonMain`'s Android path.
- **Nonce handling is mandatory, not optional.** Apple's native flow and Supabase's `signInWith(IDToken)`
  both require a nonce to prevent replay: generate a random raw nonce, SHA-256 hash it, pass the *hash*
  to `ASAuthorizationAppleIDRequest.nonce`, and pass the *raw* nonce to Supabase alongside the identity
  token it returns. Supabase hashes the raw nonce itself and compares against the claim inside the JWT.
  Getting this backwards (passing the raw nonce to Apple, or the hash to Supabase) fails sign-in silently
  or with an opaque JWT validation error — check this first if native sign-in doesn't work.
- **Capture `fullName` on first authorization only, immediately.** `ASAuthorizationAppleIDCredential.fullName`
  is populated by Apple **only on the user's very first authorization ever** for this app. Every
  subsequent sign-in (even after a full reinstall) returns `nil` for it. If it isn't written to the user's
  profile the moment it arrives, it is unrecoverable — Apple does not re-send it. Persist it in the same
  call that creates/updates the Supabase user row, not in a "finish onboarding later" step.

---

## 3. Apple Developer Portal + Supabase Dashboard config (do this before writing code)

These are outside the repo — do them first so code changes can be tested immediately after.

1. **Developer Portal → Identifiers → `app.splitevenly`**: enable the **Sign In with Apple** capability
   on the App ID. (If Xcode is set to Automatic Signing, ticking the capability in Xcode in step 4 does
   this for you — do it there instead and skip this step, but verify it actually landed on the portal
   afterward.)
2. **Developer Portal → Identifiers → Services IDs**: only needed if you keep a *web* callback path
   (e.g. for the existing Supabase redirect flow, or Android's browser OAuth) — the native flow does not
   need a Services ID, it uses the App ID directly. Don't create one unless something else in this repo
   still needs it; check whether Supabase's current Apple provider config already has one before adding
   a duplicate.
3. **Developer Portal → Keys**: create a new key with "Sign In with Apple" enabled, download the `.p8`
   once (Apple will not let you download it again), note the Key ID and your Team ID. This key is
   needed for §5 P4 (server-side token revocation), not for the client-side sign-in itself — don't skip
   creating it now just because sign-in works without it, or P4 will require redoing this step.
4. **Xcode → `iosApp` target → Signing & Capabilities → + Capability → Sign In with Apple.** This creates
   `iosApp/iosApp.entitlements` and wires `CODE_SIGN_ENTITLEMENTS` in the pbxproj automatically. Confirm
   both actually landed with `git diff` after — don't trust the UI silently.
5. **Supabase Dashboard → Authentication → Providers → Apple**: confirm `app.splitevenly` (the raw
   bundle ID) is in the **Authorized Client IDs** list. This is separate from whatever Services ID is
   configured for the existing browser flow — native ID tokens carry `aud = app.splitevenly`, and
   Supabase rejects the token if that exact value isn't allow-listed. This is the single most common
   native-Apple-Sign-In failure mode; check it first if `signInWith(IDToken)` returns an audience/claim
   error.

---

## 4. New/changed files

| File | Change |
| --- | --- |
| `code/iosApp/iosApp.entitlements` | New — generated by Xcode in §3.4, `com.apple.developer.applesignin: [Default]` |
| `code/iosApp/iosApp/AppleSignInCoordinator.swift` | New — `ASAuthorizationControllerDelegate` + `ASAuthorizationControllerPresentationContextProviding`. Generates the nonce pair, builds the request, presents the sheet, extracts `identityToken` (JWT, decode as UTF8 string) + `fullName` on success, hands both to Kotlin. |
| `code/iosApp/iosApp/iOSApp.swift` | Wire the coordinator so it has a presentation anchor — SwiftUI `App` has no `UIWindow` directly; use `UIApplication.shared.connectedScenes` to find the key window, same pattern any `ASAuthorizationControllerPresentationContextProviding` needs. |
| `code/shared/src/iosMain/kotlin/app/splitevenly/platform/AppleSignInBridge.ios.kt` | New — holder object mirroring `IosPushTokenHolder`, exposes a suspend/callback surface Swift calls into with the ID token, raw nonce, and optional full name. |
| `code/shared/src/commonMain/kotlin/app/splitevenly/domain/auth/AuthSession.kt` | Add `signInWithAppleIdToken(idToken: String, rawNonce: String, fullName: String?): AppResult<Unit>` to the interface |
| `code/shared/src/commonMain/kotlin/app/splitevenly/data/auth/SupabaseAuthSession.kt` | Implement it: `client.auth.signInWith(IDToken) { provider = Apple; this.idToken = idToken; nonce = rawNonce }`, then if `fullName` non-null, write it to the profile in the same call chain |
| `code/shared/src/commonMain/kotlin/app/splitevenly/data/auth/StubAuthSession.kt` | Add the matching stub implementation — check how the existing stub handles `signInWithProvider` and mirror it, don't leave the interface unimplemented |
| `code/shared/src/commonMain/kotlin/app/splitevenly/ui/navigation/WiredScreens.kt` | The `"apple" ->` branch needs to call the native path on iOS and the existing browser path on Android — this requires an `expect`/`actual` platform check or a platform-injected callback; look at how the file already distinguishes platforms (if it doesn't yet, that's new plumbing — check `platform/AGENTS.md` for the established pattern before inventing one) |

---

## 5. Build order

Each step ends green on **both** platforms per root `AGENTS.md` §4.1 — Android must not break even
though this feature is iOS-only, because the shared interface change touches `commonMain`.

### P0 — Portal + Dashboard config
Do all of §3 first. Nothing in P1+ can be verified without it.

### P1 — Entitlements + Swift coordinator
Xcode capability (§3.4), then `AppleSignInCoordinator.swift`. At this point you can log the raw
identity token to the console from a temporary button tap and confirm Apple's sheet appears correctly
on a simulator signed into a demo Apple ID (Settings → Sign in to your iPhone, on the simulator itself —
Face ID sim sign-in works via Features → Face ID → Matching Face after `⌘⇧M`).

### P2 — Kotlin bridge + interface
`AppleSignInBridge.ios.kt`, the `AuthSession` interface addition, both `actual`/implementation sides
(`SupabaseAuthSession`, `StubAuthSession`). Verify with:
```bash
./gradlew :shared:compileAndroidMain
./gradlew :shared:compileKotlinIosSimulatorArm64
```
Both must pass — the interface change lands in `commonMain` even though only iOS calls the new method.

### P3 — Wire the UI + end-to-end test
Update `WiredScreens.kt`, run on the iOS simulator (`code/iosApp/run-ios-sim.sh`), tap the Apple button,
confirm:
- The native sheet appears (not a Safari popup)
- Sign-in completes and lands on the same post-auth screen the Google/Facebook buttons reach
- A fresh Supabase user row is created with `full_name` populated (delete the simulator's demo Apple ID
  session between test runs if you need to re-trigger a "first authorization," since `fullName` won't
  reappear otherwise — Settings → Apple ID → Password & Security → Apps Using Apple ID → Evenly → Stop
  Using, on the simulator)
- Signing out and back in with the *same* Apple ID still works and does **not** require `fullName` again

### P4 — Token revocation on account deletion (do not skip for App Store submission)
**Done.** Guideline 5.1.1(v): apps that support account deletion and use Sign In with Apple must also
revoke the Apple token server-side when the account is deleted, via Apple's `/auth/revoke` REST
endpoint, using a client secret JWT signed with the `.p8` key from §3.3.

Two deviations from this section's original sketch, both forced by things that changed after it was
written:

- **Account deletion is no longer `delete_my_account()` / immediate.** A concurrent change replaced it
  with a 30-day grace-period flow — `AuthSession.requestAccountDeletion()` stamps
  `users.deletion_requested_at` via the `request_account_deletion` RPC, and a daily `pg_cron` job
  (`purge_deleted_accounts`) does the actual `auth.users` delete after the grace period. `purge_*` runs
  on a schedule with no outbound-HTTP path (`pg_net` isn't installed on this project), so revocation
  happens at **request** time instead — `SupabaseAuthSession.requestAccountDeletion()` calls
  `apple-revoke-token` right after the RPC succeeds and before the local sign-out, while the session is
  still live. See `data/AGENTS.md` Rule 9 for the full deletion-flow writeup.
- **Two edge functions, not one.** The native flow's identity token alone can't be revoked — only a
  refresh/access token obtained by exchanging the authorization code Apple's sheet also returns.
  `apple-link-token` does that exchange right after a successful native sign-in and stores the refresh
  token in a new `apple_oauth_tokens` table (RLS enabled, zero policies — service-role only, matching
  `web_sessions`); `apple-revoke-token` reads it back and calls `/auth/revoke`. Both are inert (return
  `{linked:false}`/`{revoked:false}`, never error) until the four `APPLE_TEAM_ID`/`APPLE_KEY_ID`/
  `APPLE_CLIENT_ID`/`APPLE_PRIVATE_KEY` secrets are set — see `supabase/functions/apple-link-token/README.md`.

`AppleSignInResult.authorizationCode` (`platform/AppleSignIn.kt`) and the matching
`AuthSession.signInWithAppleIdToken(..., authorizationCode)` param carry the code through; both ends are
best-effort and never fail sign-in or deletion on a missing/expired code or a down Apple endpoint. Not
yet done: setting the four `APPLE_*` secrets themselves (needs the owner's Apple Developer `.p8` key,
Team ID, Key ID) — until then both functions no-op and native sign-in/deletion behave exactly as before
this change. Also not done: automatic retry if `/auth/revoke` itself fails (see the README's note).

---

## 6. Verification checklist before calling this done

- [ ] `./gradlew :shared:compileAndroidMain` green
- [ ] `./gradlew :shared:compileKotlinIosSimulatorArm64` green
- [ ] `./gradlew :androidApp:assembleDebug` green (confirms nothing in `commonMain` broke Android's build)
- [ ] Native Apple sheet appears on iOS simulator, not a browser popup
- [ ] Fresh sign-in creates a Supabase user with `full_name` captured
- [ ] Repeat sign-in (same Apple ID, no reset) still succeeds without `fullName`
- [ ] Android's Apple button still works unchanged (browser OAuth) — regression check, not new work
- [ ] `git diff` on the Xcode project confirms the entitlements file and `CODE_SIGN_ENTITLEMENTS` setting
      actually landed, not just appeared in the UI
- [ ] P4 (token revocation) done or explicitly deferred with the owner's sign-off — don't ship account
      deletion + Apple Sign In without it and call it finished silently
