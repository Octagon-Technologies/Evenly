package app.splitevenly.ui.screen.group

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvDivider
import app.splitevenly.ui.components.EvExpenseRow
import app.splitevenly.ui.components.EvSectionLabel
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.money
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** A matched expense in the search overlay. */
data class SearchExpenseUi(
    val id: String,
    val title: String,
    val sub: String,
    val remaining: Double,
    val original: Double,
    val currencySymbol: String = "$",
)

/** A matched member in the search overlay. */
data class SearchMemberUi(val name: String, val sub: String, val me: Boolean = false)

/** 8 · Search overlay (design/src/screens-group2.jsx) — wired to the real group feed (F6). */
@Composable
fun SearchOverlay(
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    expenseResults: List<SearchExpenseUi> = emptyList(),
    memberResults: List<SearchMemberUi> = emptyList(),
    onBack: () -> Unit = {},
    onOpenExpense: (String) -> Unit = {},
) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().background(c.page).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EvTextField(
                query, onQueryChange, modifier = Modifier.weight(1f), minHeight = 44.dp,
                leading = { EvIcon(EvIcons.Search, size = 18.dp, tint = c.ink3) },
                placeholder = "Search expenses, members…",
            )
            EvButton("Cancel", onBack, variant = ButtonVariant.Text)
        }
        EvDivider()
        val trimmed = query.trim()
        when {
            trimmed.isEmpty() -> Hint("Search this group", "Find an expense by name or a member to see their share.")
            expenseResults.isEmpty() && memberResults.isEmpty() ->
                Hint("No matches", "Nothing here matches “$trimmed”.")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (expenseResults.isNotEmpty()) {
                    EvSectionLabel("Expenses", Modifier.padding(start = 12.dp, top = 10.dp))
                    expenseResults.forEach { r ->
                        EvExpenseRow(
                            r.title, r.sub, icon = EvIcons.Receipt,
                            remaining = money(r.remaining, r.currencySymbol),
                            original = money(r.original, r.currencySymbol),
                            onClick = { onOpenExpense(r.id) },
                        )
                    }
                }
                if (memberResults.isNotEmpty()) {
                    EvSectionLabel("Members", Modifier.padding(start = 12.dp, top = 14.dp))
                    memberResults.forEach { m ->
                        ResultRow(
                            onClick = {},
                            leading = { EvAvatar(m.name, me = m.me, size = AvatarSize.Sm) },
                            title = m.name, sub = m.sub,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(title: String, body: String) {
    val c = EvenlyTheme.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Text(title, color = c.ink, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(body, color = c.ink2, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ResultRow(onClick: () -> Unit, leading: @Composable () -> Unit, title: String, sub: String) {
    val c = EvenlyTheme.colors
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
        EvIcon(EvIcons.ChevR, size = 16.dp, tint = c.ink3)
    }
}

@Preview
@Composable
private fun SearchPreview() {
    EvenlyTheme {
        SearchOverlay(
            query = "din",
            expenseResults = listOf(SearchExpenseUi("1", "Dinner at La Negra", "Andrew paid", 24.0, 96.0)),
            memberResults = listOf(SearchMemberUi("Maya", "2 expenses")),
        )
    }
}
