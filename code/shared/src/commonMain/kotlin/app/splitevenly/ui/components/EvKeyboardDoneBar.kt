package app.splitevenly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * A right-aligned "Done" bar that appears directly above the software keyboard while it's visible.
 * Numeric/decimal keyboards have no return key, so without this the only way to dismiss them is
 * tapping away, which isn't discoverable and can hide content the user still needs to see.
 *
 * [WindowInsets.isImeVisible] is Android-only in this Compose Multiplatform version (unresolved on
 * Kotlin/Native), so visibility is read from the ime inset height directly, which resolves on both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EvKeyboardDoneBar(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    if (WindowInsets.ime.getBottom(density) <= 0) return
    val c = EvenlyTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime)
            .background(c.surface)
            .topHairline(c.border),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            "Done",
            color = c.blueText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .clickable(onClick = onDone)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
