package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.UserEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `users` (02 §3.2). Writes are `suspend` (one-shot); reads come in two flavours:
 * `suspend` for one-shot fetches and `Flow` for reactive streams the UI collects.
 *
 * `@Upsert` is the sync-friendly write: insert-or-update keyed on the primary key, so replaying a
 * server row that already exists locally is idempotent (no `OnConflictStrategy` juggling).
 */
@Dao
interface UserDao {

    @Upsert
    suspend fun upsert(user: UserEntity)

    @Upsert
    suspend fun upsertAll(users: List<UserEntity>)

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getById(id: String): UserEntity?

    /** Remove a user row (account deletion / local cleanup). */
    @Query("DELETE FROM users WHERE id = :id")
    suspend fun delete(id: String)

    /** Every local row — the push side of sync (the device only holds rows the user may see). */
    @Query("SELECT * FROM users")
    suspend fun allForSync(): List<UserEntity>

    @Query("SELECT * FROM users WHERE id = :id")
    fun observeById(id: String): Flow<UserEntity?>

    /** Case-insensitive lookup (mirrors `citext`); used to resolve a sign-in email to its user. */
    @Query("SELECT * FROM users WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun findByEmail(email: String): UserEntity?

    /** Set the user's per-payee payment handles in place (Profile editor / onboarding). */
    @Query(
        """
        UPDATE users
        SET venmo_handle = :venmo, cashapp_handle = :cashapp, paypal_handle = :paypal, zelle_handle = :zelle,
            updated_at = :now, row_version = row_version + 1
        WHERE id = :id
        """
    )
    suspend fun updatePaymentHandles(id: String, venmo: String?, cashapp: String?, paypal: String?, zelle: String?, now: Long)

    /** Set the user's preferred payment app (a [da.chelimo.sharecost.domain.settlement.PaymentApp]
     *  name, or null to clear) — the default others see when settling with them. */
    @Query("UPDATE users SET preferred_payment_app = :app, updated_at = :now, row_version = row_version + 1 WHERE id = :id")
    suspend fun updatePreferredPaymentApp(id: String, app: String?, now: Long)

    /** Persist onboarding basics (display name + base currency). */
    @Query(
        """
        UPDATE users
        SET display_name = :name, base_currency = :currency, updated_at = :now, row_version = row_version + 1
        WHERE id = :id
        """
    )
    suspend fun updateProfile(id: String, name: String, currency: String, now: Long)

    /** Rename the user (Profile editor). */
    @Query("UPDATE users SET display_name = :name, updated_at = :now, row_version = row_version + 1 WHERE id = :id")
    suspend fun updateDisplayName(id: String, name: String, now: Long)

    /** Persist the user's notification preferences (Profile editor). */
    @Query(
        """
        UPDATE users
        SET notify_new_expenses = :newExpenses, notify_payments = :payments,
            notify_conflict_reminders = :conflictReminders, updated_at = :now, row_version = row_version + 1
        WHERE id = :id
        """
    )
    suspend fun updateNotificationPrefs(id: String, newExpenses: Boolean, payments: Boolean, conflictReminders: Boolean, now: Long)

    /** Persist the user's appearance preference (Profile → Appearance). */
    @Query("UPDATE users SET theme_mode = :themeMode, updated_at = :now, row_version = row_version + 1 WHERE id = :id")
    suspend fun updateThemeMode(id: String, themeMode: String, now: Long)

    /**
     * Still-claimable placeholders in a group, name-ordered — feeds the reconcile picker (AC-M1-030)
     * and the join-sheet identity picker. Joined to `members` so a placeholder whose membership was
     * already claimed/merged (member `LEFT` / `placeholder_claim_completed_at` set) or removed drops
     * out instead of lingering as a pickable identity. An INNER JOIN is correct: every placeholder is
     * created with a matching member row (`addPlaceholder`), and an orphan placeholder with no active
     * member should not be claimable.
     */
    @Query(
        """
        SELECT u.* FROM users u
        JOIN members m ON m.user_id = u.id AND m.group_id = u.placeholder_group_id
        WHERE u.is_placeholder = 1
          AND u.placeholder_group_id = :groupId
          AND m.status = 'ACTIVE'
          AND m.placeholder_claim_completed_at IS NULL
        ORDER BY u.display_name COLLATE NOCASE ASC
        """
    )
    fun observePlaceholdersInGroup(groupId: String): Flow<List<UserEntity>>

    /**
     * Case-insensitive count of a placeholder name within a group — drives the uniqueness guard in
     * AC-M1-021 ("Tyler" / "tyler" / "TYLER" collide; same name in another group does not).
     */
    @Query(
        """
        SELECT COUNT(*) FROM users
        WHERE is_placeholder = 1
          AND placeholder_group_id = :groupId
          AND display_name = :name COLLATE NOCASE
        """
    )
    suspend fun countPlaceholderName(groupId: String, name: String): Int
}
