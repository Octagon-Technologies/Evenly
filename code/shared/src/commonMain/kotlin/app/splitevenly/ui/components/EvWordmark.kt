package app.splitevenly.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * The Evenly feather, traced from `design/logo-inspo/Evenly Logo.png`.
 *
 * One filled outline on a 100-unit square viewport, drawn with the current tint. The white quill
 * slit is an open notch in the same outline, not a second path, so the mark stays a single-colour
 * silhouette: it reads as the brand on a blue fill, on the page, or knocked out on a photo.
 * The Android launcher and iOS icon assets are generated from this same outline.
 */
val EvenlyMark: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 100f,
        viewportHeight = 100f,
    ).run {
        addPath(
            pathData = PathParser().parsePathString(EVENLY_MARK_PATH).toNodes(),
            fill = SolidColor(Color.White),
        )
        build()
    }
}

/**
 * Feather + "Evenly", the horizontal lock-up used on sign-in and anywhere the app names itself.
 *
 * [size] is the cap height of the wordmark text; the feather is sized from it so the two stay in
 * proportion at any scale. Pass [tint]/[textColor] to knock the whole lock-up out in white on the
 * blue splash; the defaults are the on-page treatment (blue feather, ink text).
 */
@Composable
fun EvWordmark(
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    tint: Color = EvenlyTheme.colors.blueText,
    textColor: Color = EvenlyTheme.colors.ink,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(size * 0.26f),
    ) {
        Icon(
            imageVector = EvenlyMark,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 1.12f),
        )
        Text(
            text = "Evenly",
            color = textColor,
            fontSize = size.value.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
        )
    }
}

private const val EVENLY_MARK_PATH: String =
    "M96.32 0.05C85.02 1.19 73.68 3.79 63 7.66C55.8 10.28 48.59 13.69 42.62 18.58C37.62 22.67 33.67 " +
        "27.64 30.77 33.43C29.69 35.6 28.63 37.83 27.86 40.13C27.74 40.49 27.17 42.43 26.82 42.42C26.12 " +
        "42.39 25.48 40.76 25.2 40.24C23.93 37.92 23.09 35.3 22.97 32.65C22.93 31.71 23.11 30.79 23.25 " +
        "29.87C23.26 29.84 23.5 28.73 23.1 28.9C22.23 29.27 21.49 30.36 20.8 30.97C18.75 32.8 16.92 " +
        "34.83 15.04 36.83C8.72 43.58 3.18 51.87 2.1 61.25C1.38 67.42 2.76 73.79 3.7 79.85C4.18 82.87 " +
        "4.22 85.95 4.15 89C4.13 89.76 3.64 92.38 3.93 92.78C4.22 93.18 5.11 91.6 5.24 91.37C6.29 89.58 " +
        "7.74 88 8.92 86.29C11.9 81.96 15.46 77.93 18.89 73.94C29.54 61.56 40.83 49.91 53.79 39.9C57.68 " +
        "36.9 61.67 34.02 65.76 31.3C67.3 30.28 68.84 29.28 70.42 28.32C70.63 28.2 71.88 27.21 71.96 " +
        "27.79C72.04 28.39 70.47 30.57 70.12 31.24C68.18 35.03 65.78 38.57 63.58 42.2C54.73 56.78 43.2 " +
        "69.52 31.17 81.52C26.87 85.81 22.44 89.94 17.85 93.92C16.34 95.23 14.83 96.52 13.26 97.76C11.7 " +
        "98.99 10.81 99.81 11.09 99.87C12.09 100.09 13.46 99.43 14.48 99.35C17.27 99.11 20.04 98.93 " +
        "22.86 99.02C28.43 99.18 33.9 100.24 39.5 100C39.98 99.98 40.5 99.8 40.98 99.74C50.47 98.62 " +
        "57.03 93.73 63.94 87.54C66.56 85.2 68.85 82.47 71.13 79.81C71.66 79.19 73.66 77.21 73.78 " +
        "76.52C73.85 76.12 71.2 76.71 70.26 76.72C69.41 76.73 68.48 76.78 67.65 76.72C65.76 76.58 63.68 " +
        "75.77 61.98 74.94C61.63 74.77 60.25 74.21 60.34 73.68C60.42 73.29 61.46 72.99 61.77 72.88C63.19 " +
        "72.38 64.54 71.63 65.88 70.95C70.83 68.42 75.57 65.18 79.3 61.04C90.28 48.84 94.33 32 96.65 " +
        "16.15C97.23 12.17 97.71 8.11 97.85 4.09C97.96 1 98.92 -0.08 96.32 0.05Z"
