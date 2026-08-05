package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `shares` (02 §3.8). One row per participant of an expense.
 *
 * `share_owed_subunits` is what they owe (ground truth). What's still unpaid (`remaining`) is **not
 * stored here** — it is derived on read as `owed − Σ(applied settlement allocations)` (see
 * [app.splitevenly.data.db.projection.ShareRow]); the payer's own share is always 0. Storing it
 * was the cause of the "editing a split wipes payments" bug. The raw split inputs (units/percentage/
 * exact) are preserved so the split editor can be re-rendered without recomputing.
 *
 * Invariant: `SUM(share_owed_subunits) for an expense == expenses.amount_subunits` (AC-INV-001).
 *
 * Soft-delete: a participant removed on an edit is tombstoned ([deletedAt]) so the removal syncs;
 * the (expense_id, user_id) index is **non-unique** because a soft-deleted row can coexist with a
 * re-added active one (the server enforces uniqueness over active rows via a partial index).
 */
@Entity(
    tableName = "shares",
    indices = [
        Index(value = ["expense_id", "user_id"]),
        Index(value = ["expense_id"]),
        Index(value = ["user_id"]),
    ],
)
@Serializable
data class ShareEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "share_owed_subunits")
    val shareOwedSubunits: Long,

    @ColumnInfo(name = "share_units")
    val shareUnits: Int? = null,

    @ColumnInfo(name = "share_percentage")
    val sharePercentage: Double? = null,

    @ColumnInfo(name = "share_exact_subunits")
    val shareExactSubunits: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
)
