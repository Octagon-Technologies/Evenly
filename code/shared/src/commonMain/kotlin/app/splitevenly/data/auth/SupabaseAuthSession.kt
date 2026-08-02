package app.splitevenly.data.auth

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.error.asErr
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.db.dao.UserDao
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.data.remote.supabase.PushController
import app.splitevenly.data.remote.supabase.SyncEngine
import app.splitevenly.data.remote.supabase.SyncManager
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.auth.OAuthProvider
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.AppForeground
import app.splitevenly.platform.EvAnalytics
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Facebook
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Real [AuthSession] over Supabase Auth (06 §5.5). Supports the three providers the sign-in screen
 * offers — **Google / Apple** (browser OAuth via the `splitevenly://login-callback` redirect) and
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
    private val appForeground: AppForeground? = null,
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val analytics: EvAnalytics? = null,
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
        // Live sync (F7): prompt push-on-write + Realtime doorbell pull + a periodic safety net,
        // replacing the old fixed 15s heartbeat. Gated on the app being visible so a backgrounded
        // device holds no socket and runs no loops; with no AppForeground wired (tests) it runs
        // ungated, as before. Falls back to nothing extra when no SyncManager is wired (tests).
        syncManager?.bind(scope, currentUserId, appForeground?.state ?: flowOf(true))
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
    }.getOrElse { it.toEmailSendError().asErr() }

    /**
     * Map an email-send failure to a typed error. Supabase throttles the built-in email service (a few
     * sends/hour, ~60s between sends to one address); that surfaces as HTTP 429
     * `over_email_send_rate_limit`. We single it out so the UI can say "wait a minute" instead of the
     * misleading "check the address". Everything else stays Unexpected.
     */
    private fun Throwable.toEmailSendError(): AppError {
        val msg = (message ?: "").lowercase()
        val rateLimited = "over_email_send_rate_limit" in msg || "rate limit" in msg || "429" in msg
        return if (rateLimited) {
            AppError.Backend(status = 429, code = "over_email_send_rate_limit", detail = message)
        } else {
            AppError.Unexpected(this)
        }
    }

    override suspend fun verifyEmailOtp(email: String, token: String): AppResult<UserId> = runCatching {
        client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email.trim(), token = token.trim())
        val user = client.auth.currentUserOrNull() ?: return AppError.SessionExpired.asErr()
        mirrorCurrentUser()
        AppResult.Ok(UserId(user.id))
    }.getOrElse { AppError.Unexpected(it).asErr() }

    override suspend fun signInWithPassword(email: String, password: String): AppResult<UserId> = runCatching {
        client.auth.signInWith(Email) { this.email = email.trim(); this.password = password }
        val user = client.auth.currentUserOrNull() ?: return AppError.SessionExpired.asErr()
        mirrorCurrentUser()
        AppResult.Ok(UserId(user.id))
    }.getOrElse { AppError.Unexpected(it).asErr() }

    override suspend fun hasOnboardedProfile(): Boolean {
        val uid = client.auth.currentUserOrNull()?.id ?: return false
        // Ask the server directly: a returning user's `users` row carries a real display name, while a
        // brand-new account has either no row yet or the "You" placeholder mirrorCurrentUser seeds. We
        // can't trust local Room here — on a fresh install it's empty (or momentarily "You") until the
        // first pull lands. Best-effort: any network/decode failure → treat as not-onboarded.
        return runCatching {
            val row = client.from("users")
                .select(Columns.ALL) { filter { eq("id", uid) } }
                .decodeList<UserEntity>()
                .firstOrNull()
            val name = row?.displayName?.trim()
            !name.isNullOrBlank() && name != PLACEHOLDER_NAME
        }.getOrDefault(false)
    }

    override fun signOut() {
        analytics?.capture(AnalyticsEvents.USER_SIGNED_OUT)
        analytics?.reset()
        scope.launch { client.auth.signOut() }
        _currentUserId.value = null
    }

    override suspend fun deleteAccount(): AppResult<Unit> {
        // Server-side delete via a security-definer RPC (removes the caller's profile + device tokens +
        // auth.users row). Then drop the local account row + sign out regardless, so the device is clean.
        // (Shared group/expense rows are keyed by user and never shown to a different signed-in account.)
        val uid = client.auth.currentUserOrNull()?.id
        val server = runCatching { client.postgrest.rpc("delete_my_account") }
        runCatching { client.auth.signOut() }
        uid?.let { runCatching { userDao.delete(it) } }
        _currentUserId.value = null
        return server.fold(
            onSuccess = {
                analytics?.capture(AnalyticsEvents.ACCOUNT_DELETED)
                analytics?.reset()
                AppResult.Ok(Unit)
            },
            onFailure = { AppError.Unexpected(it).asErr() },
        )
    }

    /**
     * Upsert the live Supabase user into Room. When there's no local row yet, a RETURNING account already
     * has a real server `users` row (display name, base currency, payment handles, notification prefs), so
     * we SELECT and mirror THAT — never a fresh-stamped "You" default. Seeding a default here was a silent
     * profile-wipe: `mirrorCurrentUser` runs `syncNow` (push before pull), so the default's `now` timestamp
     * beat the server via `keepNewer` and the real profile was erased on every device (P0 #4). We seed the
     * default ONLY when the server confirms it has no row (a genuinely new account); on a network failure we
     * seed nothing and let the subsequent pull hydrate, rather than risk pushing a default over a real row.
     * An existing local row is left intact except its email, so a user's own edits survive a session refresh.
     */
    private suspend fun mirrorCurrentUser(fallbackName: String? = null) {
        val user = client.auth.currentUserOrNull() ?: return
        val wasSignedOut = _currentUserId.value == null
        val now = clock.nowEpochMillis()
        val existing = userDao.getById(user.id)
        when {
            existing == null -> {
                val fetch = runCatching {
                    client.from("users").select(Columns.ALL) { filter { eq("id", user.id) } }
                        .decodeList<UserEntity>()
                }
                val serverRow = fetch.getOrNull()?.firstOrNull()
                when {
                    // Returning account → mirror the real profile verbatim (same timestamp, so it can't
                    // out-race a newer edit made elsewhere).
                    serverRow != null -> userDao.upsert(serverRow)
                    // Server confirmed no row → brand-new account, seed the default.
                    fetch.isSuccess -> userDao.upsert(
                        UserEntity(
                            id = user.id,
                            displayName = providerName(user) ?: fallbackName ?: PLACEHOLDER_NAME,
                            email = user.email,
                            baseCurrency = "USD",
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    // Network/decode failure → seed nothing; the pull triggered below hydrates the profile.
                    else -> Unit
                }
            }
            existing.email != user.email -> userDao.upsert(existing.copy(email = user.email, updatedAt = now))
        }
        _currentUserId.value = UserId(user.id)
        analytics?.identify(user.id)
        if (wasSignedOut) analytics?.capture(AnalyticsEvents.USER_SIGNED_IN)
        scope.launch { syncEngine?.syncNow(user.id) }
    }

    /** Best-effort display name from OAuth provider metadata (`full_name` / `name`). */
    private fun providerName(user: UserInfo): String? {
        val md = user.userMetadata ?: return null
        val raw = md["full_name"] ?: md["name"] ?: return null
        return (raw as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private companion object {
        /** Seeded for a brand-new account that hasn't set a real name yet (also the onboarding sentinel). */
        const val PLACEHOLDER_NAME = "You"
    }
}
