package app.splitevenly.core.time

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * How far this device's wall clock is from the server's, learned from the `Date` header that rides on
 * every Supabase response (`ServerClockPlugin` installs the observer; this file stays free of Ktor so
 * `core/` keeps no transport dependency).
 *
 * **Why a money app cares about a wrong clock.** Sync outside `merge_expense` is client-side
 * last-write-wins on `updated_at`, and `updated_at` is whatever the writing device's clock said. A
 * phone set a year forward — manually, or after a battery-dead boot with a bad RTC — stamps a row in
 * 2027, pushes it, and from then on every honest edit by anybody is dropped by `keepNewer` on every
 * device that pulled that row. The field silently stops accepting edits, the group disagrees about it,
 * and nothing on screen says why. Only an edit stamped even further into the future can undo it, which
 * is to say: no honest device can.
 *
 * Two halves close it, and both live here:
 *
 * - **[clamp] caps what we stamp.** `Clock.nowEpochMillis()` — the one function every `*_at` write goes
 *   through — passes its reading through this, so a fast device writes `min(local, server + 60s)`
 *   instead of its own fiction. This mirrors the server's `_clamp_client_ts` exactly, including the 60s
 *   of slack: clamping to the server's `now()` on the nose would make a marginally-fast phone lose its
 *   own writes to its own last-write-wins guard.
 * - **[trustHorizonMillis] caps what we believe.** `SyncEngine.keepNewer` treats a *local* stamp past
 *   the horizon as no evidence of newness, so a row already poisoned by someone else's clock accepts
 *   the next honest edit instead of being pinned forever.
 *
 * **Known gap, stated rather than hidden:** the offset is learned from the network, so a device that
 * has never reached Supabase since launch clamps nothing. Until the first response lands, [clamp] is
 * the identity function — which is also exactly what makes this a no-op in tests and on a device whose
 * clock is fine.
 */
object ServerClock {
    /**
     * `server - device`, or null until a response has been seen. Negative means the device runs fast,
     * which is the direction that does the damage.
     */
    private val offsetMillis = MutableStateFlow<Long?>(null)

    /**
     * The slack allowed above the server's clock, matching `_clamp_client_ts`'s `+ 60000` in
     * `supabase/schema.sql`. The two must move together: the client's guards compare against values the
     * server has already clamped, so a tighter bound here would make our own writes lose.
     */
    const val SKEW_TOLERANCE_MS: Long = 60_000

    /** Fold in one observation. [deviceEpochMillis] must be this device's reading of the same moment. */
    fun observe(
        serverEpochMillis: Long,
        deviceEpochMillis: Long,
    ) {
        offsetMillis.value = serverEpochMillis - deviceEpochMillis
    }

    /** The learned offset, or null if no server response has been seen yet. Diagnostics and tests. */
    fun offsetOrNull(): Long? = offsetMillis.value

    /**
     * This device's reading of "now", capped at the server's clock plus [SKEW_TOLERANCE_MS].
     *
     * Deliberately relative — it caps [deviceEpochMillis] against *itself* plus the offset rather than
     * against a fresh `Clock.System` reading, so an injected test clock (or any other non-system
     * [kotlin.time.Clock]) is returned untouched whenever no offset has been learned.
     */
    fun clamp(deviceEpochMillis: Long): Long {
        val offset = offsetMillis.value ?: return deviceEpochMillis
        val headroom = offset + SKEW_TOLERANCE_MS
        return if (headroom >= 0) deviceEpochMillis else deviceEpochMillis + headroom
    }

    /**
     * The newest timestamp worth believing. A stored `updated_at` beyond this came from a wrong clock,
     * not from a genuinely later edit.
     */
    fun trustHorizonMillis(deviceEpochMillis: Long): Long = deviceEpochMillis + (offsetMillis.value ?: 0L) + SKEW_TOLERANCE_MS

    /** Forget the learned offset. Tests only — production learns it again on the next response. */
    internal fun resetForTest() {
        offsetMillis.value = null
    }
}
