package app.splitevenly.domain.expense

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CategoryGuessTest {
    private val defaults = CategoryDefaults.all

    @Test
    fun keywordsPickTheObviousCategory() {
        assertEquals("food", guessCategory("Dinner at Nando's", emptyList(), null, defaults))
        assertEquals("transport", guessCategory("Uber to the airport", emptyList(), null, defaults))
        assertEquals("utilities", guessCategory("Rent for March", emptyList(), null, defaults))
        assertNull(guessCategory("Misc", emptyList(), null, defaults))
        assertNull(guessCategory("", emptyList(), null, defaults))
    }

    @Test
    fun groupHistoryBeatsTheKeywordList() {
        val rent = GroupCategory("c-rent", "Rent", "home", 0xFF000000, isDefault = false, sortOrder = 9)
        val history = listOf(CategoryHistoryEntry("Rent March", "c-rent"))
        assertEquals("c-rent", guessCategory("Rent April", history, null, defaults + rent))
    }

    @Test
    fun historyMatchesOnTheLeadingWordOnly() {
        val history = listOf(CategoryHistoryEntry("Airbnb Austin", "lodging"), CategoryHistoryEntry("Dinner at Tavolo", "food"))
        assertEquals("lodging", guessCategory("Airbnb deposit", history, null, defaults))
        // "Uber to dinner" shares a word with a food expense but leads with "uber", so the keyword list decides.
        assertEquals("transport", guessCategory("Uber to dinner", history, null, defaults))
    }

    @Test
    fun receiptCategorySitsBetweenHistoryAndKeywords() {
        assertEquals("entertainment", guessCategory("Dinner and a show", emptyList(), "entertainment", defaults))
        assertEquals("food", guessCategory("", emptyList(), "food", defaults))
    }

    @Test
    fun aCategoryTheGroupNoLongerHasIsNeverReturned() {
        val noTransport = defaults.filterNot { it.key == "transport" }
        assertNull(guessCategory("Uber home", emptyList(), "transport", noTransport))
        // A deleted history category is skipped too.
        assertNull(guessCategory("Uber home", listOf(CategoryHistoryEntry("Uber home", "gone")), null, noTransport))
    }

    @Test
    fun aBuiltInSurvivesUnderItsLabelAfterCustomizing() {
        val materialized = listOf(GroupCategory("uuid-1", "Transport", "transport", 0xFF818CF8, isDefault = false, sortOrder = 0))
        assertEquals("uuid-1", guessCategory("Lyft", emptyList(), null, materialized))
    }
}
