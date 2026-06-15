package da.chelimo.sharecost.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import da.chelimo.sharecost.ui.theme.ShareCostTheme

private val CardShape = RoundedCornerShape(16.dp)

/** `.sc-card` — page surface, 1px hairline, 16dp radius. `fill` → surface bg, no border. */
@Composable
fun ScCard(
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    padded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ShareCostTheme.colors
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(if (fill) c.surface else c.page)
            .then(if (fill) Modifier else Modifier.border(1.dp, c.border, CardShape))
            .then(if (padded) Modifier.padding(16.dp) else Modifier),
        content = content,
    )
}

/** A `.sc-card` stacking [items] as rows separated by [topHairline] — the design's list idiom. */
@Composable
fun <T> ScListCard(
    items: List<T>,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    row: @Composable (T) -> Unit,
) {
    val c = ShareCostTheme.colors
    ScCard(modifier = modifier, fill = fill) {
        items.forEachIndexed { i, item ->
            Box(if (i > 0) Modifier.topHairline(c.border) else Modifier) { row(item) }
        }
    }
}

/** Visual of `.sc-sheet` (grabber, rounded top, title/sub) without the scrim. */
@Composable
fun ScSheetSurface(
    modifier: Modifier = Modifier,
    title: String? = null,
    sub: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ShareCostTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(c.page)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 14.dp).size(width = 36.dp, height = 5.dp).clip(RoundedCornerShape(99.dp)).background(c.borderStrong))
        title?.let {
            Text(it, Modifier.fillMaxWidth().padding(bottom = 4.dp), color = c.ink, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        }
        sub?.let {
            Text(it, Modifier.fillMaxWidth().padding(bottom = 16.dp), color = c.ink2, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        content()
    }
}

/** `.sc-scrim` + bottom-aligned [ScSheetSurface]. Tapping the scrim dismisses; the sheet consumes. */
@Composable
fun ScSheetScaffold(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    sub: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(ShareCostTheme.colors.ink.copy(alpha = 0.4f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
        ScSheetSurface(
            modifier = Modifier.align(Alignment.BottomCenter).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            title = title,
            sub = sub,
            content = content,
        )
    }
}

/** `.sc-scrim--center` + `.sc-modal` (centered dialog card, max 320dp). */
@Composable
fun ScModalScaffold(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ShareCostTheme.colors
    Box(
        modifier.fillMaxSize().background(c.ink.copy(alpha = 0.4f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.page)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}).padding(24.dp),
            content = content,
        )
    }
}
