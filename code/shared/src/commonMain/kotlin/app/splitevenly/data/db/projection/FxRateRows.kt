package app.splitevenly.data.db.projection

import androidx.room.ColumnInfo

/**
 * A live `fx_rates` row reduced to what the FX lookup chain needs: the rate and the date it is dated
 * (03 §6.2/§6.3 — the date drives the staleness signal). NOT an `@Entity`.
 */
data class FxLiveRateRow(
    @ColumnInfo(name = "rate_per_usd") val ratePerUsd: Double,
    @ColumnInfo(name = "rate_date") val rateDate: String,
)

/** A baked-snapshot row reduced to the rate + its snapshot date (03 §6.2 fallback leg). */
data class FxBakedRateRow(
    @ColumnInfo(name = "rate_per_usd") val ratePerUsd: Double,
    @ColumnInfo(name = "snapshot_date") val snapshotDate: String,
)
