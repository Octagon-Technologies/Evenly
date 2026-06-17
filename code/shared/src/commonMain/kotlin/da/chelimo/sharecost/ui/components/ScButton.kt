package da.chelimo.sharecost.ui.components

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

enum class ButtonVariant { Primary, Secondary, Text, Danger }

private data class BtnStyle(val bg: Color, val fg: Color, val border: Color, val borderWidth: Float)

/** Slot-based `.sc-btn` — full control for buttons that hold a spinner/custom content. */
@Composable
fun ScButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
    small: Boolean = false,
    fillMaxWidth: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val c = ShareCostTheme.colors
    val style = when (variant) {
        ButtonVariant.Primary ->
            if (enabled) BtnStyle(c.blue, c.onAccent, Color.Transparent, 0f)
            else BtnStyle(c.blueTint2, c.disabledInk, Color.Transparent, 0f)
        ButtonVariant.Secondary -> BtnStyle(Color.Transparent, c.bluePressed, c.blue, 1.5f)
        ButtonVariant.Text -> BtnStyle(Color.Transparent, c.blue, Color.Transparent, 0f)
        ButtonVariant.Danger -> BtnStyle(Color.Transparent, c.danger, c.danger.copy(alpha = 0.3f), 1f)
    }
    val height = when { small -> 40.dp; variant == ButtonVariant.Text -> 44.dp; else -> 52.dp }
    val shape = RoundedCornerShape(if (small) 11.dp else 14.dp)
    val autoWidth = small || variant == ButtonVariant.Text
    Row(
        modifier = modifier
            .then(if (!autoWidth && fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
            .height(height)
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
fun ScButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    small: Boolean = false,
    fillMaxWidth: Boolean = true,
) {
    ScButton(onClick, modifier, variant, enabled, small, fillMaxWidth) {
        leadingIcon?.let { ScIcon(it, size = if (small) 16.dp else 20.dp, tint = LocalContentColor.current) }
        Text(
            text = text,
            color = LocalContentColor.current,
            fontSize = if (small) 14.sp else 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
        )
    }
}

/** `.sc-oauth` — provider sign-in button (54dp, page bg, strong hairline). */
@Composable
fun ScOAuthButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = ShareCostTheme.colors.ink,
    enabled: Boolean = true,
) {
    val c = ShareCostTheme.colors
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
        ScIcon(icon, size = 22.dp, tint = if (enabled) iconTint else c.ink3)
        Text(text = label, color = if (enabled) c.ink else c.ink3, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** `.sc-fab` — blue floating action button with the design's colored drop shadow. Position via [modifier]. */
@Composable
fun ScFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = "Add expense",
    icon: ImageVector = ScIcons.Plus,
) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .shadow(elevation = 12.dp, shape = shape, ambientColor = c.blue, spotColor = c.blue)
            .clip(shape)
            .background(c.blue)
            .clickable(onClick = onClick)
            .height(56.dp)
            .then(if (label == null) Modifier.width(56.dp) else Modifier.padding(horizontal = 20.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScIcon(icon, size = 22.dp, tint = c.onAccent)
        if (label != null) {
            Text(text = label, color = c.onAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
