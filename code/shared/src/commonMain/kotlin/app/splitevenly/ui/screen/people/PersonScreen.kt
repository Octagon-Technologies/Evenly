package app.splitevenly.ui.screen.people

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.splitevenly.domain.people.LinkCandidate
import app.splitevenly.domain.people.Person
import app.splitevenly.domain.people.PersonExpense
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * One person's page: what they owe you, a collapsible names card (same-name matches to Link or rule out,
 * names already linked, and "Link other names"), and a timeline of the expenses behind the total. Read
 * only: tapping an expense opens it where editing already lives. [person] null renders the bare page
 * while the data loads.
 */
@Composable
fun PersonScreen(
    person: Person?,
    onBack: () -> Unit,
    onRecordPayment: () -> Unit,
    onLinkMatch: (LinkCandidate) -> Unit,
    onRuleOut: (LinkCandidate) -> Unit,
    onUnlink: (String) -> Unit,
    onLinkOtherNames: () -> Unit,
    onOpenExpense: (PersonExpense) -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim(c.page)
        EvTopBar(
            title = person?.name ?: "",
            navIcon = { EvIconButton(EvIcons.Back, onClick = onBack) },
            showDivider = false,
        )
        if (person == null) return@Column
        val byMonth = person.expenses.groupBy { monthLabel(it.expenseDate) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "hero") { Hero(person, onRecordPayment) }
            item(key = "names") { NamesCard(person, onLinkMatch, onRuleOut, onUnlink, onLinkOtherNames) }
            byMonth.forEach { (month, expenses) ->
                item(key = "m-$month") {
                    Text(
                        month.uppercase(),
                        style = t.sectionLabel.copy(color = c.ink3),
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                    )
                }
                items(expenses, key = { "e-" + it.groupId + it.expenseId }) { e -> ExpenseRow(e) { onOpenExpense(e) } }
            }
            if (person.offsets.isNotEmpty()) {
                item(key = "offsets") {
                    Column(Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        person.offsets.forEach { o ->
                            Text(
                                "You owe ${person.name} ${moneySubunits(
                                    o.amountSubunits,
                                    o.currency,
                                )} in ${o.groupName}, so that group's total is lower.",
                                style = t.caption.copy(color = c.ink3),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(
    person: Person,
    onRecordPayment: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    EvCard(padded = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EvAvatar(person.name, size = AvatarSize.Md)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (person.owesYou) "Owes you" else "All paid back", style = t.caption.copy(color = c.ink3))
                Text(
                    if (person.owesYou) totalsText(person.totals) else "Nothing owed",
                    style = t.screenTitle.copy(color = if (person.owesYou) c.settled else c.ink2, fontWeight = FontWeight.ExtraBold),
                )
            }
        }
        if (person.owesYou) {
            Spacer(Modifier.height(12.dp))
            EvButton(onClick = onRecordPayment, variant = ButtonVariant.Primary) { Text("Record payment") }
        }
    }
}

@Composable
private fun NamesCard(
    person: Person,
    onLinkMatch: (LinkCandidate) -> Unit,
    onRuleOut: (LinkCandidate) -> Unit,
    onUnlink: (String) -> Unit,
    onLinkOtherNames: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    val matches = person.sameNameMatches
    // Nothing to review and nothing linked: the button alone, not a card that opens onto one button.
    if (matches.isEmpty() && person.otherNames.isEmpty()) {
        LinkOtherNamesButton(onLinkOtherNames)
        return
    }
    var open by rememberSaveable(person.key) { mutableStateOf(matches.isNotEmpty()) }
    val label =
        when {
            matches.isNotEmpty() -> "Same name elsewhere · ${matches.size}"
            else -> "Other names · ${person.otherNames.size}"
        }
    EvCard(padded = true, modifier = Modifier.animateContentSize()) {
        SectionToggle(label, open) { open = !open }
        if (!open) return@EvCard
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            matches.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "\"${m.name}\" in ${m.groupEmoji} ${m.groupName}",
                            style = t.body,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("Owes you ${totalsText(m.owedByCurrency)}", style = t.caption.copy(color = c.ink3))
                    }
                    EvButton(
                        onClick = { onLinkMatch(m) },
                        small = true,
                        fillMaxWidth = false,
                        variant = ButtonVariant.Primary,
                    ) { Text("Link") }
                    Spacer(Modifier.width(4.dp))
                    EvButton(onClick = { onRuleOut(m) }, small = true, fillMaxWidth = false, variant = ButtonVariant.Text) { Text("No") }
                }
            }
            person.otherNames.forEach { n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("\"${n.name}\" in ${n.groupEmoji} ${n.groupName}", style = t.body, modifier = Modifier.weight(1f), maxLines = 2)
                    EvButton(
                        onClick = { onUnlink(n.userId) },
                        small = true,
                        fillMaxWidth = false,
                        variant = ButtonVariant.Text,
                    ) { Text("Unlink") }
                }
            }
            LinkOtherNamesButton(onLinkOtherNames)
        }
    }
}

@Composable
private fun LinkOtherNamesButton(onClick: () -> Unit) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, c.borderStrong, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ Link other names", style = t.buttonSmall.copy(color = c.blueText))
    }
}

@Composable
private fun ExpenseRow(
    e: PersonExpense,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    EvCard(padded = true, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(e.expenseDate.takeLast(2).trimStart('0'), style = t.rowTitle)
                Text(monthShort(e.expenseDate), style = t.micro.copy(color = c.ink3))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = t.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val where = if (e.isQuick) "One-time" else e.groupName
                Text(
                    listOfNotNull(where, e.alias?.let { "as $it" }).joinToString(" · "),
                    style = t.caption.copy(color = c.ink3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(moneySubunits(e.amountSubunits, e.currency), style = t.rowValue.copy(color = c.settled, fontWeight = FontWeight.Bold))
        }
    }
}

private val MONTHS =
    listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/** "November 2026" from an ISO day; the raw text if it doesn't parse. */
internal fun monthLabel(iso: String): String {
    val name = monthName(iso) ?: return iso
    return "$name ${iso.take(4)}"
}

internal fun monthShort(iso: String): String = monthName(iso)?.take(3) ?: ""

private fun monthName(iso: String): String? =
    if (iso.length <
        7
    ) {
        null
    } else {
        iso.substring(5, 7).toIntOrNull()?.let { MONTHS.getOrNull(it - 1) }
    }
