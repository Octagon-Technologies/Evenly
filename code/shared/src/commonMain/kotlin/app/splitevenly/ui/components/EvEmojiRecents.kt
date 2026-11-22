package app.splitevenly.ui.components

/**
 * The emojis a brand-new install shows in the picker row, oldest first.
 *
 * The row is None + these + the catalog tile, and must stay one line on a 360dp phone inside a 20dp-padded
 * sheet (320dp). Four keeps it at six 40dp tiles; the owner chose which presets to cut, so change the count
 * only with them.
 */
val EvEmojiDefaults = listOf("🏝️", "🏠", "✈️", "🎉")

/**
 * Returns [deck] after [emoji] is used: it goes to the end and, if it was new, the oldest (first) drops off.
 *
 * An emoji already in the deck only moves to the end, so the deck never holds duplicates and nothing is
 * evicted. A blank [emoji] ("None") is not an emoji and leaves the deck as it was.
 */
fun pushRecentEmoji(
    deck: List<String>,
    emoji: String,
    size: Int = EvEmojiDefaults.size,
): List<String> {
    if (emoji.isBlank()) return deck
    return (deck.filterNot { it == emoji } + emoji).takeLast(size)
}

/** Serialized form for the device-local store. Newline never appears inside an emoji. */
fun encodeRecentEmojis(deck: List<String>): String = deck.joinToString("\n")

/** Inverse of [encodeRecentEmojis]; a missing or malformed value falls back to [EvEmojiDefaults]. */
fun decodeRecentEmojis(raw: String?): List<String> {
    val deck =
        raw
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
    return if (deck.size == EvEmojiDefaults.size) deck else EvEmojiDefaults
}
