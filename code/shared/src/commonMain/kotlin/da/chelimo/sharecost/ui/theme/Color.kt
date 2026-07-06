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
val ScBlueDark = Color(0xFF5B8DEF)        // lifted for AA on a dark page
val ScBluePressedDark = Color(0xFF3D6FD6)
val ScBlueTintDark = Color(0xFF16243F)
val ScBlueTint2Dark = Color(0xFF1E3357)
// "You owe" accent — a brighter sky-blue so the debt amount reads clearly on the dark page (the plain
// #5B8DEF sank into the navy tint and blended in). Paired with an outlined chip in dark, mirroring the
// amber "you're owed". Light mode keeps the brand blue (owe == blue there), so only dark shifts.
val ScOweDark = Color(0xFF7AA6FF)

val ScPageDark = Color(0xFF0B0F17)        // near-ink, shares the ink hue family
val ScSurfaceDark = Color(0xFF141A24)
val ScBorderDark = Color(0xFF232C3A)
val ScBorderStrongDark = Color(0xFF334052)

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
