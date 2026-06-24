package da.chelimo.sharecost.ui.theme

import androidx.compose.runtime.Composable

/**
 * Drive the *system bar icon contrast* (clock / battery / gesture pill) from the active in-app theme,
 * not the OS dark-mode setting — so a user who forces Light while the system is Dark still gets dark
 * icons on our light bars (and vice-versa). The bar *backgrounds* are handled separately by painting
 * the theme `page` color behind them (`StatusBarScrim` + `ScBottomNav(navBarInset)`).
 *
 * Android sets the `WindowInsetsController` light/dark appearance flags. iOS is a no-op (the scrim
 * blend covers the visual need; per-VC status-bar style control isn't worth the complexity).
 */
@Composable
expect fun SystemBarsAppearance(darkTheme: Boolean)
