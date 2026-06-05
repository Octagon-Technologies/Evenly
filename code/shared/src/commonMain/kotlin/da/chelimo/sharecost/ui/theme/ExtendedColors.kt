package da.chelimo.sharecost.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Brand-semantic colors Material 3 has no role for (06 §4.3). Exposed through a CompositionLocal,
 * per the official "custom design systems in Compose" guidance — NOT a parallel token system.
 * Access via `ShareCostTheme.colors.positive`.
 */
@Immutable
data class ExtendedColors(
    val positive: Color,          // you are owed / credit
    val negative: Color,          // you owe / debit
    val warning: Color,           // approaching storage cap, soft alerts
    val textMuted: Color,         // tertiary text below onSurfaceVariant
    val reconcileOutline: Color,  // explicit green selection outline (05 §14)
)

internal val ExtendedDark = ExtendedColors(
    positive = PositiveGreen,
    negative = NegativeOrange,
    warning = WarningAmber,
    textMuted = DarkTextMuted,
    reconcileOutline = BrandGreen,
)

internal val ExtendedLight = ExtendedColors(
    positive = BrandGreenDark,
    negative = NegativeOrange,
    warning = WarningAmber,
    textMuted = LightTextMuted,
    reconcileOutline = BrandGreenDark,
)

val LocalExtendedColors = staticCompositionLocalOf<ExtendedColors> {
    error("ExtendedColors not provided — wrap content in ShareCostTheme { }")
}

/** Accessor object mirroring `MaterialTheme` usage: `ShareCostTheme.colors.positive`. */
object ShareCostTheme {
    val colors: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
}
