package da.chelimo.sharecost.data.remote.supabase

import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.data.db.ShareCostDatabase
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Live sync driver (F7) — supersedes the old fixed 15s heartbeat. For the signed-in user it:
 *  - **pushes promptly** whenever local tables change (Room invalidation → debounced [SyncEngine.push]),
 *  - **pulls in near-real-time** via Supabase Realtime (any server change on our tables → debounced pull),
 *  - runs a **periodic full sync** as a safety net (missed realtime events, offline → reconnect drains).
 *
 * Best-effort throughout: a failed push/pull is swallowed (the engine returns an `Err`, not throws) and
 * retried by the next trigger or the fallback tick, so a transient network blip never wedges the loop.
 */
@OptIn(FlowPreview::class)
class SyncManager(
    private val client: SupabaseClient,
    private val syncEngine: SyncEngine,
    private val db: ShareCostDatabase,
) {
    /** Start the loops on [scope], driven by [currentUserId]. Idempotent per scope (call once on bind). */
    fun bind(scope: CoroutineScope, currentUserId: StateFlow<UserId?>) {
        // 1. Prompt push: any local write to a synced table → debounced push.
        scope.launch {
            db.invalidationTracker
                .createFlow(*SYNC_TABLES, emitInitialState = false)
                .debounce(PUSH_DEBOUNCE_MS)
                .collect { if (currentUserId.value != null) syncEngine.push() }
        }
        // 2. Realtime pull: any server change on our schema → debounced pull for the current user. RLS
        //    scopes delivered events to rows this user may see. Wrapped so a realtime failure (e.g. the
        //    publication isn't enabled) doesn't crash the loop — the fallback tick still keeps data fresh.
        scope.launch {
            runCatching {
                val channel = client.channel(REALTIME_CHANNEL)
                val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public")
                channel.subscribe()
                changes.debounce(PULL_DEBOUNCE_MS).collect {
                    currentUserId.value?.let { syncEngine.pull(it.value) }
                }
            }
        }
        // 3. Periodic full-sync safety net (also drains offline edits once connectivity returns).
        scope.launch {
            while (true) {
                delay(FALLBACK_INTERVAL_MS)
                currentUserId.value?.let { syncEngine.syncNow(it.value) }
            }
        }
    }

    private companion object {
        const val REALTIME_CHANNEL = "sharecost-sync"
        const val PUSH_DEBOUNCE_MS = 1_200L
        const val PULL_DEBOUNCE_MS = 600L
        const val FALLBACK_INTERVAL_MS = 60_000L

        /** Synced tables whose local changes should trigger a push (mirrors [SyncEngine.push]). */
        val SYNC_TABLES = arrayOf(
            "users", "groups", "members", "expenses", "shares",
            "settlements", "conflicts", "comments", "receipts", "expense_history",
        )
    }
}
