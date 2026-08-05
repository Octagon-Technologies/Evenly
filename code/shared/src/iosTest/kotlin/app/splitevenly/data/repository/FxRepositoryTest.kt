package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.dao.FxCurrencyDao
import app.splitevenly.data.db.dao.FxRateDao
import app.splitevenly.data.db.entity.FxBakedEntity
import app.splitevenly.data.db.entity.FxRateEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.data.remote.fx.FxSnapshot
import app.splitevenly.domain.fx.FxResult
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [FxRepositoryImpl] — the lookup chain (03 §6.2), the 7-day staleness signal (§6.3), the
 * baked fallback (§6.4), and the once-per-day best-effort refresh (§6.1). Network is faked, so these
 * never touch the wire.
 */
class FxRepositoryTest {

    private lateinit var db: EvenlyDatabase
    private lateinit var dao: FxRateDao
    private lateinit var currencyDao: FxCurrencyDao

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        dao = db.fxRateDao()
        currencyDao = db.fxCurrencyDao()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun repo(today: String, fetch: AppResult<FxSnapshot> = AppResult.Ok(FxSnapshot(today, emptyMap()))) =
        FxRepositoryImpl(dao, currencyDao, FakeFxFetcher(fetch), clockAt(today))

    private fun rate(date: String, ccy: String, perUsd: Double) =
        FxRateEntity(rateDate = date, quoteCurrency = ccy, ratePerUsd = perUsd, fetchedAt = 1L)

    @Test
    fun sameCurrency_isIdentity() = runTest {
        assertEquals(FxResult.Same, repo("2026-06-12").rate("EUR", "EUR", "2026-06-12"))
    }

    @Test
    fun liveCross_routesViaUsd_freshWithinSevenDays() = runTest {
        dao.upsertRates(listOf(rate("2026-06-10", "EUR", 0.90), rate("2026-06-10", "GBP", 0.80)))
        val r = repo("2026-06-12").rate("EUR", "GBP", "2026-06-12")
        assertTrue(r is FxResult.Live)
        assertEquals(0.80 / 0.90, r.rate, 1e-12)
        assertEquals("2026-06-10", r.asOfDate)
        assertTrue(!r.stale) // 2 days old
    }

    @Test
    fun usdLeg_usesIdentity() = runTest {
        dao.upsertRates(listOf(rate("2026-06-12", "EUR", 0.90)))
        val r = repo("2026-06-12").rate("USD", "EUR", "2026-06-12")
        assertTrue(r is FxResult.Live)
        assertEquals(0.90, r.rate, 1e-12) // (USD->EUR) / (USD->USD=1.0)
    }

    @Test
    fun live_isStale_whenOlderThanSevenDays() = runTest {
        dao.upsertRates(listOf(rate("2026-06-01", "EUR", 0.90), rate("2026-06-01", "GBP", 0.80)))
        val r = repo("2026-06-12").rate("EUR", "GBP", "2026-06-12")
        assertTrue(r is FxResult.Live)
        assertTrue(r.stale) // 11 days old > 7
    }

    @Test
    fun fallsBackToBaked_whenNoLiveRate() = runTest {
        dao.upsertBaked(
            listOf(
                FxBakedEntity(quoteCurrency = "EUR", ratePerUsd = 0.92, snapshotDate = "2026-01-01"),
                FxBakedEntity(quoteCurrency = "GBP", ratePerUsd = 0.80, snapshotDate = "2026-01-01"),
            )
        )
        val r = repo("2026-06-12").rate("EUR", "GBP", "2026-06-12")
        assertTrue(r is FxResult.Baked)
        assertEquals(0.80 / 0.92, r.rate, 1e-12)
        assertTrue(r.stale) // January snapshot in June
    }

    @Test
    fun unavailable_whenNeitherLiveNorBaked() = runTest {
        assertEquals(FxResult.Unavailable, repo("2026-06-12").rate("EUR", "JPY", "2026-06-12"))
    }

    @Test
    fun refresh_fetchesAndUpserts_whenStale_includingUsdAnchor() = runTest {
        val fetcher = FakeFxFetcher(AppResult.Ok(FxSnapshot("2026-06-12", mapOf("EUR" to 0.90, "GBP" to 0.80))))
        val repo = FxRepositoryImpl(dao, currencyDao, fetcher, clockAt("2026-06-12"))

        assertTrue(repo.refreshIfStale() is AppResult.Ok)
        assertEquals(1, fetcher.calls)
        assertEquals("2026-06-12", dao.maxRateDate())
        assertEquals(0.90, dao.ratePerUsdOnOrBefore("EUR", "2026-06-12"))
        assertEquals(1.0, dao.ratePerUsdOnOrBefore("USD", "2026-06-12")) // USD anchor stored
    }

    @Test
    fun refresh_noOp_whenAlreadyHaveTodaysRates() = runTest {
        dao.upsertRates(listOf(rate("2026-06-12", "EUR", 0.90)))
        val fetcher = FakeFxFetcher(AppResult.Ok(FxSnapshot("2026-06-12", mapOf("EUR" to 0.99))))
        val repo = FxRepositoryImpl(dao, currencyDao, fetcher, clockAt("2026-06-12"))

        assertTrue(repo.refreshIfStale() is AppResult.Ok)
        assertEquals(0, fetcher.calls)
        assertEquals(0.90, dao.ratePerUsdOnOrBefore("EUR", "2026-06-12")) // untouched
    }

    @Test
    fun refresh_swallowsFetchError() = runTest {
        val fetcher = FakeFxFetcher(AppResult.Err(AppError.Network(AppError.Network.Kind.Timeout)))
        val repo = FxRepositoryImpl(dao, currencyDao, fetcher, clockAt("2026-06-12"))

        assertTrue(repo.refreshIfStale() is AppResult.Ok) // failure not surfaced (03 §6.4)
        assertEquals(1, fetcher.calls)
        assertEquals(null, dao.maxRateDate()) // nothing written
    }
}
