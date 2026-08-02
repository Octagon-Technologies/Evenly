package app.splitevenly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

enum class ButtonVariant { Primary, Secondary, Tonal, Text, Danger, PrimarySolid }

private data class BtnStyle(val bg: Color, val fg: Color, val border: Color, val borderWidth: Float, val elevation: Dp = 0.dp)

/** Slot-based `.sc-btn` — full control for buttons that hold a spinner/custom content. */
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
    val style = when (variant) {
        // Primary hero: a WHITE chip with bold blue text. The hairline does the edge definition, so the
        // shadow only needs to *barely* whisper a lift — 1dp. Anything more reads as a hard grey halo on
        // white and looks unrealistic; the border already separates it from the page. In dark mode `page`
        // is the elevated near-ink surface, so it still reads as a lifted button.
        ButtonVariant.Primary ->
            if (enabled) BtnStyle(c.page, c.blue, c.borderStrong, 1f, elevation = 1.dp)
            else BtnStyle(c.surface, c.disabledInk, c.border, 1f)
        // In dark mode a transparent bg + blue outline barely reads against the near-black page, so
        // Secondary goes solid there (same treatment as PrimarySolid) instead of staying an outline.
        ButtonVariant.Secondary ->
            if (c.isDark) BtnStyle(c.blue, c.onAccent, Color.Transparent, 0f, elevation = 2.dp)
            else BtnStyle(Color.Transparent, c.bluePressed, c.blue, 1.5f)
        // Tonal: a soft blue fill — a clearly-secondary full-width action that still reads as a button
        // (distinct from the solid-blue Primary hero above it).
        ButtonVariant.Tonal ->
            if (enabled) BtnStyle(c.blueTint, c.blue, Color.Transparent, 0f)
            else BtnStyle(c.blueTint, c.disabledInk, Color.Transparent, 0f)
        ButtonVariant.Text -> BtnStyle(Color.Transparent, c.blue, Color.Transparent, 0f)
        ButtonVariant.Danger -> BtnStyle(Color.Transparent, c.danger, c.danger.copy(alpha = 0.3f), 1f)
        // Solid variants: for surfaces (like dark mode's near-black page) where the white-chip Primary
        // and transparent Secondary don't read as buttons at all — a solid blue fill / blue hairline
        // with white content instead.
        ButtonVariant.PrimarySolid ->
            if (enabled) BtnStyle(c.blue, c.onAccent, Color.Transparent, 0f, elevation = 2.dp)
            else BtnStyle(c.surface, c.disabledInk, c.border, 1f)
    }
    val height = when { small -> 40.dp; variant == ButtonVariant.Text -> 44.dp; else -> 52.dp }
    val shape = RoundedCornerShape(if (small) 11.dp else 14.dp)
    val autoWidth = small || variant == ButtonVariant.Text
    Row(
        modifier = modifier
            .then(if (!autoWidth && fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .then(if (style.elevation > 0.dp) Modifier.shadow(style.elevation, shape, clip = false) else Modifier)
            .clip(shape)
            .background(style.bg)
            .then(if (style.borderWidth > 0f) Modifier.border(style.borderWidth.dp, style.border, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides style.fg) { content() }
    }
}

/** Text `.sc-btn` with optional leading icon. */
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
        Text(
            text = text,
            color = LocalContentColor.current,
            fontSize = if (small) 14.sp else 16.sp,
            // The white-chip Primary needs heavier weight so the blue label stays legible on white; the
            // solid variants go a step heavier still since white-on-blue/transparent needs it.
            fontWeight = when (variant) {
                ButtonVariant.Primary -> FontWeight.Bold
                ButtonVariant.PrimarySolid -> FontWeight.ExtraBold
                else -> FontWeight.SemiBold
            },
            letterSpacing = (-0.1).sp,
        )
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
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(c.page)
            .border(1.dp, c.borderStrong, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvIcon(icon, size = 22.dp, tint = if (enabled) iconTint else c.ink3)
        Text(text = label, color = if (enabled) c.ink else c.ink3, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** `.sc-fab` — blue floating action button with the design's colored drop shadow. Position via [modifier]. */
@Composable
fun EvFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = "Add expense",
    icon: ImageVector = EvIcons.Plus,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            // Natural lift, no coloured halo: a soft neutral 2dp shadow. The blue-tinted glow read as a
            // slab hovering off the page; a plain shadow settles it in (and simply blends on the dark page,
            // where the solid-blue fill against black is lift enough).
            .shadow(elevation = 2.dp, shape = shape)
            .clip(shape)
            .background(c.blue)
            .clickable(onClick = onClick)
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
