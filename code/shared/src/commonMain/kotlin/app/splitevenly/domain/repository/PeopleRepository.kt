package app.splitevenly.domain.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.people.PeopleSummary
import app.splitevenly.domain.people.Person
import kotlinx.coroutines.flow.Flow

/** The People tab: who owes the viewer across every group, and the viewer's private name links. */
interface PeopleRepository {
    fun observePeople(me: UserId): Flow<PeopleSummary>

    /** Attach each of [userIds] (names typed by hand) to the person whose key is [personKey]. */
    suspend fun link(me: UserId, userIds: List<String>, personKey: String)

    /** "No": [userIds] are not the person [personKey]; the same-name suggestion stops appearing. */
    suspend fun ruleOut(me: UserId, userIds: List<String>, personKey: String)

    /** Detach [userId] from whoever it was linked to. Soft-deletes its links. */
    suspend fun unlink(me: UserId, userId: String)

    /**
     * Records that [person] paid the viewer, as one ordinary payment per group in [split] (group id to
     * amount, in [currency]). Each allocates oldest-first inside its group. Stops at the first failure and
     * returns it; payments already written stay, exactly as if recorded one group at a time.
     */
    suspend fun recordPayment(
        me: UserId,
        person: Person,
        currency: String,
        split: Map<String, Long>,
    ): AppResult<Unit>
}
