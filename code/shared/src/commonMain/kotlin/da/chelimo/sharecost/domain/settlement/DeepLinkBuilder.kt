package da.chelimo.sharecost.domain.settlement

/** Payment apps ShareCost can deep-link into (spec 03-business-rules.md §5.1). */
enum class PaymentApp { VENMO, CASH_APP, PAYPAL, ZELLE }

/**
 * The outcome of building a payment deep link (spec 03-business-rules.md §5.1, §5.2).
 *
 * [url] is the app URL to open, or null when the app has no reliable deep link
 * (Zelle — clipboard-only, §5.1). [clipboardFallback] is always populated as
 * "<handle>  <dollars> USD" (literal two-space separator, §5.2) so the user is never
 * dead-ended if the launcher rejects the URL (D-13).
 */
data class DeepLinkResult(
    val url: String?,
    val clipboardFallback: String,
)

/**
 * Builds a payment-app deep link — spec 03-business-rules.md §5.1 (URL templates are
 * normative) and §5.2 (clipboard fallback). Payment is always USD in v1 (D-05).
 *
 * URL templates:
 * - VENMO:    `venmo://paycharge?txn=pay&recipients=<handle>&amount=<dollars>&note=<encoded>`
 * - CASH_APP: `https://cash.app/$<handle>/<dollars>` (handle already includes `$`)
 * - PAYPAL:   `https://paypal.me/<handle>/<dollars>USD`
 * - ZELLE:    no URL (null) — clipboard only.
 *
 * Only the note is URL-encoded (§5.1); handles are inserted verbatim per the templates.
 */
fun buildDeepLink(
    app: PaymentApp,
    handle: String,
    amountUsdSubunits: Long,
    groupName: String,
    expenseTitle: String,
): DeepLinkResult {
    val dollars = formatUsd(amountUsdSubunits)
    val note = "ShareCost: $groupName · $expenseTitle"
    val url = when (app) {
        PaymentApp.VENMO ->
            "venmo://paycharge?txn=pay&recipients=$handle&amount=$dollars&note=${percentEncode(note)}"
        PaymentApp.CASH_APP -> "https://cash.app/$handle/$dollars"
        PaymentApp.PAYPAL -> "https://paypal.me/$handle/${dollars}USD"
        PaymentApp.ZELLE -> null
    }
    return DeepLinkResult(
        url = url,
        clipboardFallback = "$handle  $dollars USD", // literal two-space separator (§5.2)
    )
}

/**
 * Formats USD subunits (cents) as a two-decimal dollar string, e.g. 1234 -> "12.34",
 * 100 -> "1.00", 1 -> "0.01". Done with integer arithmetic — `String.format` is not
 * available in commonMain, and subunit math is exact and locale-independent.
 */
private fun formatUsd(amountUsdSubunits: Long): String {
    val dollars = amountUsdSubunits / 100
    val cents = amountUsdSubunits % 100
    return "$dollars.${cents.toString().padStart(2, '0')}"
}

/** RFC 3986 unreserved characters — kept verbatim when percent-encoding. */
private const val UNRESERVED =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

/**
 * Percent-encodes [text] per RFC 3986 over its UTF-8 bytes: unreserved characters pass
 * through, every other byte becomes `%XX`. Space -> %20, and multi-byte characters such
 * as `·` (U+00B7) encode to their UTF-8 bytes (%C2%B7). Pure commonMain — there is no
 * `java.net.URLEncoder` in shared code.
 */
private fun percentEncode(text: String): String {
    val sb = StringBuilder()
    for (byte in text.encodeToByteArray()) {
        val v = byte.toInt() and 0xFF
        val ch = v.toChar()
        if (v < 0x80 && ch in UNRESERVED) {
            sb.append(ch)
        } else {
            sb.append('%').append(v.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return sb.toString()
}
