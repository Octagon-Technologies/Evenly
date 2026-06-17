package da.chelimo.sharecost.data.auth

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.error.asErr
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.data.db.dao.UserDao
import da.chelimo.sharecost.data.db.entity.UserEntity
import da.chelimo.sharecost.data.remote.supabase.PushController
import da.chelimo.sharecost.data.remote.supabase.SyncEngine
import da.chelimo.sharecost.data.remote.supabase.SyncManager
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.auth.OAuthProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Facebook
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Real [AuthSession] over Supabase Auth (06 §5.5). Supports the three providers the sign-in screen
 * offers — **Google / Apple** (browser OAuth via the `sharecost://login-callback` redirect) and
 * **Email** (magic-link + 6-digit OTP) — plus an anonymous fallback for the offline-first path.
 *
 * Provider/OTP sessions arrive asynchronously (browser redirect or email tap → [SupabaseClient]
 * handles the deep link → `sessionStatus` flips), so [mirrorCurrentUser] runs off the session-status
 * stream: whenever a session exists it mirrors the server `users` row into Room (preserving any local
 * profile edits) and updates [currentUserId]. Each provider call only *launches* the flow.
 */
@OptIn(ExperimentalTime::class)
class SupabaseAuthSession(
    private val client: SupabaseClient,
    private val userDao: UserDao,
    private val syncEngine: SyncEngine? = null,
    private val syncManager: SyncManager? = null,
    private val pushController: PushController? = null,
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AuthSession {

    private val _currentUserId = MutableStateFlow(client.auth.currentUserOrNull()?.id?.let(::UserId))
    override val currentUserId: StateFlow<UserId?> = _currentUserId.asStateFlow()

    init {
        // Single source of truth for "who is signed in": react to every session change (sign-in via
        // any provider, refresh, expiry, sign-out) and mirror/clear the local account accordingly.
        scope.launch {
            client.auth.sessionStatus.collect {
                if (client.auth.currentUserOrNull() != null) mirrorCurrentUser() else _currentUserId.value = null
            }
        }
        // A restored session (relaunch) should hydrate from the server immediately.
        client.auth.currentUserOrNull()?.id?.let { id -> scope.launch { syncEngine?.pull(id) } }
        // Live sync (F7): prompt push-on-write + Realtime pull + a periodic safety net, replacing the
        // old fixed 15s heartbeat. Falls back to nothing extra when no SyncManager is wired (tests).
        syncManager?.bind(scope, currentUserId)
        // Push (F7): register the FCM token for the signed-in user + pull on delivered messages.
        pushController?.bind(scope, currentUserId)
    }

    override suspend fun signIn(displayName: String): UserId {
        if (client.auth.currentUserOrNull() == null) {
            client.auth.signInAnonymously()
        }
        val user = client.auth.currentUserOrNull() ?: error("Supabase sign-in returned no session")
        mirrorCurrentUser(fallbackName = displayName.ifBlank { "You" })
        return UserId(user.id)
    }

    override suspend fun signInWithProvider(provider: OAuthProvider): AppResult<Unit> = runCatching {
        when (provider) {
            OAuthProvider.GOOGLE -> client.auth.signInWith(Google)
            OAuthProvider.APPLE -> client.auth.signInWith(Apple)
            OAuthProvider.FACEBOOK -> client.auth.signInWith(Facebook)
        }
        AppResult.Ok(Unit)
    }.getOrElse { AppError.Unexpected(it).asErr() }

    override suspend fun sendEmailOtp(email: String): AppResult<Unit> = runCatching {
        client.auth.signInWith(OTP) { this.email = email.trim() }
        AppResult.Ok(Unit)
    }.getOrElse { AppError.Unexpected(it).asErr() }

    override suspend fun verifyEmailOtp(email: String, token: String): AppResult<UserId> = runCatching {
        client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email.trim(), token = token.trim())
        val user = client.auth.currentUserOrNull() ?: return AppError.SessionExpired.asErr()
        mirrorCurrentUser()
        AppResult.Ok(UserId(user.id))
    }.getOrElse { AppError.Unexpected(it).asErr() }

    override fun signOut() {
        scope.launch { client.auth.signOut() }
        _currentUserId.value = null
    }

    /**
     * Upsert the live Supabase user into Room. A brand-new sign-in inserts the row (display name from
     * the OAuth provider metadata when present); an existing local row is left intact except its email,
     * so a user's own name/currency/handle edits are never clobbered by a session refresh.
     */
    private suspend fun mirrorCurrentUser(fallbackName: String? = null) {
        val user = client.auth.currentUserOrNull() ?: return
        val now = clock.nowEpochMillis()
        val existing = userDao.getById(user.id)
        when {
            existing == null -> userDao.upsert(
                UserEntity(
                    id = user.id,
                    displayName = providerName(user) ?: fallbackName ?: "You",
                    email = user.email,
                    baseCurrency = "USD",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            existing.email != user.email -> userDao.upsert(existing.copy(email = user.email, updatedAt = now))
        }
        _currentUserId.value = UserId(user.id)
        scope.launch { syncEngine?.syncNow(user.id) }
    }

    /** Best-effort display name from OAuth provider metadata (`full_name` / `name`). */
    private fun providerName(user: UserInfo): String? {
        val md = user.userMetadata ?: return null
        val raw = md["full_name"] ?: md["name"] ?: return null
        return (raw as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
