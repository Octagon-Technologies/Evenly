package app.splitevenly.domain.repository

import app.splitevenly.domain.pro.ProStatus
import kotlinx.coroutines.flow.Flow

/**
 * A group's Evenly Pro state: whether a pass is live, and how much of the free allowance is spent
 * (`PRO_PASS_SPEC.md`).
 *
 * [freeUsed] is null until the count has been fetched at least once for this group. That is a real
 * state, not a loading placeholder to paper over: the meter stays hidden rather than showing a number
 * it would then have to correct.
 */
data class GroupProState(
    val status: ProStatus,
    val freeUsed: Int?,
    val freeLimit: Int,
    /**
     * This group has been Pro at some point through either route, live or not. Distinct from
     * `!status.isPro`, and the distinction matters on screen: only a group that once had Pro can be told
     * "Pro ended". Saying it to a group that never had it invents a loss that never happened.
     */
    val everHadPro: Boolean = false,
)

interface ProRepository {

    /** Live Pro state for a group. Reads only local rows, so it works offline and costs no round trip. */
    fun observe(groupId: String): Flow<GroupProState>

    /**
     * Re-read the group's free-scan count from the server and cache it.
     *
     * Best-effort by design: a failure leaves the previous cached count in place and is swallowed. This
     * number labels a meter, and there is no version of "we could not load your scan count" worth
     * putting in front of someone who is trying to split a dinner. Enforcement is server-side either
     * way, so a stale label can never let an extra scan through.
     */
    suspend fun refresh(groupId: String)
}
