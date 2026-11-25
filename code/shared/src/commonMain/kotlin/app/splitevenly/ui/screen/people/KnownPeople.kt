package app.splitevenly.ui.screen.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.splitevenly.domain.people.Person
import app.splitevenly.domain.people.normalizeName
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.theme.EvenlyTheme

/** Someone from the viewer's other groups, offered when adding a name to a group. */
data class KnownPersonUi(
    val key: String,
    val name: String,
    /** "New York, Boston +2 more". */
    val where: String,
)

/** Everyone not already in [groupId], for the "People you know" list. */
fun knownPeopleFor(
    everyone: List<Person>,
    groupId: String,
): List<KnownPersonUi> =
    everyone
        .filter { groupId !in it.groupIds }
        .map { p ->
            val names = (p.groups.map { it.groupName } + p.otherNames.map { it.groupName }).distinct()
            val where =
                when {
                    names.isEmpty() -> ""
                    names.size <= 2 -> names.joinToString(", ")
                    else -> names.take(2).joinToString(", ") + " +${names.size - 2} more"
                }
            KnownPersonUi(p.key, p.name, where)
        }

/** Matches for what has been typed so far: any word of the name starting with it, case ignored. */
fun matchKnown(
    known: List<KnownPersonUi>,
    typed: String,
): List<KnownPersonUi> {
    val q = normalizeName(typed)
    if (q.isEmpty()) return emptyList()
    return known.filter { k -> normalizeName(k.name).let { n -> n.startsWith(q) || n.split(" ").any { it.startsWith(q) } } }.take(4)
}

/**
 * "People you know" under a name field: picking one adds them under that name and links them to the
 * person they already are in your other groups, so a new group never makes a duplicate.
 */
@Composable
fun KnownPeopleList(
    matches: List<KnownPersonUi>,
    onPick: (KnownPersonUi) -> Unit,
) {
    if (matches.isEmpty()) return
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("PEOPLE YOU KNOW", style = t.sectionLabel.copy(color = c.ink3), modifier = Modifier.padding(bottom = 4.dp))
        matches.forEach { k ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(k) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EvAvatar(k.name, size = AvatarSize.Sm)
                Column(Modifier.weight(1f)) {
                    Text(k.name, style = t.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (k.where.isNotEmpty()) {
                        Text(
                            k.where,
                            style = t.caption.copy(color = c.ink3),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text("Add", style = t.buttonSmall.copy(color = c.blueText))
            }
        }
    }
}
