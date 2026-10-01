package app.splitevenly.domain.receipt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReceiptTitleTest {
    @Test
    fun restaurantMealFollowsThePrintedHour() {
        assertEquals("Breakfast at Tavolo", suggestBillTitle("Tavolo", VenueKind.RESTAURANT, 8, fallbackHour = 20))
        assertEquals("Lunch at Tavolo", suggestBillTitle("Tavolo", VenueKind.RESTAURANT, 13, fallbackHour = 20))
        assertEquals("Dinner at Tavolo", suggestBillTitle("Tavolo", VenueKind.RESTAURANT, 20, fallbackHour = 8))
        assertEquals("Late dinner at Tavolo", suggestBillTitle("Tavolo", VenueKind.RESTAURANT, 23, fallbackHour = 8))
    }

    @Test
    fun noPrintedTimeFallsBackToThePhoneClock() {
        assertEquals("Dinner at Tavolo", suggestBillTitle("Tavolo", VenueKind.RESTAURANT, null, fallbackHour = 20))
    }

    @Test
    fun shoppingNamesThePartOfDay() {
        assertEquals("Evening shopping at Westgate", suggestBillTitle("Westgate", VenueKind.SHOP, 19, 0))
        assertEquals("Morning shopping at Carrefour", suggestBillTitle("Carrefour", VenueKind.GROCERY, 9, 0))
        assertEquals("Afternoon shopping at Carrefour", suggestBillTitle("Carrefour", VenueKind.GROCERY, 14, 0))
    }

    @Test
    fun cafeAndBar() {
        assertEquals("Breakfast at Java", suggestBillTitle("Java", VenueKind.CAFE, 7, 0))
        assertEquals("Coffee at Java", suggestBillTitle("Java", VenueKind.CAFE, 15, 0))
        assertEquals("Drinks at Alchemist", suggestBillTitle("Alchemist", VenueKind.BAR, 22, 0))
    }

    @Test
    fun partialInformation() {
        assertEquals("Dinner", suggestBillTitle(null, VenueKind.RESTAURANT, 20, 0))
        assertEquals("Shell", suggestBillTitle("Shell", VenueKind.OTHER, 20, 0))
        assertEquals("Shell", suggestBillTitle("  Shell ", null, null, 0))
        assertNull(suggestBillTitle(null, null, null, 12))
        assertNull(suggestBillTitle("  ", VenueKind.OTHER, 12, 12))
    }

    @Test
    fun wireParsing() {
        assertEquals(VenueKind.GROCERY, venueKindOf("grocery"))
        assertNull(venueKindOf("casino"))
        assertNull(venueKindOf(null))
        assertEquals(20, printedHourOf("20:05"))
        assertNull(printedHourOf("8pm"))
        assertNull(printedHourOf("25:00"))
    }
}
