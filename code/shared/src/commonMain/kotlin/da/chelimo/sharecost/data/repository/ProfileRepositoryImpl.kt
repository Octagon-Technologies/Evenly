package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.error.asErr
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.data.db.dao.UserDao
import da.chelimo.sharecost.data.db.entity.UserEntity
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.auth.NotificationPrefs
import da.chelimo.sharecost.domain.auth.ThemeMode
import da.chelimo.sharecost.domain.auth.UserProfile
import da.chelimo.sharecost.domain.repository.ProfileRepository
import da.chelimo.sharecost.domain.settlement.PaymentApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Local-first [ProfileRepository] (06 §3): the current account is whatever [AuthSession] points at;
 * handle edits write straight to the `users` row. Server push is deferred to S-1.
 */
@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class ProfileRepositoryImpl(
    private val userDao: UserDao,
    private val auth: AuthSession,
    private val clock: Clock = Clock.System,
) : ProfileRepository {

    override fun observeProfile(): Flow<UserProfile?> =
        auth.currentUserId.flatMapLatest { id ->
            if (id == null) flowOf(null) else userDao.observeById(id.value).map { it?.toProfile() }
        }

    override suspend fun updatePaymentHandles(handles: Map<PaymentApp, String>): AppResult<Unit> {
        val id = auth.currentUserId.value
            ?: return AppError.Validation(mapOf("user" to AppError.Validation.Reason.Required)).asErr()
        fun clean(app: PaymentApp) = handles[app]?.trim()?.takeIf { it.isNotEmpty() }
        userDao.updatePaymentHandles(
            id = id.value,
            venmo = clean(PaymentApp.VENMO),
            cashapp = clean(PaymentApp.CASH_APP),
            paypal = clean(PaymentApp.PAYPAL),
            zelle = clean(PaymentApp.ZELLE),
            now = clock.nowEpochMillis(),
        )
        return AppResult.Ok(Unit)
    }

    override suspend fun updatePreferredPaymentApp(app: PaymentApp?): AppResult<Unit> {
        val id = auth.currentUserId.value
            ?: return AppError.Validation(mapOf("user" to AppError.Validation.Reason.Required)).asErr()
        userDao.updatePreferredPaymentApp(id = id.value, app = app?.name, now = clock.nowEpochMillis())
        return AppResult.Ok(Unit)
    }

    override suspend fun updateProfile(displayName: String, baseCurrency: String): AppResult<Unit> {
        val id = auth.currentUserId.value
            ?: return AppError.Validation(mapOf("user" to AppError.Validation.Reason.Required)).asErr()
        userDao.updateProfile(
            id = id.value,
            name = displayName.trim().ifBlank { "You" },
            currency = baseCurrency.trim().ifBlank { "USD" },
            now = clock.nowEpochMillis(),
        )
        return AppResult.Ok(Unit)
    }

    override suspend fun updateDisplayName(displayName: String): AppResult<Unit> {
        val id = auth.currentUserId.value
            ?: return AppError.Validation(mapOf("user" to AppError.Validation.Reason.Required)).asErr()
        val name = displayName.trim()
        if (name.isEmpty()) return AppError.Validation(mapOf("displayName" to AppError.Validation.Reason.Required)).asErr()
        userDao.updateDisplayName(id = id.value, name = name, now = clock.nowEpochMillis())
        return AppResult.Ok(Unit)
    }

    override suspend fun updateNotificationPrefs(prefs: NotificationPrefs): AppResult<Unit> {
        val id = auth.currentUserId.value
            ?: return AppError.Validation(mapOf("user" to AppError.Validation.Reason.Required)).asErr()
        userDao.updateNotificationPrefs(
            id = id.value,
            newExpenses = prefs.newExpenses,
            payments = prefs.payments,
            conflictReminders = prefs.conflictReminders,
            now = clock.nowEpochMillis(),
        )
        return AppResult.Ok(Unit)
    }

    override suspend fun updateThemeMode(mode: ThemeMode): AppResult<Unit> {
        val id = auth.currentUserId.value
            ?: return AppError.Validation(mapOf("user" to AppError.Validation.Reason.Required)).asErr()
        userDao.updateThemeMode(id = id.value, themeMode = mode.name, now = clock.nowEpochMillis())
        return AppResult.Ok(Unit)
    }
}

private fun UserEntity.toProfile(): UserProfile = UserProfile(
    userId = UserId(id),
    displayName = displayName,
    email = email,
    baseCurrency = baseCurrency ?: "USD",
    paymentHandles = paymentHandles(venmoHandle, cashappHandle, paypalHandle, zelleHandle),
    preferredPaymentApp = parsePaymentApp(preferredPaymentApp),
    notifications = NotificationPrefs(
        newExpenses = notifyNewExpenses,
        payments = notifyPayments,
        conflictReminders = notifyConflictReminders,
    ),
    themeMode = ThemeMode.fromName(themeMode),
)
