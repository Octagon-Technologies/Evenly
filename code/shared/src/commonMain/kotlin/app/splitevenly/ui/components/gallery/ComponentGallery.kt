package app.splitevenly.ui.components.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.BannerVariant
import app.splitevenly.ui.components.BottomNavItem
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.ChipVariant
import app.splitevenly.ui.components.EvAmountText
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvAvatarStack
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.EvBottomNav
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCheck
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvDebtRow
import app.splitevenly.ui.components.EvEmptyState
import app.splitevenly.ui.components.EvExpenseRow
import app.splitevenly.ui.components.EvFab
import app.splitevenly.ui.components.EvListCard
import app.splitevenly.ui.components.EvPendingPill
import app.splitevenly.ui.components.EvProgress
import app.splitevenly.ui.components.EvRadio
import app.splitevenly.ui.components.EvSectionLabel
import app.splitevenly.ui.components.EvSegmented
import app.splitevenly.ui.components.EvSkeletonRow
import app.splitevenly.ui.components.EvSubTabs
import app.splitevenly.ui.components.EvToggle
import app.splitevenly.ui.components.EvWordmark
import app.splitevenly.ui.components.money
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * A visual gallery of the component library — mirrors `design/src/overview.jsx`. Used as a `@Preview`
 * and as a screenshot target to compare the Compose components against the design.
 */
@Composable
fun ComponentGallery() {
    val c = EvenlyTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.surface).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        EvWordmark(size = 30.dp)

        Section("Color") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatch(c.blue); Swatch(c.bluePressed); Swatch(c.blueTint); Swatch(c.ink); Swatch(c.ink2); Swatch(c.surface); Swatch(c.danger)
            }
        }

        Section("Type") {
            Text("Large title 28/700", style = MaterialTheme.typography.headlineMedium, color = c.ink)
            Text("Section title 18/600", style = MaterialTheme.typography.titleLarge, color = c.ink)
            Text("Body 15/400 — calm and quiet.", style = MaterialTheme.typography.bodyLarge, color = c.ink)
            Text("Caption 12 · muted", style = MaterialTheme.typography.bodySmall, color = c.ink2)
            EvAmountText(remaining = money(48.0), original = money(96.0))
        }

        Section("Chips") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EvChip("you owe")
                EvChip("you're owed", variant = ChipVariant.Solid)
                EvChip("Settled", variant = ChipVariant.Blue)
                EvChip("conflict", variant = ChipVariant.Amber)
            }
        }

        Section("Buttons") {
            EvButton("Primary", {})
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EvButton("Secondary", {}, variant = ButtonVariant.Secondary, fillMaxWidth = false)
                EvButton("Text", {}, variant = ButtonVariant.Text)
                EvButton("Delete", {}, variant = ButtonVariant.Danger, fillMaxWidth = false)
            }
        }

        Section("Rows") {
            EvListCard(items = listOf(0, 1, 2)) { i ->
                when (i) {
                    0 -> EvExpenseRow("Group dinner — La Negra", "Andrew paid · you owe", remaining = money(24.99), original = money(48.0), onClick = {})
                    1 -> EvExpenseRow("Airport taxi", "You paid · settled up", settled = true, onClick = {})
                    else -> EvDebtRow("You", "Andrew", money(42.0), onClick = {})
                }
            }
        }

        Section("Avatars") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EvAvatar("Alex Rivera", me = true)
                EvAvatar("Bob Lee")
                EvAvatar("Maya", size = AvatarSize.Sm)
                EvAvatarStack(names = listOf("Andrew", "Bob", "Maya", "Tyler"))
            }
        }

        Section("Selectors") {
            EvSegmented(options = listOf("Even", "Share", "%", "Exact"), selected = "Even", onSelect = {})
            EvSubTabs(tabs = listOf("Active", "All", "Settled"), selected = "Active", onSelect = {})
            EvBottomNav(
                items = listOf(
                    BottomNavItem("expenses", "Expenses", app.splitevenly.ui.components.icon.EvIcons.Receipt),
                    BottomNavItem("balances", "Balances", app.splitevenly.ui.components.icon.EvIcons.Swap),
                    BottomNavItem("conflicts", "Conflicts", app.splitevenly.ui.components.icon.EvIcons.Flag, badge = 2),
                    BottomNavItem("overview", "Overview", app.splitevenly.ui.components.icon.EvIcons.Chart),
                ),
                selectedId = "expenses",
                onSelect = {},
            )
        }

        Section("Controls") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                EvToggle(checked = true, onCheckedChange = {})
                EvToggle(checked = false, onCheckedChange = {})
                EvRadio(selected = true)
                EvRadio(selected = false)
                EvCheck(checked = true)
                EvCheck(checked = false)
            }
        }

        Section("Feedback") {
            EvBanner("Offline — your changes will sync.")
            EvBanner("3 conflicts need your attention.", variant = BannerVariant.Amber, leadingIcon = app.splitevenly.ui.components.icon.EvIcons.Alert)
            EvProgress(0.6f)
            EvPendingPill()
            EvSkeletonRow()
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(c.page)) {
                EvEmptyState("No groups yet", "Create a group to start tracking who owes whom.", ctaText = "Create a group", onCta = {})
            }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { EvFab(onClick = {}) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EvSectionLabel(title)
        content()
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(color))
}

@Preview
@Composable
private fun GalleryLightPreview() {
    EvenlyTheme(darkTheme = false) { ComponentGallery() }
}

@Preview
@Composable
private fun GalleryDarkPreview() {
    EvenlyTheme(darkTheme = true) { ComponentGallery() }
}
