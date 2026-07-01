package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `expense_items` — one line on a "Split the bill" expense (the restaurant/itemized
 * flow). The bill's items are the creator-owned "menu"; people then stake claims against them
 * ([ItemClaimEntity]). The **line total** ([lineTotalSubunits]) is the entered source of truth (matches
 * how receipts print, penny-exact); [unitPriceSubunits] is the derived per-unit for display only
 * (`round(line_total / quantity)`) and is read nowhere as truth — the claim math splits the line total.
 *
 * Synced (`@Serializable`, snake_case columns 1:1 with Postgres, no Room FK), carries `group_id` for
 * pull scoping + `row_version` + `updated_at` (so the `keepNewer` last-write-wins guard applies on
 * pull). Soft-delete only (Rule 1): removing an item from the bill tombstones it via [deletedAt] so the
 * removal syncs; `allForSync()` ships tombstones. `sort_order` preserves receipt order.
 */
@Entity(
    tableName = "expense_items",
    indices = [
        Index(value = ["expense_id"]),
        Index(value = ["group_id"]),
    ],
)
@Serializable
data class ExpenseItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "quantity")
    val quantity: Int,

    @ColumnInfo(name = "unit_price_subunits")
    val unitPriceSubunits: Long,

    @ColumnInfo(name = "line_total_subunits", defaultValue = "0")
    val lineTotalSubunits: Long = 0,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
)
