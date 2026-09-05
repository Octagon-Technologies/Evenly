package app.splitevenly.ui.theme

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
 * via `EvenlyTheme.colors.blue`. Both light and dark instances are defined so each can remap
 * independently (the dark values are derived — see [Color]).
 *
 * Note the deliberate `success == blue`: the design uses **calm blue for settled/positive, never
 * green**.
 */
@Immutable
data class ExtendedColors(
    // True in the dark scheme. A few components (e.g. the owe/owed amount chips) render differently by
    // theme — filled in light, outlined in dark — and read this rather than recomputing from the OS.
    val isDark: Boolean,
    // primary, expressed by weight
    val blue: Color,
    val bluePressed: Color,
    val blueText: Color,          // blue as FOREGROUND text/icon — legible on the dark page (blue==this in light)
    val blueTint: Color,
    val blueTint2: Color,
    val owe: Color,               // "you owe" accent — brighter than blue in dark so the debt reads
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
    // balance-state semantics (owner-approved, scoped exception to "never green"): see [Color]
    val settled: Color,           // "all settled up" — calm green
    val settledTint: Color,
    val settledTint2: Color,      // progress track on the settled band
    val credit: Color,            // "you're owed / paid extra" — warm amber, attention without "error"
    val creditTint: Color,
    val danger: Color,
    val dangerTint: Color,
    val warning: Color,           // muted slate, soft alerts / conflicts
    val warningTint: Color,
    val pending: Color,           // "Pending sync" pill
    val bannerOffline: Color,     // offline banner background
    val bannerOfflineInk: Color,  // offline banner text
    // interaction
    val selectionTint: Color,     // selected fill (participant chip, scan card) — neutral in dark, blue tint in light
    val selectionStroke: Color,   // selected outline — neutral in dark, blue in light
    val disabledInk: Color,       // disabled primary-button label
    // skeleton shimmer stops
    val skeleton1: Color,
    val skeleton2: Color,
)

internal val ExtendedLight = ExtendedColors(
    isDark = false,
    blue = EvBlue,
    bluePressed = EvBluePressed,
    blueText = EvBlue,            // light: foreground blue reads fine on white — same as the brand blue
    blueTint = EvBlueTint,
    blueTint2 = EvBlueTint2,
    owe = EvBlue,                 // light: owe == the brand blue (chip stays filled, unchanged)
    onAccent = EvOnAccent,
    ink = EvInk,
    ink2 = EvInk2,
    ink3 = EvInk3,
    page = EvPage,
    surface = EvSurface,
    border = EvBorder,
    borderStrong = EvBorderStrong,
    success = EvBlue,
    settled = EvGreen,
    settledTint = EvGreenTint,
    settledTint2 = EvGreenTint2,
    credit = EvCredit,
    creditTint = EvCreditTint,
    danger = EvRed,
    dangerTint = EvRedTint,
    warning = EvAmber,
    warningTint = EvAmberTint,
    pending = EvAmber,
    bannerOffline = EvBannerOffline,
    bannerOfflineInk = EvOnAccent,
    selectionTint = EvBlueTint,
    selectionStroke = EvBlue,
    disabledInk = EvDisabledInk,
    skeleton1 = EvSkeleton1,
    skeleton2 = EvSkeleton2,
)

internal val ExtendedDark = ExtendedColors(
    isDark = true,
    blue = EvBlueDark,
    bluePressed = EvBluePressedDark,
    blueText = EvBlueTextDark,    // dark: brighter sky-blue so text/icons read on the black page
    blueTint = EvBlueTintDark,
    blueTint2 = EvBlueTint2Dark,
    owe = EvOweDark,              // dark: brighter sky-blue so the outlined owe chip + label read
    onAccent = EvOnAccent,
    ink = EvInkDark,
    ink2 = EvInk2Dark,
    ink3 = EvInk3Dark,
    page = EvPageDark,
    surface = EvSurfaceDark,
    border = EvBorderDark,
    borderStrong = EvBorderStrongDark,
    success = EvBlueDark,
    settled = EvGreenDark,
    settledTint = EvGreenTintDark,
    settledTint2 = EvGreenTint2Dark,
    credit = EvCreditDark,
    creditTint = EvCreditTintDark,
    danger = EvRedDark,
    dangerTint = EvRedTintDark,
    warning = EvAmberDark,
    warningTint = EvAmberTintDark,
    pending = EvAmberDark,
    bannerOffline = EvBannerOffline,
    bannerOfflineInk = EvOnAccent,
    selectionTint = EvSelectionTintDark,
    selectionStroke = EvBorderStrongDark,
    disabledInk = EvInk3Dark,
    skeleton1 = EvSkeleton1Dark,
    skeleton2 = EvSkeleton2Dark,
)

val LocalExtendedColors = staticCompositionLocalOf<ExtendedColors> {
    error("ExtendedColors not provided — wrap content in EvenlyTheme { }")
}

/**
 * Accessor mirroring `MaterialTheme` usage: `EvenlyTheme.colors.blue`,
 * `EvenlyTheme.amounts.remaining`, `EvenlyTheme.monoFamily`.
 */
object EvenlyTheme {
    val colors: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current

    val amounts: AmountTextStyles
        @Composable @ReadOnlyComposable get() = LocalAmountTextStyles.current

    /** Semantic text roles. See [EvTextStyles]: prefer `style = EvenlyTheme.text.description` over a
     *  bare `fontSize`, so the ramp stays tunable from one file. */
    val text: EvTextStyles
        @Composable @ReadOnlyComposable get() = LocalEvTextStyles.current

    val monoFamily: FontFamily
        @Composable @ReadOnlyComposable get() = LocalMonoFontFamily.current
}
