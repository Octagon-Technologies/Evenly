package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `groups` (02 §3.4). `admin_user_id` is nullable — NULL means the group is
 * abandoned (zero active members, AC-INV-005). `deleted_at` drives soft delete.
 */
@Entity(
    tableName = "groups",
    indices = [Index(value = ["invite_token"], unique = true)],
)
@Serializable
data class GroupEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "emoji")
    val emoji: String = "💸",

    @ColumnInfo(name = "base_currency")
    val baseCurrency: String = "USD",

    @ColumnInfo(name = "admin_user_id")
    val adminUserId: String? = null,

    @ColumnInfo(name = "invite_token")
    val inviteToken: String,

    @ColumnInfo(name = "invite_token_rotated_at")
    val inviteTokenRotatedAt: Long? = null,

    @ColumnInfo(name = "reminder_cadence")
    val reminderCadence: String = "WEEKLY",

    @ColumnInfo(name = "last_conflict_reminder_at")
    val lastConflictReminderAt: Long? = null,

    @ColumnInfo(name = "storage_bytes_used")
    val storageBytesUsed: Long = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "created_by")
    val createdBy: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
)
