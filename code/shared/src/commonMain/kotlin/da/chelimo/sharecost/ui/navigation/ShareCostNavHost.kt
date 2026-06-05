package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import da.chelimo.sharecost.ui.screen.home.HomeScreen

/** Root NavHost (06 §4.5). Add `composable<Route.X> { ... }` entries as screens land. */
@Composable
fun ShareCostNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Route.Home,
        modifier = modifier,
    ) {
        composable<Route.Home> {
            HomeScreen()
        }
    }
}
