package app.splitevenly.ui.theme

import androidx.compose.runtime.Composable

/**
 * Drive the *system bar icon contrast* (clock / battery / gesture pill) from the active in-app theme,
 * not the OS dark-mode setting — so a user who forces Light while the system is Dark still gets dark
 * icons on our light bars (and vice-versa). The bar *backgrounds* are handled separately by painting
 * the theme `page` color behind them (`StatusBarScrim` + `EvBottomNav(navBarInset)`).
 *
 * Android sets the `WindowInsetsController` light/dark appearance flags. iOS is a no-op (the scrim
 * blend covers the visual need; per-VC status-bar style control isn't worth the complexity).
 */
@Composable
expect fun SystemBarsAppearance(darkTheme: Boolean)

/**
 * Force an *embedded native* surface — one the OS renders itself rather than Compose drawing it, e.g.
 * RevenueCat's `Paywall()` — to honor [darkTheme] instead of independently re-reading the raw OS setting.
 *
 * RevenueCat's paywall composable calls `isSystemInDarkTheme()` on its own, which reads the OS setting
 * directly and ignores [EvenlyTheme]'s `darkTheme` override entirely. A user who picks Light while their
 * phone is set to Dark (or vice versa) would otherwise see a paywall in the wrong mode. Android overrides
 * the `Configuration` composition local so the nested Compose read picks it up; iOS overrides the key
 * window's `overrideUserInterfaceStyle`, since the native paywall there is a real `UIViewController`
 * outside the Compose tree and inherits its trait collection from the window.
 */
@Composable
expect fun ForceNativeDarkMode(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
)
