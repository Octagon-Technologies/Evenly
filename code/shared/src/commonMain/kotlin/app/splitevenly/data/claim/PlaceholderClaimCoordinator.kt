package app.splitevenly.data.claim

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.remote.supabase.PlaceholderClaimGateway
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.platform.AppForeground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Where a claim is in its life. The UI renders from this and nothing else. */
sealed interface ClaimStatus {
    data object Idle : ClaimStatus

    /**
     * Confirmed by the user, **not yet written**. Undo is offered for exactly this state and no other,
     * so the button can never be pressed against a merge that already happened.
     */
    data class Undoable(
        val groupId: GroupId,
        val placeholderUserId: UserId,
        val name: String,
    ) : ClaimStatus

    /** Flushed: asking the server who gets the name. Past the point of undo. */
    data class Confirming(
        val groupId: GroupId,
        val name: String,
    ) : ClaimStatus

    data class Claimed(
        val groupId: GroupId,
        val name: String,
    ) : ClaimStatus

    /** Somebody else got there first. [winnerName] is null when the server can't say who. */
    data class Lost(
        val groupId: GroupId,
        val name: String,
        val winnerName: String?,
    ) : ClaimStatus

    /** The server couldn't be reached, so nothing was written and the name is still up for grabs. */
    data class Failed(
        val groupId: GroupId,
        val name: String,
    ) : ClaimStatus
}

/**
 * Undo by DELAY, not by inverse.
 *
 * A claim rewrites other people's balances, so it gets a 5-second window to take back. The window is
 * implemented by **not writing anything yet**: the commit is scheduled and Undo cancels it. Writing
 * immediately and building an un-claim was the alternative, and it is materially harder and riskier —
 * once `shares.user_id` is rewritten nothing in the row records where it came from, and a sync landing
 * between the merge and the undo publishes the merged state to everyone. Deferring means there is no
 * partial state to repair. The cost is 5 seconds where the database still shows the old numbers, which
 * is fine because the user is looking at a toast that says what just happened.
 *
 * The commit is **flushed early** when the window elapses, when the user leaves the group screen
 * ([flush]), or when the app is backgrounded. If the process dies inside the window nothing was written
 * and the card returns on the next launch: losing an un-made merge is safe, a half-made one is not.
 *
 * **Past the flush there is a second window, and it needs a durable record.** The guard below is a
 * server-side write: it retires the placeholder and stamps it claimed, so the next pull drops it from
 * `observePlaceholdersInGroup` and it stops being pickable in either identity picker. Dying between that
 * and the local merge therefore stranded the name's whole history with no affordance left to retry from.
 * So the claim is parked in a [PendingClaimStore] *before* the guard is asked and cleared only once
 * [GroupRepository.reconcilePlaceholder] has returned, and [resumePending] finishes anything left over.
 * Same shape as `ProPurchaseCoordinator`'s parked purchase, and for the same reason: the process is not
 * a thing either half can assume.
 *
 * At flush the first-claim-wins guard runs **before** the merge, not after. The spec's rollback path
 * assumed a committed Room transaction could be reversed; it can't, and an un-claim API is explicitly
 * out of scope. Asking first gets the same guarantee with no reversal to write: a loser never merged
 * anything, and a device that cannot reach the server does not merge either — it says so and leaves the
 * card in place to retry. **"Couldn't ask" must never be read as "won"**, or both devices merge the
 * name and its history splits between them with nothing signalling it.
 *
 * Lives here rather than in a Composable: it outlives any one screen (backgrounding flushes it) and it
 * moves money.
 */
