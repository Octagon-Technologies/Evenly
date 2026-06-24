package da.chelimo.sharecost.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** `.sc-topbar` — 56dp min, page bg, optional bottom hairline. Leading/title/actions slots. */
@Composable
fun ScTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    center: Boolean = false,
    showDivider: Boolean = true,
) {
    val c = ShareCostTheme.colors
    Column(modifier.fillMaxWidth().background(c.page)) {
        val content = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp)
        if (center) {
            Box(content) {
                navIcon?.let { Box(Modifier.align(Alignment.CenterStart)) { it() } }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.ink2) }
                }
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        } else {
            Row(content, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                navIcon?.invoke()
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.ink2) }
                }
                actions()
            }
        }
        if (showDivider) ScDivider()
    }
}

/** `.sc-iconbtn` — 40dp tappable icon, optional active tint + unread badge dot. */
@Composable
fun ScIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 22.dp,
    badgeDot: Boolean = false,
    tint: Color = if (active) ShareCostTheme.colors.blue else ShareCostTheme.colors.ink,
) {
    Box(
        modifier = modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ScIcon(icon, size = size, tint = tint)
        if (badgeDot) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp).size(8.dp).clip(CircleShape).background(ShareCostTheme.colors.blue))
        }
    }
}

data class BottomNavItem(val id: String, val label: String, val icon: ImageVector, val badge: Int? = null)

/**
 * Paints [color] up behind the status bar so the system bar blends with the top app bar that sits
 * directly below it (edge-to-edge on both Android and iOS). Place as the first child of a screen's
 * root Column with the same color as the top bar (usually `page`).
 */
@Composable
fun StatusBarScrim(color: Color = ShareCostTheme.colors.page) {
    Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(color))
}

/**
 * `.sc-bnav` — bottom navigation; per-item numeric badge (conflicts). Used both inside a group and at
 * the app root (Groups/Settings). [navBarInset] extends the `page` background down behind the system
 * navigation bar / home indicator so the nav area blends with the bar (the bg is painted before the
 * inset padding, so it fills the padded strip).
 */
@Composable
fun ScBottomNav(
    items: List<BottomNavItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    navBarInset: Boolean = false,
) {
    val c = ShareCostTheme.colors
    Column(
        modifier.fillMaxWidth().background(c.page)
            .then(if (navBarInset) Modifier.navigationBarsPadding() else Modifier),
    ) {
        ScDivider()
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            items.forEach { item ->
                val on = item.id == selectedId
                val tint = if (on) c.blue else c.ink3
                Column(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).selectable(selected = on) { onSelect(item.id) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ScIcon(item.icon, size = 23.dp, tint = tint)
                        if (item.badge != null) {
                            Box(
                                modifier = Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-4).dp)
                                    .defaultMinSize(minWidth = 16.dp).height(16.dp).clip(RoundedCornerShape(8.dp))
                                    .background(c.blue).padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${item.badge}", color = c.onAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(item.label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** `.sc-subtabs` — Active · All · Settled style underline tabs. */
@Composable
fun ScSubTabs(
    tabs: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ShareCostTheme.colors
    Column(modifier.fillMaxWidth().background(c.page)) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp)) {
            tabs.forEach { tab ->
                val on = tab == selected
                Text(
                    text = tab,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelect(tab) }
                        .then(
                            if (on) Modifier.drawBehind {
                                val stroke = 2.dp.toPx()
                                val inset = 12.dp.toPx()
                                val y = size.height - stroke / 2f
                                drawLine(c.blue, Offset(inset, y), Offset(size.width - inset, y), stroke, cap = StrokeCap.Round)
                            } else Modifier,
                        )
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                    color = if (on) c.blue else c.ink2,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        ScDivider()
    }
}

/** `.sc-seg` — segmented control (Even · Share · % · Exact). */
@Composable
fun ScSegmented(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ShareCostTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        options.forEach { opt ->
            val on = opt == selected
            Box(
                modifier = Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(9.dp))
                    .then(if (on) Modifier.shadow(2.dp, RoundedCornerShape(9.dp)).background(c.page) else Modifier)
                    .clickable { onSelect(opt) },
                contentAlignment = Alignment.Center,
            ) {
                Text(opt, color = if (on) c.ink else c.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }
}
