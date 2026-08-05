package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `item_claims` — one person's stake in an [ExpenseItemEntity] ("I had 2 of these").
 * This is the **live, multi-device layer** of a "Split the bill" expense: claims are partitioned by
 * user (you only ever write your own), so concurrent claiming is conflict-free — there's no CAS, the
 * way an expense edit needs one. [quantity] is the claimed unit count for a countable item, or an equal
 * weight for a "shared by these people" item; the item's exact cost splits across claimants by weight.
 *
 * Synced (`@Serializable`, carries `group_id` + `expense_id` denormalised for pull scoping/grouping,
 * `row_version` + `updated_at` for the `keepNewer` guard). Soft-delete only (Rule 1): un-claiming
 * tombstones the row via [deletedAt] so the removal syncs. The (item_id, user_id) index is non-unique
 * because a tombstone can coexist with a re-added active claim; the server enforces uniqueness over
 * active rows with a partial index.
 */
@Entity(
    tableName = "item_claims",
    indices = [
        Index(value = ["item_id", "user_id"]),
        Index(value = ["expense_id"]),
        Index(value = ["group_id"]),
    ],
)
@Serializable
data class ItemClaimEntity(
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

    @ColumnInfo(name = "quantity")
    val quantity: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
)
