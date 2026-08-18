package app.splitevenly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.theme.EvenlyTheme

enum class ChipVariant { Neutral, Blue, Solid, Amber, Red, Green, Credit, Ghost, Owe, Owed }

private data class ChipColors(
    val bg: Color,
    val fg: Color,
    val border: Color,
)

@Composable
private fun chipColors(variant: ChipVariant): ChipColors {
    val c = EvenlyTheme.colors
    return when (variant) {
        ChipVariant.Neutral -> {
            ChipColors(c.surface, c.ink2, c.border)
        }

        // `blueText`, not `blue`: this is a FOREGROUND (see `ui/AGENTS.md`). The fill-tuned cobalt
        // reaches only 3.1:1 against `blueTint` on the dark page, short of the 4.5:1 a 13sp label needs.
        // Identical in light mode, where `blueText == blue`.
        ChipVariant.Blue -> {
            ChipColors(c.blueTint, c.blueText, c.blueTint2)
        }

        ChipVariant.Solid -> {
            ChipColors(c.blue, c.onAccent, Color.Transparent)
        }

        ChipVariant.Amber -> {
            ChipColors(c.warningTint, c.warning, c.warning.copy(alpha = 0.18f))
        }

        ChipVariant.Red -> {
            ChipColors(c.dangerTint, c.danger, c.danger.copy(alpha = 0.22f))
        }

        ChipVariant.Green -> {
            ChipColors(c.settledTint, c.settled, c.settled.copy(alpha = 0.20f))
        }

        ChipVariant.Credit -> {
            ChipColors(c.creditTint, c.credit, c.credit.copy(alpha = 0.20f))
        }

        ChipVariant.Ghost -> {
            ChipColors(Color.Transparent, c.ink2, c.border)
        }

        // Balance amounts — you-owe (blue) / you're-owed (amber). In DARK they render as outlined pills
        // (transparent fill + colored ring + colored text) so the amount stands clearly apart from the
        // near-black background; in LIGHT they stay the original filled tint chips (identical to before).
        ChipVariant.Owe -> {
            if (c.isDark) {
                ChipColors(Color.Transparent, c.owe, c.owe)
            } else {
                ChipColors(c.blueTint, c.blue, c.blueTint2)
            }
        }

        ChipVariant.Owed -> {
            if (c.isDark) {
                ChipColors(Color.Transparent, c.credit, c.credit)
            } else {
                ChipColors(c.creditTint, c.credit, c.credit.copy(alpha = 0.20f))
            }
        }
    }
}

/** Slot-based `.sc-chip` — bg/fg/border per [variant]; height 28 (lg 34). Content inherits fg. */
@Composable
fun EvChip(
    modifier: Modifier = Modifier,
    variant: ChipVariant = ChipVariant.Neutral,
    large: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val cc = chipColors(variant)
    val radius = if (large) 10.dp else 8.dp
    Row(
        modifier =
            modifier
                .height(if (large) 34.dp else 28.dp)
                .clip(RoundedCornerShape(radius))
                .background(cc.bg)
                .then(if (cc.border == Color.Transparent) Modifier else Modifier.border(1.dp, cc.border, RoundedCornerShape(radius)))
                .padding(horizontal = if (large) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides cc.fg) { content() }
    }
}

/** Text `.sc-chip`. `mono` renders the label in IBM Plex Mono (chip amounts like `$42.00`). */
@Composable
fun EvChip(
    text: String,
    modifier: Modifier = Modifier,
    variant: ChipVariant = ChipVariant.Neutral,
    leadingIcon: ImageVector? = null,
    large: Boolean = false,
    mono: Boolean = false,
) {
    val cc = chipColors(variant)
    EvChip(modifier = modifier, variant = variant, large = large) {
        leadingIcon?.let { EvIcon(it, size = if (large) 16.dp else 14.dp, tint = cc.fg) }
        Text(
            text = text,
            color = cc.fg,
            fontSize = if (large) 14.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) EvenlyTheme.monoFamily else null,
        )
    }
}

/** `.sc-pchip` — selectable participant pill (38dp). Selected → blue tint + blue ring + pressed ink. */
@Composable
fun EvParticipantChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = EvenlyTheme.colors
    // Selected = solid primary blue + white text/icon (the check reads clearly). The old "neutral gray
    // fill + colored ink" selected style was near-invisible on the dark page. Content colour is provided
    // so untinted leading/trailing icons (the check, star, wallet) follow the foreground automatically.
    val fg = if (selected) c.onAccent else c.ink
    Row(
        modifier =
            modifier
                .height(38.dp)
                .clip(CircleShape)
                .background(if (selected) c.blue else c.surface)
                .border(1.dp, if (selected) c.blue else c.border, CircleShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            leading?.invoke()
            Text(text = text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            trailing?.invoke()
        }
    }
}
