package app.splitevenly.domain.pro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the purchase funnel's shape and the pass sheet's remote configuration.
 *
 * Both exist because the scan gate opens Evenly's own sheet rather than RevenueCat's paywall, which
 * takes that traffic out of RevenueCat's analytics and Experiments. These cases are what keep the
 * replacement honest: one property schema across both doors, and a config that degrades to today's
 * behaviour rather than to a broken sheet.
 */
class ProFunnelTest {
    private fun offer(
        pkg: String,
        product: String,
        micros: Long,
    ) = PassOffer(packageId = pkg, productId = product, title = pkg, price = "x", priceMicros = micros, currency = "USD")

    private val week1 = offer("pass_week_1", "app.splitevenly.pass.week1", 990_000)
    private val week2 = offer("pass_week_2", "app.splitevenly.pass.week2", 1_990_000)
    private val month1 = offer("pass_month_1", "app.splitevenly.pass.month1", 3_990_000)
    private val all = listOf(week1, week2, month1)

    @Test
    fun bothSurfacesShareOneSchema() {
        // The reason this file exists: a funnel whose two doors disagree about their property names is
        // not a funnel, and the pass-vs-subscription comparison is exactly what routing the scan gate
        // away from RevenueCat put at risk.
        val pass = ProFunnel.base(ProFunnel.Surface.PASS_SHEET, ProFunnel.Kind.PASS, "scan", "g1", "b")
        val paywall = ProFunnel.base(ProFunnel.Surface.RC_PAYWALL, ProFunnel.Kind.SUBSCRIPTION, "profile", null, null)
        assertTrue(pass.keys.containsAll(listOf("surface", "kind", "trigger")))
        assertTrue(paywall.keys.containsAll(listOf("surface", "kind", "trigger")))
        assertEquals("g1", pass["group_id"])
        // Absent, not null or empty: the subscription needs no group, and that difference is the product
        // showing up in the schema rather than missing data.
        assertTrue("group_id" !in paywall)
    }

    @Test
    fun moneyCarriesExactMicrosAndADecimal() {
        val m = ProFunnel.money(3_990_000, "KES")
        assertEquals(3_990_000L, m["price_micros"])
        assertEquals(3.99, m["price"])
        assertEquals("KES", m["currency"])
    }

    @Test
    fun bestValueIsCentsPerDayNotTheCheapest() {
        // $0.99/7d = 141k per day; $1.99/14d = 142k; $3.99/30d = 133k. The month wins on value while
        // being the priciest, which is the whole reason the flag is arithmetic and not a hardcoded tier.
        assertEquals(month1, all.bestValueOffer())
    }

    @Test
    fun bestValueIgnoresAnUnknownPackage() {
        val mystery = offer("pass_decade", "app.splitevenly.pass.decade", 1)
        // No known duration means no cents-per-day, so it cannot win by being absurdly cheap.
        assertEquals(month1, listOf(month1, mystery).bestValueOffer())
    }

    @Test
    fun defaultConfigIsTodaysBehaviour() {
        val c = PassSheetConfig.Default
        assertEquals(all, all.ordered(c))
        assertEquals(month1, all.preselected(c))
        assertTrue(c.showBestValueFlag)
        assertNull(c.variant)
    }

    @Test
    fun defaultOrderIsShortestToLongestEvenWhenTheStoreDisagrees() {
        // The dashboard's own package order is not guaranteed smallest-to-longest, and the sheet promises
        // it is. With no remote `order`, PassTier's declared order is the fallback, not just the input.
        val scrambled = listOf(month1, week1, week2)
        assertEquals(listOf(week1, week2, month1), scrambled.ordered(PassSheetConfig.Default))
    }

    @Test
    fun missingOrMalformedPayloadFallsBackRatherThanThrowing() {
        // Remote input into a screen that takes money. A dashboard typo must cost us an experiment, not
        // a rendered sheet.
        val c = PassSheetConfig.from(variant = "v1", payload = mapOf("order" to 42, "preselect" to ""))
        assertEquals(emptyList(), c.order)
        assertEquals(PassSheetConfig.PRESELECT_BEST_VALUE, c.preselect)
        assertTrue(c.showBestValueFlag)
        assertEquals("v1", c.variant)
    }

    @Test
    fun experimentCanReorderAndPreselect() {
        val c =
            PassSheetConfig.from(
                variant = "cheapest_first",
                payload = mapOf("preselect" to "cheapest", "order" to listOf("pass_month_1", "pass_week_1")),
            )
        assertEquals(listOf(month1, week1, week2), all.ordered(c))
        assertEquals(week1, all.preselected(c))
    }

    @Test
    fun aPartialOrderKeepsEveryTierBuyable() {
        // A stale `order` naming one package must not drop the other two off the sheet: the cost of a
        // half-updated dashboard is a worse ordering, never a tier nobody can buy.
        val c = PassSheetConfig.from(variant = null, payload = mapOf("order" to listOf("pass_month_1")))
        assertEquals(setOf(week1, week2, month1), all.ordered(c).toSet())
        assertEquals(month1, all.ordered(c).first())
    }

    @Test
    fun preselectAlwaysResolvesToSomethingBuyable() {
        // The button names the selected amount, so "nothing selected" would put a verb with no price on
        // the one control that takes money.
        val c = PassSheetConfig.from(variant = null, payload = mapOf("preselect" to "pass_does_not_exist"))
        assertEquals(month1, all.preselected(c))
        assertNull(emptyList<PassOffer>().preselected(c))
    }

    @Test
    fun bestValueFlagCanBeTurnedOff() {
        val c = PassSheetConfig.from(variant = "no_flag", payload = mapOf("best_value_flag" to false))
        assertEquals(false, c.showBestValueFlag)
    }
}
