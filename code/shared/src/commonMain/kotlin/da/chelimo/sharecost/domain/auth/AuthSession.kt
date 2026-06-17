package da.chelimo.sharecost.domain.auth

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.UserId
import kotlinx.coroutines.flow.StateFlow

/**
 * The signed-in user (§5.5). The rest of the app depends only on this interface; it is backed by
 * [da.chelimo.sharecost.data.auth.SupabaseAuthSession] (real Google/Apple/Email auth) when Supabase
 * is configured, or [da.chelimo.sharecost.data.auth.StubAuthSession] (a local-only account) otherwise.
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

    fun signOut()
}

/** OAuth identity providers offered on the sign-in screen. */
enum class OAuthProvider { GOOGLE, APPLE, FACEBOOK }
