package da.chelimo.sharecost.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Brand-semantic colors the Material 3 [androidx.compose.material3.ColorScheme] has no role for,
 * named after the design tokens so component code reads the way the CSS does (`.sc-*`). Exposed
 * through a CompositionLocal, per the official "custom design systems in Compose" guidance — access
 * via `ShareCostTheme.colors.blue`. Both light and dark instances are defined so each can remap
 * independently (the dark values are derived — see [Color]).
 *
 * Note the deliberate `success == blue`: the design uses **calm blue for settled/positive, never
 * green**.
 */
@Immutable
data class ExtendedColors(
    // primary, expressed by weight
    val blue: Color,
    val bluePressed: Color,
    val blueTint: Color,
    val blueTint2: Color,
    val onAccent: Color,          // text/icon on a blue fill
    // text ramp (3 tiers; Material gives ~2 useful)
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    // surfaces / hairlines
    val page: Color,
    val surface: Color,
    val border: Color,
    val borderStrong: Color,
    // semantic
    val success: Color,           // "Settled" — blue, not green
    val danger: Color,
    val dangerTint: Color,
    val warning: Color,           // muted slate, soft alerts / conflicts
    val warningTint: Color,
    val pending: Color,           // "Pending sync" pill
    val bannerOffline: Color,     // offline banner background
    val bannerOfflineInk: Color,  // offline banner text
    // interaction
    val selectionTint: Color,     // selected fill (reconcile / participant)
    val selectionStroke: Color,   // selected outline
    val disabledInk: Color,       // disabled primary-button label
    // skeleton shimmer stops
    val skeleton1: Color,
    val skeleton2: Color,
)

internal val ExtendedLight = ExtendedColors(
    blue = ScBlue,
    bluePressed = ScBluePressed,
    blueTint = ScBlueTint,
    blueTint2 = ScBlueTint2,
    onAccent = ScOnAccent,
    ink = ScInk,
    ink2 = ScInk2,
    ink3 = ScInk3,
    page = ScPage,
    surface = ScSurface,
    border = ScBorder,
    borderStrong = ScBorderStrong,
    success = ScBlue,
    danger = ScRed,
    dangerTint = ScRedTint,
    warning = ScAmber,
    warningTint = ScAmberTint,
    pending = ScAmber,
    bannerOffline = ScBannerOffline,
    bannerOfflineInk = ScOnAccent,
    selectionTint = ScBlueTint,
    selectionStroke = ScBlue,
    disabledInk = ScDisabledInk,
    skeleton1 = ScSkeleton1,
    skeleton2 = ScSkeleton2,
)

internal val ExtendedDark = ExtendedColors(
    blue = ScBlueDark,
    bluePressed = ScBluePressedDark,
    blueTint = ScBlueTintDark,
    blueTint2 = ScBlueTint2Dark,
    onAccent = ScOnAccent,
    ink = ScInkDark,
    ink2 = ScInk2Dark,
    ink3 = ScInk3Dark,
    page = ScPageDark,
    surface = ScSurfaceDark,
    border = ScBorderDark,
    borderStrong = ScBorderStrongDark,
    success = ScBlueDark,
    danger = ScRedDark,
    dangerTint = ScRedTintDark,
    warning = ScAmberDark,
    warningTint = ScAmberTintDark,
    pending = ScAmberDark,
    bannerOffline = ScBannerOffline,
    bannerOfflineInk = ScOnAccent,
    selectionTint = ScBlueTintDark,
    selectionStroke = ScBlueDark,
    disabledInk = ScInk3Dark,
    skeleton1 = ScSkeleton1Dark,
    skeleton2 = ScSkeleton2Dark,
)

val LocalExtendedColors = staticCompositionLocalOf<ExtendedColors> {
    error("ExtendedColors not provided — wrap content in ShareCostTheme { }")
}

/**
 * Accessor mirroring `MaterialTheme` usage: `ShareCostTheme.colors.blue`,
 * `ShareCostTheme.amounts.remaining`, `ShareCostTheme.monoFamily`.
 */
object ShareCostTheme {
    val colors: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current

    val amounts: AmountTextStyles
        @Composable @ReadOnlyComposable get() = LocalAmountTextStyles.current

    val monoFamily: FontFamily
        @Composable @ReadOnlyComposable get() = LocalMonoFontFamily.current
}
