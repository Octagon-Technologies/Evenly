package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.data.remote.fx.FxRateFetcher
import da.chelimo.sharecost.data.remote.fx.FxSnapshot
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A fixed [Clock] pinned to midnight UTC of [date] (ISO "YYYY-MM-DD") — deterministic timestamps. */
@OptIn(ExperimentalTime::class)
fun clockAt(date: String): Clock = object : Clock {
    private val fixed: Instant = LocalDate.parse(date).atStartOfDayIn(TimeZone.UTC)
    override fun now(): Instant = fixed
}

/** A canned [FxRateFetcher] for [da.chelimo.sharecost.data.repository.FxRepositoryImpl] tests — no real network. */
class FakeFxFetcher(var result: AppResult<FxSnapshot>) : FxRateFetcher {
    var calls = 0
        private set

    override suspend fun fetchLatest(base: String): AppResult<FxSnapshot> {
        calls++
        return result
    }

    override suspend fun fetchCurrencies(): AppResult<Map<String, String>> = AppResult.Ok(emptyMap())
}
