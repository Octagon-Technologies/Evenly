package da.chelimo.sharecost.ui.components.gallery

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
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.BannerVariant
import da.chelimo.sharecost.ui.components.BottomNavItem
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAmountText
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScAvatarStack
import da.chelimo.sharecost.ui.components.ScBanner
import da.chelimo.sharecost.ui.components.ScBottomNav
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCheck
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScDebtRow
import da.chelimo.sharecost.ui.components.ScEmptyState
import da.chelimo.sharecost.ui.components.ScExpenseRow
import da.chelimo.sharecost.ui.components.ScFab
import da.chelimo.sharecost.ui.components.ScListCard
import da.chelimo.sharecost.ui.components.ScPendingPill
import da.chelimo.sharecost.ui.components.ScProgress
import da.chelimo.sharecost.ui.components.ScRadio
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScSegmented
import da.chelimo.sharecost.ui.components.ScSkeletonRow
import da.chelimo.sharecost.ui.components.ScSubTabs
import da.chelimo.sharecost.ui.components.ScToggle
import da.chelimo.sharecost.ui.components.ScWordmark
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/**
 * A visual gallery of the component library — mirrors `design/src/overview.jsx`. Used as a `@Preview`
 * and as a screenshot target to compare the Compose components against the design.
 */
@Composable
fun ComponentGallery() {
    val c = ShareCostTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.surface).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScWordmark(size = 30.dp)

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
            ScAmountText(remaining = money(48.0), original = money(96.0))
        }

        Section("Chips") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScChip("you owe")
                ScChip("you're owed", variant = ChipVariant.Solid)
                ScChip("Settled", variant = ChipVariant.Blue)
                ScChip("conflict", variant = ChipVariant.Amber)
            }
        }

        Section("Buttons") {
            ScButton("Primary", {})
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScButton("Secondary", {}, variant = ButtonVariant.Secondary, fillMaxWidth = false)
                ScButton("Text", {}, variant = ButtonVariant.Text)
                ScButton("Delete", {}, variant = ButtonVariant.Danger, fillMaxWidth = false)
            }
        }

        Section("Rows") {
            ScListCard(items = listOf(0, 1, 2)) { i ->
                when (i) {
                    0 -> ScExpenseRow("Group dinner — La Negra", "Andrew paid · you owe", remaining = money(24.99), original = money(48.0), onClick = {})
                    1 -> ScExpenseRow("Airport taxi", "You paid · settled up", settled = true, onClick = {})
                    else -> ScDebtRow("You", "Andrew", money(42.0), onClick = {})
                }
            }
        }

        Section("Avatars") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScAvatar("Alex Rivera", me = true)
                ScAvatar("Bob Lee")
                ScAvatar("Maya", size = AvatarSize.Sm)
                ScAvatarStack(names = listOf("Andrew", "Bob", "Maya", "Tyler"))
            }
        }

        Section("Selectors") {
            ScSegmented(options = listOf("Even", "Share", "%", "Exact"), selected = "Even", onSelect = {})
            ScSubTabs(tabs = listOf("Active", "All", "Settled"), selected = "Active", onSelect = {})
            ScBottomNav(
                items = listOf(
                    BottomNavItem("expenses", "Expenses", da.chelimo.sharecost.ui.components.icon.ScIcons.Receipt),
                    BottomNavItem("balances", "Balances", da.chelimo.sharecost.ui.components.icon.ScIcons.Swap),
                    BottomNavItem("conflicts", "Conflicts", da.chelimo.sharecost.ui.components.icon.ScIcons.Flag, badge = 2),
                    BottomNavItem("overview", "Overview", da.chelimo.sharecost.ui.components.icon.ScIcons.Chart),
                ),
                selectedId = "expenses",
                onSelect = {},
            )
        }

        Section("Controls") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ScToggle(checked = true, onCheckedChange = {})
                ScToggle(checked = false, onCheckedChange = {})
                ScRadio(selected = true)
                ScRadio(selected = false)
                ScCheck(checked = true)
                ScCheck(checked = false)
            }
        }

        Section("Feedback") {
            ScBanner("Offline — your changes will sync.")
            ScBanner("3 conflicts need your attention.", variant = BannerVariant.Amber, leadingIcon = da.chelimo.sharecost.ui.components.icon.ScIcons.Alert)
            ScProgress(0.6f)
            ScPendingPill()
            ScSkeletonRow()
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(c.page)) {
                ScEmptyState("No groups yet", "Create a group to start tracking who owes whom.", ctaText = "Create a group", onCta = {})
            }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { ScFab(onClick = {}) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScSectionLabel(title)
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
    ShareCostTheme(darkTheme = false) { ComponentGallery() }
}

@Preview
@Composable
private fun GalleryDarkPreview() {
    ShareCostTheme(darkTheme = true) { ComponentGallery() }
}
