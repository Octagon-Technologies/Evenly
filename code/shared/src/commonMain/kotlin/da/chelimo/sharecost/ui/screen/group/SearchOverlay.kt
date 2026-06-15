package da.chelimo.sharecost.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScDivider
import da.chelimo.sharecost.ui.components.ScExpenseRow
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** 8 · Search overlay (design/src/screens-group2.jsx). */
@Composable
fun SearchOverlay(onBack: () -> Unit = {}, onResult: () -> Unit = {}) {
    val c = ShareCostTheme.colors
    var query by remember { mutableStateOf("tax") }
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().background(c.page).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScTextField(
                query, { query = it }, modifier = Modifier.weight(1f), minHeight = 44.dp,
                leading = { ScIcon(ScIcons.Search, size = 18.dp, tint = c.ink3) },
                placeholder = "Search expenses, members…",
            )
            ScButton("Cancel", onBack, variant = ButtonVariant.Text)
        }
        ScDivider()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScSectionLabel("Expenses", Modifier.padding(start = 12.dp, top = 10.dp))
            ScExpenseRow("Tax-included dinner", "Andrew paid · you owe \$24.00", icon = ScIcons.Food, remaining = money(24.0), original = money(96.0), onClick = onResult)
            ScExpenseRow("Taxi to cenotes", "Bob paid · you owe \$9.00", icon = ScIcons.Car, remaining = money(9.0), original = money(36.0), onClick = onResult)
            ScSectionLabel("Members", Modifier.padding(start = 12.dp, top = 14.dp))
            ResultRow(onResult, leading = { ScAvatar("Maya", size = da.chelimo.sharecost.ui.components.AvatarSize.Sm) }, title = "Maya", sub = "4 expenses match")
            ScSectionLabel("Categories", Modifier.padding(start = 12.dp, top = 14.dp))
            ResultRow(
                onResult,
                leading = { Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(c.surface), contentAlignment = Alignment.Center) { ScIcon(ScIcons.Ticket, size = 20.dp, tint = c.ink2) } },
                title = "Taxes & fees", sub = "\$38.40 across 3 expenses",
            )
        }
    }
}

@Composable
private fun ResultRow(onClick: () -> Unit, leading: @Composable () -> Unit, title: String, sub: String) {
    val c = ShareCostTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, style = MaterialTheme.typography.titleSmall)
            Text(sub, color = c.ink2, style = MaterialTheme.typography.bodyMedium)
        }
        ScIcon(ScIcons.ChevR, size = 16.dp, tint = c.ink3)
    }
}

@Preview
@Composable
private fun SearchPreview() {
    ShareCostTheme { SearchOverlay() }
}
