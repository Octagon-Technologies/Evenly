package app.splitevenly.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.getValue
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
import app.splitevenly.platform.isIOS
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.theme.EvMotion
import app.splitevenly.ui.theme.EvenlyTheme

/** `.sc-topbar` — 56dp min, page bg, optional bottom hairline. Leading/title/actions slots. */
@Composable
fun EvTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    center: Boolean = false,
    showDivider: Boolean = true,
) {
    val c = EvenlyTheme.colors
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
        if (showDivider) EvDivider()
    }
}

/** `.sc-iconbtn` — 40dp tappable icon, optional active tint + unread badge dot. */
@Composable
fun EvIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 22.dp,
    badgeDot: Boolean = false,
    tint: Color = if (active) EvenlyTheme.colors.blue else EvenlyTheme.colors.ink,
) {
    Box(
        modifier = modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EvIcon(icon, size = size, tint = tint)
        if (badgeDot) {
            Box(
                Modifier
                    .align(
                        Alignment.TopEnd,
                    ).offset(x = (-6).dp, y = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(EvenlyTheme.colors.blue),
            )
        }
    }
}

data class BottomNavItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val badge: Int? = null,
)

/**
 * Paints [color] up behind the status bar so the system bar blends with the top app bar that sits
 * directly below it (edge-to-edge on both Android and iOS). Place as the first child of a screen's
 * root Column with the same color as the top bar (usually `page`).
 */
@Composable
fun StatusBarScrim(color: Color = EvenlyTheme.colors.page) {
    Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(color))
}

/**
 * `.sc-bnav` — bottom navigation; per-item numeric badge (conflicts). Used both inside a group and at
 * the app root (Groups/Settings). Renders a **platform-idiomatic** variant: on Android the edge-to-edge
 * Material bar with a top hairline ([MaterialBottomNav]); on iOS a Tinder-style floating, inset,
 * softly-shadowed island with the active tab in a blue-tint pill ([IosFloatingNav]). [navBarInset]
 * keeps the nav clear of the system navigation bar / home indicator on both.
 */
@Composable
fun EvBottomNav(
    items: List<BottomNavItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    navBarInset: Boolean = false,
) {
    if (isIOS()) {
        IosFloatingNav(items, selectedId, onSelect, modifier, navBarInset)
    } else {
        MaterialBottomNav(items, selectedId, onSelect, modifier, navBarInset)
    }
}

/** Android: edge-to-edge `page` bar with a top hairline; active blue, inactive `ink3`. */
@Composable
private fun MaterialBottomNav(
    items: List<BottomNavItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    navBarInset: Boolean = false,
) {
    val c = EvenlyTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.page)
            .then(if (navBarInset) Modifier.navigationBarsPadding() else Modifier),
    ) {
        EvDivider()
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            items.forEach { item ->
                val on = item.id == selectedId
                val tint by animateColorAsState(if (on) c.blue else c.ink3, EvMotion.quick())
                Column(
                    modifier =
                        Modifier
                            .weight(
                                1f,
                            ).clip(RoundedCornerShape(10.dp))
                            .selectable(selected = on) { onSelect(item.id) }
                            .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        EvIcon(item.icon, size = 23.dp, tint = tint)
                        item.badge?.let { NavBadge(it) }
                    }
                    Text(item.label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * iOS: a floating, inset, softly-shadowed `page` island (Tinder-style). The active tab sits in a
 * blue-tint pill with a blue icon+label; inactive tabs are `ink3`. The bar background stays transparent
 * so the screen color shows through behind the island and the system home-indicator inset, giving the
 * card its lift. Scales from the 2-tab root nav to the 3–4-tab in-group nav.
 */
@Composable
private fun IosFloatingNav(
    items: List<BottomNavItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    navBarInset: Boolean = false,
) {
    val c = EvenlyTheme.colors
    val island = RoundedCornerShape(26.dp)
    val pill = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .then(if (navBarInset) Modifier.navigationBarsPadding() else Modifier)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(10.dp, island)
                .background(c.page, island)
                .border(1.dp, c.border, island)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val on = item.id == selectedId
                val tint by animateColorAsState(if (on) c.blue else c.ink3, EvMotion.quick())
                val pillColor by animateColorAsState(if (on) c.blueTint else Color.Transparent, EvMotion.quick())
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(pill)
                            .background(pillColor)
                            .selectable(selected = on) { onSelect(item.id) }
                            .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            EvIcon(item.icon, size = 23.dp, tint = tint)
                            item.badge?.let { NavBadge(it) }
                        }
                        Text(item.label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** The small numeric badge (e.g. unresolved conflicts) pinned to the top-end of a nav icon. */
@Composable
private fun BoxScope.NavBadge(count: Int) {
    val c = EvenlyTheme.colors
    Box(
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 12.dp, y = (-4).dp)
                .defaultMinSize(minWidth = 16.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.blue)
                .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", color = c.onAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** `.sc-subtabs` — Active · All · Settled style underline tabs. */
@Composable
fun EvSubTabs(
    tabs: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    Column(modifier.fillMaxWidth().background(c.page)) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp)) {
            tabs.forEach { tab ->
                val on = tab == selected
                val tint by animateColorAsState(if (on) c.blue else c.ink2, EvMotion.quick())
                Text(
                    text = tab,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelect(tab) }
                            .then(
                                if (on) {
                                    Modifier.drawBehind {
                                        val stroke = 2.dp.toPx()
                                        val inset = 12.dp.toPx()
                                        val y = size.height - stroke / 2f
                                        drawLine(c.blue, Offset(inset, y), Offset(size.width - inset, y), stroke, cap = StrokeCap.Round)
                                    }
                                } else {
                                    Modifier
                                },
                            ).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                    color = tint,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        EvDivider()
    }
}

/** `.sc-seg` — segmented control (Even · Share · % · Exact). */
@Composable
fun EvSegmented(
    options: List<String>,
    // Nullable so a form can ask a question nobody has answered yet: the feedback form must not
    // preselect a type, or every abandoned tap files itself as whichever option sat under the pill.
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        options.forEach { opt ->
            val on = opt == selected
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .then(if (on) Modifier.shadow(2.dp, RoundedCornerShape(9.dp)).background(c.page) else Modifier)
                        .clickable { onSelect(opt) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    opt,
                    color = if (on) c.ink else c.ink2,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
