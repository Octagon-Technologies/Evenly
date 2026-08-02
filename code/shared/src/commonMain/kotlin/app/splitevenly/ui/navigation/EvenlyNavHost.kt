package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
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
import app.splitevenly.ui.screen.settle.DeepLinkConfirmSheet
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
            MainShell(
                onOpenGroup = { navController.navigate(Route.GroupHome(it)) },
                onNewGroup = { navController.navigate(Route.NewGroup) },
                onJoin = { navController.navigate(Route.JoinByLink) },
                onOpenArchived = { navController.navigate(Route.Archived) },
                onSignedOut = { navController.navigate(Route.SignIn) { popUpTo(Route.Home) { inclusive = true } } },
                onSignIn = { navController.navigate(Route.SignIn) },
                onEditPaymentApps = { navController.navigate(Route.PaymentHandles) },
            )
        }
        composable<Route.NewGroup> {
            NewGroupRoute(
                onDismiss = { navController.popBackStack() },
                onCreated = { id -> navController.navigate(Route.GroupHome(id)) { popUpTo(Route.Home) } },
            )
        }
        composable<Route.Archived> { ArchivedRoute(onBack = { navController.popBackStack() }) }
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
                onOpenBill = { navController.navigate(Route.ClaimBill(r.groupId, it)) },
                onSearch = { navController.navigate(Route.Search(r.groupId)) },
                onOpenSettings = { navController.navigate(Route.GroupSettings(r.groupId)) },
                onSettlePeer = { peer -> navController.navigate(Route.SettlePerson(r.groupId, peer)) },
                onIncludeNav = { conflictId, expenseId, memberUserId ->
                    navController.navigate(Route.IncludeMember(r.groupId, conflictId, expenseId, memberUserId))
                },
                onExport = {},
                onClaimNames = { navController.navigate(Route.Reconcile(r.groupId)) },
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
                onSettleThis = { navController.navigate(Route.SettleExpense(r.groupId, r.expenseId)) },
                onEdit = { navController.navigate(Route.EditExpense(r.groupId, r.expenseId)) },
                onOpenClaim = { navController.navigate(Route.ClaimBill(r.groupId, r.expenseId)) },
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
                // An itemized ("By what each had") save creates the bill in-place, then opens its live claim
                // screen — replacing this editor on the back stack so Back lands on the group, not here.
                onCreatedBill = { newId ->
                    navController.navigate(Route.ClaimBill(r.groupId, newId)) {
                        popUpTo(Route.AddExpense(r.groupId)) { inclusive = true }
                    }
                },
            )
        }
        composable<Route.EditExpense> { entry ->
            val r = entry.toRoute<Route.EditExpense>()
            EditExpenseRoute(groupId = r.groupId, expenseId = r.expenseId, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }

        // ── Split the bill (itemized) ───────────────────────────────────
        composable<Route.SplitBill> { entry ->
            val r = entry.toRoute<Route.SplitBill>()
            BillEditRoute(
                groupId = r.groupId,
                expenseId = r.expenseId,
                onBack = { navController.popBackStack() },
                onCreated = { newId ->
                    navController.navigate(Route.ClaimBill(r.groupId, newId)) {
                        popUpTo(Route.SplitBill(r.groupId, r.expenseId)) { inclusive = true }
                    }
                },
            )
        }
        composable<Route.ClaimBill> { entry ->
            val r = entry.toRoute<Route.ClaimBill>()
            BillClaimRoute(
                groupId = r.groupId,
                expenseId = r.expenseId,
                onBack = { navController.popBackStack() },
                onEditBill = { navController.navigate(Route.SplitBill(r.groupId, r.expenseId)) },
                onAskGroup = { navController.navigate(Route.ExpenseDetail(r.groupId, r.expenseId)) },
            )
        }

        // ── Settle ──────────────────────────────────────────────────────
        composable<Route.SettleExpense> { entry ->
            val r = entry.toRoute<Route.SettleExpense>()
            SettleSingleRoute(
                groupId = r.groupId,
                expenseId = r.expenseId,
                onBack = { navController.popBackStack() },
                onSettled = { navController.popBackStack() },
            )
        }
        composable<Route.SettlePerson> { entry ->
            val r = entry.toRoute<Route.SettlePerson>()
            SettlePersonRoute(
                groupId = r.groupId,
                peerUserId = r.peerUserId,
                onBack = { navController.popBackStack() },
                onSettled = { navController.popBackStack() },
            )
        }
        composable<Route.SettleConfirm> { entry ->
            val r = entry.toRoute<Route.SettleConfirm>()
            DeepLinkConfirmSheet(
                handle = "@${r.payeeName.lowercase()}",
                onDismiss = { navController.popBackStack() },
                onYes = { navController.popBackStack() },
            )
        }

        // ── Settings / reconcile ────────────────────────────────────────
        composable<Route.GroupSettings> { entry ->
            val sgGroupId = entry.toRoute<Route.GroupSettings>().groupId
            GroupSettingsRoute(
                groupId = sgGroupId,
                onBack = { navController.popBackStack() },
                onLeft = { navController.navigate(Route.Home) { popUpTo(Route.Home) { inclusive = true } } },
                onReconcile = { navController.navigate(Route.Reconcile(sgGroupId)) },
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
        composable<Route.Reconcile> { entry ->
            val r = entry.toRoute<Route.Reconcile>()
            ReconcileRoute(
                groupId = r.groupId,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
    }
}
