package app.splitevenly.data.auth

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.UserId
import app.splitevenly.newId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.db.dao.UserDao
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.auth.OAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Local-only [AuthSession] for the MVP. "Signing in" with any provider creates (or reloads) a single
 * local account keyed by a fixed email, so groups/expenses created under it persist across launches.
 * No network — real Supabase auth replaces this impl behind the same interface (06 §5.5).
 */
@OptIn(ExperimentalTime::class)
class StubAuthSession(
    private val userDao: UserDao,
    private val clock: Clock = Clock.System,
) : AuthSession {

    private val _currentUserId = MutableStateFlow<UserId?>(null)
    override val currentUserId: StateFlow<UserId?> = _currentUserId.asStateFlow()

    override suspend fun signIn(displayName: String): UserId {
        val existing = userDao.findByEmail(STUB_EMAIL)
        val id = existing?.id ?: run {
            val now = clock.nowEpochMillis()
            val user = UserEntity(
                id = newId(),
                displayName = displayName.ifBlank { "You" },
                email = STUB_EMAIL,
                baseCurrency = "USD",
                createdAt = now,
                updatedAt = now,
            )
            userDao.upsert(user)
            user.id
        }
        return UserId(id).also { _currentUserId.value = it }
    }

    // No network in the stub: every provider / OTP path resolves to the same single local account,
    // so the offline-first build stays fully usable without Supabase configured.
    override suspend fun signInWithProvider(provider: OAuthProvider): AppResult<Unit> {
        signIn(provider.name.lowercase().replaceFirstChar { it.uppercase() })
        return AppResult.Ok(Unit)
    }

    override suspend fun sendEmailOtp(email: String): AppResult<Unit> = AppResult.Ok(Unit)

    override suspend fun verifyEmailOtp(email: String, token: String): AppResult<UserId> =
        AppResult.Ok(signIn("You"))

    override suspend fun signInWithPassword(email: String, password: String): AppResult<UserId> =
        AppResult.Ok(signIn(email.substringBefore('@').replaceFirstChar { it.uppercase() }))

    override suspend fun hasOnboardedProfile(): Boolean {
        // No server in the stub: the single local account counts as onboarded once it has a real name
        // (anything other than the "You" placeholder a brand-new OTP sign-in seeds).
        val name = userDao.findByEmail(STUB_EMAIL)?.displayName?.trim()
        return !name.isNullOrBlank() && name != "You"
    }

    override fun signOut() {
        _currentUserId.value = null
    }

    override suspend fun deleteAccount(): AppResult<Unit> {
        // No server in the stub: drop the local account row and sign out.
        userDao.findByEmail(STUB_EMAIL)?.let { userDao.delete(it.id) }
        _currentUserId.value = null
        return AppResult.Ok(Unit)
    }

    private companion object {
        const val STUB_EMAIL = "you@evenly.local"
    }
}
