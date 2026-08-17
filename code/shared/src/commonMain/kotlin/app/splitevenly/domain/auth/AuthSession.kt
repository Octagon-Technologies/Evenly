package app.splitevenly.domain.auth

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.UserId
import kotlinx.coroutines.flow.StateFlow

/**
 * The signed-in user (§5.5). The rest of the app depends only on this interface; it is backed by
 * [app.splitevenly.data.auth.SupabaseAuthSession] (real Google/Apple/Email auth) when Supabase
 * is configured, or [app.splitevenly.data.auth.StubAuthSession] (a local-only account) otherwise.
 *
 * OAuth + magic-link sessions arrive **asynchronously** (browser redirect / email tap), so callers
 * react to [currentUserId] rather than awaiting the launch call.
 */

/**
 * The Google Play review demo account — the one email allowed to sign in with a password in
 * release builds (see [AuthSession.signInWithPassword]), since a real reviewer can't complete an
 * email-OTP round-trip against an inbox that doesn't exist.
 */
const val PLAY_REVIEW_DEMO_EMAIL = "trial@split-evenly.app"

interface AuthSession {
    /** The current user's id, or null when signed out. Data screens observe this. */
    val currentUserId: StateFlow<UserId?>

    /** Anonymous/local sign-in (stub + offline fallback), returning the user id. */
    suspend fun signIn(displayName: String): UserId

    /**
     * Launch the browser OAuth flow for [provider]. Returns once the flow is launched; the session
     * surfaces later through [currentUserId] when the redirect deep link is handled by the host.
     */
    suspend fun signInWithProvider(provider: OAuthProvider): AppResult<Unit>

    /**
     * Native iOS Sign In with Apple: exchanges the ID token from [app.splitevenly.platform.AppleSignIn]
     * for a Supabase session directly, no browser redirect. [rawNonce] is the un-hashed nonce the
     * identity token was requested with; Supabase hashes it itself and compares against the token's
     * `nonce` claim. [fullName] is Apple's `ASAuthorizationAppleIDCredential.fullName`, non-null only on
     * this Apple ID's very first-ever authorization for this app; pass it through unconditionally so it
     * gets persisted the one time it's available. [authorizationCode] is exchanged server-side
     * (`apple-link-token`) for a refresh token, so account deletion can later revoke the grant via
     * Apple's `/auth/revoke` (Guideline 5.1.1(v)) — best-effort, sign-in never fails because of it. On
     * success [currentUserId] becomes non-null.
     */
    suspend fun signInWithAppleIdToken(
        idToken: String,
        rawNonce: String,
        fullName: String?,
        authorizationCode: String?,
    ): AppResult<Unit>

    /** Email a magic link + 6-digit OTP to [email] (Supabase email provider). */
    suspend fun sendEmailOtp(email: String): AppResult<Unit>

    /** Verify the 6-digit [token] emailed to [email]; on success [currentUserId] becomes non-null. */
    suspend fun verifyEmailOtp(
        email: String,
        token: String,
    ): AppResult<UserId>

    /**
     * Email + password sign-in. Used for seeded test accounts (no email round-trip), so QA can switch
     * between users without burning the OTP rate limit. On success [currentUserId] becomes non-null.
     * **Debug builds, plus [PLAY_REVIEW_DEMO_EMAIL] in release** — a release binary can otherwise only
     * prove email ownership via OTP/magic-link, never a password for an email it doesn't own; the one
     * exception exists because Play Store reviewers can't complete an OTP round-trip against a demo
     * inbox that doesn't exist. [app.splitevenly.data.auth.SupabaseAuthSession] fails closed with
     * [app.splitevenly.core.error.AppError.NotAuthorized] for every other email in release.
     */
    suspend fun signInWithPassword(
        email: String,
        password: String,
    ): AppResult<UserId>

    /**
     * True when the signed-in user **already has a finished profile on the server** — i.e. they've
     * onboarded before. The email/OTP flow both *creates* and *signs in* (Supabase auto-creates the
     * account on first OTP), so the UI uses this to send returning users straight to Home and reserve
     * the onboarding screen for genuinely new accounts. Checks the server (not the local cache) so it's
     * correct even on a fresh install where Room is empty. Best-effort: returns false on any error.
     */
    suspend fun hasOnboardedProfile(): Boolean

    /**
     * Sign out, and clear this device's cache of the account that just left.
     *
     * Clearing is the point, not a tidy-up: without it the next account to sign in on this device
     * inherits the previous one's rows and pushes them up under its own session (#24). Pro made that
     * worse — passes and subscriptions are per-user.
     *
     * The cache may hold local writes that never reached the server, and those are real user data
     * (`data/AGENTS.md` Rule 1). So the flow is: push first; if everything lands, wipe and return
     * [SignOutOutcome.SignedOut]; if the push fails AND something is still pending, return
     * [SignOutOutcome.UnsyncedChanges] having done NOTHING — still signed in, nothing wiped — so the
     * caller can tell the user what they are about to lose. Calling again with [discardUnsynced] `=
     * true` is the user's answer.
     */
    suspend fun signOut(discardUnsynced: Boolean = false): SignOutOutcome

    /**
     * Start the 30-day account-deletion countdown server-side (`request_account_deletion` RPC), then
     * sign out. Returns the epoch-millis purge date. Nothing is deleted yet: the profile stays live and
     * shared expenses/settlements are untouched until the grace period elapses, so signing back in
     * before then (via [cancelAccountDeletion]) fully restores the account.
     */
    suspend fun requestAccountDeletion(): AppResult<Long>

    /** Cancel a pending deletion still inside its grace period. No-op if none is pending. */
    suspend fun cancelAccountDeletion(): AppResult<Unit>

    /**
     * The signed-in user's pending deletion purge date (epoch millis), or null if none is pending.
     * Checked right after sign-in to gate the app behind a "cancel or sign out" screen.
     */
    suspend fun pendingDeletionAt(): AppResult<Long?>
}

/** OAuth identity providers offered on the sign-in screen. */
enum class OAuthProvider { GOOGLE, APPLE, FACEBOOK }

/** What [AuthSession.signOut] did. */
sealed interface SignOutOutcome {
    /** Signed out and the device's cache is clear. */
    data object SignedOut : SignOutOutcome

    /**
     * Nothing happened: local changes could not be pushed, and signing out would discard them. The user
     * is still signed in and the cache is untouched. Ask, then call `signOut(discardUnsynced = true)` if
     * they say go ahead.
     *
     * [pendingWrites] is **null when the count itself could not be taken** — a DB read failing is not
     * evidence that nothing is pending, and the one read standing between a failed push and destroying
     * someone's only copy of their expenses has to fail closed. The dialog says "some of what you did"
     * rather than inventing a number.
     */
    data class UnsyncedChanges(
        val pendingWrites: Int?,
    ) : SignOutOutcome
}
