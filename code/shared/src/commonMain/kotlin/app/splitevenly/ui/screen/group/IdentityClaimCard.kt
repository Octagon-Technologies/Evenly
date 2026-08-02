package app.splitevenly.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * One name with no account that the viewer has not answered yet, plus the evidence for deciding.
 * [amountLabel] is already formatted (and absent when the name's expenses span several currencies,
 * where one summed number would be meaningless).
 */
data class UnclaimedNameUi(
    val id: String,
    val name: String,
    val expenseCount: Int,
    val amountLabel: String?,
    val recentExpenses: List<Pair<String, String>> = emptyList(),
)

/**
 * "Is this you?" — the identity claim card on the group's Expenses tab.
 *
 * A group logs expenses against people by name before those people have the app. When they join, the
 * group has two of the same person: a name carrying real money and a fresh account carrying none. This
 * card is the question that fixes it, asked where people actually look.
 *
 * **Always expanded, never a collapsed pill.** A pill is opt-in, and not going looking is the exact
 * failure this exists to fix.
 *
 * **Never gated on the name matching.** The motivating case is someone added as "Chelimo" who signed up
 * as "Andrew": no match at all, and precisely the case that must not be missed. A single open name gets
 * the direct question; several get the list, with any plausible match ordered first. That is the only
 * thing matching is allowed to affect.
 *
 * **Three answers, never two.** A two-button card forces the unsure to lie, and most of them press the
 * dismissive one, which is the permanent one. "Later" decides nothing and costs nothing.
 *
 * The card carries **evidence**, not just names: "Are you Chelimo?" is unanswerable on its own, while
 * "Chelimo bought the airport taxi" is answerable in a second.
 */
@Composable
fun IdentityClaimCard(
    names: List<UnclaimedNameUi>,
    onThatsMe: (String) -> Unit,
    onNotMe: (String) -> Unit,
    onNoneOfThese: () -> Unit,
    onLater: () -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (names.isEmpty()) return
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(c.blueTint)
            .border(1.dp, c.border, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (names.size == 1) NamedShape(names.single(), onThatsMe, onNotMe, onLater)
        else ListShape(names, onThatsMe, onNotMe, onNoneOfThese, onLater, onSeeAll)
    }
}

/** One open name: ask about it directly. */
@Composable
private fun NamedShape(
    person: UnclaimedNameUi,
    onThatsMe: (String) -> Unit,
    onNotMe: (String) -> Unit,
    onLater: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EvAvatar(name = person.name, size = AvatarSize.Sm)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Are you ${person.name}?",
                color = c.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            )
            Text(
                evidenceLine(person),
                color = c.ink2,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
            )
        }
    }
    if (person.recentExpenses.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            person.recentExpenses.forEach { (title, amount) -> ExpenseEvidenceChip(title, amount) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        EvButton("That's me", { onThatsMe(person.id) }, modifier = Modifier.weight(1f))
        EvButton("Not me", { onNotMe(person.id) }, variant = ButtonVariant.Secondary, modifier = Modifier.weight(1f))
        EvButton("Later", onLater, variant = ButtonVariant.Text)
    }
}

/** Several open names: list them with their evidence and let one tap settle each. */
@Composable
private fun ListShape(
    names: List<UnclaimedNameUi>,
    onThatsMe: (String) -> Unit,
    onNotMe: (String) -> Unit,
    onNoneOfThese: () -> Unit,
    onLater: () -> Unit,
    onSeeAll: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Were you here before you joined?",
            color = c.ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
        )
        Text(
            "These names have expenses but no account. If one is you, claim it so the history lands on your balance.",
            color = c.ink2,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 20.sp,
        )
    }
    // At most three inline: a fourth interstitial buries the feed this card sits on top of. The rest
    // are one tap away and ordered so the names most worth asking about are the ones shown.
    names.take(INLINE_CAP).forEach { person ->
        NameRow(person, onThatsMe, onNotMe)
    }
    if (names.size > INLINE_CAP) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onSeeAll)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "See all ${names.size}",
                color = c.blueText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            EvIcon(EvIcons.ChevR, size = 16.dp, tint = c.blueText)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EvButton(
            "None of these are me",
            onNoneOfThese,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.weight(1f),
        )
        EvButton("Later", onLater, variant = ButtonVariant.Text)
    }
}

