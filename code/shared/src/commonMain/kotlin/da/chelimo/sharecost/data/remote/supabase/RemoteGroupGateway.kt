package da.chelimo.sharecost.data.remote.supabase

import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.entity.GroupEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlin.coroutines.cancellation.CancellationException

/**
 * Server-side group lookup for join-by-link (F7). The local-first [joinByToken] resolves the invite
 * token against the Room cache, which fails for a group this device has never synced
 * (`GROUP_NOT_CACHED`). This gateway asks the server directly so a cross-device join works.
 *
 * Bound only when Supabase is configured; [GroupRepositoryImpl] takes it as an optional dependency and
 * falls back to the local-only behaviour when it's null.
 */
interface RemoteGroupGateway {
    /** Look up a group by its invite token on the server, caching the row locally; null if not found. */
    suspend fun resolveByToken(token: String): GroupEntity?
}

class SupabaseRemoteGroupGateway(
    private val client: SupabaseClient,
    private val db: ShareCostDatabase,
) : RemoteGroupGateway {

    override suspend fun resolveByToken(token: String): GroupEntity? =
        try {
            val group = client.from("groups")
                .select(Columns.ALL) { filter { eq("invite_token", token.trim()) } }
                .decodeSingleOrNull<GroupEntity>()
            // Cache it so the join write + the group screen have the row immediately; the post-join
            // sync (membership push → membership-scoped pull) hydrates members/expenses.
            if (group != null) db.groupDao().upsert(group)
            group
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
}
