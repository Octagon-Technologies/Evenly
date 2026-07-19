package da.chelimo.sharecost.ui.screen.home

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.domain.fx.CurrencyInfo
import da.chelimo.sharecost.domain.fx.FxCurrencyDefaults
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScSelectField
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

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
    val c = ShareCostTheme.colors
    val emojis = listOf("🏝️", "🏠", "🍝", "✈️", "🎉", "🎂")
    var sel by remember { mutableStateOf(emojis.first()) }
    var customEmoji by remember { mutableStateOf<String?>(null) }
    var editingCustom by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf(TextFieldValue("")) }
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("USD") }
    var showCurrencyPicker by remember { mutableStateOf(false) }

    // systemBarsPadding insets top + bottom so the top bar clears the status-bar icons and the footer
    // button clears the nav bar; the page-colored background still fills behind both bars (no seam).
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        ScTopBar(title = "New group", navIcon = { ScIconButton(ScIcons.Back, onClick = onDismiss) })
        Column(
            Modifier.fillMaxSize().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    Text(sel, fontSize = 38.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    emojis.forEach { e ->
                        EmojiTile(e, selected = e == sel, onClick = {
                            sel = e
                            customEmoji = null
                            editingCustom = false
                        })
                    }
                    if (editingCustom) {
                        val focusRequester = remember { FocusRequester() }
                        val keyboard = LocalSoftwareKeyboardController.current
                        BasicTextField(
                            value = customInput,
                            onValueChange = { new ->
                                customInput = new
                                if (new.text.isNotEmpty()) {
                                    customEmoji = new.text
                                    sel = new.text
                                    editingCustom = false
                                    keyboard?.hide()
                                }
                            },
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                                .background(c.surface)
                                .border(2.dp, c.blue, RoundedCornerShape(11.dp))
                                .focusRequester(focusRequester),
                            textStyle = LocalTextStyle.current.copy(fontSize = 20.sp, textAlign = TextAlign.Center),
                            singleLine = true,
                        )
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    } else {
                        val custom = customEmoji
                        if (custom != null) {
                            EmojiTile(custom, selected = custom == sel, onClick = {
                                if (sel == custom) {
                                    customInput = TextFieldValue(custom, TextRange(0, custom.length))
                                    editingCustom = true
                                } else {
                                    sel = custom
                                }
                            })
                        } else {
                            Box(
                                Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                                    .background(c.surface)
                                    .border(1.dp, c.border, RoundedCornerShape(11.dp))
                                    .clickable {
                                        customInput = TextFieldValue("")
                                        editingCustom = true
                                    },
                                contentAlignment = Alignment.Center,
                            ) { ScIcon(ScIcons.Plus, size = 16.dp, tint = c.ink2) }
                        }
                    }
                }
            }
            ScField("Group name") { ScTextField(name, { name = it }, placeholder = "e.g. Trip to Puerto Rico") }
            ScField("Base currency") {
                val currencyLabel = currencies.firstOrNull { it.code == currency }?.name
                ScSelectField(
                    if (currencyLabel != null) "$currency ($currencyLabel)" else currency,
                    onClick = { showCurrencyPicker = true },
                    leading = { ScIcon(ScIcons.Globe, size = 18.dp, tint = c.ink2) },
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            ScButton("Create group", { onCreate(name, sel, currency) }, leadingIcon = ScIcons.Check)
        }
    }

    if (showCurrencyPicker) {
        CurrencyPickerScreen(
            currencies = currencies,
            selected = currency,
            onSelect = { currency = it; showCurrencyPicker = false },
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
    val c = ShareCostTheme.colors
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val results = if (q.isEmpty()) currencies else currencies.filter {
        it.code.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true)
    }
    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        ScTopBar(title = "Base currency", navIcon = { ScIconButton(ScIcons.Back, onClick = onDismiss) })
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            ScTextField(
                query, { query = it }, minHeight = 44.dp,
                leading = { ScIcon(ScIcons.Search, size = 18.dp, tint = c.ink3) },
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
                        if (code == selected) ScIcon(ScIcons.Check, size = 20.dp, tint = c.blueText)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmojiTile(emoji: String, selected: Boolean, onClick: () -> Unit) {
    val c = ShareCostTheme.colors
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
            .background(if (selected) c.blueTint else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.blue else c.border, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(emoji, fontSize = 22.sp) }
}

@Preview
@Composable
private fun NewGroupPreview() {
    ShareCostTheme { NewGroupSheet() }
}
