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

/** The one and only time the plaintext token is readable (spec §4.2). */
@Serializable
data class WebBillLinkCreated(
    val id: String,
    val token: String,
    @SerialName("expires_at") val expiresAt: Long,
)

/** `{ok, expires_at}` from extend; `{ok}` from revoke. */
@Serializable
data class WebBillLinkAck(
    val ok: Boolean,
    @SerialName("expires_at") val expiresAt: Long? = null,
)

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
    @SerialName("extended_count") val extendedCount: Int = 0,
    val sessions: List<WebSessionSeen> = emptyList(),
)

/**
 * The payer's half of the web claim link (`WEB_CLAIM_SPEC.md` §3.9.3), reached only through
 * `security definer` RPCs.
 *
 * `web_bill_links` has RLS enabled with **no policies** and must keep it. A table policy would expose
 * `token_hash` to every authenticated user — and that hash is the entire authorisation check the
 * `web-claim` edge function performs, so a readable hash column is a readable bill for 72 hours. These
 * RPCs re-derive group membership themselves and never return the hash.
 *
 * Bound only when Supabase is configured, same pattern as [PlaceholderClaimGateway]. Throws on failure:
 * a link is an authorisation, and "couldn't ask" must never be reported to the payer as "revoked".
 */
interface WebBillLinkGateway {
    suspend fun status(expenseId: String, actor: String): WebBillLinkStatusDto
    suspend fun create(expenseId: String, actor: String, now: Long): WebBillLinkCreated
    suspend fun extend(expenseId: String, actor: String, now: Long): WebBillLinkAck
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

    override suspend fun extend(expenseId: String, actor: String, now: Long): WebBillLinkAck = client.postgrest
        .rpc("extend_web_bill_link", LinkActionParams(expenseId, actor, now))
        .decodeAs<WebBillLinkAck>()

    override suspend fun revoke(expenseId: String, actor: String, now: Long): WebBillLinkAck = client.postgrest
        .rpc("revoke_web_bill_link", LinkActionParams(expenseId, actor, now))
        .decodeAs<WebBillLinkAck>()
}
