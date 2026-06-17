package da.chelimo.sharecost.ui.screen.group

import androidx.compose.ui.graphics.vector.ImageVector
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.expense.ExpenseCategory
import da.chelimo.sharecost.ui.components.icon.ScIcons

/** Category → line icon for the Balances "Spending by category" card (icons live in the UI layer). */
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

/**
 * Aggregate spend by category for the Balances tab (F2). Uncategorised expenses fold into "Other".
 * Amounts are summed in raw subunits (a single-base-currency approximation — fine for the breakdown
 * card); the largest slice sorts first and [CategorySpendUi.fraction] is its share of the total.
 */
fun buildCategorySpend(expenses: List<Expense>): List<CategorySpendUi> {
    val byCategory = expenses
        .filter { it.amountSubunits > 0 }
        .groupBy { ExpenseCategory.fromId(it.categoryId) ?: ExpenseCategory.OTHER }
        .mapValues { (_, list) -> list.sumOf { it.amountSubunits } }
    val total = byCategory.values.sum().takeIf { it > 0 } ?: return emptyList()
    return byCategory.entries
        .sortedByDescending { it.value }
        .map { (category, subunits) ->
            CategorySpendUi(
                icon = categoryIcon(category),
                label = category.label,
                amount = subunits / 100.0,
                fraction = (subunits.toDouble() / total).toFloat(),
            )
        }
}
