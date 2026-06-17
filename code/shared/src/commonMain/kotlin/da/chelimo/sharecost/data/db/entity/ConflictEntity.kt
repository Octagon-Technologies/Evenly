package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `conflicts` (03 §8.3): a non-EVEN past expense a member was added to retroactively,
 * awaiting a per-expense decision (Include / Skip). Keyed uniquely on (expense, added user) so a
 * second retro-add for the same person is idempotent. [resolution] is INCLUDE/DISMISS once [resolvedAt]
 * is set; an unresolved row drives the Conflicts tab + its bottom-nav badge.
 */
@Entity(
    tableName = "conflicts",
    indices = [
        Index(value = ["group_id"]),
        Index(value = ["expense_id", "added_user_id"], unique = true),
    ],
)
@Serializable
data class ConflictEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "added_user_id")
    val addedUserId: String,

    @ColumnInfo(name = "triggered_by_user_id")
    val triggeredByUserId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "resolved_at")
    val resolvedAt: Long? = null,

    /** INCLUDE | DISMISS, once resolved (03 §8.4). Null while pending. */
    @ColumnInfo(name = "resolution")
    val resolution: String? = null,
)
