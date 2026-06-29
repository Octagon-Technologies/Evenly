package da.chelimo.sharecost.domain.expense

/**
 * A category as seen by a group — either one of the built-in defaults (when the group has never
 * customized) or a stored row from the `categories` table. The UI renders from this uniform shape; it
 * doesn't care whether the group has materialized its categories yet.
 *
 * [key] is what an expense persists in `category_id` (`"food"` … for a default, a uuid for a custom
 * one). [iconToken] resolves to a vector via `ui/screen/group/CategoryCatalog`; [colorHex] is an ARGB
 * value (e.g. `0xFF2563EB`).
 */
data class GroupCategory(
    val key: String,
    val label: String,
    val iconToken: String,
    val colorHex: Long,
    val isDefault: Boolean,
    val sortOrder: Int,
)

/**
 * The built-in default categories, the single source of truth for a fresh group. These are what the UI
 * shows until a group first edits its categories, and exactly what gets materialized into the
 * `categories` table on that first edit (copy-on-write). [GroupCategory.key] mirrors the historical
 * [ExpenseCategory.id] values so expenses created before this feature keep resolving 1:1.
 *
 * Colors mirror `ui/theme/CategoryColors` so the materialized look is identical to today's; tokens
 * mirror `ui/screen/group/CategoryCatalog` icon resolution. Order is the display/sort order.
 */
object CategoryDefaults {
    val all: List<GroupCategory> = listOf(
        GroupCategory("food", "Food & Drink", "food", 0xFF38BDF8, isDefault = true, sortOrder = 0),
        GroupCategory("groceries", "Groceries", "groceries", 0xFF2DD4BF, isDefault = true, sortOrder = 1),
        GroupCategory("transport", "Transport", "transport", 0xFF818CF8, isDefault = true, sortOrder = 2),
        GroupCategory("lodging", "Lodging", "lodging", 0xFF2563EB, isDefault = true, sortOrder = 3),
        GroupCategory("entertainment", "Entertainment", "entertainment", 0xFFA78BFA, isDefault = true, sortOrder = 4),
        GroupCategory("shopping", "Shopping", "shopping", 0xFFF59E0B, isDefault = true, sortOrder = 5),
        GroupCategory("utilities", "Utilities", "utilities", 0xFF60A5FA, isDefault = true, sortOrder = 6),
        GroupCategory("health", "Health", "health", 0xFFF472B6, isDefault = true, sortOrder = 7),
        GroupCategory("other", "Other", "other", 0xFF94A3B8, isDefault = true, sortOrder = 8),
    )

    /** Resolve a stored/used [key] to a default, if it is one (used as a fallback when a group has no rows). */
    fun byKey(key: String?): GroupCategory? = key?.let { k -> all.firstOrNull { it.key == k } }
}
