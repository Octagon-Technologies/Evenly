package app.splitevenly.data.remote.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Params shared by every link RPC that only needs "which bill, acting as whom, when". */
@Serializable
private data class LinkActionParams(
    @SerialName("p_expense_id") val pExpenseId: String,
    @SerialName("p_actor") val pActor: String,
    @SerialName("p_now") val pNow: Long,
)

/** `web_bill_link_status` takes no clock — it reports stamps rather than making one. */
@Serializable
private data class LinkStatusParams(
    @SerialName("p_expense_id") val pExpenseId: String,
    @SerialName("p_actor") val pActor: String,
)

/** Returned only to an authenticated active group member through the guarded RPC. */
@Serializable
data class WebBillLinkCreated(
    val id: String,
    val token: String,
    @SerialName("expires_at") val expiresAt: Long? = null,
)

@Serializable
data class WebBillLinkAck(val ok: Boolean)

/** One participant's newest live web-session activity in this group. */
@Serializable
data class WebSessionSeen(
    @SerialName("user_id") val userId: String,
    @SerialName("opened_at") val openedAt: Long,
)

/** Everything the payer's share and progress screens need. Never carries `token_hash`. */
@Serializable
data class WebBillLinkStatusDto(
    val exists: Boolean,
    @SerialName("link_id") val linkId: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("revoked_at") val revokedAt: Long? = null,
    val sessions: List<WebSessionSeen> = emptyList(),
)

/**
 * The payer's half of the web claim link (`WEB_CLAIM_SPEC.md` §3.9.3), reached only through
 * `security definer` RPCs.
 *
 * `web_bill_links` has RLS enabled with **no policies** and must keep it. The table contains the
 * permanent bearer token and its hash; only membership-checked RPCs return the token to group members.
 * The edge function uses the hash, and direct table reads remain closed.
 *
 * Bound only when Supabase is configured, same pattern as [PlaceholderClaimGateway]. Throws on failure:
 * a link is an authorisation, and "couldn't ask" must never be reported to the payer as "revoked".
 */
interface WebBillLinkGateway {
    suspend fun status(expenseId: String, actor: String): WebBillLinkStatusDto
    suspend fun create(expenseId: String, actor: String, now: Long): WebBillLinkCreated
    suspend fun revoke(expenseId: String, actor: String, now: Long): WebBillLinkAck
}

class SupabaseWebBillLinkGateway(
    private val client: SupabaseClient,
) : WebBillLinkGateway {

    override suspend fun status(expenseId: String, actor: String): WebBillLinkStatusDto = client.postgrest
        .rpc("web_bill_link_status", LinkStatusParams(expenseId, actor))
        .decodeAs<WebBillLinkStatusDto>()

    override suspend fun create(expenseId: String, actor: String, now: Long): WebBillLinkCreated = client.postgrest
        .rpc("create_web_bill_link", LinkActionParams(expenseId, actor, now))
        .decodeAs<WebBillLinkCreated>()

    override suspend fun revoke(expenseId: String, actor: String, now: Long): WebBillLinkAck = client.postgrest
        .rpc("revoke_web_bill_link", LinkActionParams(expenseId, actor, now))
        .decodeAs<WebBillLinkAck>()
}
