package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `fx_baked` (02 §7) — a Room-ONLY table with no server counterpart. It ships inside the app binary
 * as a build-time FX snapshot so conversions still work on a cold first launch with no network.
 *
 * Lookup chain (03 §6, assembled in FxRepository): Room `fx_rates` → Room `fx_baked` → null.
 */
@Entity(tableName = "fx_baked")
data class FxBakedEntity(
    @PrimaryKey
    @ColumnInfo(name = "quote_currency")
    val quoteCurrency: String,

    @ColumnInfo(name = "rate_per_usd")
    val ratePerUsd: Double,

    @ColumnInfo(name = "snapshot_date")
    val snapshotDate: String,
)
