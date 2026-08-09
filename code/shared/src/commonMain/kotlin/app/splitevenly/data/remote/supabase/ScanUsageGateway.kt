package app.splitevenly.data.remote.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class ScanUsageParams(@SerialName("p_group_id") val pGroupId: String)

/** What the group has spent of its free allowance. `freeLimit` rides along so the meter never hardcodes
 *  a number the server could have been tuned away from. */
@Serializable
data class ScanUsage(
    @SerialName("free_used") val freeUsed: Int,
    @SerialName("free_limit") val freeLimit: Int,
)

/**
 * Reads a group's free-scan usage via the `my_group_scan_usage` RPC (`PRO_PASS_SPEC.md` §4).
 *
 * A group's total cannot be read from `receipt_scan_log` directly: its RLS scopes a client to its own
 * rows, so a member querying it would see only the scans they personally did. The RPC is `security
 * definer` and re-derives the caller's ACTIVE membership, so it answers for your groups and no others.
 *
 * Bound only when Supabase is configured, same as [JoinItemPortionGateway]. Throws on failure; the
 * caller keeps the last cached count rather than showing the user an error about a label.
 */
interface ScanUsageGateway {
    suspend fun usage(groupId: String): ScanUsage
}

class SupabaseScanUsageGateway(
    private val client: SupabaseClient,
) : ScanUsageGateway {

    override suspend fun usage(groupId: String): ScanUsage = client.postgrest
        .rpc("my_group_scan_usage", ScanUsageParams(groupId))
        // The RPC returns a one-row table, so the body is a single-element array.
        .decodeList<ScanUsage>()
        .firstOrNull()
        ?: error("my_group_scan_usage returned no row")
}
