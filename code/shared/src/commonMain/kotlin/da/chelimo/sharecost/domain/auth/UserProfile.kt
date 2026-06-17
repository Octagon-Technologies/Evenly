package da.chelimo.sharecost.domain.auth

import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.settlement.PaymentApp

/**
 * The signed-in user's editable profile (02 §3.2). [paymentHandles] holds only the apps the user has
 * set — the values other members deep-link into when paying them back (03 §5.1).
 */
data class UserProfile(
    val userId: UserId,
    val displayName: String,
    val email: String?,
    val baseCurrency: String,
    val paymentHandles: Map<PaymentApp, String>,
    val notifications: NotificationPrefs = NotificationPrefs(),
)

/** The user's per-event push preferences (06 §5.4). Persisted on the user row + synced across devices. */
data class NotificationPrefs(
    val newExpenses: Boolean = true,
    val payments: Boolean = true,
    val conflictReminders: Boolean = false,
)
