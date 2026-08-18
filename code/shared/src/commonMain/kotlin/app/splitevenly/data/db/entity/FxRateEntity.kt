package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * `fx_rates` (02 §3.14) — a Room-ONLY cache of fetched FX rates, with **no server counterpart** (same
 * as [FxBakedEntity]/[FxCurrencyEntity]; the "mirror of a Postgres table" phrasing the synced entities
 * use does not apply here). Rates are stored as `(USD, quote)`; cross-currency conversion always
 * routes via USD (03 §6). Composite primary key `(rate_date, quote_currency)` — one rate per currency
 * per day — so there's no surrogate id and `@Upsert` replaces a day's rate.
 */
@Entity(
    tableName = "fx_rates",
    primaryKeys = ["rate_date", "quote_currency"],
    indices = [Index(value = ["quote_currency", "rate_date"])],
)
data class FxRateEntity(
    @ColumnInfo(name = "rate_date")
    val rateDate: String,
    @ColumnInfo(name = "quote_currency")
    val quoteCurrency: String,
    @ColumnInfo(name = "rate_per_usd")
    val ratePerUsd: Double,
    @ColumnInfo(name = "source")
    val source: String = "FRANKFURTER",
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
)
