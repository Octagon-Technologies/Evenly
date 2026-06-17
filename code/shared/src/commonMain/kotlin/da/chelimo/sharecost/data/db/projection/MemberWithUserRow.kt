package da.chelimo.sharecost.data.db.projection

import androidx.room.ColumnInfo

/**
 * Read-only projection joining `members` to its `users` row so a roster carries display names in one
 * query. A `LEFT JOIN` is used so a member whose user row has not synced yet still appears (with a
 * null [displayName]) instead of vanishing — out-of-order sync is expected (02 §7).
 */
data class MemberWithUserRow(
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "is_placeholder") val isPlaceholder: Boolean,
    @ColumnInfo(name = "is_admin") val isAdmin: Boolean,
    @ColumnInfo(name = "joined_at") val joinedAt: Long,
    @ColumnInfo(name = "venmo_handle") val venmoHandle: String? = null,
    @ColumnInfo(name = "cashapp_handle") val cashappHandle: String? = null,
    @ColumnInfo(name = "paypal_handle") val paypalHandle: String? = null,
    @ColumnInfo(name = "zelle_handle") val zelleHandle: String? = null,
)
