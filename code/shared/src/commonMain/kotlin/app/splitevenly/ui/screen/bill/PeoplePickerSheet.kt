package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCheck
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.theme.EvenlyTheme

/** Someone the picker can offer: a group member, or a person already on the bill. */
data class PickerPersonUi(
    val userId: String,
    val name: String,
    val isMe: Boolean,
)

/** What a picker visit chose: existing people by id, plus names typed in to be created on Done. */
data class PeoplePick(
    val userIds: Set<String>,
    val newNames: List<String>,
)

private const val NEW_PREFIX = "new:"

/**
 * One sheet for "who's in": the whole roster with the current selection ticked, plus a create row on
 * top. Nothing is written until Done, so a person created here and then unticked is never made.
 *
 * Rows are ordered once, on open (ticked first), and never re-sort while the user taps. A typed name
 * that matches someone already listed ticks them instead of creating a duplicate.
 */
@Composable
internal fun PeoplePickerSheet(
    title: String,
    sub: String?,
    people: List<PickerPersonUi>,
    initiallySelected: Set<String>,
    sectionLabel: String,
    // Tag on the rows that were ticked when the sheet opened ("On this dish"); null for no tag.
    selectedTag: String?,
    summary: (count: Int) -> String,
    onDone: (PeoplePick) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val ordered = remember { people.sortedByDescending { it.userId in initiallySelected } }
    var selected by remember { mutableStateOf(initiallySelected) }
    var newNames by remember { mutableStateOf(emptyList<String>()) }
    var typed by remember { mutableStateOf("") }
    var nameMissing by remember { mutableStateOf(false) }
    var noneSelected by remember { mutableStateOf(false) }
    var dismissRequested by remember { mutableStateOf(false) }

    fun label(p: PickerPersonUi) = if (p.isMe) "You" else p.name

    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
        noneSelected = false
    }

    fun create() {
        val name = typed.trim()
        if (name.isEmpty()) {
            nameMissing = true
            return
        }
        val existing = ordered.firstOrNull { label(it).equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
        val key =
            when {
                existing != null -> {
                    existing.userId
                }

                else -> {
                    val dup = newNames.firstOrNull { it.equals(name, ignoreCase = true) }
                    if (dup == null) newNames = newNames + name
                    NEW_PREFIX + (dup ?: name)
                }
            }
        selected = selected + key
        typed = ""
        nameMissing = false
        noneSelected = false
    }

    EvSheetScaffold(
        onDismiss = onDismiss,
        title = title,
        sub = sub,
        dimBackground = true,
        dismissRequested = dismissRequested,
    ) {
        SectionLabel("Create a new person")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EvTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    nameMissing = false
                },
                placeholder = "Name",
                minHeight = 46.dp,
                isError = nameMissing,
                modifier = Modifier.weight(1f),
            )
            EvButton("Create", { create() }, variant = ButtonVariant.PrimarySolid, small = true, fillMaxWidth = false)
        }
        if (nameMissing) {
            Text("Type a name first", color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }

        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if (ordered.isNotEmpty()) SectionLabel(sectionLabel)
            ordered.forEach { p ->
                PersonRow(
                    name = label(p),
                    avatarName = p.name,
                    me = p.isMe,
                    tag = selectedTag?.takeIf { p.userId in initiallySelected },
                    note = null,
                    checked = p.userId in selected,
                    onClick = { toggle(p.userId) },
                )
            }
            if (newNames.isNotEmpty()) {
                SectionLabel("New on this bill")
                newNames.forEach { name ->
                    PersonRow(
                        name = name,
                        avatarName = name,
                        me = false,
                        tag = null,
                        note = "Added when you tap Done",
                        checked = NEW_PREFIX + name in selected,
                        onClick = { toggle(NEW_PREFIX + name) },
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (noneSelected) "Pick at least one person" else summary(selected.size),
                color = if (noneSelected) c.danger else c.ink2,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(end = 10.dp),
            )
            EvButton(
                "Done",
                {
                    if (selected.isEmpty()) {
                        noneSelected = true
                    } else {
                        onDone(
                            PeoplePick(
                                userIds = selected.filterNot { it.startsWith(NEW_PREFIX) }.toSet(),
                                newNames = newNames.filter { NEW_PREFIX + it in selected },
                            ),
                        )
                        dismissRequested = true
                    }
                },
                variant = ButtonVariant.PrimarySolid,
                fillMaxWidth = false,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = EvenlyTheme.text.sectionLabel,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 2.dp),
    )
}

@Composable
private fun PersonRow(
    name: String,
    // The real name, so "You" still shows your initials like the chips on the card do.
    avatarName: String,
    me: Boolean,
    tag: String?,
    note: String?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .then(if (checked) Modifier.background(c.blueTint) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EvAvatar(name = avatarName, me = me, size = AvatarSize.Sm)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (tag != null) {
                    Box(Modifier.clip(RoundedCornerShape(99.dp)).background(c.page).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text(tag, color = c.blueText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (note != null) Text(note, color = c.ink3, fontSize = 12.sp)
        }
        EvCheck(checked = checked, onCheckedChange = { onClick() })
    }
}
