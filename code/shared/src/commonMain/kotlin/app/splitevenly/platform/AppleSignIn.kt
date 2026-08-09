package app.splitevenly.platform

import app.splitevenly.core.error.AppResult

/**
 * A completed native Sign In with Apple authorization. [identityToken] is the JWT Supabase verifies;
 * [rawNonce] is the un-hashed nonce it was requested with (Supabase hashes it and compares against the
 * token's `nonce` claim). [fullName] is Apple's `ASAuthorizationAppleIDCredential.fullName`, formatted
 * for display, and is non-null **only** on this Apple ID's very first-ever authorization for this app.
 * [authorizationCode] is the one-time code the `apple-link-token` edge function exchanges for a refresh
 * token, which is the only thing account deletion can later hand to Apple's `/auth/revoke` (Guideline
 * 5.1.1(v)) — the identity token alone cannot be revoked.
 */
data class AppleSignInResult(
    val identityToken: String,
    val rawNonce: String,
    val fullName: String?,
    val authorizationCode: String?,
)

/**
 * iOS-only native "Sign In with Apple" (`ASAuthorizationController`, a system framework Kotlin/Native
 * calls directly, same as [FilePicker]'s `PhotosUI`/`UIKit` calls). Android has no native equivalent
 * API, so Android's Apple button keeps using [app.splitevenly.domain.auth.AuthSession.signInWithProvider]
 * (browser OAuth) instead; callers gate on [isIOS] before reaching for this.
 */
expect class AppleSignIn {
    /** Presents the native Apple sheet; `Err` on cancel or failure. */
    suspend fun signIn(): AppResult<AppleSignInResult>
}
