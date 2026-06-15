package da.chelimo.sharecost.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Material 3 foundation for the ShareCost design system (06 §4), reworked to the blue-led, light-first
 * design (`design/`). The M3 [androidx.compose.material3.ColorScheme] is mapped from the design tokens
 * so stock Material components (ripples, text-field defaults, menus) are already on-brand; brand
 * roles Material lacks live in [ExtendedColors] and the mono amount styles in [AmountTextStyles].
 *
 * Default is **light-first** but honors the system setting (a derived dark scheme exists). Dynamic
 * color is intentionally OFF — blue carries domain meaning. The appearance setting (Profile, §10)
 * overrides [darkTheme] later.
 */
private val LightColorScheme = lightColorScheme(
    primary = ScBlue,
    onPrimary = ScOnAccent,
    primaryContainer = ScBlueTint,
    onPrimaryContainer = ScBluePressed,
    inversePrimary = ScBlueTint2,
    secondary = ScInk2,
    onSecondary = ScOnAccent,
    secondaryContainer = ScSurface,
    onSecondaryContainer = ScInk,
    tertiary = ScBlue,
    onTertiary = ScOnAccent,
    background = ScPage,
    onBackground = ScInk,
    surface = ScPage,
    onSurface = ScInk,
    surfaceVariant = ScSurface,
    onSurfaceVariant = ScInk2,
    surfaceContainerLowest = ScPage,
    surfaceContainerLow = ScSurface,
    surfaceContainer = ScSurface,
    surfaceContainerHigh = ScSurface,
    surfaceContainerHighest = ScSurface,
    outline = ScBorderStrong,
    outlineVariant = ScBorder,
    error = ScRed,
    onError = ScOnAccent,
    errorContainer = ScRedTint,
    onErrorContainer = ScRed,
    scrim = ScInk,
    inverseSurface = ScInk,
    inverseOnSurface = ScPage,
)

private val DarkColorScheme = darkColorScheme(
    primary = ScBlueDark,
    onPrimary = ScOnAccent,
    primaryContainer = ScBlueTintDark,
    onPrimaryContainer = ScInkDark,
    inversePrimary = ScBlueTint2Dark,
    secondary = ScInk2Dark,
    onSecondary = ScPageDark,
    secondaryContainer = ScSurfaceDark,
    onSecondaryContainer = ScInkDark,
    tertiary = ScBlueDark,
    onTertiary = ScOnAccent,
    background = ScPageDark,
    onBackground = ScInkDark,
    surface = ScPageDark,
    onSurface = ScInkDark,
    surfaceVariant = ScSurfaceDark,
    onSurfaceVariant = ScInk2Dark,
    surfaceContainerLowest = ScPageDark,
    surfaceContainerLow = ScSurfaceDark,
    surfaceContainer = ScSurfaceDark,
    surfaceContainerHigh = ScSurfaceDark,
    surfaceContainerHighest = ScSurfaceDark,
    outline = ScBorderStrongDark,
    outlineVariant = ScBorderDark,
    error = ScRedDark,
    onError = ScOnAccent,
    errorContainer = ScRedTintDark,
    onErrorContainer = ScRedDark,
    scrim = ScInk,
    inverseSurface = ScInkDark,
    inverseOnSurface = ScPageDark,
)

@Composable
fun ShareCostTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) ExtendedDark else ExtendedLight

    val sans = plexSansFamily()
    val mono = plexMonoFamily()
    val typography = shareCostTypography(sans)
    val amounts = amountTextStyles(mono)

    CompositionLocalProvider(
        LocalExtendedColors provides extended,
        LocalAmountTextStyles provides amounts,
        LocalMonoFontFamily provides mono,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = ShareCostShapes,
            content = content,
        )
    }
}
