package app.splitevenly.ui.screen.reconcile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
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
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCheck
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.money
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Claim a name — the full-screen list.
 *
 * The same question the "Is this you?" card asks, for the cases the card deliberately doesn't cover:
 * more than three open names, and someone who came looking through Group settings. It shows the same
 * set from the same source (unclaimed names in this group, minus the ones this person has answered),
 * so the two can never disagree and the list visibly shrinks as it gets answered.
 *
 * Multi-select is retained for the genuine multi-name claim (someone tracked under two spellings), and
 * each row also carries a per-name **No** so ruling one out doesn't require claiming anything.
 */
@Composable
fun ReconcileScreen(
    groupName: String = "Tulum Trip",
    people: List<ReconcilePerson> = DemoReconcilePeople,
    onBack: () -> Unit = {},
    onConfirm: (List<String>) -> Unit = {},
    onNotMe: (String) -> Unit = {},
    onNoneOfThese: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    var selected by remember(people) { mutableStateOf(emptySet<String>()) }

    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(
            title = "Claim a name",
            subtitle = groupName,
            navIcon = { EvIconButton(EvIcons.Close, onClick = onBack) },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Are any of these you?",
                    color = c.ink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                )
                Text(
                    "Before you joined, the group logged these names. Claim the ones that are you to merge your history.",
                    color = c.ink2,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 21.sp,
                )
            }

            if (people.isEmpty()) {
                Text(
                    "Nothing left to claim. You've been through every name in this group.",
                    color = c.ink2,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            people.forEach { person ->
                ReconcileCard(
                    person = person,
                    selected = person.id in selected,
                    onToggle = {
                        selected = if (person.id in selected) selected - person.id else selected + person.id
                    },
                    onNotMe = { onNotMe(person.id) },
                )
            }
        }

        // Bottom CTA — pinned below the scroll. Hidden once there is nothing left to answer, so the
        // empty state doesn't offer two buttons that would do nothing.
        if (people.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().topHairline(c.border).background(c.page).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EvButton(
                    text = "These are me (${selected.size})",
                    onClick = { onConfirm(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                )
                EvButton(
                    text = "None of these are me",
                    onClick = onNoneOfThese,
                    variant = ButtonVariant.Text,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/** A claimable name: its user id, display name, and the expenses logged under it. */
data class ReconcilePerson(val id: String, val name: String, val expenses: List<Pair<String, Double>>)

internal val DemoReconcilePeople = listOf(
    ReconcilePerson("p1", "Alex R.", listOf("Dinner at La Negra" to 24.0, "Airport taxi" to 14.5, "Cenote day trip" to 18.0)),
    ReconcilePerson("p2", "A. Rivera", listOf("Supermarket run" to 9.4, "Beach drinks" to 12.0)),
    ReconcilePerson("p3", "Alejandro", listOf("Sunset cruise" to 31.0)),
)

/**
 * One claimable name: the display name + a horizontally-scrolling strip of expense thumbnails (each a
 * small rounded box with the mono amount). Selected → [selectionTint] fill + 2dp [selectionStroke]
 * border + a check (color is never the sole indicator).
 */
@Composable
private fun ReconcileCard(
    person: ReconcilePerson,
    selected: Boolean,
    onToggle: () -> Unit,
    onNotMe: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) c.selectionTint else c.page)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) c.selectionStroke else c.border,
                shape = shape,
            )
            .clickable(onClick = onToggle)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EvCheck(checked = selected)
            EvAvatar(name = person.name, me = selected, size = AvatarSize.Sm)
            Text(
                person.name,
                color = c.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (person.expenses.size == 1) "1 expense" else "${person.expenses.size} expenses",
                color = c.ink2,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // Chip strip of expense thumbnails (amount + currency, mono).
        if (person.expenses.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                person.expenses.forEach { (title, amount) ->
                    ExpenseThumb(title = title, amount = amount, selected = selected)
                }
            }
        }
        // Ruling one name out must not require claiming another, so "No" lives on the row itself
        // rather than only in the all-or-nothing footer.
        Text(
            "No, that isn't me",
            color = c.ink2,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onNotMe)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** A small rounded thumbnail showing a receipt glyph + the mono amount — the per-expense chip. */
@Composable
private fun ExpenseThumb(title: String, amount: Double, selected: Boolean) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) c.page else c.surface)
            .border(1.dp, c.border, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) c.surface else c.page),
            contentAlignment = Alignment.Center,
        ) {
            EvIcon(EvIcons.Receipt, size = 16.dp, tint = c.ink2)
        }
        Column {
            Text(
                title,
                color = c.ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                money(amount),
                color = c.ink2,
                fontSize = 12.sp,
                fontFamily = EvenlyTheme.monoFamily,
            )
        }
    }
}

@Preview
@Composable
private fun ReconcilePreview() {
    EvenlyTheme { ReconcileScreen() }
}

@Preview
@Composable
private fun ReconcileEmptyPreview() {
    EvenlyTheme { ReconcileScreen(people = emptyList()) }
}
