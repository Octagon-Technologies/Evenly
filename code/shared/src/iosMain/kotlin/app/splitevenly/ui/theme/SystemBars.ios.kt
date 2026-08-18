package app.splitevenly.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIWindow
import platform.UIKit.overrideUserInterfaceStyle

@Composable
actual fun SystemBarsAppearance(darkTheme: Boolean) {
    // No-op: on iOS the status-bar background blends via StatusBarScrim, and status-bar text contrast
    // is managed by the system. Driving it per-theme needs UIViewController-level config in the host,
    // which isn't worth the complexity for the contrast win.
}

/**
 * The RevenueCat paywall is a real `UIViewController` embedded via UIKit interop, so it inherits its
 * trait collection (and therefore its dark/light rendering) from the key window rather than anything in
 * the Compose tree. Overriding the window for the lifetime of this composable is the only lever that
 * reaches it; the previous value is restored on dispose so nothing else in the app is affected once the
 * paywall closes.
 */
@Composable
actual fun ForceNativeDarkMode(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    DisposableEffect(darkTheme) {
        val window = (
            UIApplication.sharedApplication.keyWindow
                ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow
        )
        val previousStyle = window?.overrideUserInterfaceStyle
        window?.overrideUserInterfaceStyle =
            if (darkTheme) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
        onDispose {
            window?.overrideUserInterfaceStyle =
                previousStyle ?: UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
        }
    }
    content()
}