@Composable
private fun NameRow(person: UnclaimedNameUi, onThatsMe: (String) -> Unit, onNotMe: (String) -> Unit) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(c.page).border(1.dp, c.border, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EvAvatar(name = person.name, size = AvatarSize.Sm)
            Column(Modifier.weight(1f)) {
                Text(person.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(evidenceLine(person), color = c.ink2, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EvButton("That's me", { onThatsMe(person.id) }, modifier = Modifier.weight(1f))
            EvButton("No", { onNotMe(person.id) }, variant = ButtonVariant.Secondary, modifier = Modifier.weight(1f))
        }
    }
}

/** A small receipt chip: the actual expense, which is what makes the question answerable. */
@Composable
private fun ExpenseEvidenceChip(title: String, amount: String) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier.clip(shape).background(c.page).border(1.dp, c.border, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(c.surface),
            contentAlignment = Alignment.Center,
        ) {
            EvIcon(EvIcons.Receipt, size = 14.dp, tint = c.ink2)
        }
        Column {
            Text(title, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(amount, color = c.ink2, fontSize = 12.sp, fontFamily = EvenlyTheme.monoFamily)
        }
    }
}

/**
 * The closing note. Once the last name is answered the card is replaced by this for the rest of the
 * session, so the tap visibly did something instead of the card simply vanishing.
 */
@Composable
fun IdentityClaimDoneNote(modifier: Modifier = Modifier) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape).background(c.surface).border(1.dp, c.border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EvIcon(EvIcons.Check, size = 16.dp, tint = c.ink2)
        Text(
            "That's every name in this group. We won't ask again.",
            color = c.ink2,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The evidence in one line. A name with nothing booked against it still gets asked about (it is still a
 * candidate identity), it just says so plainly instead of showing a zero.
 */
private fun evidenceLine(person: UnclaimedNameUi): String = when {
    person.expenseCount == 0 -> "No expenses yet, and no account."
    person.amountLabel == null -> "${person.expenseCount} expenses, and no account."
    person.expenseCount == 1 -> "1 expense, ${person.amountLabel}, and no account."
    else -> "${person.expenseCount} expenses, ${person.amountLabel}, and no account."
}

/** Three names inline; beyond that the card would bury the feed it sits on top of. */
private const val INLINE_CAP = 3

@Preview
@Composable
private fun IdentityClaimCardNamedPreview() {
    EvenlyTheme {
        IdentityClaimCard(
            names = listOf(
                UnclaimedNameUi(
                    "p1", "Chelimo", 3, "$56.50",
                    listOf("Dinner at La Negra" to "$24.00", "Airport taxi" to "$14.50", "Cenote trip" to "$18.00"),
                ),
            ),
            onThatsMe = {}, onNotMe = {}, onNoneOfThese = {}, onLater = {}, onSeeAll = {},
        )
    }
}

@Preview
@Composable
private fun IdentityClaimCardListPreview() {
    EvenlyTheme {
        IdentityClaimCard(
            names = listOf(
                UnclaimedNameUi("p1", "Chelimo", 3, "$56.50"),
                UnclaimedNameUi("p2", "Andrew (2)", 2, "$27.40"),
                UnclaimedNameUi("p3", "Tyler R.", 1, "$31.00"),
                UnclaimedNameUi("p4", "Sam", 0, null),
            ),
            onThatsMe = {}, onNotMe = {}, onNoneOfThese = {}, onLater = {}, onSeeAll = {},
        )
    }
}
