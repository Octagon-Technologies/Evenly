package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.log.Log
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.core.time.todayUtc
import da.chelimo.sharecost.data.db.dao.FxCurrencyDao
import da.chelimo.sharecost.data.db.dao.FxRateDao
import da.chelimo.sharecost.data.db.entity.FxCurrencyEntity
import da.chelimo.sharecost.data.db.entity.FxRateEntity
import da.chelimo.sharecost.data.remote.fx.FxRateFetcher
import da.chelimo.sharecost.domain.fx.CurrencyInfo
import da.chelimo.sharecost.domain.fx.FxCurrencyDefaults
import da.chelimo.sharecost.domain.fx.FxResult
import da.chelimo.sharecost.domain.repository.FxRepository
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * [FxRepository] over Room (`fx_rates` / `fx_baked`) + a [FxRateFetcher]. Implements the lookup chain
 * and staleness rules verbatim from 03 §6.2/§6.3, and the once-per-day cold-start refresh from
 * 03 §6.1 / 04 §5.2. All rates are `USD → quote`; cross-pairs route via USD.
 */
@OptIn(ExperimentalTime::class)
class FxRepositoryImpl(
    private val fxRateDao: FxRateDao,
    private val fxCurrencyDao: FxCurrencyDao,
    private val fetcher: FxRateFetcher,
    private val clock: Clock = Clock.System,
) : FxRepository {

    override suspend fun rate(from: String, to: String, asOf: String): FxResult {
        if (from == to) return FxResult.Same
        val today = clock.todayUtc()

        // Live legs (a USD leg is identity 1.0 and contributes no rate date).
        val liveFrom = liveLeg(from, asOf)
        val liveTo = liveLeg(to, asOf)
        if (liveFrom != null && liveTo != null) {
            val usedDate = newestDate(liveFrom.date, liveTo.date)
            return FxResult.Live(
                rate = liveTo.perUsd / liveFrom.perUsd,
                asOfDate = usedDate ?: today,
                stale = isStale(usedDate, today),
            )
        }

        // Baked-snapshot fallback (03 §6.4).
        val bakedFrom = bakedLeg(from)
        val bakedTo = bakedLeg(to)
        if (bakedFrom != null && bakedTo != null) {
            val usedDate = newestDate(bakedFrom.date, bakedTo.date)
            return FxResult.Baked(
                rate = bakedTo.perUsd / bakedFrom.perUsd,
                snapshotDate = usedDate ?: today,
                stale = isStale(usedDate, today),
            )
        }
        return FxResult.Unavailable
    }

    override suspend fun refreshIfStale(): AppResult<Unit> {
        val today = clock.todayUtc()
        val newest = fxRateDao.maxRateDate()
        if (newest != null && newest >= today) return AppResult.Ok(Unit) // already have today's rates

        val snapshot = when (val result = fetcher.fetchLatest()) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> {
                Log.w("FX refresh skipped (best-effort); using cached/baked rates") // 03 §6.1/§6.4
                return AppResult.Ok(Unit)
            }
        }

        val fetchedAt = clock.nowEpochMillis()
        val rows = buildList {
            // Frankfurter omits the base from `rates`; store USD→USD=1.0 so USD legs resolve uniformly.
            add(FxRateEntity(rateDate = snapshot.rateDate, quoteCurrency = USD, ratePerUsd = 1.0, fetchedAt = fetchedAt))
            for ((ccy, perUsd) in snapshot.ratesPerUsd) {
                add(FxRateEntity(rateDate = snapshot.rateDate, quoteCurrency = ccy, ratePerUsd = perUsd, fetchedAt = fetchedAt))
            }
        }
        fxRateDao.upsertRates(rows)
        return AppResult.Ok(Unit)
    }

    override suspend fun currencies(): List<CurrencyInfo> {
        val cached = fxCurrencyDao.all()
        if (cached.isNotEmpty()) return cached.map { CurrencyInfo(it.code, it.name) }

        return when (val result = fetcher.fetchCurrencies()) {
            is AppResult.Ok -> {
                val rows = result.value.map { (code, name) -> FxCurrencyEntity(code, name) }
                if (rows.isEmpty()) return FxCurrencyDefaults.fallback
                fxCurrencyDao.upsertAll(rows)
                rows.map { CurrencyInfo(it.code, it.name) }.sortedBy { it.code }
            }
            is AppResult.Err -> {
                Log.w("Currency list fetch failed; using the built-in fallback")
                FxCurrencyDefaults.fallback
            }
        }
    }

    private class Leg(val perUsd: Double, val date: String?)

    private suspend fun liveLeg(ccy: String, asOf: String): Leg? =
        if (ccy == USD) Leg(1.0, null)
        else fxRateDao.latestRateRowOnOrBefore(ccy, asOf)?.let { Leg(it.ratePerUsd, it.rateDate) }

    private suspend fun bakedLeg(ccy: String): Leg? =
        if (ccy == USD) Leg(1.0, null)
        else fxRateDao.bakedRateRow(ccy)?.let { Leg(it.ratePerUsd, it.snapshotDate) }

    /** Newest of two ISO dates (which sort lexicographically); null legs (USD) are ignored. */
    private fun newestDate(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        else -> if (a >= b) a else b
    }

    /** 03 §6.3: stale when `today - (date used) > 7 days`. A USD-only "date" (null) is never stale. */
    private fun isStale(usedDate: String?, today: String): Boolean {
        if (usedDate == null) return false
        return LocalDate.parse(usedDate).daysUntil(LocalDate.parse(today)) > STALE_DAYS
    }

    private companion object {
        const val USD = "USD"
        const val STALE_DAYS = 7
    }
}
