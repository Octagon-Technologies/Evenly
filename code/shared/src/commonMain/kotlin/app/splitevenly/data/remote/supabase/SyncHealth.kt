package app.splitevenly.data.remote.supabase

import app.splitevenly.core.error.AppError

/**
 * What the sync loop last did, so a wedged sync is observable instead of silent. `SyncManager`
 * swallows every push/pull result by design (a transient blip must not wedge the loop), which used to
 * mean an RLS denial and a dead radio looked identical — and both looked like nothing at all.
 *
 * [consecutiveFailures] is the signal worth surfacing: one failure is a blip, twenty in a row is a
 * defect. [lastError] says which kind, so "you are offline" is only ever shown for a genuine
 * [AppError.Network].
 */
data class SyncHealth(
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastError: AppError? = null,
) {
    /** True only for genuine transport failure — the one case "you're offline" is honest copy. */
    val looksOffline: Boolean get() = consecutiveFailures > 0 && lastError is AppError.Network

    fun recordSuccess(now: Long) = SyncHealth(lastSuccessAt = now, lastFailureAt = lastFailureAt)

    fun recordFailure(error: AppError, now: Long) = copy(
        lastFailureAt = now,
        consecutiveFailures = consecutiveFailures + 1,
        lastError = error,
    )
}
