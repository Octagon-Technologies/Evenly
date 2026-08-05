package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

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
@Serializable
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

    /**
     * Who claimed this placeholder. [placeholderClaimCompletedAt] records *that* a name was claimed but
     * not by whom, and the loser of a concurrent claim has to be told who won. Mirrored on the entity (not
     * just server-side) because the merged member row is pushed right after the guard RPC stamps it — an
     * entity without the column would push a null straight back over the winner.
     */
    @ColumnInfo(name = "placeholder_claimed_by")
    val placeholderClaimedBy: String? = null,

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
