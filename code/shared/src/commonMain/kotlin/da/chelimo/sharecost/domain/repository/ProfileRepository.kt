package da.chelimo.sharecost.domain.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.domain.auth.UserProfile
import da.chelimo.sharecost.domain.settlement.PaymentApp
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

    /** Persist the onboarding basics — display name + default base currency for new groups. */
    suspend fun updateProfile(displayName: String, baseCurrency: String): AppResult<Unit>

    /** Rename the current user (Profile editor). */
    suspend fun updateDisplayName(displayName: String): AppResult<Unit>
}
