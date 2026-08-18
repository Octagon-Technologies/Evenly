package app.splitevenly.data.remote.supabase

import app.splitevenly.data.upload.AccessTokenProvider
import app.splitevenly.platform.isIOS
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * How a POST to the `feedback` edge function ended, split by what the caller should *do* rather than by
 * status code. The distinction that matters is [Rejected] versus [Unreachable]: one means retrying will
 * never work and the outbox row should go, the other means try again later.
 */
sealed interface FeedbackPostResult {
    data object Accepted : FeedbackPostResult

    /** 401. The token expired or is missing; the ticket is kept and goes out after the next sign-in. */
    data object Unauthenticated : FeedbackPostResult

    /** 429. 30 an hour per user (spec §8). Keep the row, stop hammering. */
    data object RateLimited : FeedbackPostResult

    /** A 4xx the server will give again for the same body. Retrying is pointless; drop the row. */
    data class Rejected(
        val code: String?,
    ) : FeedbackPostResult

    /** Offline, DNS, TLS, a 5xx, an unconfigured build. Transient by assumption. */
    data object Unreachable : FeedbackPostResult
}

/** The one call [app.splitevenly.data.repository.FeedbackOutbox] needs, so tests can stand in for it. */
interface FeedbackPoster {
    suspend fun post(
        type: String,
        category: String,
        message: String,
        appVersion: String?,
    ): FeedbackPostResult
}

/**
 * The only client-side writer to `feedback_tickets`, via `supabase/functions/feedback`.
 *
 * Authenticates **as the user**: the function refuses an `app_ios`/`app_android` ticket with no valid
 * token outright rather than filing it anonymously, so a signed-in person's bug report can never land
 * as "some stranger". It also ignores `name` and `email` from an app source, which is why neither is
 * on the form or in [Body].
 */
class FeedbackHttp(
    private val http: HttpClient,
    private val accessTokenProvider: AccessTokenProvider? = null,
) : FeedbackPoster {
    @Serializable
    private data class Body(
        val type: String,
        val category: String,
        val message: String,
        val source: String,
        @SerialName("appVersion") val appVersion: String? = null,
    )

    override suspend fun post(
        type: String,
        category: String,
        message: String,
        appVersion: String?,
    ): FeedbackPostResult {
        if (!SupabaseConfig.isConfigured) return FeedbackPostResult.Unreachable
        val token = accessTokenProvider?.token() ?: return FeedbackPostResult.Unauthenticated
        return try {
            val response =
                http.post("${SupabaseConfig.URL}/functions/v1/feedback") {
                    header("Authorization", "Bearer $token")
                    header("apikey", SupabaseConfig.ANON_KEY)
                    contentType(ContentType.Application.Json)
                    setBody(
                        Body(
                            type = type,
                            category = category,
                            message = message,
                            source = if (isIOS()) SOURCE_IOS else SOURCE_ANDROID,
                            appVersion = appVersion?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            when {
                response.status.isSuccess() -> {
                    FeedbackPostResult.Accepted
                }

                response.status == HttpStatusCode.Unauthorized -> {
                    FeedbackPostResult.Unauthenticated
                }

                response.status == HttpStatusCode.TooManyRequests -> {
                    FeedbackPostResult.RateLimited
                }

                // ONLY 400 and 422 are permanent. Those are the codes the function's own validation
                // returns (bad_type, bad_category, bad_source, empty_message, too_long), and replaying
                // that body every reconnect would never fix it.
                //
                // Every other 4xx is treated as transient, and 404 is why: an edge function that is not
                // deployed yet answers 404, and dropping someone's bug report because the backend has
                // not shipped is the worst possible reading of "the server refused it". Found by walking
                // the screen on the simulator against exactly that state.
                response.status.value == 400 || response.status.value == 422 -> {
                    FeedbackPostResult.Rejected(response.status.description)
                }

                else -> {
                    FeedbackPostResult.Unreachable
                }
            }
        } catch (e: CancellationException) {
            // Same rule as GroupExportHttp: never swallow structured-concurrency cancellation. Leaving the
            // screen cancels this call, and recording that as a failed attempt would burn a retry budget
            // on something that did not fail.
            throw e
        } catch (_: Throwable) {
            FeedbackPostResult.Unreachable
        }
    }

    private companion object {
        const val SOURCE_IOS = "app_ios"
        const val SOURCE_ANDROID = "app_android"
    }
}
