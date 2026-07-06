package da.chelimo.sharecost.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import da.chelimo.sharecost.ui.components.BottomNavItem
import da.chelimo.sharecost.ui.components.ScBottomNav
import da.chelimo.sharecost.ui.components.StatusBarScrim
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** App-root tabs. Internal screen state (not a nav arg) — keeps the back stack flat and Native-safe. */
enum class RootTab { Groups, Settings }

/**
 * The app's root shell (Airbnb/Duolingo pattern): a persistent bottom nav over two top-level
 * sections — **Groups** (the home list) and **Settings** (the profile/preferences page). Like
 * [GroupHomeScreen], the tabs are *state inside one destination* rather than separate routes, so
 * Back leaves the app instead of cycling tabs, and there's no enum nav-arg to crash the Native
 * NavHost. Opening a group is a full-screen push *over* this shell (covering the root nav), exactly
 * as a listing/lesson covers the tab bar in Airbnb/Duolingo.
 *
 * Chrome blends with the system bars here (task: system-bar theming): a [StatusBarScrim] paints the
 * `page` color behind the status bar (matching each tab's top app bar) and [ScBottomNav]'s
 * `navBarInset` paints `page` behind the navigation bar — light and dark, both platforms.
 */
@Composable
fun MainShell(
    onOpenGroup: (String) -> Unit = {},
    onNewGroup: () -> Unit = {},
    onJoin: () -> Unit = {},
    onOpenArchived: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    onEditPaymentApps: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var tab by remember { mutableStateOf(RootTab.Groups) }
    // The Groups tab's empty state (no groups yet) hides this bar entirely and offers its own way
    // into Settings (a gear button) — see HomeScreen/HomeWelcome. Nothing to switch to while empty.
    var homeIsEmpty by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        StatusBarScrim(c.page)
        Box(Modifier.fillMaxSize().weight(1f)) {
            when (tab) {
                RootTab.Groups -> HomeRoute(
                    onOpenGroup = onOpenGroup,
                    onNewGroup = onNewGroup,
                    onJoin = onJoin,
                    onOpenArchived = onOpenArchived,
                    onOpenSettings = { tab = RootTab.Settings },
                    onEmptyStateChanged = { homeIsEmpty = it },
                )
                RootTab.Settings -> ProfileRoute(
                    onSignedOut = onSignedOut,
                    onEditPaymentApps = onEditPaymentApps,
                )
            }
        }
        if (!(tab == RootTab.Groups && homeIsEmpty)) {
            ScBottomNav(
                items = listOf(
                    BottomNavItem("groups", "Groups", ScIcons.Users),
                    BottomNavItem("settings", "Settings", ScIcons.Gear),
                ),
                selectedId = tab.name.lowercase(),
                onSelect = { id -> tab = if (id == "settings") RootTab.Settings else RootTab.Groups },
                navBarInset = true,
            )
        }
    }
}
