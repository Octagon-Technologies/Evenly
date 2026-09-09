package app.splitevenly.ui.theme

import androidx.compose.runtime.Composable

@Composable
actual fun SystemBarsAppearance(darkTheme: Boolean) {
    // No-op: on iOS the status-bar background blends via StatusBarScrim, and status-bar text contrast
    // is managed by the system. Driving it per-theme needs UIViewController-level config in the host,
    // which isn't worth the complexity for the contrast win.
}
