package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import app.splitevenly.platform.SecureStorage
import app.splitevenly.ui.components.EvEmojiDefaults
import app.splitevenly.ui.components.decodeRecentEmojis
import app.splitevenly.ui.components.encodeRecentEmojis
import app.splitevenly.ui.components.pushRecentEmoji

/**
 * Device-local deck behind the group emoji picker row. Not synced: it is this phone's habit, not group data,
 * and a sign-out (which clears [SecureStorage]) resetting it to the defaults is acceptable.
 */
const val RECENT_EMOJIS_KEY = "recent_group_emojis"

/** The saved deck, starting from the defaults until the store answers. */
@Composable
fun rememberRecentEmojis(storage: SecureStorage): State<List<String>> =
    produceState(EvEmojiDefaults, storage) { value = decodeRecentEmojis(storage.getString(RECENT_EMOJIS_KEY)) }

/** Records [emoji] as just used; called on save, never on a mere tap in the picker. */
suspend fun SecureStorage.recordRecentEmoji(emoji: String) {
    val deck = decodeRecentEmojis(getString(RECENT_EMOJIS_KEY))
    val next = pushRecentEmoji(deck, emoji)
    if (next != deck) putString(RECENT_EMOJIS_KEY, encodeRecentEmojis(next))
}
