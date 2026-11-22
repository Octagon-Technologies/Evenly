package app.splitevenly.domain.fx

/** A currency the FX provider supports: ISO code + display name (04 §5.x `GET /currencies`). */
data class CurrencyInfo(val code: String, val name: String)

/** Small built-in list so the currency picker is never empty on a cold, offline first launch,
 *  before [app.splitevenly.domain.repository.FxRepository.currencies] has ever fetched
 *  successfully. Overwritten by the live fetch (and then served from the local cache) once it
 *  succeeds — this is a fallback, not the source of truth. */
object FxCurrencyDefaults {
    val fallback: List<CurrencyInfo> = listOf(
        CurrencyInfo("USD", "US Dollar"),
        CurrencyInfo("EUR", "Euro"),
        CurrencyInfo("GBP", "British Pound"),
        CurrencyInfo("CAD", "Canadian Dollar"),
        CurrencyInfo("AUD", "Australian Dollar"),
        CurrencyInfo("MXN", "Mexican Peso"),
        CurrencyInfo("JPY", "Japanese Yen"),
        CurrencyInfo("CNY", "Chinese Yuan"),
        CurrencyInfo("INR", "Indian Rupee"),
        CurrencyInfo("SGD", "Singapore Dollar"),
        CurrencyInfo("HKD", "Hong Kong Dollar"),
        CurrencyInfo("NZD", "New Zealand Dollar"),
    )

    /** Currencies the app supports that the ECB-backed provider does not list (Frankfurter has no KES),
     *  so they would otherwise be missing from every picker. Expenses in them stay in their own currency
     *  when no rate exists. */
    private val appOnly: List<CurrencyInfo> = listOf(CurrencyInfo("KES", "Kenyan Shilling"))

    fun withAppCurrencies(fetched: List<CurrencyInfo>): List<CurrencyInfo> =
        (fetched + appOnly.filter { extra -> fetched.none { it.code == extra.code } }).sortedBy { it.code }
}
