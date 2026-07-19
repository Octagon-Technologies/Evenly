package da.chelimo.sharecost.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import da.chelimo.sharecost.ui.theme.ShareCostTheme

// The "S/$" ledger glyph from design/src/screens-auth.jsx (24-unit, white stroke 2.4, round caps).
private val WordmarkGlyph: ImageVector by lazy {
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).run {
        addPath(
            pathData = PathParser().parsePathString("M12 3v18M7 8h7.5a2.5 2.5 0 010 5H7M17 16H9.5a2.5 2.5 0 010-5").toNodes(),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        build()
    }
}

/** ShareCost wordmark: blue rounded tile with the ledger glyph + "Share" (ink) "Cost" (blue). */
@Composable
fun ScWordmark(modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val colors = ShareCostTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(size * 0.34f),
    ) {
        Box(
            modifier = Modifier
                .size(size * 1.18f)
                .clip(RoundedCornerShape(size * 0.34f))
                .background(colors.blue),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = WordmarkGlyph,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(size * 0.62f),
            )
        }
        Text(
            text = buildAnnotatedString {
                append("Share")
                withStyle(SpanStyle(color = colors.blueText)) { append("Cost") }
            },
            color = colors.ink,
            fontSize = size.value.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
        )
    }
}
