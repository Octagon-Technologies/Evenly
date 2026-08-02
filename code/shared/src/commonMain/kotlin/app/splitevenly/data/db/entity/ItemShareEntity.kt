package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `item_shares` — one person's membership in a **shared portion** of a line. A line's
 * sharing is a set of PORTIONS ([portionId] groups a slice's members), each covering [quantity] units and
 * split evenly among its members — so "2 solo, 3 solo, 1 shared, 2 left" is expressible (a single
 * all-leftover set couldn't). A legacy row (null [portionId]) is one implicit all-leftover portion.
 *
 * Membership is an **additive, auto-union set** within a portion: overlapping "shared with" declarations
 * merge for free. [addedBy] records who put a member in; the *member* always has the final say — they can
 * remove themselves (soft-delete). A person CAN be in more than one portion of the same line at once (the
 * per-serving assign sheet needs this: solo on one serving, shared with someone else on another) — the
 * uniqueness key is `(item_id, user_id, portion_id)`, not `(item_id, user_id)`.
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

    // Groups the members of one shared slice; null on legacy rows (one implicit all-leftover portion).
    @ColumnInfo(name = "portion_id")
    val portionId: String? = null,

    // Units this shared slice covers (denormalised onto each member row; same across a portion's members).
    @ColumnInfo(name = "quantity")
    val quantity: Int = 1,

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
