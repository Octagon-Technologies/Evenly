package app.splitevenly.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import app.splitevenly.ui.components.BottomNavItem
import app.splitevenly.ui.components.EvBottomNav
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvMotion
import app.splitevenly.ui.theme.EvenlyTheme

/** App-root tabs. Internal screen state (not a nav arg) — keeps the back stack flat and Native-safe. */
enum class RootTab { Groups, People, Settings }

/**
 * Saves the active tab by name so it survives leaving the shell for a pushed sub-screen (e.g. Payment
 * apps) and coming back — otherwise popping that sub-screen would drop you on the default Groups tab
 * instead of the Settings tab you opened it from. Stored as a String (an enum saver is Native-fragile).
 */
private val RootTabSaver = Saver<RootTab, String>(save = { it.name }, restore = { RootTab.valueOf(it) })

/**
 * The app's root shell over two top-level sections — **Groups** (the home list) and **Settings**
 * (the profile/preferences page). Like [GroupHomeScreen], the tabs are *state inside one
 * destination* rather than separate routes, so Back leaves the app instead of cycling tabs, and
 * there's no enum nav-arg to crash the Native NavHost. Opening a group is a full-screen push *over*
 * this shell.
 *
 * A two-item bottom nav (Groups, People) shows on those two tabs only. Settings stays off it: the avatar
 * in each tab's top row is the way in, and Settings has a plain top-bar back button (see [ProfileScreen])
 * as the way out. While the nav shows, the content above it has the navigation-bar inset consumed, so
 * Home's own bottom pill sits on the nav rather than leaving a second gap for the home indicator.
 *
 * Chrome blends with the system bars here (task: system-bar theming): a [StatusBarScrim] paints the
 * `page` color behind the status bar, matching each tab's top app bar.
 */
@Composable
fun MainShell(
    onOpenGroup: (String) -> Unit = {},
    onOpenQuickSplit: (groupId: String, expenseId: String?) -> Unit = { id, _ -> onOpenGroup(id) },
    onNewGroup: () -> Unit = {},
    onJoin: () -> Unit = {},
    onSplitBill: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenArchived: () -> Unit = {},
    onOpenRecentlyDeleted: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onEditPaymentApps: () -> Unit = {},
    onSendFeedback: () -> Unit = {},
    onEditExpense: (groupId: String, expenseId: String, itemized: Boolean) -> Unit = { _, _, _ -> },
    onOpenPerson: (String) -> Unit = {},
) {
    val c = EvenlyTheme.colors
    var tab by rememberSaveable(stateSaver = RootTabSaver) { mutableStateOf(RootTab.Groups) }
    var backTo by rememberSaveable(stateSaver = RootTabSaver) { mutableStateOf(RootTab.Groups) }
    val showNav = tab != RootTab.Settings

    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim(c.page)
        Box(
            Modifier
                .fillMaxSize()
                .weight(1f)
                .then(if (showNav) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
        ) {
            Crossfade(targetState = tab, animationSpec = EvMotion.standard()) { current ->
                when (current) {
                    RootTab.Groups -> {
                        HomeRoute(
                            onOpenGroup = onOpenGroup,
                            onOpenQuickSplit = onOpenQuickSplit,
                            onNewGroup = onNewGroup,
                            onJoin = onJoin,
                            onSplitBill = onSplitBill,
                            onOpenHistory = onOpenHistory,
                            onOpenArchived = onOpenArchived,
                            onOpenRecentlyDeleted = onOpenRecentlyDeleted,
                            onOpenSettings = {
                                backTo = RootTab.Groups
                                tab = RootTab.Settings
                            },
                            onEditExpense = onEditExpense,
                        )
                    }

                    RootTab.People -> {
                        PeopleTabRoute(
                            onOpenPerson = onOpenPerson,
                            onOpenSettings = {
                                backTo = RootTab.People
                                tab = RootTab.Settings
                            },
                        )
                    }

                    RootTab.Settings -> {
                        ProfileRoute(
                            onBack = { tab = backTo },
                            onSignedOut = onSignedOut,
                            onSignIn = onSignIn,
                            onEditPaymentApps = onEditPaymentApps,
                            onSendFeedback = onSendFeedback,
                        )
                    }
                }
            }
        }
        if (showNav) {
            EvBottomNav(
                items =
                    listOf(
                        BottomNavItem("groups", "Groups", EvIcons.Home),
                        BottomNavItem("people", "People", EvIcons.Users),
                    ),
                selectedId = if (tab == RootTab.People) "people" else "groups",
                onSelect = { tab = if (it == "people") RootTab.People else RootTab.Groups },
                navBarInset = true,
            )
        }
    }
}
