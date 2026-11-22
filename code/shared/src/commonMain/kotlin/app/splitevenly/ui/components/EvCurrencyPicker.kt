package app.splitevenly.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.fx.CurrencyInfo
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Centered, searchable currency card shared by Profile, New group and Group settings. The list is
 * height-capped and scrolls inside the card because [EvModalScaffold] already scrolls its own content;
 * search matches code or name. Picking a row selects and dismisses.
 */
@Composable
fun EvCurrencyPickerModal(
    title: String,
    currencies: List<CurrencyInfo>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val results =
        if (q.isEmpty()) {
            // The current choice leads, so what is selected is visible without scrolling.
            currencies.sortedByDescending { it.code == selected }
        } else {
            currencies.filter { it.code.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true) }
        }
    EvModalScaffold(onDismiss = onDismiss) {
        Text(title, style = t.sheetTitle, modifier = Modifier.padding(bottom = 12.dp))
        EvTextField(
            query,
            { query = it },
            minHeight = 44.dp,
            leading = { EvIcon(EvIcons.Search, size = 18.dp, tint = c.ink3) },
            placeholder = "Search code or name",
        )
        if (results.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("No currencies match “$q”", color = c.ink2, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().height(300.dp).padding(top = 8.dp)) {
                items(results, key = { it.code }) { info ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(info.code) }.padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            info.code,
                            color = c.ink,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = EvenlyTheme.monoFamily,
                        )
                        Text(info.name, color = c.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (info.code == selected) EvIcon(EvIcons.Check, size = 20.dp, tint = c.blueText)
                    }
                }
            }
        }
    }
}
