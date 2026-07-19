package da.chelimo.sharecost.ui.screen.auth

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ExtendedColors
import da.chelimo.sharecost.ui.theme.ShareCostTheme
import kotlinx.coroutines.launch

/**
 * First-launch welcome carousel (design/ "ShareCost Onboarding"). Five swipeable slides that pitch
 * the product before sign-in: fair splitting, receipt scan, tap-to-assign, add-anyone, and per-person
 * balances. Each slide pairs a headline with an illustrative mock card. Dismissing (Skip or Get
 * started) marks the flow seen so it never shows again — that persistence lives in the Route wrapper.
 *
 * Stateless + previewable: takes a single [onFinish] callback, no DI.
 */
@Composable
fun WelcomeScreen(onFinish: () -> Unit = {}) {
    val c = ShareCostTheme.colors
    val pageCount = 5
    val pager = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val isLast = pager.currentPage == pageCount - 1

    Box(Modifier.fillMaxSize().background(c.page)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
        ) { page ->
            when (page) {
                0 -> WelcomeSlide(
                    title = "Group costs, kept ", accent = "fair.",
                    body = "Split what you share and keep a clear record, so nothing gets lost or argued about later.",
                    cardOnTop = false,
                ) { GroupCard() }
                1 -> WelcomeSlide(
                    title = "Scan a receipt, ", accent = "done.",
                    body = "Snap the bill and we pull out every line item. You just confirm before we split it.",
                    cardOnTop = true,
                ) { ReceiptCard() }
                2 -> WelcomeSlide(
                    title = "Everyone taps ", accent = "what they had.",
                    body = "Assign items in seconds. One person can sort the whole bill, no back-and-forth.",
                    cardOnTop = false,
                ) { WhoHadWhatCard() }
                3 -> WelcomeSlide(
                    title = "Add anyone, ", accent = "even before they join.",
                    body = "Split with people who aren't here yet. When they join, they claim their spot, no re-entry.",
                    cardOnTop = true,
                ) { ClaimCard() }
                else -> WelcomeSlide(
                    title = "Always know ", accent = "where you stand.",
                    body = "See who owes what, per person, never netted into one confusing number.",
                    cardOnTop = false,
                ) { BalancesCard() }
            }
        }

        // Bottom controls: pager dots + Skip / next, over a fade so slide art never crowds them.
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(horizontal = 26.dp)
                .padding(bottom = 18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pageCount) { i ->
                    val active = i == pager.currentPage
                    val w by animateDpAsState(if (active) 26.dp else 8.dp)
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .width(w)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(if (active) c.blue else c.borderStrong),
                    )
                }
            }

            if (isLast) {
                ScButton("Get started", onClick = onFinish, leadingIcon = ScIcons.Check)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(c.surface)
                            .clickable(onClick = onFinish),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Skip", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(c.blue)
                            .clickable { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        ScIcon(ScIcons.ChevR, size = 22.dp, tint = c.onAccent)
                    }
                }
            }
        }
    }
}

/** Slide scaffold: a headline block + an illustrative card, ordered top/bottom by [cardOnTop]. */
@Composable
private fun WelcomeSlide(
    title: String,
    accent: String,
    body: String,
    cardOnTop: Boolean,
    card: @Composable () -> Unit,
) {
    val c = ShareCostTheme.colors
    // Leave room at the bottom for the fixed controls; the systemBarsPadding on the slide keeps the
    // headline clear of the status bar.
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(top = 40.dp, bottom = 150.dp),
    ) {
        val header: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = 30.dp)) {
                Text(
                    buildAnnotatedString {
                        append(title)
                        withStyle(SpanStyle(color = c.blueText)) { append(accent) }
                    },
                    color = c.ink,
                    fontSize = 34.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                )
                Text(
                    body,
                    color = c.ink2,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
        val art: @Composable () -> Unit = {
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 26.dp),
                contentAlignment = Alignment.Center,
            ) { card() }
        }
        if (cardOnTop) {
            art()
            header()
        } else {
            header()
            art()
        }
    }
}

// ── Shared card primitives ─────────────────────────────────────────────────

/** The floating white mock card the slides show their product moments inside. */
@Composable
private fun MockCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = ShareCostTheme.colors
    Box(
        modifier
            .width(302.dp)
            .clip(RoundedCornerShape(16.dp))
            // In dark mode a page-colored card vanishes; lift it onto the elevated surface so the
            // border + fill still read against the near-black page.
            .background(if (c.isDark) c.surface else c.page)
            .border(1.dp, c.border, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) { content() }
}

