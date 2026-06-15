package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.screen.auth.MagicLinkScreen
import da.chelimo.sharecost.ui.screen.auth.OnboardingScreen
import da.chelimo.sharecost.ui.screen.auth.SignInScreen
import da.chelimo.sharecost.ui.screen.expense.AddExpenseScreen
import da.chelimo.sharecost.ui.screen.expense.ExpenseDetailScreen
import da.chelimo.sharecost.ui.screen.group.FilterSheet
import da.chelimo.sharecost.ui.screen.group.GroupHomeScreen
import da.chelimo.sharecost.ui.screen.group.IncludeMemberSheet
import da.chelimo.sharecost.ui.screen.group.SearchOverlay
import da.chelimo.sharecost.ui.screen.home.ArchivedScreen
import da.chelimo.sharecost.ui.screen.home.HomeScreen
import da.chelimo.sharecost.ui.screen.home.JoinGroupSheet
import da.chelimo.sharecost.ui.screen.home.NewGroupSheet
import da.chelimo.sharecost.ui.screen.reconcile.ReconcileScreen
import da.chelimo.sharecost.ui.screen.settings.GroupSettingsScreen
import da.chelimo.sharecost.ui.screen.settings.ProfileScreen
import da.chelimo.sharecost.ui.screen.settle.DeepLinkConfirmSheet
import da.chelimo.sharecost.ui.screen.settle.SettlePersonScreen
import da.chelimo.sharecost.ui.screen.settle.SettleSingleSheet

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
            SignInScreen(onProvider = { provider ->
                if (provider == "mail") navController.navigate(Route.MagicLink) else navController.navigate(Route.Onboarding)
            })
        }
        composable<Route.MagicLink> {
            MagicLinkRoute(
                onBack = { navController.popBackStack() },
                onSent = { navController.navigate(Route.Home) { popUpTo(Route.SignIn) { inclusive = true } } },
            )
        }
        composable<Route.Onboarding> {
            OnboardingRoute(onFinished = { navController.navigate(Route.Home) { popUpTo(Route.SignIn) { inclusive = true } } })
        }

        // ── Home ────────────────────────────────────────────────────────
        composable<Route.Home> {
            HomeRoute(
                onOpenGroup = { navController.navigate(Route.GroupHome(it)) },
                onNewGroup = { navController.navigate(Route.NewGroup) },
                onOpenProfile = { navController.navigate(Route.Profile) },
            )
        }
        composable<Route.NewGroup> {
            NewGroupRoute(
                onDismiss = { navController.popBackStack() },
                onCreated = { id -> navController.navigate(Route.GroupHome(id)) { popUpTo(Route.Home) } },
            )
        }
        composable<Route.Archived> { ArchivedScreen(onBack = { navController.popBackStack() }) }
        composable<Route.Join> {
            JoinGroupSheet(
                onDismiss = { navController.popBackStack() },
                onJoin = { navController.navigate(Route.GroupHome("joined")) },
                onOpen = { navController.navigate(Route.GroupHome("joined")) },
            )
        }

        // ── Group ───────────────────────────────────────────────────────
        composable<Route.GroupHome> { entry ->
            val r = entry.toRoute<Route.GroupHome>()
            GroupHomeScreen(
                groupId = r.groupId,
                initialTab = r.tab,
                conflictCount = 0,
                onAdd = { navController.navigate(Route.AddExpense(r.groupId)) },
                onOpenExpense = { navController.navigate(Route.ExpenseDetail(r.groupId, it)) },
                onSearch = { navController.navigate(Route.Search(r.groupId)) },
                onFilter = { navController.navigate(Route.Filter(r.groupId)) },
                onOpenSettings = { navController.navigate(Route.GroupSettings(r.groupId)) },
                onSettlePeer = { peer -> navController.navigate(Route.SettlePerson(r.groupId, peer)) },
                onIncludeNav = { navController.navigate(Route.IncludeMember(r.groupId, "e1", "tyler")) },
                onExport = {},
            )
        }
        composable<Route.Filter> {
            FilterSheet(onDismiss = { navController.popBackStack() }, onApply = { navController.popBackStack() })
        }
        composable<Route.Search> { SearchOverlay(onBack = { navController.popBackStack() }) }
        composable<Route.IncludeMember> {
            IncludeMemberSheet(onDismiss = { navController.popBackStack() }, onConfirm = { navController.popBackStack() })
        }

        // ── Expense ─────────────────────────────────────────────────────
        composable<Route.ExpenseDetail> { entry ->
            val r = entry.toRoute<Route.ExpenseDetail>()
            ExpenseDetailRoute(
                groupId = r.groupId,
                expenseId = r.expenseId,
                onBack = { navController.popBackStack() },
                onSettleThis = { navController.navigate(Route.SettleExpense(r.groupId, r.expenseId)) },
            )
        }
        composable<Route.AddExpense> { entry ->
            val r = entry.toRoute<Route.AddExpense>()
            AddExpenseRoute(groupId = r.groupId, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }

        // ── Settle ──────────────────────────────────────────────────────
        composable<Route.SettleExpense> { entry ->
            val r = entry.toRoute<Route.SettleExpense>()
            SettleSingleSheet(
                onDismiss = { navController.popBackStack() },
                onOpenApp = { navController.navigate(Route.SettleConfirm(r.groupId, "Andrew", money(24.0))) },
                onMarkPaid = { navController.popBackStack() },
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
            GroupSettingsRoute(groupId = entry.toRoute<Route.GroupSettings>().groupId, onBack = { navController.popBackStack() })
        }
        composable<Route.Profile> {
            ProfileRoute(
                onBack = { navController.popBackStack() },
                onSignedOut = { navController.navigate(Route.SignIn) { popUpTo(Route.Home) { inclusive = true } } },
            )
        }
        composable<Route.Reconcile> {
            ReconcileScreen(
                onBack = { navController.popBackStack() },
                onConfirm = { navController.navigate(Route.GroupHome("1")) { popUpTo(Route.Home) } },
                onNotMe = { navController.popBackStack() },
            )
        }
    }
}
