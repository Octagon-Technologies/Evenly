package app.splitevenly.data.remote.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Params for the `join_item_portion` RPC; the snake_case serializer maps `pItemId` → `p_item_id`. */
@Serializable
private data class JoinItemPortionParams(
    @SerialName("p_item_id") val pItemId: String,
    @SerialName("p_joiner_user_id") val pJoinerUserId: String,
    @SerialName("p_portion_id") val pPortionId: String?,
    @SerialName("p_now") val pNow: Long,
    @SerialName("p_over_claim_ack") val pOverClaimAck: Boolean,
)

/** The server's canonical result: [portionId] and every member now in it, so the caller can mirror the
 *  outcome locally wholesale instead of recomputing which rows changed. */
@Serializable
data class JoinItemPortionOutcome(
    val ok: Boolean,
    @SerialName("portion_id") val portionId: String,
    val quantity: Int,
    val members: List<String>,
)

/**
 * The write a device can never safely make itself (`supabase/AGENTS.md` "Web claim" section,
 * WEB_CLAIM_SPEC.md §5.3): converting someone else's solo `item_claims` row into a shared `item_shares`
 * portion. `item_claims` is partitioned by user — each device only ever writes its own row — so folding
 * Mary's solo claim into a portion with Jane has to happen server-side, atomically, under a row lock.
 *
 * Bound only when Supabase is configured, same as [PlaceholderClaimGateway]. Throws on failure, including
 * a raised `'OVERCLAIMED'` Postgres exception when the join would push assigned units past the line's
 * quantity and [overClaimAck] wasn't set — the caller translates that into an [app.splitevenly.core.error.AppError].
 */
interface JoinItemPortionGateway {
    suspend fun join(
        itemId: String,
        joinerUserId: String,
        portionId: String?,
        now: Long,
        overClaimAck: Boolean = false,
    ): JoinItemPortionOutcome
}

class SupabaseJoinItemPortionGateway(
    private val client: SupabaseClient,
) : JoinItemPortionGateway {

    override suspend fun join(
        itemId: String,
        joinerUserId: String,
        portionId: String?,
        now: Long,
        overClaimAck: Boolean,
    ): JoinItemPortionOutcome = client.postgrest
        .rpc("join_item_portion", JoinItemPortionParams(itemId, joinerUserId, portionId, now, overClaimAck))
        .decodeAs<JoinItemPortionOutcome>()
}
