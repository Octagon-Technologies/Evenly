package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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

    /** Non-null iff [isPlaceholder]; the group the placeholder lives in (02 §3.2 invariant). */
    @ColumnInfo(name = "placeholder_group_id")
    val placeholderGroupId: String? = null,

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
