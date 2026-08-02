package app.splitevenly.data.remote.fx

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * [FxRateFetcher] backed by the public Frankfurter API (04 §5.1) — no auth, no key. A 5-second
 * timeout (03 §6.1) caps cold-start latency; any failure becomes an [AppError.Network] the caller
 * swallows (03 §6.4). The base URL is injectable for tests.
 */
class FrankfurterFxFetcher(
    private val client: HttpClient,
    private val baseUrl: String = "https://api.frankfurter.app",
) : FxRateFetcher {

    override suspend fun fetchLatest(base: String): AppResult<FxSnapshot> =
        try {
            val response: LatestResponse = withTimeout(TIMEOUT_MS) {
                client.get("$baseUrl/latest") { parameter("base", base) }.body()
            }
            AppResult.Ok(FxSnapshot(rateDate = response.date, ratesPerUsd = response.rates))
        } catch (e: TimeoutCancellationException) {
            AppResult.Err(AppError.Network(AppError.Network.Kind.Timeout, e))
        } catch (e: CancellationException) {
            throw e // never swallow structured-concurrency cancellation
        } catch (e: Exception) {
            AppResult.Err(AppError.Network(AppError.Network.Kind.Unreachable, e))
        }

    override suspend fun fetchCurrencies(): AppResult<Map<String, String>> =
        try {
            val response: Map<String, String> = withTimeout(TIMEOUT_MS) {
                client.get("$baseUrl/currencies").body()
            }
            AppResult.Ok(response)
        } catch (e: TimeoutCancellationException) {
            AppResult.Err(AppError.Network(AppError.Network.Kind.Timeout, e))
        } catch (e: CancellationException) {
            throw e // never swallow structured-concurrency cancellation
        } catch (e: Exception) {
            AppResult.Err(AppError.Network(AppError.Network.Kind.Unreachable, e))
        }

    /** Frankfurter shape: `{ "amount":1.0, "base":"USD", "date":"YYYY-MM-DD", "rates":{ "EUR":0.9, ... } }`. */
    @Serializable
    private data class LatestResponse(
        val amount: Double = 1.0,
        val base: String = "USD",
        val date: String,
        val rates: Map<String, Double> = emptyMap(),
    )

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
