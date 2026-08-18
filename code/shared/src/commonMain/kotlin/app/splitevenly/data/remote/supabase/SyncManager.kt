package app.splitevenly.data.remote.supabase

import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Live sync driver (F7) — supersedes the old fixed 15s heartbeat. While the app is **visible and
 * signed in** it:
 *  - **pushes promptly** whenever local tables change (Room invalidation → debounced [SyncEngine.push]),
 *  - **pulls in near-real-time** off the Realtime *doorbell* (see below),
 *  - runs a **periodic full sync** as a safety net (missed doorbells, offline → reconnect drains).
 *
 * **The doorbell.** We never read a realtime payload — an event only ever means "something changed,
 * pull now". Subscribing to `schema = "public"` therefore delivered one message PER ROW PER CONNECTED
 * CLIENT for zero benefit, which (with a blind full-table re-push) burned 13.9M messages against a 5M
 * quota. The server now publishes ONLY `group_activity`: one row per group, bumped once per writing
 * transaction by statement-level triggers, its RLS scoping delivery to that group's members. So we
 * subscribe to that one table and keep treating it as a pure "pull now" signal.
 *
 * **The gate.** Everything hangs off [gatedUser]: sign out or leave the app and all three loops
 * cancel, the channel is removed and the socket closes (supabase-kt's `disconnectOnNoSubscriptions`);
 * come back and they restart with a catch-up [SyncEngine.syncNow]. Previously all three ran for the
 * whole process lifetime — backgrounded phones and idle simulators held sockets open and kept syncing.
 *
 * Best-effort throughout: a failed push/pull is swallowed (the engine returns an `Err`, not throws) and
 * retried by the next trigger or the fallback tick, so a transient network blip never wedges the loop.
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncManager(
    private val client: SupabaseClient,
    private val syncEngine: SyncEngine,
    private val db: EvenlyDatabase,
) {
    /**
     * Start the loops on [scope], gated on [currentUserId] and [foreground]. Idempotent per scope
     * (call once on bind). Pass a constant `flowOf(true)` for [foreground] to run ungated.
     */
    fun bind(
        scope: CoroutineScope,
        currentUserId: StateFlow<UserId?>,
        foreground: Flow<Boolean> = flowOf(true),
    ) {
        scope.launch {
            gatedUser(currentUserId, foreground).collectLatest { user ->
                if (user == null) return@collectLatest
                val userId = user.value
                // supervisorScope, NOT coroutineScope: one loop throwing (e.g. the Room invalidation
                // flow) must not cancel its siblings AND the gate collector itself — that would leave
                // sync dead until the process restarts.
                supervisorScope {
                    // 1. Prompt push: any local write to a synced table → debounced push.
                    launch {
                        runCatching {
                            db.invalidationTracker
                                .createFlow(*SyncEngine.SYNCED_TABLES.toTypedArray(), emitInitialState = false)
                                .debounce(PUSH_DEBOUNCE_MS)
                                .collect { syncEngine.push(userId) }
                        }
                    }
                    // 2. Realtime doorbell → debounced pull. Wrapped so a realtime failure (e.g. the
                    //    publication isn't enabled) doesn't kill the loop — the fallback tick still
                    //    keeps data fresh.
                    launch {
                        runCatching {
                            val channel = client.channel(REALTIME_CHANNEL)
                            // Must be created BEFORE subscribe() — the channel errors if it's already
                            // SUBSCRIBED. Payload ignored: the event itself is the whole signal.
                            val changes =
                                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                                    table = DOORBELL_TABLE
                                }
                            try {
                                channel.subscribe()
                                changes.debounce(PULL_DEBOUNCE_MS).collect { syncEngine.pull(userId) }
                            } finally {
                                // NonCancellable: these are suspend calls running under cancellation,
                                // so they'd abort instantly and leak the socket. removeChannel alone is
                                // the complete teardown (it unsubscribes, drops the channel from the
                                // map, and lets disconnectOnNoSubscriptions close the socket) —
                                // unsubscribe() alone would leave it in the map and the socket open.
                                withContext(NonCancellable) {
                                    runCatching { client.realtime.removeChannel(channel) }
                                }
                            }
                        }
                    }
                    // 3. Periodic full-sync safety net (also drains offline edits once connectivity returns).
                    launch {
                        while (true) {
                            delay(FALLBACK_INTERVAL_MS)
                            syncEngine.syncNow(userId)
                        }
                    }
                    // Catch up on whatever we missed while signed out / backgrounded.
                    syncEngine.syncNow(userId)
                }
            }
        }
    }

    internal companion object {
        const val REALTIME_CHANNEL = "evenly-sync"

        /** The only table in the `supabase_realtime` publication — see the doorbell note above. */
        const val DOORBELL_TABLE = "group_activity"
        const val PUSH_DEBOUNCE_MS = 1_200L
        const val PULL_DEBOUNCE_MS = 600L
        const val FALLBACK_INTERVAL_MS = 60_000L

        /**
         * How long the app must stay backgrounded before we tear the loops down. Absorbs Android
         * activity recreation (rotation, theme change) so it doesn't churn the socket, and leaves room
         * for a pending [PUSH_DEBOUNCE_MS] push to land when the user edits then immediately leaves.
         * iOS may suspend us sooner; those rows just stay dirty and the on-entry `syncNow` catches them.
         */
        const val BACKGROUND_GRACE_MS = 5_000L

        /**
         * The signed-in user to sync as, or null to stop everything. Emits null the instant the user
         * signs out, but waits [graceMs] before reacting to the app going away (the grace is applied to
         * [foreground] *before* the combine, so a sign-out is never delayed by it).
         *
         * `internal` + pure so [SyncManagerTest] can exercise the gate on virtual time without standing
         * up a Supabase client — the same pattern as [SyncEngine.keepNewer].
         */
        internal fun gatedUser(
            currentUserId: Flow<UserId?>,
            foreground: Flow<Boolean>,
            graceMs: Long = BACKGROUND_GRACE_MS,
        ): Flow<UserId?> {
            val settledForeground =
                foreground
                    .mapLatest { visible ->
                        if (!visible) delay(graceMs) // cancelled if we come back within the grace
                        visible
                    }.distinctUntilChanged()
            return combine(currentUserId, settledForeground) { user, visible ->
                user.takeIf { visible }
            }.distinctUntilChanged()
        }
    }
}
