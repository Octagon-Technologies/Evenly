package da.chelimo.sharecost.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens for the ShareCost "blue-led monochrome" system (see `design/src/design.css :root`).
 * One chromatic color — blue — expressed by weight; everything else is a neutral ink/surface ramp.
 * Principle: *calm blue for success — never green.*
 *
 * The **light** values are verbatim from the design export. The **dark** values are derived (the
 * export ships light only): same hue identity, inverted luminance, blue lifted so it keeps WCAG AA
 * contrast on a dark page, and the soft tints become low-luminance washes of the same hue.
 */

// ── Light (design export, verbatim) ─────────────────────────────────────────
val ScBlue = Color(0xFF2563EB)            // --blue (the only chroma)
val ScBluePressed = Color(0xFF1E40AF)     // --blue-press
val ScBlueTint = Color(0xFFEFF4FF)        // --blue-tint
val ScBlueTint2 = Color(0xFFDBE6FF)       // --blue-tint-2

val ScPage = Color(0xFFFFFFFF)            // --page
val ScSurface = Color(0xFFF6F8FB)         // --surface
val ScBorder = Color(0xFFE6EAF0)          // --border
val ScBorderStrong = Color(0xFFD6DCE6)    // --border-strong

val ScInk = Color(0xFF0B1220)             // --ink
val ScInk2 = Color(0xFF5B6577)            // --ink-2
val ScInk3 = Color(0xFF9AA3B2)            // --ink-3

val ScRed = Color(0xFFE0686B)             // --red (reserved: errors / "off by")
val ScRedTint = Color(0xFFFDEEED)         // --red-tint
val ScAmber = Color(0xFF687590)           // --amber (a muted slate, not yellow)
val ScAmberTint = Color(0xFFEEF1F8)       // --amber-tint

// Balance-state semantics (product decision, owner-approved): a deliberate, scoped exception to the
// "never green" principle above — used ONLY for settle-up states, not general success. `settled` = a
// calm green for "all square"; `credit` = a warm burnt-amber for "you're owed / paid extra" — it draws
// attention without red's "you did something wrong" connotation (overpaying is in the user's favor).
val ScGreen = Color(0xFF16A34A)           // settled accent
val ScGreenTint = Color(0xFFE7F4EC)       // settled fill
val ScGreenTint2 = Color(0xFFC7E7D2)      // settled progress track
val ScCredit = Color(0xFFC2410C)          // in-credit / overpaid accent (burnt amber, not red)
val ScCreditTint = Color(0xFFFFF1E6)      // credit fill

val ScOnAccent = Color(0xFFFFFFFF)        // text/icon on a blue fill
val ScDisabledInk = Color(0xFFA9BEE8)     // disabled primary-button label
val ScBannerOffline = Color(0xFF2B3240)   // .sc-banner--offline background
val ScSkeleton1 = Color(0xFFEDF0F5)       // shimmer trough
val ScSkeleton2 = Color(0xFFF6F8FB)       // shimmer crest

// ── Dark (derived) ──────────────────────────────────────────────────────────
// "Cobalt, richer" — chosen from a CTA color study (owner-approved letter N): a purer, less
// violet-shifted cobalt than the original #5B8DEF, which read as washed-out/pastel on a true-black
// page. Every CTA/major button routes through this one token (ScButton, ScFab, ScChip's blue variant,
// ScForm's selected radio/checkbox fill), so this single change re-tints all of them in dark mode.
val ScBlueDark = Color(0xFF3A52D6)
val ScBluePressedDark = Color(0xFF2C3FA8)
// Blue used as FOREGROUND (text/icon) on the dark page — NOT the deep CTA cobalt above. The cobalt is
// tuned as a *fill* (white sits on it); as text on a near-black surface it only reaches ~3:1 and reads
// as "blends into the background". This brighter sky-blue clears AA for text/icons on the dark page.
// Light mode has no such problem, so `blueText` == ScBlue there (foreground blue only shifts in dark).
val ScBlueTextDark = Color(0xFF7AA6FF)
// The wash source for every dark-mode surface/border/tile below: a brighter sky-blue than the CTA
// blue above, used ONLY at low alpha over the black page — never painted solid. Kept distinct from
// ScBlueDark so buttons stay the deliberately deep/rich cobalt while elevated surfaces stay legible
// (a wash this low-alpha needs a brighter source hue to read as "tinted," not just "black").
val ScTintBlueDark = Color(0xFF6E9BFF)
val ScBlueTintDark = ScTintBlueDark.copy(alpha = 0.10f)
val ScBlueTint2Dark = ScTintBlueDark.copy(alpha = 0.16f)
// "You owe" accent — a brighter sky-blue so the debt amount reads clearly on the dark page (the plain
// #5B8DEF sank into the navy tint and blended in). Paired with an outlined chip in dark, mirroring the
// amber "you're owed". Light mode keeps the brand blue (owe == blue there), so only dark shifts.
val ScOweDark = Color(0xFF7AA6FF)

val ScPageDark = Color(0xFF000000)        // genuinely black (was near-black 0xFF0A0A0C)
val ScSurfaceDark = ScTintBlueDark.copy(alpha = 0.06f)
val ScBorderDark = ScTintBlueDark.copy(alpha = 0.16f)
val ScBorderStrongDark = ScTintBlueDark.copy(alpha = 0.26f)
// Neutral gray for the "selection" tokens (chip-selected fill/stroke, scan card) — deliberately NOT
// blue/navy in dark mode; see ExtendedColors.selectionTint/selectionStroke.
val ScSelectionTintDark = Color(0xFF1C1C1E)

val ScInkDark = Color(0xFFE7ECF3)
val ScInk2Dark = Color(0xFF9BA6B8)
val ScInk3Dark = Color(0xFF67738A)

val ScRedDark = Color(0xFFE98487)
val ScRedTintDark = Color(0xFF2A1718)
val ScAmberDark = Color(0xFF8A95AD)
val ScAmberTintDark = Color(0xFF1B2230)

val ScGreenDark = Color(0xFF4ADE80)       // lifted for AA on a dark page
val ScGreenTintDark = Color(0xFF13271B)
val ScGreenTint2Dark = Color(0xFF1E3A2A)
val ScCreditDark = Color(0xFFFB923C)      // lifted warm amber for dark
val ScCreditTintDark = Color(0xFF2A1810)

val ScSkeleton1Dark = Color(0xFF1A2230)
val ScSkeleton2Dark = Color(0xFF222C3C)
