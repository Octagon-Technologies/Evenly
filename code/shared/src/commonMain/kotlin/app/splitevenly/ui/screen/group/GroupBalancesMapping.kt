package app.splitevenly.ui.screen.group

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.expense.CategoryDefaults
import app.splitevenly.domain.expense.Expense
import app.splitevenly.domain.expense.ExpenseCategory
import app.splitevenly.domain.expense.ExpenseWithShares
import app.splitevenly.domain.expense.GroupCategory
import app.splitevenly.ui.components.icon.EvIcons

/** Category → line icon for the spending-tracker breakdown (icons live in the UI layer). */
fun categoryIcon(category: ExpenseCategory): ImageVector = when (category) {
    ExpenseCategory.FOOD -> EvIcons.Food
    ExpenseCategory.GROCERIES -> EvIcons.Cart
    ExpenseCategory.TRANSPORT -> EvIcons.Car
    ExpenseCategory.LODGING -> EvIcons.Bed
    ExpenseCategory.ENTERTAINMENT -> EvIcons.Ticket
    ExpenseCategory.SHOPPING -> EvIcons.Tag
    ExpenseCategory.UTILITIES -> EvIcons.Bolt
    ExpenseCategory.HEALTH -> EvIcons.Sparkle
    ExpenseCategory.OTHER -> EvIcons.Wallet
}

/**
 * Resolve an expense's stored [Expense.categoryId] key to one of the group's effective categories
 * (the defaults when the group hasn't customized, else its stored rows). An unknown or blank key — e.g.
 * a category that was later deleted — falls back to the group's "other", then the built-in default, so
 * a slice always has a label/icon/color.
 */
private fun resolveCategory(key: String?, categories: List<GroupCategory>): GroupCategory {
    val k = key?.takeIf { it.isNotBlank() }
    return categories.firstOrNull { it.key == k }
        ?: categories.firstOrNull { it.key == "other" }
        ?: CategoryDefaults.byKey("other")
        ?: CategoryDefaults.all.last()
}

/**
 * Fold (category, subunits) contributions into sorted [CategorySpendUi] slices. Amounts are summed in
 * raw subunits (a single-base-currency approximation — fine for the breakdown card); the largest slice
 * sorts first and [CategorySpendUi.fraction] is its share of the total. Zero-amount contributions are
 * ignored; an all-zero set yields an empty list. Slices key on [GroupCategory.key], so a *custom*
 * category gets its own slice (with its own label/icon/color) instead of bucketing into "Other".
 */
private fun foldCategorySpend(contributions: List<Pair<GroupCategory, Long>>): List<CategorySpendUi> {
    val positive = contributions.filter { it.second > 0 }
    val total = positive.sumOf { it.second }.takeIf { it > 0 } ?: return emptyList()
    return positive
        .groupBy { it.first.key }
        .map { (_, items) -> items.first().first to items.sumOf { it.second } }
        .sortedByDescending { it.second }
        .map { (category, subunits) ->
            CategorySpendUi(
                icon = CategoryCatalog.icon(category.iconToken),
                label = category.label,
                amount = subunits / 100.0,
                fraction = (subunits.toDouble() / total).toFloat(),
                color = Color(category.colorHex),
            )
        }
}

/** Whole-group spend by category (the Group toggle): each expense's full amount keyed by its category. */
fun buildCategorySpend(expenses: List<Expense>, categories: List<GroupCategory> = CategoryDefaults.all): List<CategorySpendUi> =
    foldCategorySpend(expenses.map { resolveCategory(it.categoryId, categories) to it.amountSubunits })

/**
 * The current user's spend by category (the Personal toggle): each expense contributes only [userId]'s
 * owed share. A payer with no share contributes nothing — so paying $100 you'll be repaid never counts
 * as your spend. Uses owed (not remaining) so settled expenses still count as "what you spent".
 */
fun buildPersonalCategorySpend(expenses: List<ExpenseWithShares>, userId: UserId?, categories: List<GroupCategory> = CategoryDefaults.all): List<CategorySpendUi> =
    foldCategorySpend(
        expenses.map { ews ->
            resolveCategory(ews.expense.categoryId, categories) to ews.shares.filter { it.userId == userId }.sumOf { it.owedSubunits }
        },
    )

/** One card in the spending-tracker history list: an expense name + the amount attributed to it. */
data class SpendItemUi(
    val title: String,
    val amountSubunits: Long,
    val currency: String,
    val icon: ImageVector,
)

/** Group history: every expense at its full amount (newest first — the input flow is already sorted). */
fun buildGroupHistory(expenses: List<Expense>, categories: List<GroupCategory> = CategoryDefaults.all): List<SpendItemUi> =
    expenses.map { SpendItemUi(it.title, it.amountSubunits, it.currency, CategoryCatalog.icon(resolveCategory(it.categoryId, categories).iconToken)) }

/**
 * Personal history: one card per expense the user actually participates in, showing their owed share.
 * Expenses where the user's share is 0 (incl. payer-only) are omitted. Preserves input order (newest first).
 */
fun buildPersonalHistory(expenses: List<ExpenseWithShares>, userId: UserId?, categories: List<GroupCategory> = CategoryDefaults.all): List<SpendItemUi> =
    expenses.mapNotNull { ews ->
        val owed = ews.shares.filter { it.userId == userId }.sumOf { it.owedSubunits }
        if (owed <= 0) null
        else SpendItemUi(ews.expense.title, owed, ews.expense.currency, CategoryCatalog.icon(resolveCategory(ews.expense.categoryId, categories).iconToken))
    }
