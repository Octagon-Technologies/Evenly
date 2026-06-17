package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `shares` (02 §3.8). One row per participant of an expense.
 *
 * `share_owed_subunits` is what they owe; `remaining_subunits` is what's still unpaid
 * (`remaining <= owed` always). The raw split inputs (units/percentage/exact) are preserved so the
 * split editor can be re-rendered without recomputing.
 *
 * Invariant: `SUM(share_owed_subunits) for an expense == expenses.amount_subunits` (AC-INV-001).
 */
@Entity(
    tableName = "shares",
    indices = [
        Index(value = ["expense_id", "user_id"], unique = true),
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

    @ColumnInfo(name = "remaining_subunits")
    val remainingSubunits: Long,

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
)
