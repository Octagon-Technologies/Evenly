package app.splitevenly.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.platform.isIOS
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Button hierarchy, mapped to Material 3 on Android and the HIG on iOS
 * (design/brainstorm/button-states.html, ios-secondary-buttons.html).
 *
 * - [Primary]: filled blue (M3 Filled / iOS prominent).
 * - [Secondary]: hairline outline on both OSes. Use it for side-by-side rows and for stacked
 *   choices that are equally valid ("Start a trip group" under "Split a one-time bill").
 * - [Dismiss]: backs out of the action stacked directly above it ("Cancel", "Keep editing").
 *   Plain text on iOS, which is Apple's own sheet and setup-screen pattern; outlined on Android.
 *   In a side-by-side row use [Secondary] instead, or the row reads as one button and a stray label.
 * - [Tonal], [Text], [Danger]: soft fill, label only, red outline.
 *
 * No variant casts a shadow. Neither guideline puts elevation on a button, and Compose's shadow
 * on iOS draws as a hard grey rim rather than a lift.
 */
enum class ButtonVariant { Primary, Secondary, Dismiss, Tonal, Text, Danger }

/** iOS dims a plain (shapeless) button's label to this while pressed. */
private const val IOS_PLAIN_PRESSED_ALPHA = 0.35f

private data class BtnStyle(
    val bg: Color,
    val fg: Color,
    val border: Color = Color.Transparent,
    val borderWidth: Dp = 0.dp,
    val filled: Boolean = false,
) {
    val plain: Boolean get() = bg == Color.Transparent && borderWidth == 0.dp
}

/** Slot-based button for content beyond a label and icon (a spinner, a two-part label). */
@Composable
fun EvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
    small: Boolean = false,
    fillMaxWidth: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val c = EvenlyTheme.colors
    val ios = remember { isIOS() }
    val outline = BtnStyle(Color.Transparent, if (enabled) c.blueText else c.disabledInk, c.borderStrong, 1.dp)
    val style =
        when (variant) {
            ButtonVariant.Primary -> {
                if (enabled) {
                    BtnStyle(c.blue, c.onAccent, filled = true)
                } else {
                    BtnStyle(c.surface, c.disabledInk, c.border, 1.dp)
                }
            }

            ButtonVariant.Secondary -> {
                outline
            }

            ButtonVariant.Dismiss -> {
                if (ios) BtnStyle(Color.Transparent, if (enabled) c.blueText else c.disabledInk) else outline
            }

            // Deep CTA blue sinks into the dark tonal surface, so the label goes white there.
            ButtonVariant.Tonal -> {
                BtnStyle(
                    c.blueTint,
                    if (!enabled) {
                        c.disabledInk
                    } else if (c.isDark) {
                        c.onAccent
                    } else {
                        c.blue
                    },
                )
            }

            // Foreground blue: c.blue is a fill and sinks into the dark page as text.
            ButtonVariant.Text -> {
                BtnStyle(Color.Transparent, c.blueText)
            }

            ButtonVariant.Danger -> {
                BtnStyle(Color.Transparent, c.danger, c.danger.copy(alpha = 0.3f), 1.dp)
            }
        }
    val height =
        when {
            small -> 36.dp

            ios && style.plain -> 44.dp

            // HIG minimum hit region, with no shape drawn to pad it out
            ios -> 50.dp

            // large control size
            else -> 40.dp // M3 small (default) button
        }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // M3 Expressive: pressing morphs a round button toward square (8dp at the small size). iOS keeps the capsule.
    val corner by animateDpAsState(if (pressed && !ios) 8.dp else height / 2, label = "buttonCorner")
    val shape = RoundedCornerShape(corner)
    // Pressed feedback per platform: M3 lays a 10% label-colored state layer over every style. iOS darkens a
    // filled button, deepens a shaped one, and dims a plain label.
    val pressLayer =
        when {
            !pressed -> Color.Transparent
            ios && style.filled -> Color.Black.copy(alpha = 0.18f)
            ios && style.plain -> Color.Transparent
            else -> style.fg.copy(alpha = 0.10f)
        }
    val focusManager = LocalFocusManager.current
    // Dismiss stays full width even when plain: the label centers under the button above and the whole
    // row is the hit area.
    val autoWidth = small || variant == ButtonVariant.Text
    Row(
        modifier =
            modifier
                .then(if (!autoWidth && fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
                .height(height)
                .clip(shape)
                .background(style.bg)
                .background(pressLayer)
                .then(
                    if (style.borderWidth > 0.dp) Modifier.border(style.borderWidth, style.border, shape) else Modifier,
                ).clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
                    focusManager.clearFocus()
                    onClick()
                }.then(if (ios && style.plain && pressed) Modifier.alpha(IOS_PLAIN_PRESSED_ALPHA) else Modifier)
                .padding(
                    horizontal =
                        if (small) {
                            14.dp
                        } else if (ios) {
                            20.dp
                        } else {
                            16.dp
                        },
                ),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Slot content inherits the platform label style, so a custom label (spinner + "Saving…") matches
        // the text overload instead of hardcoding its own size.
        val t = EvenlyTheme.text
        val plainOnIos = ios && (variant == ButtonVariant.Text || variant == ButtonVariant.Dismiss)
        val label =
            (if (small) t.buttonSmall else t.button).copy(
                // iOS: 17pt Semibold (15pt for a small control), Regular for a plain button.
                // Android: M3 Label Large, 14sp Medium.
                fontSize = if (ios) (if (small) 15.sp else 17.sp) else 14.sp,
                fontWeight =
                    when {
                        plainOnIos -> FontWeight.Normal
                        ios -> FontWeight.SemiBold
                        else -> FontWeight.Medium
                    },
                letterSpacing = if (ios) (-0.2).sp else 0.1.sp,
            )
        CompositionLocalProvider(LocalContentColor provides style.fg, LocalTextStyle provides label) { content() }
    }
}

