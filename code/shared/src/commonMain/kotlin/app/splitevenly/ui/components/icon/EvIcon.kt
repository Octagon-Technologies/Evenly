package app.splitevenly.ui.components.icon

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders a [EvIcons] line icon. The vector's built-in black is replaced by [tint] (default
 * `LocalContentColor`), mirroring the web design's `currentColor` behavior — so an icon inside a
 * blue button is white, inside body text is ink, etc., with no per-icon recoloring.
 */
@Composable
fun EvIcon(
    image: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        imageVector = image,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}
