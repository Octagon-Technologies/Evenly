package app.splitevenly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * A group's icon picker: one row of "None", the device's recent emojis, and a tile that opens the catalog
 * inline beneath it. An empty [selected] means no emoji, which is a real choice and not an unset state.
 *
 * The row shows [recents] as they will look once this choice is saved: a [selected] emoji outside the deck
 * (picked from the catalog, or an existing group's emoji) takes the newest slot and the oldest one drops,
 * so the row never wraps and never hides what is selected. The deck itself is only written by the caller,
 * on save, so browsing the catalog doesn't churn it.
 *
 * The catalog opens inline rather than as a sheet because both callers are already a sheet or modal.
 */
@Composable
fun EvEmojiPicker(
    selected: String,
    onSelect: (String) -> Unit,
    recents: List<String> = EvEmojiDefaults,
) {
    val c = EvenlyTheme.colors
    var catalogOpen by remember { mutableStateOf(false) }
    val row = pushRecentEmoji(recents, selected)

    // Full width and centered so opening the catalog (which fills the width) doesn't slide the row sideways.
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // "None" is a tile rather than a clear button, so that not having an emoji looks like the
            // other choices instead of like undoing one.
            EvEmojiTileBox(selected = selected.isEmpty(), onClick = {
                onSelect("")
                catalogOpen = false
            }) { Text("None", color = c.ink2, fontSize = 11.sp) }
            row.forEach { e ->
                EvEmojiTile(e, selected = e == selected, onClick = {
                    onSelect(e)
                    catalogOpen = false
                })
            }
            EvEmojiTileBox(selected = catalogOpen, onClick = { catalogOpen = !catalogOpen }) {
                EvIcon(if (catalogOpen) EvIcons.Close else EvIcons.Plus, size = 16.dp, tint = if (catalogOpen) c.blueText else c.ink2)
            }
        }
        if (catalogOpen) {
            EvEmojiCatalogPanel(onPick = { e ->
                onSelect(e)
                catalogOpen = false
            })
        }
    }
}

@Composable
private fun EvEmojiCatalogPanel(onPick: (String) -> Unit) {
    val c = EvenlyTheme.colors
    var category by remember { mutableStateOf(EvEmojiCatalog.first()) }
    var typing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EvEmojiCatalog.forEach { cat ->
                val on = cat == category
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) c.blueTint else c.surface)
                        .border(1.dp, if (on) c.blue else c.border, RoundedCornerShape(50))
                        .clickable { category = cat }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Text(cat.label, fontSize = 12.sp, color = if (on) c.blueText else c.ink2) }
            }
        }
        category.emojis.chunked(CATALOG_COLUMNS).forEach { line ->
            Row(Modifier.fillMaxWidth()) {
                line.forEach { e ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(e) },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 24.sp) }
                }
                repeat(CATALOG_COLUMNS - line.size) { Box(Modifier.weight(1f)) }
            }
        }
        if (typing) {
            EvEmojiTypeField(onPick)
        } else {
            Text(
                "Not here? Type any emoji",
                fontSize = 13.sp,
                color = c.blueText,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { typing = true }
                        .padding(vertical = 8.dp),
            )
        }
    }
}

/** The keyboard escape hatch: the first character typed becomes the pick and closes the keyboard. */
@Composable
private fun EvEmojiTypeField(onPick: (String) -> Unit) {
    val c = EvenlyTheme.colors
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var input by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicTextField(
            value = input,
            onValueChange = { new ->
                input = new
                if (new.isNotBlank()) {
                    keyboard?.hide()
                    onPick(new.trim())
                }
            },
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(c.surface)
                    .border(2.dp, c.blue, RoundedCornerShape(11.dp))
                    .focusRequester(focusRequester),
            textStyle = LocalTextStyle.current.copy(fontSize = 20.sp, textAlign = TextAlign.Center),
            singleLine = true,
        )
        Text("Open your emoji keyboard and pick one", fontSize = 13.sp, color = c.ink2)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

private const val CATALOG_COLUMNS = 7

@Composable
private fun EvEmojiTile(
    emoji: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    EvEmojiTileBox(selected = selected, onClick = onClick) { Text(emoji, fontSize = 22.sp) }
}

@Composable
private fun EvEmojiTileBox(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = EvenlyTheme.colors
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) c.blueTint else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.blue else c.border, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
