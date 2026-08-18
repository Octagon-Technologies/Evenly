package app.splitevenly.data.remote.supabase

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult

/** The two directions of sync. They fail independently, so they are recorded independently. */
enum class SyncChannel { Push, Pull }

/**
 * What one direction of sync last did.
 *
 * [consecutiveFailures] is the signal worth surfacing: one failure is a blip, twenty in a row is a
 * defect. [lastError] says which kind, so "you are offline" is only ever shown for a genuine
 * [AppError.Network]. A success zeroes the counter but **keeps** [lastError] and [lastFailureAt] — a
 * diagnostic reader wants "the last thing that went wrong" to survive the next successful tick.
 */
data class ChannelHealth(
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastError: AppError? = null,
) {
    /** True only for genuine transport failure — the one case "you're offline" is honest copy. */
    val looksOffline: Boolean get() = consecutiveFailures > 0 && lastError is AppError.Network

    fun recordSuccess(now: Long) = copy(lastSuccessAt = now, consecutiveFailures = 0)

    fun recordFailure(
        error: AppError,
        now: Long,
    ) = copy(
        lastFailureAt = now,
        consecutiveFailures = consecutiveFailures + 1,
        lastError = error,
    )
}

/**
 * What the sync loop last did, so a wedged sync is observable instead of silent. `SyncManager`
 * swallows every push/pull result by design (a transient blip must not wedge the loop), which used to
 * mean an RLS denial and a dead radio looked identical — and both looked like nothing at all.
 *
 * **Push and pull are tracked separately, and that separation is the whole point.** They are driven by
 * two independent loops (`SyncManager.kt` — Room invalidation for push, the realtime doorbell for
 * pull) and they fail independently: a push wedged on a unique-index violation or a 403 keeps failing
 * while pulls keep succeeding, because pulls are unaffected by a wedged push. With one shared counter,
 * the next successful pull reset it, so `consecutiveFailures` never got past 1 and the health flow
 * reported a healthy sync while not one local write had reached the server. Push-fails/pull-succeeds
 * is the *normal* interleaving, not an exotic one, so the masking was permanent rather than rare.
 */
data class SyncHealth(
    val push: ChannelHealth = ChannelHealth(),
    val pull: ChannelHealth = ChannelHealth(),
) {
    /**
     * Only when **both** directions are failing on transport. A wedged push with pulls landing fine is
     * a defect on this device, not an offline phone, and telling the user they are offline would send
     * them to look at their wifi for a problem that is ours.
     */
    val looksOffline: Boolean get() = push.looksOffline && pull.looksOffline

    /** The worse of the two streaks — what a diagnostic surface should lead with. */
    val consecutiveFailures: Int get() = maxOf(push.consecutiveFailures, pull.consecutiveFailures)

    /** True while either direction has been failing long enough to be a defect rather than a blip. */
    fun isWedged(threshold: Int = WEDGED_AFTER): Boolean = consecutiveFailures >= threshold

    /** The error behind the worse streak, or null when neither direction is currently failing. */
    val activeError: AppError?
        get() =
            when {
                push.consecutiveFailures >= pull.consecutiveFailures && push.consecutiveFailures > 0 -> push.lastError
                pull.consecutiveFailures > 0 -> pull.lastError
                else -> null
            }

    /** Fold one round-trip's outcome into the channel it belongs to, leaving the other one alone. */
    fun record(
        channel: SyncChannel,
        result: AppResult<Unit>,
        now: Long,
    ): SyncHealth {
        val updated = { current: ChannelHealth ->
            when (result) {
                is AppResult.Ok -> current.recordSuccess(now)
                is AppResult.Err -> current.recordFailure(result.error, now)
            }
        }
        return when (channel) {
            SyncChannel.Push -> copy(push = updated(push))
            SyncChannel.Pull -> copy(pull = updated(pull))
        }
    }

    companion object {
        /** Long enough that a flaky lift or a backgrounding does not read as a defect. */
        const val WEDGED_AFTER: Int = 5
    }
}
