package app.splitevenly.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.fx.CurrencyInfo
import app.splitevenly.domain.fx.FxCurrencyDefaults
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvEmojiPicker
import app.splitevenly.ui.components.EvEmojiPresets
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvSelectField
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.groupIconLabel
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * 5 · New group — a real full-screen destination, not a sheet. It used to render over a dimmed
 * scrim with tap-outside-to-dismiss as the only way out; a tester couldn't tell it was dismissible
 * and got stuck. A back arrow in a normal top bar removes that ambiguity entirely.
 */
@Composable
fun NewGroupSheet(
    onDismiss: () -> Unit = {},
    currencies: List<CurrencyInfo> = FxCurrencyDefaults.fallback,
    onCreate: (name: String, emoji: String, baseCurrency: String) -> Unit = { _, _, _ -> },
) {
    val c = EvenlyTheme.colors
    var sel by remember { mutableStateOf(EvEmojiPresets.first()) }
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("USD") }
    var showCurrencyPicker by remember { mutableStateOf(false) }

    // systemBarsPadding insets top + bottom so the top bar clears the status-bar icons and the footer
    // button clears the nav bar; the page-colored background still fills behind both bars (no seam).
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(title = "New group", navIcon = { EvIconButton(EvIcons.Back, onClick = onDismiss) })
        Column(
            Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(
                            72.dp,
                        ).clip(RoundedCornerShape(22.dp))
                        .background(c.surface)
                        .border(1.dp, c.border, RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(groupIconLabel(sel, name), fontSize = 38.sp, color = c.ink)
                }
                EvEmojiPicker(selected = sel, onSelect = { sel = it })
            }
            EvField("Group name") { EvTextField(name, { name = it }, placeholder = "e.g. Trip to Puerto Rico") }
            EvField("Base currency") {
                val currencyLabel = currencies.firstOrNull { it.code == currency }?.name
                EvSelectField(
                    if (currencyLabel != null) "$currency ($currencyLabel)" else currency,
                    onClick = { showCurrencyPicker = true },
                    leading = { EvIcon(EvIcons.Globe, size = 18.dp, tint = c.ink2) },
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            EvButton("Create group", { onCreate(name, sel, currency) }, leadingIcon = EvIcons.Check)
        }
    }

    if (showCurrencyPicker) {
        CurrencyPickerScreen(
            currencies = currencies,
            selected = currency,
            onSelect = {
                currency = it
                showCurrencyPicker = false
            },
            onDismiss = { showCurrencyPicker = false },
        )
    }
}

/**
 * A real full-screen destination (not a bottom sheet) so the currency list — up to every code the
 * FX provider supports — always has the whole screen to scroll in and can never grow up behind the
 * status bar the way the old unbounded-height sheet did. `systemBarsPadding()` clears both bars,
 * matching [SearchOverlay]'s pattern. Search matches on code OR name.
 */
@Composable
private fun CurrencyPickerScreen(
    currencies: List<CurrencyInfo>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val results =
        if (q.isEmpty()) {
            currencies
        } else {
            currencies.filter {
                it.code.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true)
            }
        }
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(title = "Base currency", navIcon = { EvIconButton(EvIcons.Back, onClick = onDismiss) })
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            EvTextField(
                query,
                { query = it },
                minHeight = 44.dp,
                leading = { EvIcon(EvIcons.Search, size = 18.dp, tint = c.ink3) },
                placeholder = "Search by code or name",
            )
        }
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No currencies match “$q”", color = c.ink2, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                results.forEach { (code, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(code) }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(code, color = c.ink, fontSize = 15.sp)
                            Text(label, color = c.ink2, fontSize = 13.sp)
                        }
                        if (code == selected) EvIcon(EvIcons.Check, size = 20.dp, tint = c.blueText)
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun NewGroupPreview() {
    EvenlyTheme { NewGroupSheet() }
}
