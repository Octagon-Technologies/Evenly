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
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.theme.EvMotion
import app.splitevenly.ui.theme.EvenlyTheme

/** App-root tabs. Internal screen state (not a nav arg) — keeps the back stack flat and Native-safe. */
enum class RootTab { Groups, Settings }

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
 * No bottom nav anywhere in this shell: Groups (Home) has a gear icon in its own top row (see
 * [HomeScreen]) as the only way into Settings, and Settings has a plain top-bar back button (see
 * [ProfileScreen]) as the only way out — matching the same "dense chrome reads as clutter" call
 * that dropped the sheet-and-scrim UI elsewhere.
 *
 * Chrome blends with the system bars here (task: system-bar theming): a [StatusBarScrim] paints the
 * `page` color behind the status bar, matching each tab's top app bar.
 */
@Composable
fun MainShell(
    onOpenGroup: (String) -> Unit = {},
    onNewGroup: () -> Unit = {},
    onJoin: () -> Unit = {},
    onOpenArchived: () -> Unit = {},
    onOpenRecentlyDeleted: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onEditPaymentApps: () -> Unit = {},
    onSendFeedback: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    var tab by rememberSaveable(stateSaver = RootTabSaver) { mutableStateOf(RootTab.Groups) }

    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim(c.page)
        Box(Modifier.fillMaxSize().weight(1f)) {
            Crossfade(targetState = tab, animationSpec = EvMotion.standard()) { current ->
                when (current) {
                    RootTab.Groups -> {
                        HomeRoute(
                            onOpenGroup = onOpenGroup,
                            onNewGroup = onNewGroup,
                            onJoin = onJoin,
                            onOpenArchived = onOpenArchived,
                            onOpenRecentlyDeleted = onOpenRecentlyDeleted,
                            onOpenSettings = { tab = RootTab.Settings },
                        )
                    }

                    RootTab.Settings -> {
                        ProfileRoute(
                            onBack = { tab = RootTab.Groups },
                            onSignedOut = onSignedOut,
                            onSignIn = onSignIn,
                            onEditPaymentApps = onEditPaymentApps,
                            onSendFeedback = onSendFeedback,
                        )
                    }
                }
            }
        }
    }
}
