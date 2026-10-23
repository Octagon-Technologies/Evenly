package app.splitevenly.data.claim

import app.splitevenly.core.id.GroupId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The closing-note state for "Is this you?", deliberately **not** persisted.
 *
 * Once the last name is answered the card is replaced by a short confirmation for the rest of the
 * session, then never renders again in that group. That note is a courtesy for the tap that just
 * happened, not a fact worth syncing.
 *
 * It used to also hold "Later", an in-memory snooze. That was removed (owner call, 2026-10-03): testers
 * read "Later" as "I don't have to do this", so the question is now a required step of joining and the
 * card has no way to be put off.
 */
class IdentityPromptSnooze {

    private val _finished = MutableStateFlow(emptySet<String>())
    val finished: StateFlow<Set<String>> = _finished.asStateFlow()

    /** The user just answered the last open name here, so show the closing note instead of the card. */
    fun markFinished(groupId: GroupId) {
        _finished.value = _finished.value + groupId.value
    }
}
