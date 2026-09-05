package app.splitevenly.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's text roles, named by **what the text is** rather than by size, and carrying colour as well
 * as size/weight. Reach for these instead of `fontSize = 13.sp` at a call site:
 * `Text(x, style = EvenlyTheme.text.description)`.
 *
 * Two things this buys that the raw `fontSize` call sites did not:
 *  - **One place to re-tune the ramp.** The sizes were transcribed 1:1 out of the web mockup, where a
 *    `15px` sub-line reads far larger than a `15.sp` one does in the hand. Correcting that meant editing
 *    542 literals across 76 files; it now means editing this file.
 *  - **Colour travels with the role.** "Description text" was `13.sp`+`ink2` in most places and
 *    `13.5.sp`+`ink3` in others, purely by accident of which screen was written first.
 *
 * Colour is baked in for roles that have exactly one sensible colour. [chip] and [badge] leave it to the
 * caller because they render on varying fills; a caller can always `.copy(color = …)` for a genuine
 * one-off without abandoning the role.
 *
 * **Adding a role is cheaper than adding a size.** If a screen needs something this list lacks, add a
 * named role here rather than reintroducing a literal, which is invisible to the next re-tune.
 */
@Immutable
data class EvTextStyles(
    /** Home's "Your Groups". The one size the owner signed off as already correct; do not re-tune it. */
    val pageTitle: TextStyle,
    /** Full-screen headings: sign-in, settle, join. */
    val screenTitle: TextStyle,
    /** A bottom sheet's own headline. */
    val sheetTitle: TextStyle,
    /** Heading inside a screen, above a block of content. */
    val sectionTitle: TextStyle,
    /** Uppercase tracked label above a card group ("ABOUT", "SHARE GROUP"). */
    val sectionLabel: TextStyle,
    /** Primary line of a settings/list row. */
    val rowTitle: TextStyle,
    /** Title of an item in a list: an expense, a member, a group card. */
    val itemTitle: TextStyle,
    /** Running prose at full emphasis. */
    val body: TextStyle,
    /** Running prose, de-emphasised: sheet blurbs, explainers. */
    val bodyMuted: TextStyle,
    /** Field label above an input. */
    val fieldLabel: TextStyle,
    /** The value side of a row ("USD", "Apartment 4B"). */
    val rowValue: TextStyle,
    /** Second line under a title. The workhorse "description" role. */
    val description: TextStyle,
    /** Third-tier support text: timestamps, counts, hints under a control. */
    val caption: TextStyle,
    /** Smallest text that is still a sentence: footnotes, disclaimers. */
    val micro: TextStyle,
    /** Pill/chip text. Colour is the caller's: these sit on tinted and solid fills alike. */
    val chip: TextStyle,
    /** All-caps flag on a card ("BEST VALUE"). Colour is the caller's, usually `onAccent`. */
    val badge: TextStyle,
    /** Bottom-nav item label. */
    val navLabel: TextStyle,
    /** Full-width button label. Colour comes from the button's own variant. */
    val button: TextStyle,
    /** Compact button label. Colour comes from the button's own variant. */
    val buttonSmall: TextStyle,
)

/**
 * Builds the roles for one theme.
 *
 * The ramp is deliberately compressed at the bottom rather than scaled uniformly: the complaint was that
 * 11-13sp support text was unreadable while 15-16sp titles were fine, so the small end rises further than
 * the large end and [EvTextStyles.pageTitle] does not move at all. Body lands at 16, near the platform
 * norm (iOS body is 17pt), rather than the 13-15 the mockup implied.
 */
internal fun evTextStyles(
    sans: FontFamily,
    colors: ExtendedColors,
): EvTextStyles = EvTextStyles(
    pageTitle = TextStyle(fontFamily = sans, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, color = colors.ink),
    screenTitle = TextStyle(fontFamily = sans, fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = colors.ink),
    sheetTitle = TextStyle(fontFamily = sans, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = colors.ink),
    sectionTitle = TextStyle(fontFamily = sans, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, color = colors.ink),
    sectionLabel = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = colors.ink3),
    rowTitle = TextStyle(fontFamily = sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, color = colors.ink),
    itemTitle = TextStyle(fontFamily = sans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp, color = colors.ink),
    body = TextStyle(fontFamily = sans, fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp, color = colors.ink),
    bodyMuted = TextStyle(fontFamily = sans, fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp, color = colors.ink2),
    fieldLabel = TextStyle(fontFamily = sans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink2),
    rowValue = TextStyle(fontFamily = sans, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = colors.ink2),
    description = TextStyle(fontFamily = sans, fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp, color = colors.ink2),
    caption = TextStyle(fontFamily = sans, fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp, color = colors.ink3),
    micro = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp, color = colors.ink3),
    chip = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    badge = TextStyle(fontFamily = sans, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    navLabel = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
    button = TextStyle(fontFamily = sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    buttonSmall = TextStyle(fontFamily = sans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
)

val LocalEvTextStyles = staticCompositionLocalOf<EvTextStyles> {
    error("EvTextStyles not provided. Wrap content in EvenlyTheme { }.")
}
