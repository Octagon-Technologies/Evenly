package app.splitevenly.data.remote.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Params for the `claim_placeholder` RPC; the snake_case serializer maps `pGroupId` → `p_group_id`. */
@Serializable
private data class ClaimPlaceholderParams(
    @SerialName("p_group_id") val pGroupId: String,
    @SerialName("p_placeholder_user_id") val pPlaceholderUserId: String,
    @SerialName("p_claimer_user_id") val pClaimerUserId: String,
    @SerialName("p_now") val pNow: Long,
)

/**
 * Who got the name. [won] false means somebody else claimed it first; [winnerName] is who, when the
 * server knows (a claim stamped before the guard existed records no claimer, so the message has to
 * degrade to "someone else").
 */
@Serializable
data class ClaimOutcome(
    val won: Boolean,
    @SerialName("winner_user_id") val winnerUserId: String? = null,
    @SerialName("winner_name") val winnerName: String? = null,
)

/**
 * The first-claim-wins guard (§5.4). Two members can both claim the same name while offline; without
 * this the name's history ends up split across two accounts with nothing signalling that it happened.
 *
 * Bound only when Supabase is configured. When it is null there is no server to contend with, so a
 * claim simply applies.
 */
interface PlaceholderClaimGateway {
    /**
     * Try to win [placeholderUserId] in [groupId] for [claimerUserId]. Throws on a network failure —
     * the caller must NOT treat "couldn't ask" as "won", or two devices both merge the same name.
     */
    suspend fun claim(groupId: String, placeholderUserId: String, claimerUserId: String, now: Long): ClaimOutcome
}

class SupabasePlaceholderClaimGateway(
    private val client: SupabaseClient,
) : PlaceholderClaimGateway {

    override suspend fun claim(
        groupId: String,
        placeholderUserId: String,
        claimerUserId: String,
        now: Long,
    ): ClaimOutcome = client.postgrest
        .rpc("claim_placeholder", ClaimPlaceholderParams(groupId, placeholderUserId, claimerUserId, now))
        .decodeAs<ClaimOutcome>()
}
