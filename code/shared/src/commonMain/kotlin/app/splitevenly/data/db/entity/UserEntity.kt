package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `users` (02 §3.2): one table for both real users and placeholder participants.
 *
 * Column names are snake_case to mirror the server row 1:1 so the sync layer maps fields by name.
 * `email` is `COLLATE NOCASE` to mirror Postgres `citext` (02 §7.4); the index on it gives the
 * case-insensitive UNIQUE the server enforces (multiple NULLs are allowed → placeholders are fine).
 */
@Entity(
    tableName = "users",
    indices = [
        Index(value = ["email"], unique = true),
        Index(value = ["placeholder_group_id"]),
    ],
)
@Serializable
data class UserEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "is_placeholder")
    val isPlaceholder: Boolean = false,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "email", collate = ColumnInfo.NOCASE)
    val email: String? = null,

    @ColumnInfo(name = "avatar_url")
    val avatarUrl: String? = null,

    @ColumnInfo(name = "base_currency")
    val baseCurrency: String? = "USD",

    // Per-payee payment handles (03 §5.1). Null when the user hasn't set that app. The peer's handles
    // are what the settle screen deep-links into; each user owns their own. One column per PaymentApp.
    @ColumnInfo(name = "venmo_handle")
    val venmoHandle: String? = null,

    @ColumnInfo(name = "cashapp_handle")
    val cashappHandle: String? = null,

    @ColumnInfo(name = "paypal_handle")
    val paypalHandle: String? = null,

    @ColumnInfo(name = "zelle_handle")
    val zelleHandle: String? = null,

    /**
     * The user's *preferred* payment app (a [PaymentApp] name, or null). Highlighted as the default
     * when others settle with them; only meaningful when the matching `*_handle` above is also set.
     */
    @ColumnInfo(name = "preferred_payment_app")
    val preferredPaymentApp: String? = null,

    /** Non-null iff [isPlaceholder]; the group the placeholder lives in (02 §3.2 invariant). */
    @ColumnInfo(name = "placeholder_group_id")
    val placeholderGroupId: String? = null,

    /**
     * Who typed this name in. Set only when [isPlaceholder], and it exists for exactly one purpose: never
     * ask someone whether they are a name they created themselves. Inferring it from the earliest
     * expense's payer was the alternative, and it is wrong whenever someone adds a person to a bill they
     * didn't pay for. Rows predating the column stay null ("creator unknown") and are offered to
     * everyone, which is the pre-feature behaviour.
     */
    @ColumnInfo(name = "created_by")
    val createdBy: String? = null,

    // Notification preferences (06 §5.4). Stored on the user so they persist + sync across devices;
    // a server-side push sender reads these before targeting a recipient.
    @ColumnInfo(name = "notify_new_expenses")
    val notifyNewExpenses: Boolean = true,

    @ColumnInfo(name = "notify_payments")
    val notifyPayments: Boolean = true,

    @ColumnInfo(name = "notify_conflict_reminders")
    val notifyConflictReminders: Boolean = false,

    /** Appearance preference: "System" | "Light" | "Dark" (06 §UI). */
    @ColumnInfo(name = "theme_mode")
    val themeMode: String = "System",

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
) {
    companion object {
        /**
         * Reserved system user (02 §3.2). Authors auto-refund rows; MUST NEVER appear in any
         * member list, picker, balance, or push-recipient set (AC-INV-011).
         */
        const val SYSTEM_USER_ID: String = "00000000-0000-0000-0000-000000000000"
    }
}
