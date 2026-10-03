package app.splitevenly.domain.receipt

/** What kind of place a receipt came from, as the scan classified it. Only used to name the bill. */
enum class VenueKind { RESTAURANT, CAFE, BAR, GROCERY, SHOP, OTHER }

/** The scan's wire value ("restaurant") to a [VenueKind], or null for anything unknown. */
fun venueKindOf(wire: String?): VenueKind? = VenueKind.entries.firstOrNull { it.name.equals(wire, ignoreCase = true) }

/**
 * A suggested bill name from the receipt header: "Dinner at Tavolo", "Evening shopping at Carrefour".
 *
 * Deterministic on purpose. The scan model only reads the header (name, kind of place, printed time); the
 * wording is a fixed table here so the same receipt always gets the same name and it can be tested.
 * [printedHour] wins over [fallbackHour] (the phone's local hour at scan time), since people often scan a
 * receipt later. Returns null when there is nothing worth suggesting, so the name field stays empty.
 */
fun suggestBillTitle(
    merchant: String?,
    kind: VenueKind?,
    printedHour: Int?,
    fallbackHour: Int,
): String? {
    val name = merchant?.trim()?.takeIf { it.isNotEmpty() }
    val hour = (printedHour ?: fallbackHour).mod(24)
    val occasion =
        when (kind) {
            VenueKind.RESTAURANT -> mealFor(hour)
            VenueKind.CAFE -> if (hour in 5..10) "Breakfast" else "Coffee"
            VenueKind.BAR -> "Drinks"
            VenueKind.GROCERY, VenueKind.SHOP -> "${partOfDay(hour)} shopping"
            VenueKind.OTHER, null -> null
        }
    return when {
        occasion != null && name != null -> "$occasion at $name"
        occasion != null -> occasion
        else -> name
    }
}

/** The hour of a printed "HH:MM", or null when it is missing or malformed. */
fun printedHourOf(time: String?): Int? {
    val h = time?.substringBefore(':')?.toIntOrNull() ?: return null
    return h.takeIf { it in 0..23 }
}

private fun mealFor(hour: Int): String =
    when (hour) {
        in 5..10 -> "Breakfast"
        in 11..15 -> "Lunch"
        in 16..21 -> "Dinner"
        else -> "Late dinner"
    }

private fun partOfDay(hour: Int): String =
    when (hour) {
        in 5..11 -> "Morning"
        in 12..16 -> "Afternoon"
        else -> "Evening"
    }
