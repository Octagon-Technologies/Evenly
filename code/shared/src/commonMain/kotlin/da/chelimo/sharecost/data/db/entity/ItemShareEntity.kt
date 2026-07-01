package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `item_shares` — one person's membership in a line's **shared split** (the "I split this
 * with these people" set). The set of active rows for an item is its sharer group; the line's leftover
 * (un-individually-claimed) units split evenly across it.
 *
 * Membership is an **additive, auto-union set**: because it's a set, overlapping "shared with"
 * declarations merge for free (Bob adds Mary, Steve adds Bob → {Bob, Mary, Steve}) with nothing to
 * confirm. [addedBy] records who put a member in (for display / undo); the *member* always has the final
 * say — they can remove themselves (soft-delete), and last-write-wins on `(item_id, user_id)` settles it.
 *
 * Synced (`@Serializable`, carries `group_id` + `expense_id` for pull scoping, `row_version` +
 * `updated_at` for the `keepNewer` guard). Soft-delete only (Rule 1): leaving a share tombstones the row.
 */
@Entity(
    tableName = "item_shares",
    indices = [
        Index(value = ["item_id", "user_id"]),
        Index(value = ["expense_id"]),
        Index(value = ["group_id"]),
    ],
)
@Serializable
data class ItemShareEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "item_id")
    val itemId: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "added_by")
    val addedBy: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
)
