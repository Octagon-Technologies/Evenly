package app.splitevenly.domain.receipt

/**
 * What kind of spend a receipt or transaction was, as the scan classified it. Only used to name the expense.
 * The itemized scan only ever sends the first six; the quick read ("Split one amount") can send any of them.
 */
enum class VenueKind {
    RESTAURANT, CAFE, BAR, GROCERY, SHOP, OTHER,
    RIDE, FLIGHT, TRANSIT, FUEL, PARKING, LODGING, ENTERTAINMENT, SUBSCRIPTION, UTILITY, HEALTH,
}

/** The scan's wire value ("restaurant") to a [VenueKind], or null for anything unknown. */
fun venueKindOf(wire: String?): VenueKind? = VenueKind.entries.firstOrNull { it.name.equals(wire, ignoreCase = true) }

/**
 * A suggested expense name from the receipt header: "Dinner at Tavolo", "Uber ride", "Netflix subscription".
 *
 * Deterministic on purpose. The scan model only reads facts (name, kind of spend, printed time); the
 * wording is a fixed table here so the same receipt always gets the same name and it can be tested.
 * [printedHour] wins over [fallbackHour] (the phone's local hour at scan time), since people often scan a
 * receipt later. [modelTitle] is the quick read's own short name, used only when the table has no wording
 * for the kind. Returns null when there is nothing worth suggesting, so the name field stays empty.
 */
fun suggestBillTitle(
    merchant: String?,
    kind: VenueKind?,
    printedHour: Int?,
    fallbackHour: Int,
    modelTitle: String? = null,
): String? {
    val name = merchant?.trim()?.takeIf { it.isNotEmpty() }
    val fallback = modelTitle?.trim()?.takeIf { it.isNotEmpty() }
    val hour = (printedHour ?: fallbackHour).mod(24)
    val occasion =
        when (kind) {
            VenueKind.RESTAURANT -> mealFor(hour)
            VenueKind.CAFE -> if (hour in 5..10) "Breakfast" else "Coffee"
            VenueKind.BAR -> "Drinks"
            VenueKind.GROCERY, VenueKind.SHOP -> "${partOfDay(hour)} shopping"
            VenueKind.FUEL -> "Gas"
            VenueKind.PARKING -> "Parking"
            // These read naturally with the name first ("Uber ride"), so they return here.
            VenueKind.RIDE -> return suffixed(name, "ride", "Ride")
            VenueKind.FLIGHT -> return suffixed(name, "flight", "Flight")
            VenueKind.TRANSIT -> return suffixed(name, "fare", "Transit fare")
            VenueKind.LODGING -> return suffixed(name, "stay", "Stay")
            VenueKind.SUBSCRIPTION -> return suffixed(name, "subscription", "Subscription")
            VenueKind.UTILITY -> return suffixed(name, "bill", "Utility bill")
            VenueKind.ENTERTAINMENT, VenueKind.HEALTH, VenueKind.OTHER, null -> null
        }
    return when {
        occasion != null && name != null -> "$occasion at $name"
        occasion != null -> occasion
        else -> fallback ?: name
    }
}

/** "Uber ride", or [bare] when there is no name. Skips the noun when the name already ends in it ("Lyft Ride"). */
private fun suffixed(name: String?, noun: String, bare: String): String =
    when {
        name == null -> bare
        name.endsWith(noun, ignoreCase = true) -> name
        else -> "$name $noun"
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
