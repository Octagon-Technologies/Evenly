package app.splitevenly.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes (06 §4.5) — Compose Navigation Multiplatform, no string routes.
 *
 * Modeling notes:
 * - The in-group tabs (Expenses/Balances/Conflicts/Overview) are **state inside one [GroupHome]
 *   destination**, not separate destinations — back should leave the group, not cycle tabs. The
 *   [GroupHome.tab] arg lets a deep link / notification open a specific tab.
 * - [NewGroup] is a real full-screen destination (back arrow, no scrim) — it used to be a bottom
 *   sheet, but a sheet with no drag affordance and tap-outside-only dismissal read as broken to a
 *   first-time tester. [Join], [IncludeMember], [SettleExpense], [SettleConfirm] still
 *   render as dialog-style destinations so the screen behind stays visible under a (now lighter)
 *   scrim, but each now carries an explicit close control too — see [EvSheetScaffold]/[EvModalScaffold].
 *   The expenses-tab Filter sheet is instead shown as a local overlay inside [GroupExpensesRoute]
 *   (not a route push) so it never leaves the tab's back-stack entry.
 */
sealed interface Route {
    // ── Auth & onboarding ──────────────────────────────────────────────
    // First-launch product intro carousel — shown once before sign-in (device-local flag), distinct
    // from [Onboarding] below which is the post-auth profile-capture flow for new accounts.
    @Serializable data object Welcome : Route

    @Serializable data object SignIn : Route

    @Serializable data object MagicLink : Route

    @Serializable data object Onboarding : Route

    // ── Home ───────────────────────────────────────────────────────────
    @Serializable data object Home : Route

    @Serializable data object NewGroup : Route // full-screen

    @Serializable data object Archived : Route

    @Serializable data object RecentlyDeleted : Route

    @Serializable data object JoinByLink : Route // sheet — paste an invite manually

    @Serializable data class Join(
        val token: String,
    ) : Route // sheet + deep link

    // ── Group (single host; tabs are internal state) ───────────────────
    // [tab] is the GroupTab *name* (a plain String) rather than the enum itself: Compose Navigation
    // can only auto-derive a NavType for an enum via JVM reflection, which Kotlin/Native (iOS) lacks —
    // an enum arg here crashes the whole NavHost on iOS. String is a built-in NavType on every target.
    @Serializable data class GroupHome(
        val groupId: String,
        val tab: String = "Expenses",
    ) : Route

    @Serializable data class Search(
        val groupId: String,
    ) : Route

    @Serializable data class IncludeMember(
        val groupId: String,
        val conflictId: String,
        val expenseId: String,
        val memberUserId: String,
    ) : Route // sheet

    // ── Expense ────────────────────────────────────────────────────────
    @Serializable data class ExpenseDetail(
        val groupId: String,
        val expenseId: String,
    ) : Route // + deep link

    @Serializable data class AddExpense(
        val groupId: String,
        val draftId: String? = null,
    ) : Route

    @Serializable data class EditExpense(
        val groupId: String,
        val expenseId: String,
    ) : Route

    // ── Split the bill (itemized) ──────────────────────────────────────
    // Only used to EDIT an existing itemized bill now (from the claim screen). Creating an itemized bill
    // happens in-place on the unified Add-expense editor's "By what each had" body.
    @Serializable data class SplitBill(
        val groupId: String,
        val expenseId: String? = null,
    ) : Route // edit menu

    @Serializable data class ClaimBill(
        val groupId: String,
        val expenseId: String,
    ) : Route // live claim screen

    // ── The payer's three web-claim screens (WEB_CLAIM_SPEC.md §3.9) ───
    // All three hang off the live claim screen, not the expense detail: the phone-holder is already
    // there while the table is claiming, and that is the only moment any of them are wanted.
    @Serializable data class ReviewBillEdits(
        val groupId: String,
        val expenseId: String,
    ) : Route

    @Serializable data class BillClaimProgress(
        val groupId: String,
        val expenseId: String,
    ) : Route

    @Serializable data class ShareBillLink(
        val groupId: String,
        val expenseId: String,
    ) : Route

    // ── Settle ─────────────────────────────────────────────────────────
    @Serializable data class SettleExpense(
        val groupId: String,
        val expenseId: String,
    ) : Route // sheet

    @Serializable data class SettlePerson(
        val groupId: String,
        val peerUserId: String,
    ) : Route

    @Serializable data class SettleConfirm(
        val groupId: String,
        val payeeName: String,
        val amountLabel: String,
    ) : Route // sheet

    // ── Settings / reconcile ───────────────────────────────────────────
    // Profile/Settings is no longer a standalone route — it's the Settings tab of the root [MainShell]
    // (rendered at [Home]). PaymentHandles is still a pushed sub-screen of that tab.
    // [openPassSheet] carries the intent from the pass group picker: the person already answered "which
    // group?", so landing them on Group settings and making them find the Pro row again would ask it
    // twice. Boolean, not an enum — an enum nav arg crashes the NavHost on Kotlin/Native.
    @Serializable data class GroupSettings(
        val groupId: String,
        val openPassSheet: Boolean = false,
    ) : Route

    @Serializable data class EditCategories(
        val groupId: String,
    ) : Route

    @Serializable data object PaymentHandles : Route

    // In-app feedback (ADMIN_FEEDBACK_SPEC.md §4.1). Reached from the Settings row and from the
    // expense-detail error state, which is why it is a route rather than a sheet owned by Settings.
    @Serializable data object Feedback : Route

    // ── Evenly Pro (PRO_PASS_SPEC.md §8) ───────────────────────────────
    // [Pro] renders RevenueCat's own paywall, or the subscribed state. [ProGroupPicker] is reached only
    // from it, and only for a one-off pass: a subscription needs no group, which is the thing that makes
    // it simpler to explain. The pass SHEET is not a route — it is opened as an overlay by whichever
    // surface asked for it (group settings, the scan sheet, the export sheet, the picker), so it never
    // takes over the back stack from the screen the buyer was actually on.
    // [trigger] is analytics only (PRO_PASS_SPEC.md §12): which surface sent the user here is the
    // difference between "the scan gate converts" and "the profile row converts", and it cannot be
    // recovered after the fact. String, not an enum — an enum nav arg crashes the NavHost on Native.
    @Serializable data class Pro(
        val trigger: String = "profile",
    ) : Route

    @Serializable data object ProGroupPicker : Route

    @Serializable data class Reconcile(
        val groupId: String,
    ) : Route
}

/** In-group bottom-nav tabs. Conflicts is shown only when there is an unresolved count. */
@Serializable
enum class GroupTab { Expenses, Balances, Conflicts, Overview }
