package app.splitevenly.data.remote.revenuecat

import app.splitevenly.core.error.AppError
import app.splitevenly.data.remote.supabase.SupabaseConfig
import app.splitevenly.data.remote.supabase.SyncEngine
import app.splitevenly.data.upload.AccessTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

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
    /** What the server did with this transaction. See [ActivationOutcome] for why it is not a Boolean. */
    suspend fun activate(
        groupId: String,
        storeTxnId: String,
    ): ActivationOutcome
}

/**
 * What an activation or subscriber-sync call did.
 *
 * **Three states rather than two, because a Boolean told a paying customer the wrong thing forever.**
 * `false` meant both "airplane mode" and "the server verified this transaction with RevenueCat and
 * permanently rejected it", so a 403 or an unrecognised product id rendered as the sheet's
 * "Payment went through, tap again" — and `retryPendingActivation` runs on every launch and every
 * paywall open, so it said that on every launch, forever, with no route to a human. The charge itself
 * was never at risk (the RevenueCat webhook plus `pro_orphan_purchases` is the server-side backstop);
 * what was missing was any way for the app to say so.
 */
sealed interface ActivationOutcome {
    /** The pass exists server-side. Idempotent: saying this twice for one transaction is normal. */
    data object Activated : ActivationOutcome

    /** Nothing is wrong with the purchase — the call did not land. Retrying is the designed recovery. */
    data class Retryable(
        val error: AppError?,
    ) : ActivationOutcome

    /** The server refused, and will refuse the same transaction again. Retrying cannot fix this. */
    data class Refused(
        val status: Int?,
    ) : ActivationOutcome

    val activated: Boolean get() = this is Activated

    companion object {
        /**
         * Split an HTTP status the way `SyncEngine.classifySyncError` splits an exception: a 4xx that is
         * not 408/429 is the server's verdict on this request and will not change, everything else is
         * this attempt failing rather than the purchase being rejected.
         */
        fun of(status: HttpStatusCode): ActivationOutcome =
            when {
                status.isSuccess() -> {
                    Activated
                }

                status == HttpStatusCode.RequestTimeout || status == HttpStatusCode.TooManyRequests -> {
                    Retryable(AppError.Backend(status.value, status.description, null))
                }

                status.value in CLIENT_ERRORS -> {
                    Refused(status.value)
                }

                else -> {
                    Retryable(AppError.Backend(status.value, status.description, null))
                }
            }

        /**
         * A thrown exception is never a verdict on the purchase — it is transport, a timeout, or a decode
         * problem — so it is always [Retryable], classified only so the error carries the true kind.
         */
        fun of(error: Throwable): ActivationOutcome = Retryable(SyncEngine.classifySyncError(error))

        /** HTTP 4xx: the server judging this request, rather than failing to answer it. */
        private val CLIENT_ERRORS = 400..499
    }
}

/**
 * The signed-in caller's access token, or null when there is no way to make an authenticated call —
 * an unconfigured build, or no live session. Neither is a refusal of the purchase, so both callers
 * turn null into [ActivationOutcome.Retryable].
 */
private suspend fun AccessTokenProvider?.callerToken(): String? = if (!SupabaseConfig.isConfigured) null else this?.token()

/**
 * Run one edge-function call and read its outcome, letting structured-concurrency cancellation through.
 *
 * Shared by both gateways because the classification is the same for both and having it in one place is
 * what stops one of them drifting back to a Boolean.
 */
private suspend fun postForOutcome(call: suspend () -> HttpResponse): ActivationOutcome {
    val result = runCatching { call() }
    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    return result.fold(
        onSuccess = { ActivationOutcome.of(it.status) },
        onFailure = { ActivationOutcome.of(it) },
    )
}

class HttpPassActivationGateway(
    private val http: HttpClient,
    private val accessTokenProvider: AccessTokenProvider? = null,
) : PassActivationGateway {
    override suspend fun activate(
        groupId: String,
        storeTxnId: String,
    ): ActivationOutcome {
        // Neither is a refusal of the purchase: an unconfigured build cannot reach the function at all,
        // and a missing token means no live session right now. Both resolve without the buyer doing
        // anything differently, so both stay retryable.
        val token = accessTokenProvider.callerToken() ?: return ActivationOutcome.Retryable(AppError.SessionExpired)
        return postForOutcome {
            http.post("${SupabaseConfig.URL}/functions/v1/activate-pass") {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseConfig.ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody("""{"groupId":"$groupId","storeTxnId":"$storeTxnId"}""")
            }
        }
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
    /** Typed for the same reason [PassActivationGateway.activate] is: Restore has to be able to tell
     *  "we could not reach the server" from "the server says you own nothing". */
    suspend fun sync(): ActivationOutcome
}

class HttpSubscriberSyncGateway(
    private val http: HttpClient,
    private val accessTokenProvider: AccessTokenProvider? = null,
) : SubscriberSyncGateway {
    override suspend fun sync(): ActivationOutcome {
        val token = accessTokenProvider.callerToken() ?: return ActivationOutcome.Retryable(AppError.SessionExpired)
        return postForOutcome {
            http.post("${SupabaseConfig.URL}/functions/v1/sync-subscriber") {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseConfig.ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }
    }
}
