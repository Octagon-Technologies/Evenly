package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local mirror of `settlement_allocations` (02 §3.9): how much of a settlement was applied to one
 * share. `applied_amount_subunits` is in the **share's** currency, so it reduces
 * `shares.remaining_subunits` directly; `fx_rate_used`/`fx_rate_date` are NULL for same-currency
 * settlements and set when the payment currency differs.
 */
@Entity(
    tableName = "settlement_allocations",
    indices = [
        Index(value = ["settlement_id", "share_id"], unique = true),
        Index(value = ["share_id"]),
    ],
)
data class SettlementAllocationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "settlement_id")
    val settlementId: String,

    @ColumnInfo(name = "share_id")
    val shareId: String,

    @ColumnInfo(name = "applied_amount_subunits")
    val appliedAmountSubunits: Long,

    @ColumnInfo(name = "applied_currency")
    val appliedCurrency: String,

    @ColumnInfo(name = "fx_rate_used")
    val fxRateUsed: Double? = null,

    @ColumnInfo(name = "fx_rate_date")
    val fxRateDate: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
