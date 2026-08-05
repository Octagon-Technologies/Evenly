package app.splitevenly.data.db.projection

import androidx.room.ColumnInfo

/**
 * One (settlement, expense-title) pair: an expense that a non-voided settlement's allocations paid
 * toward. Feeds the double-payment review, where each payment shows what it covered so a duplicate is
 * easier to spot. One row per distinct expense a settlement touched.
 */
data class SettlementCoveredTitleRow(
    @ColumnInfo(name = "settlement_id") val settlementId: String,
    @ColumnInfo(name = "title") val title: String,
)
