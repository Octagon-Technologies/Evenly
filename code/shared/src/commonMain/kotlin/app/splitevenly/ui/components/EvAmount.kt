package app.splitevenly.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import app.splitevenly.ui.theme.EvenlyTheme
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The design's signature amount: a large mono **remaining** over a small muted strikethrough
 * **original** (`.sc-amt`). Right-aligned, tabular figures. [remaining]/[original] are pre-formatted
 * strings (use [money]); the original is hidden when null or equal to remaining.
 */
@Composable
fun EvAmountText(
    remaining: String,
    modifier: Modifier = Modifier,
    original: String? = null,
    strikeOriginal: Boolean = true,
    remainingColor: Color = EvenlyTheme.colors.ink,
) {
    val amounts = EvenlyTheme.amounts
    val colors = EvenlyTheme.colors
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(text = remaining, style = amounts.remaining, color = remainingColor)
        if (original != null && original != remaining) {
            Text(
                text = original,
                style = amounts.original.copy(
                    textDecoration = if (strikeOriginal) TextDecoration.LineThrough else TextDecoration.None,
                ),
                color = colors.ink3,
            )
        }
    }
}

/** Formats a major-unit value as `1,234.56` with grouping + 2 decimals (no locale dep in common). */
fun formatAmount(value: Double): String {
    val cents = (abs(value) * 100.0).roundToLong()
    val whole = cents / 100
    val frac = (cents % 100).toString().padStart(2, '0')
    val grouped = whole.toString()
        .reversed().chunked(3).joinToString(",").reversed()
    val sign = if (value < 0) "-" else ""
    return "$sign$grouped.$frac"
}

/** `$1,234.56` — currency symbol prefix + [formatAmount]. */
fun money(value: Double, currency: String = "$"): String = currency + formatAmount(value)

/** Symbol for an ISO currency code (the common ones); falls back to the bare code. */
fun currencySymbol(code: String): String = when (code.uppercase()) {
    "USD", "CAD", "AUD", "MXN", "NZD", "SGD", "HKD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    "JPY", "CNY" -> "¥"
    "INR" -> "₹"
    else -> "$code "
}

/** Format an amount stored in minor units (e.g. cents) for a currency code. */
fun moneySubunits(subunits: Long, currency: String): String = currencySymbol(currency) + formatAmount(subunits / 100.0)
