package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import da.chelimo.sharecost.ui.screen.auth.MagicLinkScreen
import da.chelimo.sharecost.ui.screen.auth.OnboardingScreen
import da.chelimo.sharecost.ui.screen.expense.AddExpenseScreen
import da.chelimo.sharecost.ui.screen.expense.ExpenseDetailScreen
import da.chelimo.sharecost.ui.screen.group.FilterRoute
import da.chelimo.sharecost.ui.screen.group.GroupHomeScreen
import da.chelimo.sharecost.ui.screen.group.IncludeMemberRoute
import da.chelimo.sharecost.ui.screen.group.SearchRoute
import da.chelimo.sharecost.ui.screen.home.HomeScreen
import da.chelimo.sharecost.ui.screen.home.NewGroupSheet
import da.chelimo.sharecost.ui.screen.settings.GroupSettingsScreen
import da.chelimo.sharecost.ui.screen.settings.ProfileScreen
import da.chelimo.sharecost.ui.screen.settle.DeepLinkConfirmSheet
import da.chelimo.sharecost.ui.screen.settle.SettlePersonScreen

/**
 * Root NavHost (06 §4.5). Screens are decoupled from navigation — each takes plain callbacks, wired
 * here to [navController]. Sheets render as full-screen destinations over a surface for the static UI;
 * dialog overlays + deep links are layered in during the wiring phase.
 */
@Composable
fun ShareCostNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    NavHost(navController = navController, startDestination = Route.SignIn, modifier = modifier) {

        // ── Auth ────────────────────────────────────────────────────────
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
                onNewGroup = { navController.navigate(Route.NewGroup()) },
                onNewGroupTemplate = { emoji, name -> navController.navigate(Route.NewGroup(emoji, name)) },
                onJoin = { navController.navigate(Route.JoinByLink) },
                onOpenArchived = { navController.navigate(Route.Archived) },
                onSignedOut = { navController.navigate(Route.SignIn) { popUpTo(Route.Home) { inclusive = true } } },
                onEditPaymentApps = { navController.navigate(Route.PaymentHandles) },
            )
        }
        composable<Route.NewGroup> { entry ->
            val r = entry.toRoute<Route.NewGroup>()
            NewGroupRoute(
                onDismiss = { navController.popBackStack() },
                onCreated = { id -> navController.navigate(Route.GroupHome(id)) { popUpTo(Route.Home) } },
                initialEmoji = r.emoji ?: "💸",
                initialName = r.name ?: "",
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
            deepLinks = listOf(navDeepLink { uriPattern = "sharecost://j/{token}" }),
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
                onAdd = { navController.navigate(Route.NewExpenseChoice(r.groupId)) },
                onOpenExpense = { navController.navigate(Route.ExpenseDetail(r.groupId, it)) },
                onSearch = { navController.navigate(Route.Search(r.groupId)) },
                onFilter = { navController.navigate(Route.Filter(r.groupId)) },
                onOpenSettings = { navController.navigate(Route.GroupSettings(r.groupId)) },
                onSettlePeer = { peer -> navController.navigate(Route.SettlePerson(r.groupId, peer)) },
                onIncludeNav = { conflictId, expenseId, memberUserId ->
                    navController.navigate(Route.IncludeMember(r.groupId, conflictId, expenseId, memberUserId))
                },
                onExport = {},
            )
        }
        composable<Route.Filter> { entry ->
            FilterRoute(groupId = entry.toRoute<Route.Filter>().groupId, onDismiss = { navController.popBackStack() })
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
                onDeleted = { navController.popBackStack() },
            )
        }
        composable<Route.AddExpense> { entry ->
            val r = entry.toRoute<Route.AddExpense>()
            AddExpenseRoute(groupId = r.groupId, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }
        composable<Route.EditExpense> { entry ->
            val r = entry.toRoute<Route.EditExpense>()
            EditExpenseRoute(groupId = r.groupId, expenseId = r.expenseId, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }

        // ── Split the bill (itemized) ───────────────────────────────────
        composable<Route.NewExpenseChoice> { entry ->
            val r = entry.toRoute<Route.NewExpenseChoice>()
            NewExpenseChoiceRoute(
                groupId = r.groupId,
                onDismiss = { navController.popBackStack() },
                onEven = { navController.navigate(Route.AddExpense(r.groupId)) { popUpTo(Route.NewExpenseChoice(r.groupId)) { inclusive = true } } },
                onSplitBill = { navController.navigate(Route.SplitBill(r.groupId)) { popUpTo(Route.NewExpenseChoice(r.groupId)) { inclusive = true } } },
            )
        }
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
