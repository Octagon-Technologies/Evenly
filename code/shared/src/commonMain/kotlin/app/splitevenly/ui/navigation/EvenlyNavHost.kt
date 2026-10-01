package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import app.splitevenly.platform.PlaceholderClaimSources
import app.splitevenly.ui.screen.auth.MagicLinkScreen
import app.splitevenly.ui.screen.auth.OnboardingScreen
import app.splitevenly.ui.screen.expense.AddExpenseScreen
import app.splitevenly.ui.screen.expense.ExpenseDetailScreen
import app.splitevenly.ui.screen.group.FilterRoute
import app.splitevenly.ui.screen.group.GroupHomeScreen
import app.splitevenly.ui.screen.group.IncludeMemberRoute
import app.splitevenly.ui.screen.group.SearchRoute
import app.splitevenly.ui.screen.home.HomeScreen
import app.splitevenly.ui.screen.home.NewGroupSheet
import app.splitevenly.ui.screen.settings.GroupSettingsScreen
import app.splitevenly.ui.screen.settings.ProfileScreen
import app.splitevenly.ui.screen.settle.SettlePersonScreen

/**
 * Root NavHost (06 §4.5). Screens are decoupled from navigation — each takes plain callbacks, wired
 * here to [navController]. Sheets render as full-screen destinations over a surface for the static UI;
 * dialog overlays + deep links are layered in during the wiring phase.
 */
