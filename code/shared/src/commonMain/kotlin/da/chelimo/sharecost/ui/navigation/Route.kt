package da.chelimo.sharecost.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes (06 §4.5) — Compose Navigation Multiplatform, no string routes.
 *
 * Modeling notes:
 * - The in-group tabs (Expenses/Balances/Conflicts/Overview) are **state inside one [GroupHome]
 *   destination**, not separate destinations — back should leave the group, not cycle tabs. The
 *   [GroupHome.tab] arg lets a deep link / notification open a specific tab.
 * - Bottom-sheets and dialogs ([NewGroup], [Join], [Filter], [IncludeMember], [SettleExpense],
 *   [SettleConfirm]) are rendered as dialog destinations so the screen behind stays visible under
 *   the scrim (matching the design's dimmed backdrop).
 */
sealed interface Route {

    // ── Auth & onboarding ──────────────────────────────────────────────
    @Serializable data object SignIn : Route
    @Serializable data object MagicLink : Route
    @Serializable data object Onboarding : Route

    // ── Home ───────────────────────────────────────────────────────────
    @Serializable data object Home : Route
    @Serializable data object NewGroup : Route            // sheet
    @Serializable data object Archived : Route
    @Serializable data class Join(val token: String) : Route   // sheet + deep link

    // ── Group (single host; tabs are internal state) ───────────────────
    @Serializable data class GroupHome(val groupId: String, val tab: GroupTab = GroupTab.Expenses) : Route
    @Serializable data class Filter(val groupId: String) : Route     // sheet
    @Serializable data class Search(val groupId: String) : Route
    @Serializable data class IncludeMember(val groupId: String, val expenseId: String, val memberUserId: String) : Route // sheet

    // ── Expense ────────────────────────────────────────────────────────
    @Serializable data class ExpenseDetail(val groupId: String, val expenseId: String) : Route  // + deep link
    @Serializable data class AddExpense(val groupId: String, val draftId: String? = null) : Route

    // ── Settle ─────────────────────────────────────────────────────────
    @Serializable data class SettleExpense(val groupId: String, val expenseId: String) : Route  // sheet
    @Serializable data class SettlePerson(val groupId: String, val peerUserId: String) : Route
    @Serializable data class SettleConfirm(val groupId: String, val payeeName: String, val amountLabel: String) : Route // sheet

    // ── Settings / reconcile ───────────────────────────────────────────
    @Serializable data class GroupSettings(val groupId: String) : Route
    @Serializable data object Profile : Route
    @Serializable data class Reconcile(val groupId: String) : Route
}

/** In-group bottom-nav tabs. Conflicts is shown only when there is an unresolved count. */
@Serializable
enum class GroupTab { Expenses, Balances, Conflicts, Overview }
