package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `categories` — per-group, editable expense categories.
 *
 * **Copy-on-write defaults.** A group has NO rows here until it first customizes; until then the client
 * renders its built-in default categories (the [app.splitevenly.domain.expense.ExpenseCategory]
 * enum). The first edit materializes the full default set ([isDefault] = true) and then mutates — so a
 * group that never touches categories costs zero rows (most groups won't).
 *
 * [key] is the stable identifier an expense stores in `expenses.category_id` — `"food"` etc. for a
 * default-derived row, a generated uuid for a custom one. It's unique per group among live rows
 * (partial unique index), so resolving an expense's category is a `(group_id, key)` lookup.
 *
 * Synced wire-mirror with a [deletedAt] tombstone, same contract as [ReceiptEntity].
 */
@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["group_id"]),
        Index(value = ["group_id", "key"]),
    ],
)
@Serializable
data class CategoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    /** Stable per-group identifier persisted on `expenses.category_id`. */
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "label")
    val label: String,

    /** Icon token (resolved to a vector in the UI layer — see `ui/screen/group/CategoryCatalog`). */
    @ColumnInfo(name = "icon")
    val icon: String,

    /** ARGB color as a hex string, e.g. `#2563EB`. */
    @ColumnInfo(name = "color")
    val color: String,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Long = 0,

    /** True when this row was materialized from a built-in default (vs. a user-created category). */
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
)
