package da.chelimo.sharecost.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import sharecost.shared.generated.resources.Res
import sharecost.shared.generated.resources.ibm_plex_mono_medium
import sharecost.shared.generated.resources.ibm_plex_mono_regular
import sharecost.shared.generated.resources.ibm_plex_mono_semibold
import sharecost.shared.generated.resources.ibm_plex_sans_bold
import sharecost.shared.generated.resources.ibm_plex_sans_medium
import sharecost.shared.generated.resources.ibm_plex_sans_regular
import sharecost.shared.generated.resources.ibm_plex_sans_semibold

/**
 * Typography for the design system: IBM Plex Sans for UI, IBM Plex Mono (tabular figures) for every
 * amount. Font loading needs composition (`Font(Res.font.…)` is `@Composable`), so the families and
 * the [Typography] are built inside [ShareCostTheme] and the brand amount styles are handed down via
 * [LocalAmountTextStyles] / [LocalMonoFontFamily].
 */

@Composable
internal fun plexSansFamily(): FontFamily = FontFamily(
    Font(Res.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(Res.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(Res.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    Font(Res.font.ibm_plex_sans_bold, FontWeight.Bold),
)

@Composable
internal fun plexMonoFamily(): FontFamily = FontFamily(
    Font(Res.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(Res.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(Res.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

/** Maps the design's type ramp onto the M3 type scale; every slot uses Plex Sans. */
internal fun shareCostTypography(sans: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = sans),
        displayMedium = base.displayMedium.copy(fontFamily = sans),
        displaySmall = base.displaySmall.copy(fontFamily = sans),
        headlineLarge = base.headlineLarge.copy(fontFamily = sans),
        // Large title (home) — overview "28 / 700 / -0.6"
        headlineMedium = TextStyle(fontFamily = sans, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        // Screen headings (sign-in, settle, join) — "22 / 700"
        headlineSmall = TextStyle(fontFamily = sans, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        // .sc-topbar__title — "18 / 600 / -0.2"
        titleLarge = TextStyle(fontFamily = sans, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        // .sc-empty__title / "Paid by" — "17 / 600"
        titleMedium = TextStyle(fontFamily = sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        // .sc-exp__title — "15 / 600"
        titleSmall = TextStyle(fontFamily = sans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        // body — "15 / 400"
        bodyLarge = TextStyle(fontFamily = sans, fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp),
        // .sc-exp__sub / .sc-sheet__sub — "13"
        bodyMedium = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
        // caption / .sc-tiny — "12"
        bodySmall = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
        // .sc-btn — "16 / 600"
        labelLarge = TextStyle(fontFamily = sans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        // .sc-label — "13 / 600"
        labelMedium = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        // .sc-bnav label — "11 / 600"
        labelSmall = TextStyle(fontFamily = sans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
    )
}

/**
 * Mono amount styles Material has no slot for (the design's signature "large remaining over small
 * muted original"). Colors are applied by the caller; these carry size/weight/tabular figures.
 */
@Immutable
data class AmountTextStyles(
    val remaining: TextStyle,   // .sc-amt__remain — 17 / 600 / -0.3
    val original: TextStyle,    // .sc-amt__orig   — 12, strikethrough
    val hero: TextStyle,        // expense-detail header — 40
    val input: TextStyle,       // add-expense amount   — 52
    val mono: TextStyle,        // inline mono figures (chips, totals) — 14 / 600
    val monoSmall: TextStyle,   // small inline mono — 13 / 500
)

private const val TNUM = "tnum"

internal fun amountTextStyles(mono: FontFamily): AmountTextStyles = AmountTextStyles(
    remaining = TextStyle(fontFamily = mono, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, fontFeatureSettings = TNUM),
    original = TextStyle(fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Normal, textDecoration = TextDecoration.LineThrough, fontFeatureSettings = TNUM),
    hero = TextStyle(fontFamily = mono, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp, fontFeatureSettings = TNUM),
    input = TextStyle(fontFamily = mono, fontSize = 52.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp, fontFeatureSettings = TNUM),
    mono = TextStyle(fontFamily = mono, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM),
    monoSmall = TextStyle(fontFamily = mono, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM),
)

val LocalAmountTextStyles = staticCompositionLocalOf<AmountTextStyles> {
    error("AmountTextStyles not provided — wrap content in ShareCostTheme { }")
}

val LocalMonoFontFamily = staticCompositionLocalOf<FontFamily> {
    error("Mono font family not provided — wrap content in ShareCostTheme { }")
}
