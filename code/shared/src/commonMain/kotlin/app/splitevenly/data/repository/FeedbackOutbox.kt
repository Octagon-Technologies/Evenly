package app.splitevenly.data.repository

import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.db.dao.FeedbackOutboxDao
import app.splitevenly.data.db.entity.FeedbackOutboxEntity
import app.splitevenly.data.remote.supabase.FeedbackPostResult
import app.splitevenly.data.remote.supabase.FeedbackPoster
import app.splitevenly.domain.feedback.FeedbackDraft
import app.splitevenly.domain.feedback.FeedbackOutcome
import app.splitevenly.domain.feedback.FeedbackSubmitter
import app.splitevenly.newId
import app.splitevenly.platform.NetworkStatus
import app.splitevenly.platform.appVersionLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Durable submission for the in-app feedback form (ADMIN_FEEDBACK_SPEC.md §4.4).
 *
 * **The order is the design: write to Room, then try the network.** Every submission is persisted
 * before a single byte goes out, so the answer to "did my words survive?" is yes in every case,
 * including the process being killed mid-POST. That is what lets the screen show one confirmation
 * whether the ticket went out now or goes out tomorrow, which is the honest thing to show: from the
 * writer's side, nothing different happened.
 *
 * A drain runs on construction (for a backlog left by a previous session) and on every transition to
 * [NetworkStatus.Online]. Mirrors [app.splitevenly.data.upload.ReceiptUploadManager], including the
 * app-lifetime scope and the self-bind in `init`.
 */
@OptIn(ExperimentalTime::class)
class FeedbackOutbox(
    private val dao: FeedbackOutboxDao,
    private val http: FeedbackPoster,
    // The status flow rather than the observer: `ConnectivityObserver` is an `expect class`, so a test
    // cannot construct one, and this collaborator only ever reads `.status` anyway.
    connectivity: Flow<NetworkStatus>,
    /**
     * Signed-in state. A second drain trigger beside connectivity, and not redundant with it: a ticket
     * written on an expired session is held back with the device fully online, so no reconnect is ever
     * coming to flush it. Without this the screen's "sign in again and this sends automatically" would
     * be false.
     */
    signedIn: Flow<Boolean>,
    private val clock: Clock = Clock.System,
    private val versionLabel: () -> String = ::appVersionLabel,
    // App-lifetime by default, exactly like ReceiptUploadManager's. Injectable only so tests can put the
    // background drains on a dispatcher they control instead of racing them.
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : FeedbackSubmitter {
    /** One drain at a time. Reconnect and app-launch can otherwise fire together and double-post a row. */
    private val draining = Mutex()

    init {
        scope.launch { drain() }
        scope.launch {
            connectivity.distinctUntilChanged().collect { status ->
                if (status == NetworkStatus.Online) drain()
            }
        }
        scope.launch {
            signedIn.distinctUntilChanged().collect { yes -> if (yes) drain() }
        }
    }

    /**
     * Persist [draft], then attempt it once immediately.
     *
     * Returns [FeedbackOutcome.Queued] rather than an error for anything retryable, because the ticket
     * genuinely is safe. The two outcomes that are *not* reported as success are the two where the person
     * would otherwise be misled: an expired session (their next sign-in flushes it, but the screen should
     * say so) and the rate limit (thirty tickets an hour is a person hitting Send repeatedly, and telling
     * them it worked invites a thirty-first).
     */
    override suspend fun submit(draft: FeedbackDraft): FeedbackOutcome {
        val type = draft.type ?: return FeedbackOutcome.Queued
        val category = draft.category ?: return FeedbackOutcome.Queued
        val row =
            FeedbackOutboxEntity(
                id = newId(),
                type = type.wire,
                category = category.wire,
                message = draft.message.trim(),
                appVersion = versionLabel().take(APP_VERSION_CAP).takeIf { it.isNotBlank() },
                createdAt = clock.nowEpochMillis(),
            )
        // The same lock `drain` takes, held across the insert and the send. Without it a drain fired by a
        // reconnect can pick this row up while its first POST is still in flight and file the ticket
        // twice. Found by the JVM tests: on Native the background drains happened not to interleave.
        return draining.withLock { insertAndSend(row) }
    }

    private suspend fun insertAndSend(row: FeedbackOutboxEntity): FeedbackOutcome {
        dao.insert(row)
        return when (send(row)) {
            FeedbackPostResult.Accepted -> {
                dao.delete(row.id)
                FeedbackOutcome.Sent
            }

            // These two keep the row. It is not lost, it is just not gone yet: the next drain, after a
            // reconnect or the next sign-in, picks it up.
            FeedbackPostResult.Unauthenticated -> {
                FeedbackOutcome.NeedsSignIn
            }

            FeedbackPostResult.RateLimited -> {
                FeedbackOutcome.RateLimited
            }

            FeedbackPostResult.Unreachable -> {
                dao.recordAttempt(row.id)
                FeedbackOutcome.Queued
            }

            // Validation the server will fail identically forever. Dropping it beats a row that retries
            // on every reconnect for the life of the install and never succeeds.
            is FeedbackPostResult.Rejected -> {
                dao.delete(row.id)
                FeedbackOutcome.Failed
            }
        }
    }

    /** Send everything pending. Safe to call at any time; a no-op when the queue is empty. */
    suspend fun drain() {
        draining.withLock { drainLocked() }
    }

    private suspend fun drainLocked() {
        for (row in dao.pending()) {
            when (send(row)) {
                is FeedbackPostResult.Accepted -> {
                    dao.delete(row.id)
                }

                is FeedbackPostResult.Rejected -> {
                    dao.delete(row.id)
                }

                // Stop the whole drain rather than marching through the queue: if the network is down or
                // the session is stale, every remaining row fails the same way, and burning each row's
                // attempt budget on one outage is how a queue silently empties itself.
                else -> {
                    dao.recordAttempt(row.id)
                    if (row.attempts + 1 >= MAX_ATTEMPTS) dao.delete(row.id)
                    return
                }
            }
        }
    }

    private suspend fun send(row: FeedbackOutboxEntity): FeedbackPostResult =
        http.post(
            type = row.type,
            category = row.category,
            message = row.message,
            appVersion = row.appVersion,
        )

    private companion object {
        /** The server's own `app_version` column cap. */
        const val APP_VERSION_CAP = 40

        /**
         * A row that has failed this many times is not coming back, and an immortal row means every
         * reconnect for the life of the install pays for it. Twenty-five reconnects is days of ordinary
         * use, so this only fires on something genuinely broken.
         */
        const val MAX_ATTEMPTS = 25
    }
}
