package da.chelimo.sharecost.data.db.dao

import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.entity.FxBakedEntity
import da.chelimo.sharecost.data.db.entity.FxRateEntity
import da.chelimo.sharecost.data.db.inMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Room tests for [FxRateDao] (02 §3.14/§7, 03 §6). Covers the composite-key upsert, the
 * "most-recent rate on or before a date" lookup, and the baked-snapshot fallback.
 */
class FxRateDaoTest {

    private lateinit var db: ShareCostDatabase
    private lateinit var dao: FxRateDao

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        dao = db.fxRateDao()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun rate(date: String, ccy: String, rate: Double) =
        FxRateEntity(rateDate = date, quoteCurrency = ccy, ratePerUsd = rate, fetchedAt = 1L)

    @Test
    fun compositeKey_upsert_replacesSameDayCurrency() = runTest {
        dao.upsertRates(listOf(rate("2026-06-01", "EUR", 0.90)))
        dao.upsertRates(listOf(rate("2026-06-01", "EUR", 0.92))) // same (date,ccy) ⇒ replace
        assertEquals(0.92, dao.ratePerUsdOnOrBefore("EUR", "2026-06-01"))
    }

    @Test
    fun ratePerUsdOnOrBefore_picksMostRecentNotAfterDate() = runTest {
        dao.upsertRates(
            listOf(
                rate("2026-06-01", "EUR", 0.90),
                rate("2026-06-03", "EUR", 0.93),
                rate("2026-06-10", "EUR", 0.99), // after the query date ⇒ ignored
            )
        )
        assertEquals(0.93, dao.ratePerUsdOnOrBefore("EUR", "2026-06-05"))
    }

    @Test
    fun ratePerUsdOnOrBefore_noRateBeforeDate_returnsNull() = runTest {
        dao.upsertRates(listOf(rate("2026-06-10", "EUR", 0.99)))
        assertNull(dao.ratePerUsdOnOrBefore("EUR", "2026-06-05"))
    }

    @Test
    fun bakedRate_servesAsFallback() = runTest {
        dao.upsertBaked(listOf(FxBakedEntity(quoteCurrency = "JPY", ratePerUsd = 150.0, snapshotDate = "2026-01-01")))
        assertEquals(150.0, dao.bakedRatePerUsd("JPY"))
        assertNull(dao.bakedRatePerUsd("GBP"))
    }
}
