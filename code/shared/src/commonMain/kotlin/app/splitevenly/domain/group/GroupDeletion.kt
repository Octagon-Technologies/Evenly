package app.splitevenly.domain.group

import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId

/**
 * The 30-day Recently deleted window.
 *
 * **This constant exists twice on purpose and the two must move together.** `purge_deleted_groups()`
 * in `supabase/schema.sql` carries the same 30 days, because only the server can act 30 days later and
 * only the client can act while offline. If they ever disagree, the shorter one wins in practice and
 * the longer one is a promise the app cannot keep: a group the UI still offers to restore is one the
 * server has already erased.
 */
object RecentlyDeleted {
    const val WINDOW_DAYS: Int = 30

    const val WINDOW_MS: Long = WINDOW_DAYS * 24L * 60 * 60 * 1000

    /** Rows deleted before this instant are past the window: purgeable, and never offered for restore. */
    fun cutoff(now: Long): Long = now - WINDOW_MS

    /**
     * Whole days a member has left to restore, floor-ed at 0 and rounded UP, so a group deleted 90
     * seconds ago reads "30 days left" rather than "29". Nobody counts a partial day in their favour
     * when the thing being counted is a deadline.
     */
    fun daysLeft(
        deletedAt: Long,
        now: Long,
    ): Int {
        val remaining = deletedAt + WINDOW_MS - now
        if (remaining <= 0) return 0
        val dayMs = 24L * 60 * 60 * 1000
        return ((remaining + dayMs - 1) / dayMs).toInt()
    }
}

/** One row of Recently deleted. */
data class DeletedGroup(
    val id: GroupId,
    val name: String,
    val emoji: String,
    val deletedAt: Long,
    val deletedBy: UserId?,
    /** Null when the deleter's `users` row has not synced to this device yet. */
    val deletedByName: String?,
    val memberCount: Int,
)

/**
 * What a delete is about to cost, counted from the real group so the confirm sheet states facts rather
 * than boilerplate.
 *
 * [proPassExpiresAt] is the one field that is not about data: an Evenly Pro pass is a purchase, it is
 * deliberately NOT purged with the group, and it is not refunded either, so the person about to delete
 * has to be told before rather than after.
 */
data class GroupDeleteImpact(
    val memberCount: Int,
    val expenseCount: Int,
    val receiptCount: Int,
    val unsettledCount: Int,
    val proPassExpiresAt: Long? = null,
)
