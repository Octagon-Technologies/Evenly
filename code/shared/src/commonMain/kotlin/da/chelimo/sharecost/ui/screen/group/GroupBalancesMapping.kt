package da.chelimo.sharecost.ui.screen.group

import androidx.compose.ui.graphics.vector.ImageVector
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.expense.ExpenseCategory
import da.chelimo.sharecost.domain.expense.ExpenseWithShares
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.categoryColor

/** Category → line icon for the spending-tracker breakdown (icons live in the UI layer). */
fun categoryIcon(category: ExpenseCategory): ImageVector = when (category) {
    ExpenseCategory.FOOD -> ScIcons.Food
    ExpenseCategory.GROCERIES -> ScIcons.Cart
    ExpenseCategory.TRANSPORT -> ScIcons.Car
    ExpenseCategory.LODGING -> ScIcons.Bed
    ExpenseCategory.ENTERTAINMENT -> ScIcons.Ticket
    ExpenseCategory.SHOPPING -> ScIcons.Tag
    ExpenseCategory.UTILITIES -> ScIcons.Bolt
    ExpenseCategory.HEALTH -> ScIcons.Sparkle
    ExpenseCategory.OTHER -> ScIcons.Wallet
}

private fun Expense.category(): ExpenseCategory = ExpenseCategory.fromId(categoryId) ?: ExpenseCategory.OTHER

/**
 * Fold (category, subunits) contributions into sorted [CategorySpendUi] slices. Amounts are summed in
 * raw subunits (a single-base-currency approximation — fine for the breakdown card); the largest slice
 * sorts first and [CategorySpendUi.fraction] is its share of the total. Zero-amount contributions are
 * ignored; an all-zero set yields an empty list.
 */
private fun foldCategorySpend(contributions: List<Pair<ExpenseCategory, Long>>): List<CategorySpendUi> {
    val byCategory = contributions
        .filter { it.second > 0 }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, amounts) -> amounts.sum() }
    val total = byCategory.values.sum().takeIf { it > 0 } ?: return emptyList()
    return byCategory.entries
        .sortedByDescending { it.value }
        .map { (category, subunits) ->
            CategorySpendUi(
                icon = categoryIcon(category),
                label = category.label,
                amount = subunits / 100.0,
                fraction = (subunits.toDouble() / total).toFloat(),
                color = categoryColor(category),
            )
        }
}

/** Whole-group spend by category (the Group toggle): each expense's full amount keyed by its category. */
fun buildCategorySpend(expenses: List<Expense>): List<CategorySpendUi> =
    foldCategorySpend(expenses.map { it.category() to it.amountSubunits })

/**
 * The current user's spend by category (the Personal toggle): each expense contributes only [userId]'s
 * owed share. A payer with no share contributes nothing — so paying $100 you'll be repaid never counts
 * as your spend. Uses owed (not remaining) so settled expenses still count as "what you spent".
 */
fun buildPersonalCategorySpend(expenses: List<ExpenseWithShares>, userId: UserId?): List<CategorySpendUi> =
    foldCategorySpend(
        expenses.map { ews ->
            ews.expense.category() to ews.shares.filter { it.userId == userId }.sumOf { it.owedSubunits }
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
fun buildGroupHistory(expenses: List<Expense>): List<SpendItemUi> =
    expenses.map { SpendItemUi(it.title, it.amountSubunits, it.currency, categoryIcon(it.category())) }

/**
 * Personal history: one card per expense the user actually participates in, showing their owed share.
 * Expenses where the user's share is 0 (incl. payer-only) are omitted. Preserves input order (newest first).
 */
fun buildPersonalHistory(expenses: List<ExpenseWithShares>, userId: UserId?): List<SpendItemUi> =
    expenses.mapNotNull { ews ->
        val owed = ews.shares.filter { it.userId == userId }.sumOf { it.owedSubunits }
        if (owed <= 0) null
        else SpendItemUi(ews.expense.title, owed, ews.expense.currency, categoryIcon(ews.expense.category()))
    }