/** Initial-badge avatar (the overlapping stack members / claim chips). */
@Composable
private fun Avatar(
    text: String,
    bg: Color,
    fg: Color,
    size: Int = 26,
    ring: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = ShareCostTheme.colors
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .then(if (ring) Modifier.background(c.page) else Modifier)
            .padding(if (ring) 2.dp else 0.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = (size * 0.4f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Mono(text: String, color: Color, size: Int = 13, weight: FontWeight = FontWeight.Normal) {
    Text(text, color = color, fontFamily = ShareCostTheme.monoFamily, fontSize = size.sp, fontWeight = weight)
}

private fun ExtendedColors.avatarBlue() = blue
private fun ExtendedColors.avatarLight() = blueTint2
private fun ExtendedColors.avatarDeep() = bluePressed

// ── Slide 1 · Group costs ───────────────────────────────────────────────────

@Composable
private fun GroupCard() {
    val c = ShareCostTheme.colors
    MockCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The ShareCost app mark: a blue tile with a white + light-blue dot (matches the real
                // launcher icon and reads in both light and dark, unlike the mock's navy tile).
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.blue),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.size(11.dp).clip(CircleShape).background(c.onAccent))
                        Box(Modifier.size(11.dp).clip(CircleShape).background(c.blueTint2))
                    }
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("Weekend trip", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("3 people · 6 expenses", color = c.ink3, fontSize = 11.5.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                    Avatar("You", c.avatarBlue(), c.onAccent, ring = true)
                    Avatar("JL", c.avatarLight(), c.ink, ring = true)
                    Avatar("MR", c.avatarDeep(), c.onAccent, ring = true)
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Total spent", color = c.ink3, fontSize = 11.sp)
                    Mono("\$412.80", c.ink, size = 20, weight = FontWeight.SemiBold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("you're owed", color = c.ink3, fontSize = 11.sp)
                    Mono("\$24.60", c.blueText, size = 16, weight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(12.dp))
            ExpenseRow("Groceries", "\$86.40")
            ExpenseRow("Gas", "\$54.10")
        }
    }
}

@Composable
private fun ExpenseRow(label: String, amount: String) {
    val c = ShareCostTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(c.blueTint2))
        Spacer(Modifier.width(8.dp))
        Text(label, color = c.ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Mono(amount, c.ink2, size = 13)
    }
}

// ── Slide 2 · Scan a receipt ────────────────────────────────────────────────

@Composable
private fun ReceiptCard() {
    val c = ShareCostTheme.colors
    MockCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.size(width = 42.dp, height = 54.dp).clip(RoundedCornerShape(8.dp))
                        .background(c.surface).border(1.dp, c.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    listOf(1f, 0.7f, 0.88f, 0.55f).forEach { frac ->
                        Box(Modifier.fillMaxWidth(frac).height(3.dp).clip(CircleShape).background(c.blueTint2))
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Brunch · Café Loop", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Mono("receipt.jpg", c.ink3, size = 11)
                }
                Row(
                    Modifier.clip(CircleShape).background(c.blueTint).padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScIcon(ScIcons.Check, size = 13.dp, tint = c.blueText)
                    Spacer(Modifier.width(5.dp))
                    Text("Scanned", color = c.blueText, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(14.dp))
            ReceiptLine("Espresso", "4.50")
            ReceiptLine("Pancakes", "12.00")
            ReceiptLine("Avocado toast", "11.50", reading = true)
            ReceiptLine("Orange juice", "5.00")
            ReceiptLine("Tax", "2.60", muted = true)

            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(1.dp).background(c.border))
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Total", color = c.ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Mono("\$35.60", c.ink, size = 14, weight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(15.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.blueTint).padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(c.blue), contentAlignment = Alignment.Center) {
                    ScIcon(ScIcons.Info, size = 13.dp, tint = c.onAccent)
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    "Verify before we split it. You confirm every line.",
                    color = c.blueText, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun ReceiptLine(label: String, amount: String, reading: Boolean = false, muted: Boolean = false) {
    val c = ShareCostTheme.colors
    val bg = if (reading) c.blueTint else Color.Transparent
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (muted) c.ink3 else c.ink, fontSize = 13.5.sp)
        if (reading) {
            Spacer(Modifier.width(7.dp))
            Text("READING…", color = c.blueText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
        }
        Spacer(Modifier.weight(1f))
        Mono(amount, if (muted) c.ink3 else c.ink, size = 13)
    }
}

// ── Slide 3 · Everyone taps what they had ──────────────────────────────────

@Composable
private fun WhoHadWhatCard() {
    val c = ShareCostTheme.colors
    MockCard {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Who had what", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Brunch · 2 people", color = c.ink3, fontSize = 12.sp)
            }
            Spacer(Modifier.height(13.dp))
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                AssignRow("Pancakes", "\$12.00") {
                    Avatar("AB", c.avatarBlue(), c.onAccent, size = 29)
                }
                AssignRow("Avocado toast", "\$11.50") {
                    Avatar("JL", c.avatarLight(), c.ink, size = 29)
                }
                AssignRow("Orange juice", "\$5.00 · split") {
                    Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                        Avatar("AB", c.avatarBlue(), c.onAccent, size = 29, ring = true)
                        Avatar("JL", c.avatarLight(), c.ink, size = 29, ring = true)
                    }
                }
                AssignRow("Espresso", "\$4.50 · tap to assign", dashed = true, subMuted = true) {
                    Box(
                        Modifier.size(29.dp).clip(CircleShape).border(1.5.dp, c.ink3, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { ScIcon(ScIcons.Plus, size = 16.dp, tint = c.ink3) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScIcon(ScIcons.User, size = 16.dp, tint = c.ink2)
                Spacer(Modifier.width(8.dp))
                Text(
                    "You can assign for the whole table, even if you're the only one here.",
                    color = c.ink2, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun AssignRow(
    name: String,
    sub: String,
    dashed: Boolean = false,
    subMuted: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .then(if (dashed) Modifier.background(c.surface) else Modifier)
            .border(1.dp, c.border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = c.ink, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Mono(sub, if (subMuted) c.ink3 else c.ink2, size = 12)
        }
        trailing()
    }
}

// ── Slide 4 · Add anyone ────────────────────────────────────────────────────

@Composable
private fun ClaimCard() {
    val c = ShareCostTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Left: the roster with a placeholder member.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Whole trip", c.surface, c.ink2)
            Box(
                Modifier.width(146.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (c.isDark) c.surface else c.page)
                    .border(1.dp, c.border, RoundedCornerShape(16.dp))
                    .padding(horizontal = 13.dp, vertical = 14.dp),
            ) {
                Column {
                    Text("Weekend trip", color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(11.dp))
                    RosterRow("You", "You", c.avatarBlue(), c.onAccent)
                    Spacer(Modifier.height(9.dp))
                    RosterRow("JL", "Jess", c.avatarLight(), c.ink)
                    Spacer(Modifier.height(9.dp))
                    // Placeholder — dashed ring, highlighted row.
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp)).background(c.surface)
                            .padding(horizontal = 7.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).border(1.5.dp, c.ink3, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("S", color = c.ink3, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.width(8.dp))
                        Text("Sam", color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        // Right: the one-tap claim moment.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("One tap claim", c.blueTint, c.blueText)
            Column(
                Modifier.width(146.dp).clip(RoundedCornerShape(16.dp)).background(c.blueTint)
                    .border(1.dp, c.blueTint2, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).border(1.5.dp, c.ink3, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("S", color = c.ink3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    Text("Placeholder", color = c.ink3, fontSize = 9.5.sp)
                }
                ScIcon(ScIcons.ChevD, size = 18.dp, tint = c.blueText)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Avatar("SM", c.avatarBlue(), c.onAccent, size = 36)
                        Box(
                            Modifier.size(15.dp).clip(CircleShape).background(c.blueTint).padding(2.dp)
                                .clip(CircleShape).background(c.blue),
                            contentAlignment = Alignment.Center,
                        ) { ScIcon(ScIcons.Check, size = 8.dp, tint = c.onAccent) }
                    }
                    Text("Claimed", color = c.blueText, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun RosterRow(initials: String, name: String, bg: Color, fg: Color) {
    val c = ShareCostTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(initials, bg, fg, size = 26)
        Spacer(Modifier.width(8.dp))
        Text(name, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Box(
        Modifier.clip(CircleShape).background(bg).padding(horizontal = 11.dp, vertical = 6.dp),
    ) { Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

// ── Slide 5 · Balances ──────────────────────────────────────────────────────

@Composable
private fun BalancesCard() {
    val c = ShareCostTheme.colors
    MockCard {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Balances", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Weekend trip", color = c.ink3, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            BalanceRow("JL", "Jess", c.avatarLight(), c.ink, "owes you", "\$18.20", c.blueText)
            Divider()
            BalanceRow("MR", "Marco", c.avatarDeep(), c.onAccent, "you owe", "\$9.50", c.danger)
            Divider()
            BalanceRow("SM", "Sam", c.avatarBlue(), c.onAccent, "owes you", "\$6.30", c.blueText)
            Spacer(Modifier.height(12.dp))
            Text(
                "Shown per person, never blended into one number.",
                color = c.ink3, fontSize = 11.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(13.dp))
            ScButton("Settle up", onClick = {}, variant = ButtonVariant.Secondary)
        }
    }
}

@Composable
private fun BalanceRow(
    initials: String,
    name: String,
    bg: Color,
    fg: Color,
    caption: String,
    amount: String,
    amountColor: Color,
) {
    val c = ShareCostTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(initials, bg, fg, size = 32)
        Spacer(Modifier.width(11.dp))
        Text(name, color = c.ink, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(caption, color = c.ink3, fontSize = 11.sp)
            Mono(amount, amountColor, size = 15, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Divider() {
    val c = ShareCostTheme.colors
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
}

@Preview
@Composable
private fun WelcomePreview() {
    ShareCostTheme { WelcomeScreen() }
}
