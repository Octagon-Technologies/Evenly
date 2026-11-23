package app.splitevenly.domain.expense

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * A bill's date is a calendar day only, ISO-8601 ("2026-11-20"). Never a time: a printed receipt time only
 * feeds the suggested name ("Dinner at …"), and storing it would invite time-zone arguments nobody needs.
 */

/** How far back a printed receipt date is trusted. Older ones are usually a misread (a year or a day/month swap). */
const val RECEIPT_DATE_MAX_DAYS_BACK = 60

private val SHORT_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** The date chip's text: "Today", "Yesterday", "Nov 20", or "Dec 30, 2025" outside this year. */
fun expenseDateChipLabel(
    date: String,
    today: String,
): String {
    val d = parseOrNull(date) ?: return date
    val t = parseOrNull(today) ?: return date
    return when (d) {
        t -> "Today"
        t.minus(DatePeriod(days = 1)) -> "Yesterday"
        else -> shortLabel(d, withYear = d.year != t.year)
    }
}

/** "Sat, Nov 21": the day-of-week form the date sheet's quick chips and confirm button use. */
fun expenseDateLongLabel(date: String): String {
    val d = parseOrNull(date) ?: return date
    val dow = d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return "$dow, ${shortLabel(d, withYear = false)}"
}

/** [date] moved by [days] (negative is earlier); unparseable input comes back unchanged. */
fun shiftIsoDate(
    date: String,
    days: Int,
): String = parseOrNull(date)?.plus(DatePeriod(days = days))?.toString() ?: date

/** True when [date] is after [today]. A bill can't be dated in the future. */
fun isFutureDate(
    date: String,
    today: String,
): Boolean {
    val d = parseOrNull(date) ?: return false
    val t = parseOrNull(today) ?: return false
    return d > t
}

/**
 * The date printed on a receipt, if it's believable: not in the future and no more than
 * [maxDaysBack] days old. Null otherwise, and the bill keeps today.
 */
fun trustedReceiptDate(
    printed: String?,
    today: String,
    maxDaysBack: Int = RECEIPT_DATE_MAX_DAYS_BACK,
): String? {
    val d = parseOrNull(printed ?: return null) ?: return null
    val t = parseOrNull(today) ?: return null
    if (d > t) return null
    if (d < t.minus(DatePeriod(days = maxDaysBack))) return null
    return d.toString()
}

private fun shortLabel(
    d: LocalDate,
    withYear: Boolean,
): String {
    val base = "${SHORT_MONTHS[d.monthNumber - 1]} ${d.dayOfMonth}"
    return if (withYear) "$base, ${d.year}" else base
}

private fun parseOrNull(iso: String): LocalDate? = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()
