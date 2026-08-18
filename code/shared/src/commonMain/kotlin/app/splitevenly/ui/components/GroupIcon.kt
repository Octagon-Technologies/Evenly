package app.splitevenly.ui.components

/**
 * What fills a group's icon tile. A group may deliberately have no emoji, and an empty tile reads as
 * an image that failed to load, so the name's first character stands in.
 *
 * Only for sites that render the icon in a tile of its own. Where the emoji sits *inline beside* the
 * name (a top bar, a settings row that already spells the name out) show nothing for a blank emoji
 * instead: a letter repeating the word next to it is noise, not a fallback.
 */
fun groupIconLabel(
    emoji: String,
    name: String,
): String = emoji.ifBlank { name.trim().firstOrNull()?.uppercase() ?: "" }
