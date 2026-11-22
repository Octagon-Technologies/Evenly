package app.splitevenly.domain.currency

/** Currencies the app's pickers offer; a detected code outside this set falls back to USD. */
val SupportedCurrencies = listOf("USD", "EUR", "GBP", "MXN", "CAD", "AUD", "JPY", "INR", "KES")

/**
 * The base currency a new account starts with. A stored non-USD choice wins; otherwise the device region's
 * currency if supported; otherwise USD. USD stored is indistinguishable from the account default, so it is
 * treated as "not chosen yet".
 */
fun startingCurrency(stored: String?, detected: String?): String {
    val kept = stored?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "USD" }
    if (kept != null) return kept
    val device = detected?.trim()?.uppercase()
    return if (device != null && device in SupportedCurrencies) device else "USD"
}
