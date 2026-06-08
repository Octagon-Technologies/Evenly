package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local mirror of `members` (02 §3.5). A user's membership in a group, with a soft `LEFT` status
 * (status='LEFT' implies left_at != null). UNIQUE(group_id, user_id) mirrors the server.
 */
@Entity(
    tableName = "members",
    indices = [
        Index(value = ["group_id", "user_id"], unique = true),
        Index(value = ["group_id"]),
        Index(value = ["user_id"]),
    ],
)
data class MemberEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "status")
    val status: String = "ACTIVE",

    @ColumnInfo(name = "is_admin")
    val isAdmin: Boolean = false,

    @ColumnInfo(name = "joined_at")
    val joinedAt: Long,

    @ColumnInfo(name = "left_at")
    val leftAt: Long? = null,

    @ColumnInfo(name = "archived_at")
    val archivedAt: Long? = null,

    @ColumnInfo(name = "placeholder_claim_completed_at")
    val placeholderClaimCompletedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_LEFT = "LEFT"
    }
}
