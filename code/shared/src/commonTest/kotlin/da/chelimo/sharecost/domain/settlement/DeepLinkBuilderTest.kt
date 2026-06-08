package da.chelimo.sharecost.domain.settlement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [buildDeepLink] (spec 03-business-rules.md §5.1, §5.2; D-05, D-13).
 *
 * Payment amounts in deep links are always USD with two decimals. Only Zelle has no
 * deep link (clipboard-only). The clipboard fallback is always populated as
 * "<handle>  <dollars> USD" (literal two-space separator, §5.2).
 */
class DeepLinkBuilderTest {

    // AC-M3-030: Venmo URL constructed correctly. Only the note is URL-encoded; the
    // middle dot '·' (U+00B7) becomes its UTF-8 percent-encoding %C2%B7.
    @Test
    fun acM3030_venmoUrlConstructedCorrectly() {
        val result = buildDeepLink(
            app = PaymentApp.VENMO,
            handle = "andrew-c",
            amountUsdSubunits = 1234L,
            groupName = "Trip",
            expenseTitle = "Dinner",
        )

        assertEquals(
            "venmo://paycharge?txn=pay&recipients=andrew-c&amount=12.34" +
                "&note=ShareCost%3A%20Trip%20%C2%B7%20Dinner",
            result.url,
        )
    }

    // AC-M3-031: clipboard fallback is always populated, with the literal two-space separator.
    @Test
    fun acM3031_clipboardFallbackAlwaysPopulated() {
        val result = buildDeepLink(
            app = PaymentApp.VENMO,
            handle = "andrew-c",
            amountUsdSubunits = 1234L,
            groupName = "Trip",
            expenseTitle = "Dinner",
        )

        assertEquals("andrew-c  12.34 USD", result.clipboardFallback)
    }

    // AC-M3-031 (extended): every app — even Zelle — carries a clipboard fallback.
    @Test
    fun clipboardFallbackPopulatedForEveryApp() {
        for (app in PaymentApp.entries) {
            val result = buildDeepLink(
                app = app,
                handle = "andrew-c",
                amountUsdSubunits = 500L,
                groupName = "Trip",
                expenseTitle = "Dinner",
            )
            assertEquals("andrew-c  5.00 USD", result.clipboardFallback)
        }
    }

    // AC-M3-034: Zelle → url is null (clipboard only); fallback still populated.
    @Test
    fun acM3034_zelleHasNoUrl() {
        val result = buildDeepLink(
            app = PaymentApp.ZELLE,
            handle = "andrew@bank.com",
            amountUsdSubunits = 1234L,
            groupName = "Trip",
            expenseTitle = "Dinner",
        )

        assertNull(result.url)
        assertEquals("andrew@bank.com  12.34 USD", result.clipboardFallback)
    }

    // Cash App template: handle already includes the leading '$'; path is literal.
    @Test
    fun cashAppUrlFollowsTemplate() {
        val result = buildDeepLink(
            app = PaymentApp.CASH_APP,
            handle = "\$andrewc",
            amountUsdSubunits = 1234L,
            groupName = "Trip",
            expenseTitle = "Dinner",
        )

        assertEquals("https://cash.app/\$andrewc/12.34", result.url)
    }

    // PayPal template: paypal.me/<handle>/<dollars>USD.
    @Test
    fun payPalUrlFollowsTemplate() {
        val result = buildDeepLink(
            app = PaymentApp.PAYPAL,
            handle = "andrew-c",
            amountUsdSubunits = 1234L,
            groupName = "Trip",
            expenseTitle = "Dinner",
        )

        assertEquals("https://paypal.me/andrew-c/12.34USD", result.url)
    }

    // Amount formatting: 100 subunits → "1.00".
    @Test
    fun amountFormatting_wholeDollar() {
        val result = buildDeepLink(
            app = PaymentApp.PAYPAL,
            handle = "h",
            amountUsdSubunits = 100L,
            groupName = "G",
            expenseTitle = "E",
        )

        assertEquals("https://paypal.me/h/1.00USD", result.url)
        assertEquals("h  1.00 USD", result.clipboardFallback)
    }

    // Amount formatting: 1 subunit → "0.01" (sub-dollar, leading zero).
    @Test
    fun amountFormatting_oneCent() {
        val result = buildDeepLink(
            app = PaymentApp.PAYPAL,
            handle = "h",
            amountUsdSubunits = 1L,
            groupName = "G",
            expenseTitle = "E",
        )

        assertEquals("https://paypal.me/h/0.01USD", result.url)
        assertEquals("h  0.01 USD", result.clipboardFallback)
    }
}
