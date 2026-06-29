package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `settlement_allocations` (02 §3.9): how much of a settlement was applied to one
 * share. `applied_amount_subunits` is in the **share's** currency. These rows are the GROUND TRUTH
 * for payments — a share's `remaining` is derived as `owed − Σ(applied of non-voided settlements)` —
 * so they are **synced** (`@Serializable`, carry `group_id` for pull scoping + `row_version`).
 * `fx_rate_used`/`fx_rate_date` are NULL for same-currency settlements and set when currencies differ.
 */
@Entity(
    tableName = "settlement_allocations",
    indices = [
        Index(value = ["settlement_id", "share_id"], unique = true),
        Index(value = ["share_id"]),
        Index(value = ["group_id"]),
    ],
)
@Serializable
data class SettlementAllocationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "settlement_id")
    val settlementId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

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

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
)
