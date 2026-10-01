package app.splitevenly.data.remote.supabase

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Whether the first server pull for a user has landed. In-memory only. */
sealed interface HydrationState {
    data object NotStarted : HydrationState

    data object Pulling : HydrationState

    data object Done : HydrationState

    data class Failed(
        val offline: Boolean,
    ) : HydrationState
}

/** What a list screen may claim when Room has nothing to show. */
sealed interface ListReadiness {
    /** A pull has landed: an empty list is genuinely empty. */
    data object Ready : ListReadiness

    /** Not known yet: render a blank page, never the empty state. */
    data object Waiting : ListReadiness

    data class Failed(
        val offline: Boolean,
    ) : ListReadiness
}

/**
 * Tracks first-pull completion per user so "Room is empty" is never mistaken for "the account is empty".
 *
 * Keyed by user id instead of reset on sign-out, so a different account never inherits `Done`. State is
 * deliberately not persisted: a persisted flag would let an empty cache after a wipe claim the empty
 * state before the new pull lands. [pendingGroups] covers a group joined by link, whose rows exist on
 * the server but not on this device until the next successful pull.
 */
class HydrationTracker(
    private val assumeHydrated: Boolean = false,
) {
    private val phases = MutableStateFlow<Map<String, HydrationState>>(emptyMap())
    private val pendingGroups = MutableStateFlow<Set<String>>(emptySet())

    // Groups pending when each user's in-flight pull began. Only those are cleared when it lands: a pull
    // that started before a join cannot carry the joined group's rows.
    private val pendingAtPullStart = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private var syncer: (suspend (String) -> AppResult<Unit>)? = null

    fun attachSyncer(syncNow: suspend (String) -> AppResult<Unit>) {
        syncer = syncNow
    }

    /** Re-run a full sync for [userId], e.g. from a "Try again" button. No-op without a sync engine. */
    suspend fun retry(userId: String) {
        syncer?.invoke(userId)
    }

    fun observe(userId: String): Flow<HydrationState> =
        if (assumeHydrated) {
            flowOf(HydrationState.Done)
        } else {
            phases.map { it[userId] ?: HydrationState.NotStarted }.distinctUntilChanged()
        }

    fun observeUserReadiness(userId: String): Flow<ListReadiness> = observe(userId).map { readiness(it, groupPending = false) }

    fun observeGroupReadiness(
        userId: String,
        groupId: String,
    ): Flow<ListReadiness> =
        combine(observe(userId), pendingGroups.map { groupId in it }.distinctUntilChanged()) { state, pending ->
            readiness(state, pending)
        }

    fun markGroupPending(groupId: String) = pendingGroups.update { it + groupId }

    fun pullStarted(userId: String) {
        pendingAtPullStart.update { it + (userId to pendingGroups.value) }
        setPhase(userId) { if (it == HydrationState.Done) it else HydrationState.Pulling }
    }

    /**
     * Drop everything known about [userId]. Called after sign-out wipes the cache: the same account
     * signing back in in this process must wait for a fresh pull rather than claim "empty" from a
     * `Done` that described rows which no longer exist.
     */
    fun forget(userId: String) {
        phases.update { it - userId }
        pendingAtPullStart.update { it - userId }
        pendingGroups.value = emptySet()
    }

    fun pullFinished(
        userId: String,
        result: AppResult<Unit>,
    ) {
        when (result) {
            is AppResult.Ok -> {
                setPhase(userId) { HydrationState.Done }
                val covered = pendingAtPullStart.value[userId].orEmpty()
                pendingAtPullStart.update { it - userId }
                pendingGroups.update { it - covered }
            }

            is AppResult.Err -> {
                val offline = result.error is AppError.Network
                // A hydrated session with nothing pending keeps its Done: a later blip must not blank a
                // list that was already known to be accurate.
                setPhase(userId) { if (it == HydrationState.Done && pendingGroups.value.isEmpty()) it else HydrationState.Failed(offline) }
            }
        }
    }

    private fun setPhase(
        userId: String,
        next: (HydrationState) -> HydrationState,
    ) = phases.update { it + (userId to next(it[userId] ?: HydrationState.NotStarted)) }

    companion object {
        fun readiness(
            state: HydrationState,
            groupPending: Boolean,
        ): ListReadiness =
            when {
                state is HydrationState.Failed -> ListReadiness.Failed(state.offline)
                state == HydrationState.Done && !groupPending -> ListReadiness.Ready
                else -> ListReadiness.Waiting
            }
    }
}
