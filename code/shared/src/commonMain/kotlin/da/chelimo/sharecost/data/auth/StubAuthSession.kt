package da.chelimo.sharecost.data.auth

import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.newId
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.data.db.dao.UserDao
import da.chelimo.sharecost.data.db.entity.UserEntity
import da.chelimo.sharecost.domain.auth.AuthSession
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

    override fun signOut() {
        _currentUserId.value = null
    }

    private companion object {
        const val STUB_EMAIL = "you@sharecost.local"
    }
}
