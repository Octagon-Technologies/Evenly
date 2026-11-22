package app.splitevenly.ui.screen.quick

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** Longest typed-in name we accept; matches what a placeholder row shows without truncating badly. */
private const val MAX_NAME_LENGTH = 40

/**
 * Step 1 of a quick split: just names (mockup 3). Return adds the name and keeps the keyboard up for the
 * next one; the creator is always in. Names are all we need, because on the link each person taps
 * their own name and claims it.
 *
 * People come before the bill (the mockup has them second) because the bill step is the full expense
 * editor, which needs the people to show the split, the payer and "By item". DI-free.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickSplitNamesScreen(
    names: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    busy: Boolean = false,
    error: String? = null,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    var draft by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(names.size) { runCatching { focus.requestFocus() } }
    // The iOS keyboard keeps its own copy of the text across the clear on return, so the next name arrives
    // as "AnnJoe". Dropping the just-added name from the front of the first keystroke undoes that.
    var lastAdded by remember { mutableStateOf<String?>(null) }

    fun commit(): Boolean {
        val name = draft.trim()
        if (name.isEmpty()) return false
        if (names.any { it.equals(name, ignoreCase = true) } || name.equals("you", ignoreCase = true)) {
            hint = "$name is already on the list."
            return false
        }
        onAdd(name.take(MAX_NAME_LENGTH))
        lastAdded = draft
        draft = ""
        hint = null
        return true
    }

    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding().imePadding()) {
        EvTopBar(
            title = "Who's splitting?",
            subtitle = "Split a bill · Step 1 of 2",
            navIcon = { EvIconButton(EvIcons.Back, onClick = onBack) },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Type each person's name. They'll tap it on the link to see their share.", style = t.description)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NameChip("You", onRemove = null)
                names.forEachIndexed { i, name -> NameChip(name, onRemove = { onRemove(i) }) }
            }
            EvTextField(
                value = draft,
                onValueChange = {
                    val stale = lastAdded
                    draft = if (draft.isEmpty() && stale != null && it.length > stale.length && it.startsWith(stale)) it.removePrefix(stale) else it
                    lastAdded = null
                    hint = null
                },
                modifier = Modifier.focusRequester(focus),
                placeholder = if (names.isEmpty()) "e.g. Ann" else "Add another name",
                trailing = {
                    Text(
                        "Add",
                        color = c.blueText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(role = Role.Button) { commit() }.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                },
                onDone = { commit() },
            )
            (hint ?: error)?.let { Text(it, color = c.owe, fontSize = 13.sp) }
        }
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            val count = names.size + 1
            EvButton(
                text =
                    when {
                        busy -> "Setting it up…"
                        count == 1 -> "Next: add the bill"
                        else -> "Next: add the bill · $count people"
                    },
                onClick = {
                    if (busy) return@EvButton
                    val added = commit()
                    // Validated on tap, never greyed out (AGENTS.md §7): say what is missing instead.
                    if (names.isEmpty() && !added) hint = "Add at least one person." else onNext()
                },
            )
        }
    }
}

@Composable
private fun NameChip(
    name: String,
    onRemove: (() -> Unit)?,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(c.blueTint2), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = c.blueText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(name, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        onRemove?.let {
            Box(Modifier.size(22.dp).clip(CircleShape).clickable(role = Role.Button, onClick = it), contentAlignment = Alignment.Center) {
                EvIcon(EvIcons.Close, size = 14.dp, tint = c.ink3)
            }
        }
    }
}

@Preview
@Composable
private fun QuickSplitNamesPreview() {
    EvenlyTheme { QuickSplitNamesScreen(names = listOf("Ann", "Joe"), onAdd = {}, onRemove = {}, onBack = {}, onNext = {}) }
}