@OptIn(ExperimentalTime::class)
@Suppress("LongParameterList") // Four of the seven are test seams with production defaults.
class PlaceholderClaimCoordinator(
    private val groups: GroupRepository,
    // NOT an optional ctor dep. It is the only thing standing between "the process died mid-claim" and a
    // person's whole expense history stranded under a name no picker will ever offer again, so a
    // null-defaulted "legacy behaviour" here would be the bug (R2).
    private val store: PendingClaimStore,
    // Null when Supabase isn't configured (the fully-offline build): no server, so nothing to contend
    // with, and a claim simply applies.
    private val gateway: PlaceholderClaimGateway? = null,
    private val appForeground: AppForeground? = null,
    private val clock: Clock = Clock.System,
    private val undoWindowMillis: Long = UNDO_WINDOW_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class Pending(
        val claim: ClaimStatus.Undoable,
        val claimer: UserId,
        val seq: Long,
    )

    private val _status = MutableStateFlow<ClaimStatus>(ClaimStatus.Idle)
    val status: StateFlow<ClaimStatus> = _status.asStateFlow()

    /** Serializes confirm / undo / flush, so the timer and a manual flush can't commit the same claim twice. */
    private val lock = Mutex()
    private var pending: Pending? = null
    private var timer: Job? = null

    /**
     * Which claim the status flow currently belongs to. A commit runs asynchronously, so without this a
     * slow first claim could publish its result on top of a second claim's live undo toast — showing
     * "Chelimo is now you" while the user is waiting to undo Tyler.
     */
    private var latestSeq = 0L

    /**
     * Schedule a claim. Only one can be pending at a time: confirming a second **flushes the first**
     * rather than queueing or silently dropping it.
     */
    suspend fun confirm(
        groupId: GroupId,
        placeholderUserId: UserId,
        name: String,
        claimer: UserId,
    ) {
        flush()
        lock.withLock {
            val claim = ClaimStatus.Undoable(groupId, placeholderUserId, name)
            latestSeq += 1
            pending = Pending(claim, claimer, latestSeq)
            _status.value = claim
            timer =
                scope.launch {
                    // Whichever comes first: the window elapsing, or the app going away. `dropWhile` waits
                    // until we have actually seen the app visible, so a coordinator built before the first
                    // frame (AppForeground starts false) doesn't flush the instant a claim is scheduled.
                    val away =
                        launch {
                            val fg = appForeground ?: return@launch
                            fg.state.dropWhile { !it }.first { !it }
                            flush()
                        }
                    delay(undoWindowMillis)
                    away.cancel()
                    flush()
                }
        }
    }

    /** Take it back. Only possible while the commit is still pending, which is the whole design. */
    suspend fun undo() {
        val cancelledTimer =
            lock.withLock {
                if (pending == null) return
                pending = null
                timer.also { timer = null }
            }
        _status.value = ClaimStatus.Idle
        cancelledTimer?.cancel()
    }

    /** Commit now instead of waiting out the window (leaving the group screen, or backgrounding). */
    suspend fun flush() {
        val (p, cancelledTimer) =
            lock.withLock {
                val p = pending ?: return
                pending = null
                p to timer.also { timer = null }
            }
        // The commit runs as a SIBLING of the timer, not inside it: `flush()` is usually called *from*
        // the timer, and cancelling the timer from within itself would abort the commit half-done.
        scope.launch {
            publish(p.seq, ClaimStatus.Confirming(p.claim.groupId, p.claim.name))
            publish(p.seq, commit(p))
        }
        cancelledTimer?.cancel()
    }

    /**
     * [flush] from a place whose own scope is about to die — leaving the screen is the main one. It runs
     * on the coordinator's scope, because a flush launched into a scope that is being cancelled is a
     * claim the user confirmed and that then silently never happened.
     */
    fun flushDetached() {
        scope.launch { flush() }
    }

    /** Clear a finished notice once the user has seen it. */
    fun acknowledge() {
        val s = _status.value
        if (s is ClaimStatus.Claimed || s is ClaimStatus.Lost || s is ClaimStatus.Failed) {
            _status.value = ClaimStatus.Idle
        }
    }

    /**
     * Publish a claim's progress only while it is still the claim on screen. A second confirm supersedes
     * the first, and the first's outcome is then dropped rather than shown over the newer toast — the
     * merge itself still happened, and the list it came from reflects it.
     */
    private suspend fun publish(
        seq: Long,
        status: ClaimStatus,
    ) {
        lock.withLock { if (seq == latestSeq) _status.value = status }
    }

    /**
     * Finish any claim that was started and never landed (finding R2).
     *
     * Called when a group screen opens. Deliberately silent: the claim belongs to an earlier session, the
     * roster it changes already reflects it, and a toast about a name the user has stopped thinking about
     * is worse than none. Both halves are idempotent — the RPC returns `won` again to the same claimer,
     * and `mergePlaceholder` has nothing left to move once it has run — so a resume that was not needed
     * costs one round trip and changes nothing.
     */
    suspend fun resumePending() {
        for (claim in store.parked()) {
            finish(claim, name = null)
        }
    }

    /** [resumePending] from a caller whose scope may not outlive it (a screen opening). */
    fun resumeDetached() {
        scope.launch { resumePending() }
    }

    private suspend fun commit(p: Pending): ClaimStatus {
        val claim = p.claim
        val parked = ParkedClaim(claim.groupId, claim.placeholderUserId, p.claimer)
        // Parked BEFORE the guard is asked, because the window this class could not survive is the one
        // between a *won* guard and the merge: the RPC retires the placeholder server-side and stamps it
        // claimed, so the next pull drops it out of `observePlaceholdersInGroup` and it disappears from
        // both identity pickers. Dying there used to strand that person's whole history under a name
        // nobody could pick again, with nothing durable to resume from and no path back short of SQL.
        //
        // A throw parks it too, and must: "the RPC committed but the reply was lost" and "the RPC never
        // ran" are indistinguishable from here, and the first one strands the name identically.
        store.park(parked)
        return finish(parked, claim.name) ?: ClaimStatus.Failed(claim.groupId, claim.name)
    }

    /**
     * Ask the guard, then merge, clearing the park only once the merge has actually landed. Returns null
     * when there is no status worth publishing (the resume path, which nobody is watching).
     */
    @Suppress("ReturnCount") // One return per outcome the guard can have; a single exit hides which is which.
    private suspend fun finish(claim: ParkedClaim, name: String?): ClaimStatus? {
        val outcome =
            try {
                gateway?.claim(
                    claim.groupId.value,
                    claim.placeholderUserId.value,
                    claim.claimerUserId.value,
                    clock.nowEpochMillis(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Left parked on purpose: see [commit]. "Couldn't ask" is never read as "won", so nothing is
                // merged now, and the resume asks again.
                return name?.let { ClaimStatus.Failed(claim.groupId, it) }
            }
        if (outcome != null && !outcome.won) {
            // Somebody else owns the name. Clearing the park is what stops a later resume merging a name
            // this device lost.
            store.clear(claim.groupId, claim.placeholderUserId)
            return name?.let { ClaimStatus.Lost(claim.groupId, it, outcome.winnerName) }
        }
        // A throw here used to escape `scope.launch`, be swallowed by the SupervisorJob, and leave the
        // status stuck on `Confirming` with nothing said and nothing parked. The park survives either
        // failure, so the resume finishes what this attempt could not.
        val merged =
            runCatching {
                groups.reconcilePlaceholder(claim.groupId, claim.placeholderUserId, claim.claimerUserId)
            }.getOrElse { if (it is CancellationException) throw it else null }
        if (merged is AppResult.Ok) store.clear(claim.groupId, claim.placeholderUserId)
        return name?.let {
            if (merged is AppResult.Ok) {
                ClaimStatus.Claimed(claim.groupId, it)
            } else {
                ClaimStatus.Failed(claim.groupId, it)
            }
        }
    }

    companion object {
        /**
         * Five seconds, not the platform-standard three: the consequence is other people's balances, and
         * three seconds is not enough to read a toast, register that it was the wrong name, and reach for
         * the button. ONE constant shared by the toast and the deferred commit, so the two can never
         * disagree about how long Undo is offered.
         */
        const val UNDO_WINDOW_MILLIS: Long = 5_000L
    }
}
