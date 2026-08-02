package app.splitevenly.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape scale mapped to the design's radii (8pt grid). Read via `MaterialTheme.shapes.*`.
 * Component-specific radii the scale doesn't cover are applied inline: buttons 14, FAB 18,
 * modal 22, segmented opt 9, icon tile 11; pills use [androidx.compose.foundation.shape.CircleShape].
 */
val EvenlyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),    // chips
    small = RoundedCornerShape(10.dp),        // --r-sm
    medium = RoundedCornerShape(12.dp),       // inputs
    large = RoundedCornerShape(16.dp),        // --r-card
    extraLarge = RoundedCornerShape(24.dp),   // bottom sheets
)