/** Text button with optional leading icon. */
@Composable
fun EvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    small: Boolean = false,
    fillMaxWidth: Boolean = true,
) {
    EvButton(onClick, modifier, variant, enabled, small, fillMaxWidth) {
        leadingIcon?.let { EvIcon(it, size = if (small) 16.dp else 20.dp, tint = LocalContentColor.current) }
        Text(text = text, color = LocalContentColor.current)
    }
}

/** `.sc-oauth` — provider sign-in button (54dp, page bg, strong hairline). */
@Composable
fun EvOAuthButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = EvenlyTheme.colors.ink,
    enabled: Boolean = true,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(shape)
                .background(c.page)
                .border(1.dp, c.borderStrong, shape)
                .then(if (enabled) Modifier.clickableClearingFocus(onClick = onClick) else Modifier)
                .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvIcon(icon, size = 22.dp, tint = if (enabled) iconTint else c.ink3)
        Text(text = label, color = if (enabled) c.ink else c.ink3, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Sign in with Google, per Google's brand guidelines: fixed fill/stroke/text colors per theme (not
 *  app tokens), the full-color "G" mark, and "Sign in with Google" wording — never tinted or relabeled
 *  like the generic [EvOAuthButton]. */
@Composable
fun EvGoogleSignInButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val isDark = EvenlyTheme.colors.isDark
    val bg = if (isDark) Color(0xFF131314) else Color(0xFFFFFFFF)
    val stroke = if (isDark) Color(0xFF8E918F) else Color(0xFF747775)
    val text = if (isDark) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(shape)
                .background(bg)
                .border(1.dp, stroke, shape)
                .then(if (enabled) Modifier.clickableClearingFocus(onClick = onClick) else Modifier)
                .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvIcon(EvIcons.GoogleColored, size = 20.dp, tint = Color.Unspecified)
        Text(text = "Sign in with Google", color = text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** Sign in with Apple, per Apple's Human Interface Guidelines: the solid black/white button pairing
 *  (black on light surfaces, white on dark, for contrast) with the official Apple mark and "Sign in
 *  with Apple" wording, never the app's blue/ink palette like the generic [EvOAuthButton]. */
@Composable
fun EvAppleSignInButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val isDark = EvenlyTheme.colors.isDark
    val bg = if (isDark) Color(0xFFFFFFFF) else Color(0xFF000000)
    val content = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(shape)
                .background(bg)
                .then(if (enabled) Modifier.clickableClearingFocus(onClick = onClick) else Modifier)
                .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvIcon(EvIcons.AppleMark, size = 20.dp, tint = content)
        Text(text = "Sign in with Apple", color = content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Blue floating action button. Position via [modifier].
 *
 * Android keeps M3's neutral FAB shadow, the one button the spec lets float. iOS has no FAB idiom
 * and no button shadows, so there it is a flat prominent fill.
 */
@Composable
fun EvFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = "Add expense",
    icon: ImageVector = EvIcons.Plus,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val ios = remember { isIOS() }
    Row(
        modifier =
            modifier
                .then(if (ios) Modifier else Modifier.shadow(elevation = 6.dp, shape = shape))
                .clip(shape)
                .background(c.blue)
                .clickableClearingFocus(onClick = onClick)
                .height(48.dp)
                .then(if (label == null) Modifier.width(48.dp) else Modifier.padding(horizontal = 16.dp)),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvIcon(icon, size = 20.dp, tint = c.onAccent)
        if (label != null) {
            Text(text = label, color = c.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Preview
@Composable
private fun EvButtonPreview() {
    EvenlyTheme(darkTheme = false) {
        Column(
            modifier = Modifier.background(EvenlyTheme.colors.page).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ButtonVariant.entries.forEach { variant ->
                EvButton(variant.name, modifier = Modifier, onClick = {}, variant = variant)
            }
            EvButton("Disabled", onClick = {}, enabled = false)
        }
    }
}
