package app.splitevenly.domain.expense

/** One past expense in this group, as far as guessing a category needs it. */
data class CategoryHistoryEntry(
    val title: String,
    val categoryKey: String,
)

/**
 * The category to pre-select for an expense nobody has picked a category for yet, or null to leave it empty.
 *
 * Order, strongest signal first:
 * 1. **This group's history**: an earlier expense whose title starts with the same word ("Airbnb Austin"
 *    for "Airbnb deposit"). It is the only signal that knows a group's own categories ("Rent").
 * 2. **[receiptCategoryKey]**: what the receipt quick read classified, when a receipt was read.
 * 3. **A fixed keyword list** over the title's words.
 *
 * Runs on every keystroke, so it is pure string work with no I/O. A guess is only returned if [available]
 * (the group's live categories) still contains it, by key or by a built-in's label, so a group that deleted
 * "Transport" never gets it back.
 * [history] is newest first; the newest match wins.
 */
fun guessCategory(
    title: String,
    history: List<CategoryHistoryEntry>,
    receiptCategoryKey: String?,
    available: List<GroupCategory>,
): String? {
    val keys = available.mapTo(HashSet()) { it.key }
    // A built-in key the group no longer has may survive under its default label ("Transport").
    fun resolve(key: String): String? =
        if (key in keys) {
            key
        } else {
            CategoryDefaults.byKey(key)?.let { d -> available.firstOrNull { it.label.equals(d.label, ignoreCase = true) }?.key }
        }
    val words = wordsOf(title)
    val lead = words.firstOrNull { it !in WEAK_LEADS }
    if (lead != null) {
        history.firstOrNull { entry ->
            entry.categoryKey in keys && wordsOf(entry.title).firstOrNull { it !in WEAK_LEADS } == lead
        }?.let { return it.categoryKey }
    }
    receiptCategoryKey?.let(::resolve)?.let { return it }
    for (word in words) {
        resolve(KEYWORDS[word] ?: continue)?.let { return it }
    }
    return null
}

private fun wordsOf(text: String): List<String> =
    text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }

/** Filler words skipped when finding the word a title leads with ("The Airbnb" leads with "airbnb"). */
private val WEAK_LEADS =
    setOf("the", "my", "our", "a", "an", "to", "at", "for", "with", "from", "and", "of", "on", "in")

/**
 * Title word → default category key. Only built-in keys appear here; a group's custom categories are
 * reached through history. Plural and singular forms are listed separately to keep matching exact.
 */
private val KEYWORDS: Map<String, String> =
    buildMap {
        fun file(key: String, vararg words: String) = words.forEach { word -> put(word, key) }
        file(
            "food",
            "dinner", "lunch", "breakfast", "brunch", "supper", "meal", "meals", "restaurant", "cafe", "coffee",
            "pizza", "sushi", "burger", "burgers", "tacos", "drinks", "drink", "bar", "beer", "beers", "wine",
            "takeout", "takeaway", "doordash", "ubereats", "grubhub", "starbucks", "snacks", "dessert", "food",
        )
        file("groceries", "groceries", "grocery", "supermarket", "costco", "aldi", "trader", "wegmans", "kroger", "safeway", "walmart")
        file(
            "transport",
            "uber", "lyft", "taxi", "cab", "ride", "rides", "bus", "train", "metro", "subway", "septa", "amtrak",
            "flight", "flights", "airfare", "gas", "fuel", "parking", "toll", "tolls", "car", "rental", "zipcar",
        )
        file("lodging", "airbnb", "hotel", "hotels", "hostel", "motel", "vrbo", "lodging", "accommodation", "stay")
        file(
            "entertainment",
            "movie", "movies", "cinema", "concert", "tickets", "ticket", "show", "netflix", "spotify", "hulu",
            "disney", "game", "games", "bowling", "museum", "party", "club",
        )
        file("shopping", "amazon", "target", "shopping", "clothes", "shoes", "gift", "gifts", "ikea", "decor")
        file(
            "utilities",
            "rent", "electricity", "electric", "power", "water", "internet", "wifi", "utilities", "utility",
            "phone", "heating", "trash",
        )
        file("health", "pharmacy", "cvs", "walgreens", "doctor", "medicine", "gym", "dentist", "clinic", "hospital")
    }
