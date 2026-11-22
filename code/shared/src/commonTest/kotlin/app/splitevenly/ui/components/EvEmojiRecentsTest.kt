package app.splitevenly.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class EvEmojiRecentsTest {
    private val deck = listOf("🏝️", "🏠", "✈️", "🎉")

    @Test
    fun newEmojiGoesToTheEndAndTheOldestDrops() {
        assertEquals(listOf("🏠", "✈️", "🎉", "🍕"), pushRecentEmoji(deck, "🍕"))
    }

    @Test
    fun emojiAlreadyInTheDeckOnlyMovesToTheEnd() {
        assertEquals(listOf("🏝️", "✈️", "🎉", "🏠"), pushRecentEmoji(deck, "🏠"))
    }

    @Test
    fun noneLeavesTheDeckAlone() {
        assertEquals(deck, pushRecentEmoji(deck, ""))
    }

    @Test
    fun encodingRoundTrips() {
        val next = pushRecentEmoji(deck, "🍕")
        assertEquals(next, decodeRecentEmojis(encodeRecentEmojis(next)))
    }

    @Test
    fun missingOrMalformedStoreFallsBackToDefaults() {
        assertEquals(EvEmojiDefaults, decodeRecentEmojis(null))
        assertEquals(EvEmojiDefaults, decodeRecentEmojis("🍕\n🍕"))
    }
}
