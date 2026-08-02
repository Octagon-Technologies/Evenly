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

    /** Email a magic link + 6-digit OTP to [email] (Supabase email provider). */
    suspend fun sendEmailOtp(email: String): AppResult<Unit>

    /** Verify the 6-digit [token] emailed to [email]; on success [currentUserId] becomes non-null. */
    suspend fun verifyEmailOtp(email: String, token: String): AppResult<UserId>

    /**
     * Email + password sign-in. Used for seeded test accounts (no email round-trip), so QA can switch
     * between users without burning the OTP rate limit. On success [currentUserId] becomes non-null.
     */
    suspend fun signInWithPassword(email: String, password: String): AppResult<UserId>

    /**
     * True when the signed-in user **already has a finished profile on the server** — i.e. they've
     * onboarded before. The email/OTP flow both *creates* and *signs in* (Supabase auto-creates the
     * account on first OTP), so the UI uses this to send returning users straight to Home and reserve
     * the onboarding screen for genuinely new accounts. Checks the server (not the local cache) so it's
     * correct even on a fresh install where Room is empty. Best-effort: returns false on any error.
     */
    suspend fun hasOnboardedProfile(): Boolean

    fun signOut()

    /**
     * Permanently delete the current account: removes the user's server profile + auth record, then
     * signs out and wipes the local cache. [currentUserId] becomes null. Irreversible.
     */
    suspend fun deleteAccount(): AppResult<Unit>
}

/** OAuth identity providers offered on the sign-in screen. */
enum class OAuthProvider { GOOGLE, APPLE, FACEBOOK }
