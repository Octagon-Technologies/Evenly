package app.splitevenly.ui.screen.people

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.splitevenly.domain.people.PeopleSummary
import app.splitevenly.domain.people.Person
import app.splitevenly.domain.people.PersonGroup
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvEmptyState
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.theme.EvenlyTheme

/** "$150.00" or "$150.00 + KES 2,000.00": currencies are listed, never added together. */
fun totalsText(totals: Map<String, Long>): String =
    totals.entries.sortedByDescending { it.value }.joinToString(" + ") { (cur, v) -> moneySubunits(v, cur) }

/**
 * The People tab: everyone who owes you, across every group. [summary] null means the first pull has not
 * landed yet, so the page stays blank rather than claiming nobody owes you.
 */
@Composable
fun PeopleListScreen(
    summary: PeopleSummary?,
    userName: String,
    onOpenPerson: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    var settledOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.page)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("People", modifier = Modifier.weight(1f), style = t.pageTitle.copy(fontWeight = FontWeight.ExtraBold))
            EvAvatar(userName, me = true, size = AvatarSize.Md, onClick = onOpenSettings)
        }
        when {
            summary == null -> {
                Unit
            }

            summary.owing.isEmpty() && summary.settled.isEmpty() -> {
                EvEmptyState(
                    title = "Nobody owes you",
                    text = "When someone owes you in any group, they show up here with the total.",
                    icon = EvIcons.Users,
                )
            }

            else -> {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "hero") { TotalCard(summary) }
                    if (summary.owing.isEmpty()) {
                        item(key = "none") {
                            Text("Nobody owes you right now.", style = t.bodyMuted, modifier = Modifier.padding(4.dp))
                        }
                    }
                    if (summary.owing.isNotEmpty()) {
                        item(key = "owe-label") {
                            Text(
                                "OWE YOU",
                                style = t.sectionLabel.copy(color = c.ink3),
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                            )
                        }
                    }
                    items(summary.owing, key = { it.key }) { person -> PersonRow(person) { onOpenPerson(person.key) } }
                    if (summary.settled.isNotEmpty()) {
                        item(key = "settled-header") {
                            SectionToggle("All settled · ${summary.settled.size}", settledOpen) { settledOpen = !settledOpen }
                        }
                        if (settledOpen) {
                            items(summary.settled, key = { "s-" + it.key }) { person -> SettledRow(person) { onOpenPerson(person.key) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalCard(summary: PeopleSummary) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    EvCard(Modifier.fillMaxWidth(), padded = true) {
        Text("OWED TO YOU", style = t.sectionLabel.copy(color = c.ink3))
        Spacer(Modifier.height(4.dp))
        Text(
            if (summary.totals.isEmpty()) moneySubunits(0, "USD") else totalsText(summary.totals),
            style = t.pageTitle.copy(color = c.settled, fontWeight = FontWeight.ExtraBold),
        )
        val groups =
            summary.owing
                .flatMap { p -> p.groups.map { it.groupId } }
                .toSet()
                .size
        val people = summary.owing.size
        Text(
            "$people ${if (people == 1) "person" else "people"} · $groups ${if (groups == 1) "group or split" else "groups and splits"}",
            style = t.caption.copy(color = c.ink2),
        )
    }
}

/** The grey group line: up to two group names, then a blue "+N more" that opens every group in place. */
@Composable
private fun PersonRow(
    person: Person,
    onOpen: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    var expanded by rememberSaveable(person.key) { mutableStateOf(false) }
    val names = person.groups.map { it.groupName }.distinct()
    val extra = names.size - 2
    EvCard(padded = true, onClick = onOpen, modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EvAvatar(person.name, size = AvatarSize.Md)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        person.name,
                        style = t.rowTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (person.sameNameMatches.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).background(c.blueText, CircleShape))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        names.take(2).joinToString(", "),
                        style = t.caption.copy(color = c.ink3),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (extra > 0) {
                        Text(
                            if (expanded) "  Show less" else "  +$extra more",
                            style = t.caption.copy(color = c.blueText, fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            modifier = Modifier.clickable { expanded = !expanded },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(totalsText(person.totals), style = t.rowValue.copy(color = c.settled, fontWeight = FontWeight.Bold))
        }
        if (expanded && extra > 0) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                person.groups.forEach { GroupLine(it) }
            }
        }
    }
}

@Composable
internal fun GroupLine(g: PersonGroup) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${g.groupEmoji}  ${g.groupName}",
            style = t.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        g.alias?.let { Text("  as $it", style = t.caption.copy(color = c.ink3), maxLines = 1) }
        Spacer(Modifier.weight(1f))
        Text(moneySubunits(g.amountSubunits, g.currency), style = t.rowValue.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun SettledRow(
    person: Person,
    onOpen: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    EvCard(padded = true, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EvAvatar(person.name, size = AvatarSize.Md)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(person.name, style = t.rowTitle.copy(color = c.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Paid you back", style = t.caption.copy(color = c.ink3))
            }
        }
    }
}

@Composable
internal fun SectionToggle(
    label: String,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvIcon(EvIcons.ChevR, size = 16.dp, tint = c.ink3, modifier = Modifier.rotate(if (open) 90f else 0f))
        Spacer(Modifier.width(6.dp))
        Text(label.uppercase(), style = t.sectionLabel.copy(color = c.ink3))
    }
}
