package app.splitevenly.ui.navigation

import app.splitevenly.core.id.UserId
import app.splitevenly.domain.activity.HistoryEvent
import app.splitevenly.domain.activity.HistoryEventType
import app.splitevenly.ui.components.moneySubunits

/**
 * Render a history event into a viewer-relative sentence (actor name resolved live). [HistoryEvent.detail]
 * carries raw tokens the repository stored ("amt:<subunits>", "edit:<old>:<new>[:<payer>:<onThisBill>]",
 * "removed:<amount>:<payer>") so the money is formatted here in the expense's [currency], keeping
 * currency formatting out of the data layer.
 */
internal fun historyText(
    ev: HistoryEvent,
    currency: String,
    viewer: UserId?,
    nameOf: (UserId?) -> String,
): String {
    paymentEditText(ev, currency, viewer, nameOf)?.let { return it }
    val verb =
        when (ev.type) {
            HistoryEventType.CREATED -> "added this expense"
            HistoryEventType.EDITED -> "edited this expense"
            HistoryEventType.SETTLED -> "settled"
            HistoryEventType.SETTLEMENT_EDITED -> "corrected a payment"
            HistoryEventType.SETTLEMENT_REMOVED -> "removed a payment"
            HistoryEventType.COMMENTED -> "commented"
            HistoryEventType.RECEIPT_ADDED -> "added a receipt"
            HistoryEventType.DELETED -> "deleted this expense"
        }
    val passive =
        when (ev.type) {
            HistoryEventType.CREATED -> "created"
            HistoryEventType.EDITED -> "edited"
            HistoryEventType.SETTLED -> "settled"
            HistoryEventType.SETTLEMENT_EDITED -> "corrected"
            HistoryEventType.SETTLEMENT_REMOVED -> "changed"
            HistoryEventType.COMMENTED -> "commented on"
            HistoryEventType.RECEIPT_ADDED -> "updated"
            HistoryEventType.DELETED -> "deleted"
        }
    val base = if (ev.actorUserId != null) "${nameOf(ev.actorUserId)} $verb" else "This expense was $passive"
    return ev.detail?.let { "$base · ${renderDetail(it, currency)}" } ?: base
}

/**
 * The two-party payment-edit sentences (`docs/features/one-time-splits.md` mockup 10): "Bob changed
 * Alice's payment $15.00 → $5.00 · now covers $5.00 of this bill" and "Bob removed Alice's $15.00
 * payment". Null for anything else, including edit rows written before the payer was recorded.
 */
private fun paymentEditText(
    ev: HistoryEvent,
    currency: String,
    viewer: UserId?,
    nameOf: (UserId?) -> String,
): String? {
    val parts = ev.detail?.split(":") ?: return null
    val actor = nameOf(ev.actorUserId)

    fun whose(payer: String): String =
        when {
            viewer?.value == payer -> "your"
            ev.actorUserId?.value == payer -> "their"
            else -> "${nameOf(UserId(payer))}'s"
        }
    return when {
        ev.type == HistoryEventType.SETTLEMENT_EDITED && parts.size >= 5 && parts[0] == "edit" -> {
            val old = parts[1].toLongOrNull() ?: return null
            val new = parts[2].toLongOrNull() ?: return null
            val here = parts[4].toLongOrNull()
            val sentence = "$actor changed ${whose(parts[3])} payment ${moneySubunits(old, currency)} → ${moneySubunits(new, currency)}"
            // Only a settle-up across bills needs the per-bill line; on a one-bill payment it repeats the total.
            if (here != null && here != new) "$sentence · now covers ${moneySubunits(here, currency)} of this bill" else sentence
        }

        ev.type == HistoryEventType.SETTLEMENT_REMOVED && parts.size >= 3 && parts[0] == "removed" -> {
            val amount = parts[1].toLongOrNull() ?: return null
            "$actor removed ${whose(parts[2])} ${moneySubunits(amount, currency)} payment"
        }

        else -> {
            null
        }
    }
}

/** Expand a stored detail token into human money text; pass through plain (non-token) details unchanged. */
private fun renderDetail(
    detail: String,
    currency: String,
): String {
    val parts = detail.split(":")
    return when (parts.firstOrNull()) {
        "amt" -> {
            parts.getOrNull(1)?.toLongOrNull()?.let { moneySubunits(it, currency) } ?: detail
        }

        "edit" -> {
            val old = parts.getOrNull(1)?.toLongOrNull()
            val new = parts.getOrNull(2)?.toLongOrNull()
            if (old != null && new != null) "${moneySubunits(old, currency)} → ${moneySubunits(new, currency)}" else detail
        }

        else -> {
            detail
        }
    }
}

/** Short relative time ("now", "5m", "2h", "3d", "2w") for activity stamps. */
internal fun relativeTimeLabel(
    thenMs: Long,
    nowMs: Long,
): String {
    val minutes = ((nowMs - thenMs).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 1_440 -> "${minutes / 60}h"
        minutes < 10_080 -> "${minutes / 1_440}d"
        else -> "${minutes / 10_080}w"
    }
}
