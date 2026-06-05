package da.chelimo.sharecost.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes (06 §4.5) — Compose Navigation Multiplatform, no string routes,
 * no Voyager. Expand as screens are added.
 */
sealed interface Route {
    @Serializable data object Home : Route

    @Serializable data class GroupHome(val groupId: String) : Route

    @Serializable data class ExpenseDetail(val groupId: String, val expenseId: String) : Route

    @Serializable data class AddExpense(val groupId: String, val draftId: String? = null) : Route

    @Serializable data class Reconcile(val groupId: String) : Route
}
