package da.chelimo.sharecost.data.remote.fx

import da.chelimo.sharecost.core.error.AppResult

/** A day's USD-based rates fetched from the FX provider. [ratesPerUsd] maps quote currency → `USD→quote`. */
data class FxSnapshot(
    val rateDate: String,
    val ratesPerUsd: Map<String, Double>,
)

/**
 * Fetches the latest FX rates (04 §5). Abstracted so [da.chelimo.sharecost.data.repository.FxRepositoryImpl]
 * can be tested without real network — tests inject a fake; production injects [FrankfurterFxFetcher].
 */
interface FxRateFetcher {
    /** Latest rates with [base] as the base currency. Errors are returned, never thrown. */
    suspend fun fetchLatest(base: String = "USD"): AppResult<FxSnapshot>

    /** Every currency code the provider supports, code -> display name. Errors are returned, never thrown. */
    suspend fun fetchCurrencies(): AppResult<Map<String, String>>
}
