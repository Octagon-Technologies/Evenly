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
    @Serializable data class NewGroup(val emoji: String? = null, val name: String? = null) : Route   // sheet
    @Serializable data object Archived : Route
    @Serializable data object JoinByLink : Route          // sheet — paste an invite manually
    @Serializable data class Join(val token: String) : Route   // sheet + deep link

    // ── Group (single host; tabs are internal state) ───────────────────
    // [tab] is the GroupTab *name* (a plain String) rather than the enum itself: Compose Navigation
    // can only auto-derive a NavType for an enum via JVM reflection, which Kotlin/Native (iOS) lacks —
    // an enum arg here crashes the whole NavHost on iOS. String is a built-in NavType on every target.
    @Serializable data class GroupHome(val groupId: String, val tab: String = "Expenses") : Route
    @Serializable data class Filter(val groupId: String) : Route     // sheet
    @Serializable data class Search(val groupId: String) : Route
    @Serializable data class IncludeMember(val groupId: String, val conflictId: String, val expenseId: String, val memberUserId: String) : Route // sheet

    // ── Expense ────────────────────────────────────────────────────────
    @Serializable data class ExpenseDetail(val groupId: String, val expenseId: String) : Route  // + deep link
    @Serializable data class AddExpense(val groupId: String, val draftId: String? = null) : Route
    @Serializable data class EditExpense(val groupId: String, val expenseId: String) : Route

    // ── Split the bill (itemized) ──────────────────────────────────────
    @Serializable data class NewExpenseChoice(val groupId: String) : Route   // sheet: pick expense kind
    @Serializable data class SplitBill(val groupId: String, val expenseId: String? = null) : Route  // edit menu (null = new)
    @Serializable data class ClaimBill(val groupId: String, val expenseId: String) : Route           // live claim screen

    // ── Settle ─────────────────────────────────────────────────────────
    @Serializable data class SettleExpense(val groupId: String, val expenseId: String) : Route  // sheet
    @Serializable data class SettlePerson(val groupId: String, val peerUserId: String) : Route
    @Serializable data class SettleConfirm(val groupId: String, val payeeName: String, val amountLabel: String) : Route // sheet

    // ── Settings / reconcile ───────────────────────────────────────────
    // Profile/Settings is no longer a standalone route — it's the Settings tab of the root [MainShell]
    // (rendered at [Home]). PaymentHandles is still a pushed sub-screen of that tab.
    @Serializable data class GroupSettings(val groupId: String) : Route
    @Serializable data class EditCategories(val groupId: String) : Route
    @Serializable data object PaymentHandles : Route
    @Serializable data class Reconcile(val groupId: String) : Route
}

/** In-group bottom-nav tabs. Conflicts is shown only when there is an unresolved count. */
@Serializable
enum class GroupTab { Expenses, Balances, Conflicts, Overview }