@Composable
fun EvenlyNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: Route = Route.SignIn,
    modifier: Modifier = Modifier,
) {
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        // ── Auth ────────────────────────────────────────────────────────
        composable<Route.Welcome> {
            WelcomeRoute(onFinished = {
                navController.navigate(Route.SignIn) { popUpTo(Route.Welcome) { inclusive = true } }
            })
        }
        composable<Route.SignIn> {
            SignInRoute(
                onEmail = { navController.navigate(Route.MagicLink) },
                onSignedIn = { navController.navigate(Route.Home) { popUpTo(Route.SignIn) { inclusive = true } } },
            )
        }
        composable<Route.MagicLink> {
            MagicLinkRoute(
                onBack = { navController.popBackStack() },
                // New accounts onboard; returning users (existing server profile) jump straight to Home.
                onVerified = { needsOnboarding ->
                    val dest = if (needsOnboarding) Route.Onboarding else Route.Home
                    navController.navigate(dest) { popUpTo(Route.SignIn) { inclusive = true } }
                },
            )
        }
        composable<Route.Onboarding> {
            OnboardingRoute(onFinished = { navController.navigate(Route.Home) { popUpTo(Route.Onboarding) { inclusive = true } } })
        }

        // ── Home / root shell (Groups + Settings tabs) ──────────────────
        composable<Route.Home> {
            HomeGateRoute(
                onOpenGroup = { navController.navigate(Route.GroupHome(it)) },
                onNewGroup = { navController.navigate(Route.NewGroup) },
                onJoin = { navController.navigate(Route.JoinByLink) },
                onOpenArchived = { navController.navigate(Route.Archived) },
                onOpenRecentlyDeleted = { navController.navigate(Route.RecentlyDeleted) },
                onSignedOut = { navController.navigate(Route.SignIn) { popUpTo(Route.Home) { inclusive = true } } },
                onSignIn = { navController.navigate(Route.SignIn) },
                onEditPaymentApps = { navController.navigate(Route.PaymentHandles) },
                onSendFeedback = { navController.navigate(Route.Feedback) },
            )
        }
        composable<Route.NewGroup> {
            NewGroupRoute(
                onDismiss = { navController.popBackStack() },
                onCreated = { id -> navController.navigate(Route.GroupHome(id)) { popUpTo(Route.Home) } },
            )
        }
        composable<Route.Archived> { ArchivedRoute(onBack = { navController.popBackStack() }) }
        composable<Route.RecentlyDeleted> { RecentlyDeletedRoute(onBack = { navController.popBackStack() }) }
        composable<Route.JoinByLink> {
            JoinByLinkRoute(
                onDismiss = { navController.popBackStack() },
                onResolved = { token -> navController.navigate(Route.Join(token)) },
            )
        }
        composable<Route.Join>(
            deepLinks = listOf(navDeepLink { uriPattern = "splitevenly://j/{token}" }),
        ) { entry ->
            val r = entry.toRoute<Route.Join>()
            JoinRoute(
                token = r.token,
                onDismiss = { navController.popBackStack() },
                onOpenGroup = { navController.navigate(Route.GroupHome(it)) { popUpTo(Route.Home) } },
            )
        }

        // ── Group ───────────────────────────────────────────────────────
        composable<Route.GroupHome> { entry ->
            val r = entry.toRoute<Route.GroupHome>()
            GroupHomeScreen(
                groupId = r.groupId,
                initialTab = GroupTab.entries.firstOrNull { it.name == r.tab } ?: GroupTab.Expenses,
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Route.AddExpense(r.groupId)) },
                onOpenExpense = { navController.navigate(Route.ExpenseDetail(r.groupId, it)) },
                onOpenBill = { navController.navigate(Route.FinishBill(r.groupId, it)) },
                onSearch = { navController.navigate(Route.Search(r.groupId)) },
                onOpenSettings = { navController.navigate(Route.GroupSettings(r.groupId)) },
                onSettlePeer = { peer -> navController.navigate(Route.SettlePerson(r.groupId, peer)) },
                onIncludeNav = { conflictId, expenseId, memberUserId ->
                    navController.navigate(Route.IncludeMember(r.groupId, conflictId, expenseId, memberUserId))
                },
                onClaimNames = {
                    navController.navigate(Route.Reconcile(r.groupId, source = PlaceholderClaimSources.GROUP_HOME))
                },
            )
        }
        composable<Route.Search> { entry ->
            val r = entry.toRoute<Route.Search>()
            SearchRoute(
                groupId = r.groupId,
                onBack = { navController.popBackStack() },
                onOpenExpense = { navController.navigate(Route.ExpenseDetail(r.groupId, it)) },
            )
        }
        composable<Route.IncludeMember> { entry ->
            val r = entry.toRoute<Route.IncludeMember>()
            IncludeMemberRoute(
                groupId = r.groupId,
                conflictId = r.conflictId,
                expenseId = r.expenseId,
                memberUserId = r.memberUserId,
                onDismiss = { navController.popBackStack() },
                onConfirmed = { navController.popBackStack() },
            )
        }

        // ── Expense ─────────────────────────────────────────────────────
        composable<Route.ExpenseDetail> { entry ->
            val r = entry.toRoute<Route.ExpenseDetail>()
            ExpenseDetailRoute(
                groupId = r.groupId,
                expenseId = r.expenseId,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Route.EditExpense(r.groupId, r.expenseId)) },
                // The card's own Share button covers the QR path, so picking skips the chooser.
                onOpenClaim = { navController.navigate(Route.ClaimBill(r.groupId, r.expenseId)) },
                onShareItems = { navController.navigate(Route.ShareBillLink(r.groupId, r.expenseId)) },
                onEditBill = { navController.navigate(Route.SplitBill(r.groupId, r.expenseId)) },
                onDeleted = { navController.popBackStack() },
            )
        }
        composable<Route.AddExpense> { entry ->
            val r = entry.toRoute<Route.AddExpense>()
            AddExpenseRoute(
                groupId = r.groupId,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                // An itemized ("By what each had") save creates the bill in-place, then asks how people will
                // pick its items, replacing this editor on the back stack so Back lands on the group, not here.
                onCreatedBill = { newId ->
                    navController.navigate(Route.FinishBill(r.groupId, newId, justSaved = true)) {
                        popUpTo(Route.AddExpense(r.groupId)) { inclusive = true }
                    }
                },
            )
        }
        composable<Route.EditExpense> { entry ->
            val r = entry.toRoute<Route.EditExpense>()
            EditExpenseRoute(groupId = r.groupId, expenseId = r.expenseId, onBack = {
                navController.popBackStack()
            }, onSaved = { navController.popBackStack() })
        }

        // ── Split the bill (itemized) ───────────────────────────────────
        composable<Route.SplitBill> { entry ->
            val r = entry.toRoute<Route.SplitBill>()
            BillEditRoute(
                groupId = r.groupId,
                expenseId = r.expenseId,
                onBack = { navController.popBackStack() },
                onCreated = { newId ->
                    navController.navigate(Route.FinishBill(r.groupId, newId, justSaved = true)) {
                        popUpTo(Route.SplitBill(r.groupId, r.expenseId)) { inclusive = true }
                    }
                },
            )
        }
        composable<Route.FinishBill> { entry ->
            val r = entry.toRoute<Route.FinishBill>()
            BillChooserRoute(
                expenseId = r.expenseId,
                justSaved = r.justSaved,
                onBack = { navController.popBackStack() },
                onAssignOnPhone = { navController.navigate(Route.ClaimBill(r.groupId, r.expenseId)) },
                onShowQr = { navController.navigate(Route.ShareBillLink(r.groupId, r.expenseId)) },
            )
        }
        composable<Route.ClaimBill> { entry ->
            val r = entry.toRoute<Route.ClaimBill>()
            val focusRequest by entry.savedStateHandle.getStateFlow<String?>(FOCUS_ITEM_KEY, null).collectAsStateWithLifecycle()
            BillAssignRoute(
                groupId = r.groupId,
                expenseId = r.expenseId,
                focusItemId = r.focusItemId,
                focusRequest = focusRequest,
                onFocusConsumed = { entry.savedStateHandle[FOCUS_ITEM_KEY] = null },
                onBack = { navController.popBackStack() },
                onEditBill = { navController.navigate(Route.SplitBill(r.groupId, r.expenseId)) },
                onReviewEdits = { navController.navigate(Route.ReviewBillEdits(r.groupId, r.expenseId)) },
                onClaimProgress = { navController.navigate(Route.BillClaimProgress(r.groupId, r.expenseId)) },
                onShowQr = { navController.navigate(Route.ShareBillLink(r.groupId, r.expenseId)) },
                onReview = { navController.navigate(Route.BillReview(r.groupId, r.expenseId)) },
            )
        }
        composable<Route.BillReview> { entry ->
            val r = entry.toRoute<Route.BillReview>()
            BillReviewRoute(
                groupId = r.groupId,
                expenseId = r.expenseId,
                onBack = { navController.popBackStack() },
                onAssign = { itemId -> navController.openAssign(r.groupId, r.expenseId, itemId) },
                onExit = { navController.exitBillFlow(r.groupId, r.expenseId) },
            )
        }

        composable<Route.ReviewBillEdits> { entry ->
            val r = entry.toRoute<Route.ReviewBillEdits>()
            BillReviewEditsRoute(r.groupId, r.expenseId, onBack = { navController.popBackStack() })
        }
        composable<Route.BillClaimProgress> { entry ->
            val r = entry.toRoute<Route.BillClaimProgress>()
            BillClaimProgressRoute(
                groupId = r.groupId,
                expenseId = r.expenseId,
                onBack = { navController.popBackStack() },
                onShareLink = { navController.navigate(Route.ShareBillLink(r.groupId, r.expenseId)) },
                onAssignItem = { itemId -> navController.openAssign(r.groupId, r.expenseId, itemId) },
                onReview = { navController.navigate(Route.BillReview(r.groupId, r.expenseId)) },
            )
        }
        composable<Route.ShareBillLink> { entry ->
            val r = entry.toRoute<Route.ShareBillLink>()
            ShareBillLinkRoute(
                groupId = r.groupId,
                expenseId = r.expenseId,
                onBack = { navController.popBackStack() },
                // The group invite is the OTHER link (§2.9) and lives with the group, not the bill.
                onInviteToGroup = { navController.navigate(Route.GroupSettings(r.groupId)) },
                onSeeProgress = { navController.navigate(Route.BillClaimProgress(r.groupId, r.expenseId)) },
            )
        }

        // ── Settle ──────────────────────────────────────────────────────
        composable<Route.SettlePerson> { entry ->
            val r = entry.toRoute<Route.SettlePerson>()
            SettlePersonRoute(
                groupId = r.groupId,
                peerUserId = r.peerUserId,
                onBack = { navController.popBackStack() },
                onSettled = { navController.popBackStack() },
            )
        }

        // ── Settings / reconcile ────────────────────────────────────────
        composable<Route.GroupSettings> { entry ->
            val sgRoute = entry.toRoute<Route.GroupSettings>()
            val sgGroupId = sgRoute.groupId
            GroupSettingsRoute(
                groupId = sgGroupId,
                onBack = { navController.popBackStack() },
                onLeft = { navController.navigate(Route.Home) { popUpTo(Route.Home) { inclusive = true } } },
                onReconcile = {
                    navController.navigate(Route.Reconcile(sgGroupId, source = PlaceholderClaimSources.GROUP_SETTINGS))
                },
                onEditCategories = { navController.navigate(Route.EditCategories(sgGroupId)) },
            )
        }
        composable<Route.EditCategories> { entry ->
            val ecGroupId = entry.toRoute<Route.EditCategories>().groupId
            EditCategoriesRoute(
                groupId = ecGroupId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.PaymentHandles> {
            PaymentHandlesRoute(onBack = { navController.popBackStack() })
        }
        composable<Route.Feedback> {
            FeedbackRoute(onBack = { navController.popBackStack() })
        }
        composable<Route.Reconcile> { entry ->
            val r = entry.toRoute<Route.Reconcile>()
            ReconcileRoute(
                groupId = r.groupId,
                source = r.source,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
    }
}

/** SavedStateHandle key carrying an "Assign it" request back to an assign visit already on the stack. */
private const val FOCUS_ITEM_KEY = "assign_focus_item"

/**
 * Open the phone accordion for this bill, focused on [itemId]. If a visit is already on the stack
 * (review or progress was pushed from it), pop back to it and hand it the focus, so its completion
 * order survives and no duplicate assign screen sits underneath.
 */
private fun NavHostController.openAssign(
    groupId: String,
    expenseId: String,
    itemId: String?,
) {
    val existing = runCatching { getBackStackEntry<Route.ClaimBill>() }.getOrNull()
    if (existing != null && existing.toRoute<Route.ClaimBill>().expenseId == expenseId) {
        existing.savedStateHandle[FOCUS_ITEM_KEY] = itemId
        popBackStack<Route.ClaimBill>(inclusive = false)
    } else {
        navigate(Route.ClaimBill(groupId, expenseId, focusItemId = itemId))
    }
}

/**
 * Leave the bill flow onto this bill's details, with only group home underneath: Back from details
 * lands on the group even when the flow was opened from details, with no duplicate detail route.
 */
private fun NavHostController.exitBillFlow(
    groupId: String,
    expenseId: String,
) {
    navigate(Route.ExpenseDetail(groupId, expenseId)) {
        popUpTo<Route.GroupHome> { inclusive = false }
    }
}
