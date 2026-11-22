package app.splitevenly.ui.components

/** One tab of the group-emoji catalog. */
data class EvEmojiCategory(
    val label: String,
    val emojis: List<String>,
)

/**
 * A curated set of group-shaped emojis rather than the full Unicode list: the picker is for naming a trip,
 * a flat, or a team, and the "Type any emoji" escape hatch covers everything else. Each category is two
 * rows of seven so switching tabs never changes the panel height.
 */
val EvEmojiCatalog: List<EvEmojiCategory> =
    listOf(
        EvEmojiCategory("Travel", "✈️ 🏝️ 🏖️ 🏔️ 🏕️ 🚗 🚆 🛳️ 🗺️ 🧳 🏨 🗽 🌍 ⛺".split(" ")),
        EvEmojiCategory("Food", "🍝 🍕 🍔 🌮 🍣 🍜 🥗 🍰 ☕ 🍺 🍷 🍸 🛒 🥘".split(" ")),
        EvEmojiCategory("Home", "🏠 🏡 🛋️ 🔑 💡 🧺 🧹 🛏️ 📦 🔧 🌱 🐶 🐱 📺".split(" ")),
        EvEmojiCategory("Fun", "🎉 🎂 🎁 🎄 🎃 💍 🎶 🎮 🎬 🎟️ 🎤 🎨 🎲 🎳".split(" ")),
        EvEmojiCategory("Sport", "⚽ 🏀 🎾 ⛳ 🏋️ 🚴 🏄 ⛷️ 🧗 🏃 🏊 🥾 🏆 🎿".split(" ")),
        EvEmojiCategory("Other", "💼 🎓 👶 💊 🚑 📚 💻 📱 💸 ❤️ ⭐ 🔥 🌈 ✨".split(" ")),
    )
