package app.splitevenly.domain.group

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The countdown a member reads on a group they can still get back. The rounding direction is the whole
 * point: this is a deadline, so a partial day counts in the reader's favour only when it is genuinely
 * still there.
 */
class RecentlyDeletedTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun justDeleted_readsFullWindow() {
        // Not 29. A group deleted 90 seconds ago saying "29 days left" is the bug this rounds away.
        assertEquals(30, RecentlyDeleted.daysLeft(deletedAt = 0, now = 90_000))
    }

    @Test
    fun partialDayRoundsUp() {
        // 2.5 days of real time left reads as 3, because 2 would be under-promising a deadline.
        assertEquals(3, RecentlyDeleted.daysLeft(deletedAt = 0, now = 30 * day - (2 * day + day / 2)))
    }

    @Test
    fun lastDayReadsAsOne() {
        assertEquals(1, RecentlyDeleted.daysLeft(deletedAt = 0, now = 30 * day - 1))
    }

    @Test
    fun exactlyAtTheDeadlineIsZero() {
        assertEquals(0, RecentlyDeleted.daysLeft(deletedAt = 0, now = 30 * day))
    }

    @Test
    fun pastTheDeadlineClampsToZeroRatherThanGoingNegative() {
        assertEquals(0, RecentlyDeleted.daysLeft(deletedAt = 0, now = 99 * day))
    }

    @Test
    fun cutoffIsThirtyDaysBack() {
        assertEquals(100 * day - 30 * day, RecentlyDeleted.cutoff(100 * day))
    }

    @Test
    fun windowMatchesTheServersGracePeriod() {
        // purge_deleted_groups() in supabase/schema.sql hardcodes the same 30 days. If this changes and
        // that does not, the app offers a restore for a group the server has already erased.
        assertEquals(30, RecentlyDeleted.WINDOW_DAYS)
        assertEquals(30 * day, RecentlyDeleted.WINDOW_MS)
    }
}
