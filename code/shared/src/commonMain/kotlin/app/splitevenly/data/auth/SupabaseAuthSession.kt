package app.splitevenly.data.auth

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.error.asErr
import app.splitevenly.core.error.asOk
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.db.dao.SignOutWipeDao
import app.splitevenly.data.db.dao.UserDao
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.data.remote.supabase.PushController
import app.splitevenly.data.remote.supabase.SupabaseConfig
import app.splitevenly.data.remote.supabase.SyncEngine
import app.splitevenly.data.remote.supabase.SyncManager
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.auth.OAuthProvider
import app.splitevenly.domain.auth.PLAY_REVIEW_DEMO_EMAIL
import app.splitevenly.domain.auth.SignOutOutcome
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.AppForeground
import app.splitevenly.platform.EvAnalytics
import app.splitevenly.platform.SecureStorage
import app.splitevenly.platform.isDebugBuild
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Facebook
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    // Calls the apple-link-token / apple-revoke-token edge functions (Apple Sign In native plan §5 P4).
    // Optional-ctor-dep: unwired (tests) just skips both calls, same as no [analytics].
    private val httpClient: HttpClient? = null,
    // Sign-out's cache wipe (#24). Optional-ctor-dep like the rest: a test that passes nothing gets
    // the old sign-out behaviour rather than a null-pointer, and production DI always wires it.
    private val signOutWipeDao: SignOutWipeDao? = null,
    // Holds the web bill-link plaintext tokens, which are per-session bearer credentials.
    private val secureStorage: SecureStorage? = null,
) : AuthSession {
    private val _currentUserId =
        MutableStateFlow(
            client.auth
                .currentUserOrNull()
                ?.id
                ?.let(::UserId),
        )
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
        client.auth
            .currentUserOrNull()
            ?.id
            ?.let { id -> scope.launch { syncEngine?.pull(id) } }
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

    override suspend fun signInWithProvider(provider: OAuthProvider): AppResult<Unit> =
        runCatching {
            when (provider) {
                OAuthProvider.GOOGLE -> client.auth.signInWith(Google)
                OAuthProvider.APPLE -> client.auth.signInWith(Apple)
                OAuthProvider.FACEBOOK -> client.auth.signInWith(Facebook)
            }
            AppResult.Ok(Unit)
        }.getOrElse { AppError.Unexpected(it).asErr() }

    override suspend fun signInWithAppleIdToken(
        idToken: String,
        rawNonce: String,
        fullName: String?,
        authorizationCode: String?,
    ): AppResult<Unit> =
        runCatching {
            client.auth.signInWith(IDToken) {
                this.idToken = idToken
                this.provider = Apple
                this.nonce = rawNonce
            }
            // The ID token flow is synchronous (unlike the browser redirect), so mirror right away
            // rather than waiting on the sessionStatus collector — same as verifyEmailOtp. fullName only
            // ever arrives here, never in Apple's JWT, so it must ride through as the seed fallback now.
            mirrorCurrentUser(fallbackName = fullName)
            // Best-effort (Apple Sign In native plan §5 P4): links this Apple grant server-side so
            // account deletion can later revoke it via apple-revoke-token. Deliberately swallowed inside
            // linkAppleToken — a missing code, a down function, or no httpClient wired must never turn a
            // successful sign-in into a failure; the only consequence is a later revoke being a no-op.
            if (authorizationCode != null) linkAppleToken(authorizationCode)
            AppResult.Ok(Unit)
        }.getOrElse { AppError.Unexpected(it).asErr() }

    private suspend fun linkAppleToken(authorizationCode: String) {
        runCatching { callAppleEdgeFunction("apple-link-token", Json.encodeToString(LinkAppleTokenReq(authorizationCode))) }
    }

    /**
     * POSTs to an `apple-link-token`/`apple-revoke-token` edge function as the signed-in user (same
     * Bearer-user-token + anon-apikey shape as [app.splitevenly.data.remote.supabase.ReceiptOcrHttp]).
     * No [httpClient] wired or no live session both silently skip the call — both functions are already
     * best-effort on the server side too (Guideline 5.1.1(v) revocation must never block sign-in or
     * account deletion).
     */
    private suspend fun callAppleEdgeFunction(
        function: String,
        body: String,
    ) {
        val http = httpClient ?: return
        val token = client.auth.currentSessionOrNull()?.accessToken ?: return
        http.post("${SupabaseConfig.URL}/functions/v1/$function") {
            header("Authorization", "Bearer $token")
            header("apikey", SupabaseConfig.ANON_KEY)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    override suspend fun sendEmailOtp(email: String): AppResult<Unit> =
        runCatching {
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

    override suspend fun verifyEmailOtp(
        email: String,
        token: String,
    ): AppResult<UserId> =
        runCatching {
            client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email.trim(), token = token.trim())
            val user = client.auth.currentUserOrNull() ?: return AppError.SessionExpired.asErr()
            mirrorCurrentUser()
            AppResult.Ok(UserId(user.id))
        }.getOrElse { AppError.Unexpected(it).asErr() }

    override suspend fun signInWithPassword(
        email: String,
        password: String,
    ): AppResult<UserId> {
        val isPlayReviewDemo = email.trim().equals(PLAY_REVIEW_DEMO_EMAIL, ignoreCase = true)
        if (!isDebugBuild() && !isPlayReviewDemo) return AppError.NotAuthorized.asErr()
        return signInWithPasswordUnguarded(email, password)
    }

    private suspend fun signInWithPasswordUnguarded(
        email: String,
        password: String,
    ): AppResult<UserId> =
        runCatching {
            client.auth.signInWith(Email) {
                this.email = email.trim()
                this.password = password
            }
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
            val row =
                client
                    .from("users")
                    .select(Columns.ALL) { filter { eq("id", uid) } }
                    .decodeList<UserEntity>()
                    .firstOrNull()
            val name = row?.displayName?.trim()
            !name.isNullOrBlank() && name != PLACEHOLDER_NAME
        }.getOrDefault(false)
    }

    /**
     * Sign out and clear this device's cache of the account that just left (#24).
     *
     * This used to be four lines that touched Room not at all, so signing in as someone else on the
     * same device left account A's rows in place and the next [SyncEngine.push] sent them up under
     * account B's session.
     *
     * The order below is the whole design, and each step is load-bearing:
     *
     * 1. **Push first.** Local writes that never reached the server are real user data; wiping them is
     *    a silent loss (`data/AGENTS.md` Rule 1).
     * 2. **Then ask the cache, not the push, whether anything is still pending.** `push()` returning
     *    `Ok` is *not* "everything reached the server": #8's in-flight-edit guard deliberately leaves an
     *    expense dirty when a local edit lands during the round trip, and that path throws nothing, so
     *    the push reports success with a local-only expense still sitting there. The count is the only
     *    honest question, so it is asked on every sign-out rather than only after a failed push — and
     *    when the count itself throws it fails **closed** (`UnsyncedChanges(null)`), because a failing
     *    DB read is not evidence that there is nothing to lose.
     * 3. **The count and the wipe run inside the sync fence, as one unit.** Closing the fence refuses
     *    every push and pull for this account *including the ones already queued*, and holding the sync
     *    lock across both means nothing can dirty a row between counting and wiping, and nothing can
     *    re-land the account's rows into the cache we just emptied. Nulling `currentUserId` only stops
     *    `SyncManager`'s loops; three other launches never gated on it. See [SyncGate].
     * 4. **Unregister the push token, then sign out of Supabase.** Both need A's session to still
     *    authenticate, so `client.auth.signOut()` stays last.
     *
     * Deliberately NOT reused by [requestAccountDeletion]: a deletion is cancellable within its grace
     * period, so its local state has to survive (Rule 9), and its failure branch must wipe nothing.
     */
    override suspend fun signOut(discardUnsynced: Boolean): SignOutOutcome {
        val userId = _currentUserId.value?.value
        val engine = syncEngine
        if (userId != null && engine != null) {
            if (!discardUnsynced) engine.push(userId)
            val question =
                engine.closeForSignOut(userId) {
                    // Inside the fence: no push, pull or wipe can interleave with either statement.
                    val pending =
                        if (discardUnsynced) 0 else pendingWritesOrUnknown { engine.countPendingLocalWrites() }
                    val question = unsyncedChangesFor(pending)
                    // Best-effort: a failure here must not strand the user signed-in-but-wiped. Room is a
                    // cache of server truth, so the worst case is a stale row that the next account's pull
                    // overwrites — whereas refusing to sign out because a DELETE failed is a dead end.
                    if (question == null) runCatching { signOutWipeDao?.wipeSignedOutAccount() }
                    question
                }
            if (question != null) {
                // Nothing was touched and the user is still signed in, so sync has to resume — otherwise
                // "Stay signed in" leaves them with a device that never syncs again.
                engine.reopenSync(userId)
                return question
            }
        }

        analytics?.capture(AnalyticsEvents.USER_SIGNED_OUT)
        analytics?.reset()
        _currentUserId.value = null
        // While A's session can still authenticate it: otherwise the server goes on believing this
        // handset is A's, and every push for one of A's groups renders on whoever holds it next.
        if (userId != null) runCatching { pushController?.unregisterCurrentToken(userId) }
        // The bill-link plaintext tokens live here, not Room: bearer credentials for one bill each, so
        // they must not outlive the session that minted them (`data/AGENTS.md`, WebBillLinkRepository).
        runCatching { secureStorage?.clear() }
        runCatching { client.auth.signOut() }
        return SignOutOutcome.SignedOut
    }

    override suspend fun requestAccountDeletion(): AppResult<Long> {
        // Server-side: starts the 30-day countdown only (security-definer RPC), nothing is deleted yet.
        // Only sign out once the server confirms (data/AGENTS.md Rule 9: never wipe local state ahead of
        // the server) — a network failure here must surface as an error with the account still live and
        // the user still signed in, not a silent sign-out with the deletion never actually requested.
        // Local Room state is otherwise left intact: the request may still be cancelled within the grace
        // period, and a same-device cancel-then-sign-back-in should resync cheaply, not from empty.
        val result = runCatching { client.postgrest.rpc("request_account_deletion").decodeAs<Long>() }
        return result.fold(
            onSuccess = { purgeAt ->
                analytics?.capture(AnalyticsEvents.ACCOUNT_DELETION_REQUESTED)
                // Best-effort Apple token revoke (Guideline 5.1.1(v) / Apple Sign In native plan §5
                // P4), while the session this call authenticates with is still live — must run BEFORE
                // signOut below. Revoked at request time rather than at the 30-day purge because
                // purge_deleted_accounts runs on a schedule with no outbound-HTTP path (pg_net isn't
                // installed on this project); request time is a live, synchronous, already-authenticated
                // call the client is making anyway. A user who never linked Apple, or a flaky Apple
                // endpoint, must never block or fail the deletion countdown.
                runCatching { callAppleEdgeFunction("apple-revoke-token", "{}") }
                runCatching { client.auth.signOut() }
                _currentUserId.value = null
                purgeAt.asOk()
            },
            onFailure = { AppError.Unexpected(it).asErr() },
        )
    }

    override suspend fun cancelAccountDeletion(): AppResult<Unit> =
        runCatching {
            client.postgrest.rpc("cancel_account_deletion")
            analytics?.capture(AnalyticsEvents.ACCOUNT_DELETION_CANCELLED)
            AppResult.Ok(Unit)
        }.getOrElse { AppError.Unexpected(it).asErr() }

    override suspend fun pendingDeletionAt(): AppResult<Long?> {
        val uid = client.auth.currentUserOrNull()?.id ?: return AppResult.Ok(null)
        return runCatching {
            client
                .from("users")
                .select(Columns.list("deletion_requested_at")) { filter { eq("id", uid) } }
                .decodeSingleOrNull<PendingDeletionRow>()
                ?.deletionRequestedAt
                ?.let { requestedAt -> requestedAt + GRACE_PERIOD_MS }
        }.fold(
            onSuccess = { it.asOk() },
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
                val fetch =
                    runCatching {
                        client
                            .from("users")
                            .select(Columns.ALL) { filter { eq("id", user.id) } }
                            .decodeList<UserEntity>()
                    }
                val serverRow = fetch.getOrNull()?.firstOrNull()
                when {
                    // Returning account → mirror the real profile verbatim (same timestamp, so it can't
                    // out-race a newer edit made elsewhere).
                    serverRow != null -> {
                        userDao.upsert(serverRow)
                    }

                    // Server confirmed no row → brand-new account, seed the default.
                    fetch.isSuccess -> {
                        userDao.upsert(
                            UserEntity(
                                id = user.id,
                                displayName = providerName(user) ?: fallbackName ?: PLACEHOLDER_NAME,
                                email = user.email,
                                baseCurrency = "USD",
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                    }

                    // Network/decode failure → seed nothing; the pull triggered below hydrates the profile.
                    else -> {
                        Unit
                    }
                }
            }

            existing.email != user.email -> {
                userDao.upsert(existing.copy(email = user.email, updatedAt = now))
            }
        }
        _currentUserId.value = UserId(user.id)
        analytics?.identify(user.id)
        if (wasSignedOut) analytics?.capture(AnalyticsEvents.USER_SIGNED_IN)
        // This account signing back in on a device it signed out of is the one thing that should lift
        // sign-out's sync fence, and it is a no-op for anyone else (the fence is keyed by user id, so a
        // different account's sign-in cannot unblock work still queued for the one that left).
        syncEngine?.reopenSync(user.id)
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

        /** Mirrors the grace period baked into `request_account_deletion`/`purge_deleted_accounts`. */
        const val GRACE_PERIOD_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * "We could not tell how much is unsaved." Negative rather than a large positive so it can never be
 * mistaken for a real count by a caller that only tests `> 0`.
 */
internal const val PENDING_UNKNOWN = -1

/**
 * How many local writes have not reached the server, or [PENDING_UNKNOWN] when the count itself threw.
 *
 * **Failing closed is the entire point.** This one read stands between a push we cannot fully trust and
 * permanently destroying the only copy of someone's expenses, and it used to be
 * `.getOrDefault(0)` — "nothing pending, safe to wipe" — which is the single most permissive answer
 * available. A DB read failing is not evidence that there is nothing to lose.
 */
internal suspend fun pendingWritesOrUnknown(count: suspend () -> Int): Int = runCatching { count() }.getOrDefault(PENDING_UNKNOWN)

/**
 * The question sign-out has to ask before wiping, or null when there is nothing to ask and the cache
 * may go.
 *
 * Only an actual zero authorises the wipe. [PENDING_UNKNOWN] becomes `UnsyncedChanges(null)` — we stop
 * and ask, without inventing a number we were not able to take.
 */
internal fun unsyncedChangesFor(pending: Int): SignOutOutcome.UnsyncedChanges? =
    if (pending == 0) null else SignOutOutcome.UnsyncedChanges(pending.takeIf { it > 0 })

/** Narrow projection of `public.users` for [SupabaseAuthSession.pendingDeletionAt]. */
@Serializable
private data class PendingDeletionRow(
    @SerialName("deletion_requested_at") val deletionRequestedAt: Long? = null,
)

/** Body for the `apple-link-token` edge function call in [SupabaseAuthSession.linkAppleToken]. */
@Serializable
private data class LinkAppleTokenReq(
    val authorizationCode: String,
)
