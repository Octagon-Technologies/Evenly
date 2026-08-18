package app.splitevenly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** The presets offered for a group icon. */
val EvEmojiPresets = listOf("🏝️", "🏠", "🍝", "✈️", "🎉", "🎂")

/**
 * A group's icon picker: "None", the presets, and one editable tile for anything else. An empty
 * [selected] means no emoji, which is a real choice here and not an unset state.
 *
 * The custom tile is a `BasicTextField` rather than a grid of every emoji, because the system keyboard
 * already has the picker (and the search in it) that we would otherwise be reimplementing. A [selected]
 * value outside [presets] starts life in that tile, so an existing group's emoji is visible and
 * re-editable instead of silently reading as "nothing chosen".
 *
 * `FlowRow` because the tile count now exceeds one row on a narrow phone, and inside a modal it has
 * less width still.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EvEmojiPicker(
    selected: String,
    onSelect: (String) -> Unit,
    presets: List<String> = EvEmojiPresets,
) {
    val c = EvenlyTheme.colors
    var customEmoji by remember { mutableStateOf(selected.takeIf { it.isNotBlank() && it !in presets }) }
    var editingCustom by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf(TextFieldValue("")) }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // "None" is a tile rather than a clear button on the field, so that not having an emoji looks
        // like the other choices instead of like undoing one.
        EvEmojiTileBox(selected = selected.isEmpty(), onClick = {
            onSelect("")
            editingCustom = false
        }) { Text("None", color = c.ink2, fontSize = 11.sp) }
        presets.forEach { e ->
            EvEmojiTile(e, selected = e == selected, onClick = {
                onSelect(e)
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
                        onSelect(new.text)
                        editingCustom = false
                        keyboard?.hide()
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
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            val custom = customEmoji
            if (custom != null) {
                EvEmojiTile(custom, selected = custom == selected, onClick = {
                    // Second tap on the already-selected custom tile reopens it for editing; the first
                    // tap only selects, so switching back from a preset never wipes what was typed.
                    if (selected == custom) {
                        customInput = TextFieldValue(custom, TextRange(0, custom.length))
                        editingCustom = true
                    } else {
                        onSelect(custom)
                    }
                })
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(c.surface)
                        .border(1.dp, c.border, RoundedCornerShape(11.dp))
                        .clickable {
                            customInput = TextFieldValue("")
                            editingCustom = true
                        },
                    contentAlignment = Alignment.Center,
                ) { EvIcon(EvIcons.Plus, size = 16.dp, tint = c.ink2) }
            }
        }
    }
}

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
