package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.FxBakedEntity
import da.chelimo.sharecost.data.db.entity.FxRateEntity

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
}
