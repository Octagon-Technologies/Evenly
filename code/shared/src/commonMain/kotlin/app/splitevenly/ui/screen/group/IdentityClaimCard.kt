package app.splitevenly.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
)

/**
 * "Are any of these you?", the identity claim card on the group's Expenses tab.
 *
 * A group logs expenses against people by name before those people have the app. When they join, the
 * group has two of the same person: a name carrying real money and a fresh account carrying none. The
 * main place this is asked is now the join flow ([app.splitevenly.ui.screen.home.JoinClaimNamesScreen]);
 * this card is the catch-all for anyone who joined before that step existed, or whose group had not
 * synced to the device when they joined.
 *
 * **Always expanded, never a collapsed pill, and no "Later".** A pill is opt-in, and testers read
 * "Later" as "I don't have to do this" (owner call, 2026-10-03), so the card stays until answered.
 *
 * **A checklist with one button.** Each name is a [IdentityNameRow]; [IdentityChecklistButton] reads
 * "None of these are me" until something is ticked and "Confirm ..." after. Both open the confirm
 * sheet, so nothing is written from the card itself.
 *
 * **Never gated on the name matching.** The motivating case is someone added as "Chelimo" who signed up
 * as "Andrew": no match at all, and precisely the case that must not be missed. Matching only orders
 * the list.
 */
@Composable
fun IdentityClaimCard(
    names: List<UnclaimedNameUi>,
    onConfirm: (selected: List<UnclaimedNameUi>, shown: List<UnclaimedNameUi>) -> Unit,
    onNoneOfThese: (shown: List<UnclaimedNameUi>) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (names.isEmpty()) return
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(16.dp)
    // At most three inline: more buries the feed this card sits on top of. The rest are one tap away and
    // ordered so the names most worth asking about are the ones shown. The button answers only the
    // shown names, so the hidden ones surface next instead of being ruled out unseen.
    val shown = names.take(INLINE_CAP)
    var selectedIds by remember(shown.map { it.id }) { mutableStateOf(emptySet<String>()) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(c.page)
            .border(1.dp, c.border, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (names.size == 1) "Is this you?" else "Are any of these you?",
                color = c.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            )
            Text(
                if (names.size == 1) {
                    "Before you joined, the group logged expenses under this name. If it's you, claim it so that history counts as yours."
                } else {
                    "Before you joined, the group logged expenses under these names. Pick yours so that history counts as yours."
                },
                color = c.ink2,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { person ->
                IdentityNameRow(
                    person = person,
                    selected = person.id in selectedIds,
                    onToggle = {
                        selectedIds = if (person.id in selectedIds) selectedIds - person.id else selectedIds + person.id
                    },
                )
            }
        }
        if (names.size > INLINE_CAP) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onSeeAll)
                    .padding(vertical = 4.dp),
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
        IdentityChecklistButton(
            selected = shown.filter { it.id in selectedIds },
            onConfirm = { onConfirm(shown.filter { it.id in selectedIds }, shown) },
            onNoneOfThese = { onNoneOfThese(shown) },
        )
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

/** Three names inline; beyond that the card would bury the feed it sits on top of. */
private const val INLINE_CAP = 3

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
            onConfirm = { _, _ -> }, onNoneOfThese = {}, onSeeAll = {},
        )
    }
}
