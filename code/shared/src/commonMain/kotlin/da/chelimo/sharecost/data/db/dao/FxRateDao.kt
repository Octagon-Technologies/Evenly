package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.FxBakedEntity
import da.chelimo.sharecost.data.db.entity.FxRateEntity
import da.chelimo.sharecost.data.db.projection.FxBakedRateRow
import da.chelimo.sharecost.data.db.projection.FxLiveRateRow

/** DAO for `fx_rates` + the build-time `fx_baked` snapshot (02 §3.14/§7, 03 §6). */
@Dao
interface FxRateDao {

    @Upsert
    suspend fun upsertRates(rates: List<FxRateEntity>)

    @Upsert
    suspend fun upsertBaked(rates: List<FxBakedEntity>)

    /**
     * Most recent rate for [quoteCurrency] dated on or before [date] — the correct rate to value an
     * expense on its date even if a newer rate has since arrived. NULL if none yet (caller then
     * falls back to [bakedRatePerUsd]).
     */
    @Query(
        """
        SELECT rate_per_usd FROM fx_rates
        WHERE quote_currency = :quoteCurrency AND rate_date <= :date
        ORDER BY rate_date DESC LIMIT 1
        """
    )
    suspend fun ratePerUsdOnOrBefore(quoteCurrency: String, date: String): Double?

    /** Build-time fallback when the live table has nothing for the currency yet. */
    @Query("SELECT rate_per_usd FROM fx_baked WHERE quote_currency = :quoteCurrency")
    suspend fun bakedRatePerUsd(quoteCurrency: String): Double?

    /**
     * Like [ratePerUsdOnOrBefore] but also returns the rate_date used — the FX lookup needs the date
     * to compute the 7-day staleness signal (03 §6.3).
     */
    @Query(
        """
        SELECT rate_per_usd, rate_date FROM fx_rates
        WHERE quote_currency = :quoteCurrency AND rate_date <= :date
        ORDER BY rate_date DESC LIMIT 1
        """
    )
    suspend fun latestRateRowOnOrBefore(quoteCurrency: String, date: String): FxLiveRateRow?

    /** Baked fallback row carrying the snapshot date (03 §6.2/§6.4). */
    @Query("SELECT rate_per_usd, snapshot_date FROM fx_baked WHERE quote_currency = :quoteCurrency")
    suspend fun bakedRateRow(quoteCurrency: String): FxBakedRateRow?

    /** Newest cached rate_date across all currencies — drives the once-per-day refresh gate (03 §6.1). */
    @Query("SELECT MAX(rate_date) FROM fx_rates")
    suspend fun maxRateDate(): String?
}
