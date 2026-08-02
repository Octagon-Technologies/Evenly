package app.splitevenly.domain.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.domain.auth.NotificationPrefs
import app.splitevenly.domain.auth.ThemeMode
import app.splitevenly.domain.auth.UserProfile
import app.splitevenly.domain.settlement.PaymentApp
import kotlinx.coroutines.flow.Flow

/**
 * The signed-in user's profile (06 §3). Reads stream the current account; the only mutation the MVP
 * needs is editing the per-payee payment handles (Profile / onboarding). Backed locally by Room; the
 * server push is part of S-1.
 */
interface ProfileRepository {
    /** The current user's profile, or null when signed out. */
    fun observeProfile(): Flow<UserProfile?>

    /** Replace the current user's payment handles (apps absent from the map are cleared). */
    suspend fun updatePaymentHandles(handles: Map<PaymentApp, String>): AppResult<Unit>

    /** Set the current user's preferred payment app (null to clear) — the default others see when
     *  paying them back. Callers should pass an app the user actually has a handle for. */
    suspend fun updatePreferredPaymentApp(app: PaymentApp?): AppResult<Unit>

    /** Persist the onboarding basics — display name + default base currency for new groups. */
    suspend fun updateProfile(displayName: String, baseCurrency: String): AppResult<Unit>

    /** Rename the current user (Profile editor). */
    suspend fun updateDisplayName(displayName: String): AppResult<Unit>

    /** Persist the current user's notification preferences (Profile editor). */
    suspend fun updateNotificationPrefs(prefs: NotificationPrefs): AppResult<Unit>

    /** Persist the current user's appearance preference (Profile → Appearance). */
    suspend fun updateThemeMode(mode: ThemeMode): AppResult<Unit>
}
