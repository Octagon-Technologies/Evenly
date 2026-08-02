package app.splitevenly.data.claim

import app.splitevenly.core.id.GroupId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The two pieces of "Is this you?" state that are deliberately **not** persisted.
 *
 * "Later" decides nothing, so it must not be stored: the whole point of offering it is that someone
 * who is unsure has an answer that isn't permanent. Keeping it in memory means it lasts the launch and
 * the question comes back, instead of quietly becoming the "dismiss forever" the two-button version
 * would have forced on them.
 *
 * [finished] is the same kind of thing from the other end: once the last name is answered the card is
 * replaced by a short confirmation for the rest of the session, then never renders again in that
 * group. That note is a courtesy for the tap that just happened, not a fact worth syncing.
 */
class IdentityPromptSnooze {

    private val _snoozed = MutableStateFlow(emptySet<String>())
    val snoozed: StateFlow<Set<String>> = _snoozed.asStateFlow()

    private val _finished = MutableStateFlow(emptySet<String>())
    val finished: StateFlow<Set<String>> = _finished.asStateFlow()

    /** "Later": hide the card for this launch. It returns on the next one, unanswered. */
    fun snooze(groupId: GroupId) {
        _snoozed.value = _snoozed.value + groupId.value
    }

    /** The user just answered the last open name here, so show the closing note instead of the card. */
    fun markFinished(groupId: GroupId) {
        _finished.value = _finished.value + groupId.value
    }
}
