package app.splitevenly.data.remote.supabase

import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.GroupEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

@Serializable
private data class ResolveByTokenParams(
    @SerialName("p_token") val pToken: String,
)

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
    private val db: EvenlyDatabase,
) : RemoteGroupGateway {
    /**
     * Goes through the `resolve_group_by_invite_token` RPC rather than selecting `groups` directly.
     *
     * `groups` is membership-scoped, and a joiner is by definition not a member yet. An RLS predicate
     * cannot see the query's WHERE clause, so no policy can express "allow this row because they
     * supplied its token" — any policy permitting this read permits reading every group in the
     * database. The RPC is `security definer`, which moves the token match inside the function where
     * it is the authorisation check rather than a filter the caller chose.
     */
    override suspend fun resolveByToken(token: String): GroupEntity? =
        try {
            val group =
                client.postgrest
                    .rpc("resolve_group_by_invite_token", ResolveByTokenParams(token.trim()))
                    // `returns setof groups`, so the body is an array of at most one row.
                    .decodeList<GroupEntity>()
                    .firstOrNull()
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
