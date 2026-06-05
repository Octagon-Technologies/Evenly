package da.chelimo.sharecost.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val DarkColors = darkColorScheme(
    primary = BrandGreen,
    onPrimary = OnBrand,
    primaryContainer = BrandGreenDark,
    onPrimaryContainer = DarkOnSurface,
    error = DangerRed,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)

private val LightColors = lightColorScheme(
    primary = BrandGreenDark,
    onPrimary = LightOnBrand,
    primaryContainer = BrandGreen,
    onPrimaryContainer = OnBrand,
    error = DangerRed,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
)

/**
 * Material 3 theme foundation (06 §4). Default theme is DARK (05 header). Dynamic color is
 * intentionally OFF — green/red carry domain meaning and must stay brand-consistent.
 */
@Composable
fun ShareCostTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val extended = if (darkTheme) ExtendedDark else ExtendedLight
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ShareCostTypography,
            shapes = ShareCostShapes,
            content = content,
        )
    }
}
