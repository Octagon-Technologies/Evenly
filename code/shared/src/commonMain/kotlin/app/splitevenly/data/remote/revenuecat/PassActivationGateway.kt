package app.splitevenly.data.remote.revenuecat

import app.splitevenly.data.remote.supabase.SupabaseConfig
import app.splitevenly.data.upload.AccessTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Calls the `activate-pass` edge function (`PRO_PASS_SPEC.md` §6.2), which verifies the transaction
 * against RevenueCat server-side and inserts the `group_passes` row.
 *
 * The client sends `{ groupId, storeTxnId }` because **the group binding exists nowhere in the store's
 * data model** — only we know which group the money was for.
 *
 * It is deliberately not "trust `CustomerInfo` and write the row": a modified client could then claim any
 * purchase, and the reward is unlimited paid vision calls for six people. The duration is measured on the
 * server clock for the same class of reason, since a wound-back device clock would otherwise be a free
 * month.
 *
 * **Retrying is free and is the designed recovery.** `(store, store_txn_id)` is the server's idempotency
 * key, so the same transaction submitted three times yields one pass and three identical successes. That
 * is what lets the buyer see "Payment went through, tap again" instead of losing a charge.
 */
interface PassActivationGateway {
    /** True once the pass exists server-side. False means "retry with this same transaction id". */
    suspend fun activate(groupId: String, storeTxnId: String): Boolean
}

class HttpPassActivationGateway(
    private val http: HttpClient,
    private val accessTokenProvider: AccessTokenProvider? = null,
) : PassActivationGateway {

    override suspend fun activate(groupId: String, storeTxnId: String): Boolean {
        if (!SupabaseConfig.isConfigured) return false
        val token = accessTokenProvider?.token() ?: return false
        return runCatching {
            http.post("${SupabaseConfig.URL}/functions/v1/activate-pass") {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseConfig.ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody("""{"groupId":"$groupId","storeTxnId":"$storeTxnId"}""")
            }.status.isSuccess()
        }.getOrDefault(false)
    }
}

/**
 * Tells the server to re-read what this subscriber owns (`PRO_PASS_SPEC.md` §6.1).
 *
 * Takes **no body**: it resolves the caller from their JWT and asks RevenueCat directly, so there is
 * nothing to forge and nothing to make idempotent. That is what makes it safe to call on every launch,
 * and what lets it double as Restore with no separate code path.
 */
interface SubscriberSyncGateway {
    suspend fun sync(): Boolean
}

class HttpSubscriberSyncGateway(
    private val http: HttpClient,
    private val accessTokenProvider: AccessTokenProvider? = null,
) : SubscriberSyncGateway {

    override suspend fun sync(): Boolean {
        if (!SupabaseConfig.isConfigured) return false
        val token = accessTokenProvider?.token() ?: return false
        return runCatching {
            http.post("${SupabaseConfig.URL}/functions/v1/sync-subscriber") {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseConfig.ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }.status.isSuccess()
        }.getOrDefault(false)
    }
}
