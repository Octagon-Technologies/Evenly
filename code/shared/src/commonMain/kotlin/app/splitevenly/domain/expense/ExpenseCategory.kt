package app.splitevenly.domain.expense

/**
 * The fixed set of expense categories (02 §3.6 ships a `categories` table later; for the MVP these are
 * a stable client-side enum). [id] is what's persisted in `expenses.category_id` and synced verbatim,
 * so the values must never change once shipped. Icons live in the UI layer (categories are icon-free
 * in the domain) — see `ui/screen/group/GroupBalancesMapping`.
 */
enum class ExpenseCategory(val id: String, val label: String) {
    FOOD("food", "Food & Drink"),
    GROCERIES("groceries", "Groceries"),
    TRANSPORT("transport", "Transport"),
    LODGING("lodging", "Lodging"),
    ENTERTAINMENT("entertainment", "Entertainment"),
    SHOPPING("shopping", "Shopping"),
    UTILITIES("utilities", "Utilities"),
    HEALTH("health", "Health"),
    OTHER("other", "Other"),
    ;

    companion object {
        fun fromId(id: String?): ExpenseCategory? = id?.let { v -> entries.firstOrNull { it.id == v } }
    }
}
