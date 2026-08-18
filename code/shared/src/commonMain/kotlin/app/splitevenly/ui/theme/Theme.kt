package app.splitevenly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Material 3 foundation for the Evenly design system (06 §4), reworked to the blue-led, light-first
 * design (`design/`). The M3 [androidx.compose.material3.ColorScheme] is mapped from the design tokens
 * so stock Material components (ripples, text-field defaults, menus) are already on-brand; brand
 * roles Material lacks live in [ExtendedColors] and the mono amount styles in [AmountTextStyles].
 *
 * Default is **light-first** but honors the system setting (a derived dark scheme exists). Dynamic
 * color is intentionally OFF — blue carries domain meaning. The appearance setting (Profile, §10)
 * overrides [darkTheme] later.
 */
private val LightColorScheme =
    lightColorScheme(
        primary = EvBlue,
        onPrimary = EvOnAccent,
        primaryContainer = EvBlueTint,
        onPrimaryContainer = EvBluePressed,
        inversePrimary = EvBlueTint2,
        secondary = EvInk2,
        onSecondary = EvOnAccent,
        secondaryContainer = EvSurface,
        onSecondaryContainer = EvInk,
        tertiary = EvBlue,
        onTertiary = EvOnAccent,
        background = EvPage,
        onBackground = EvInk,
        surface = EvPage,
        onSurface = EvInk,
        surfaceVariant = EvSurface,
        onSurfaceVariant = EvInk2,
        surfaceContainerLowest = EvPage,
        surfaceContainerLow = EvSurface,
        surfaceContainer = EvSurface,
        surfaceContainerHigh = EvSurface,
        surfaceContainerHighest = EvSurface,
        outline = EvBorderStrong,
        outlineVariant = EvBorder,
        error = EvRed,
        onError = EvOnAccent,
        errorContainer = EvRedTint,
        onErrorContainer = EvRed,
        scrim = EvInk,
        inverseSurface = EvInk,
        inverseOnSurface = EvPage,
    )

private val DarkColorScheme =
    darkColorScheme(
        primary = EvBlueDark,
        onPrimary = EvOnAccent,
        primaryContainer = EvBlueTintDark,
        onPrimaryContainer = EvInkDark,
        inversePrimary = EvBlueTint2Dark,
        secondary = EvInk2Dark,
        onSecondary = EvPageDark,
        secondaryContainer = EvSurfaceDark,
        onSecondaryContainer = EvInkDark,
        tertiary = EvBlueDark,
        onTertiary = EvOnAccent,
        background = EvPageDark,
        onBackground = EvInkDark,
        surface = EvPageDark,
        onSurface = EvInkDark,
        surfaceVariant = EvSurfaceDark,
        onSurfaceVariant = EvInk2Dark,
        surfaceContainerLowest = EvPageDark,
        surfaceContainerLow = EvSurfaceDark,
        surfaceContainer = EvSurfaceDark,
        surfaceContainerHigh = EvSurfaceDark,
        surfaceContainerHighest = EvSurfaceDark,
        outline = EvBorderStrongDark,
        outlineVariant = EvBorderDark,
        error = EvRedDark,
        onError = EvOnAccent,
        errorContainer = EvRedTintDark,
        onErrorContainer = EvRedDark,
        scrim = EvInk,
        inverseSurface = EvInkDark,
        inverseOnSurface = EvPageDark,
    )

/**
 * The [darkTheme] actually in effect, as resolved by [EvenlyTheme] (the app's own preference, not the raw
 * OS setting). Read this rather than calling `isSystemInDarkTheme()` again wherever an *embedded native*
 * surface (not drawn by Compose) needs to be told which mode to render in, e.g. [ForceNativeDarkMode]
 * around the RevenueCat paywall.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun EvenlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) ExtendedDark else ExtendedLight

    // Keep the system-bar icon contrast in step with the in-app theme (not the OS setting).
    SystemBarsAppearance(darkTheme)

    val sans = plexSansFamily()
    val mono = plexMonoFamily()
    val typography = evenlyTypography(sans)
    val amounts = amountTextStyles(mono)

    CompositionLocalProvider(
        LocalExtendedColors provides extended,
        LocalAmountTextStyles provides amounts,
        LocalMonoFontFamily provides mono,
        LocalIsDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = EvenlyShapes,
            content = content,
        )
    }
}
