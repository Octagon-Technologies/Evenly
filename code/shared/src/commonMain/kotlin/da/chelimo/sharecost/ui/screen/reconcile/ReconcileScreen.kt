package da.chelimo.sharecost.ui.screen.reconcile

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
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCheck
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.money
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/**
 * 21 · Reconcile past activity — the selection step (design/src/screens-misc.jsx).
 *
 * Before a member joined, the group logged expenses under loose display names. This screen lists the
 * candidate names with the expenses booked against each, and lets the user claim the ones that are
 * them so the history merges onto their balance. Blue-led selection: a claimed card gets the blue
 * [selectionStroke] outline **and** a check (never color alone).
 */
@Composable
fun ReconcileScreen(
    onBack: () -> Unit = {},
    onConfirm: (List<String>) -> Unit = {},
    onNotMe: () -> Unit = {},
) {
    val c = ShareCostTheme.colors

    // Demo placeholder data (ported from the web design: name + the expenses logged under it).
    val people = remember {
        listOf(
            ReconcilePerson("Alex R.", listOf("Dinner at La Negra" to 24.0, "Airport taxi" to 14.5, "Cenote day trip" to 18.0)),
            ReconcilePerson("A. Rivera", listOf("Supermarket run" to 9.4, "Beach drinks" to 12.0)),
            ReconcilePerson("Alejandro", listOf("Sunset cruise" to 31.0)),
        )
    }
    // Seed the first two as selected, mirroring the design's initial state.
    var selected by remember { mutableStateOf(setOf(people[0].name, people[1].name)) }

    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        ScTopBar(
            title = "Reconcile",
            subtitle = "Tulum Trip",
            navIcon = { ScIconButton(ScIcons.Close, onClick = onBack) },
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

            people.forEach { person ->
                ReconcileCard(
                    person = person,
                    selected = person.name in selected,
                    onToggle = {
                        selected = if (person.name in selected) selected - person.name else selected + person.name
                    },
                )
            }
        }

        // Bottom CTA — pinned below the scroll.
        Column(
            modifier = Modifier.fillMaxWidth().topHairline(c.border).background(c.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScButton(
                text = "These are me (${selected.size})",
                onClick = { onConfirm(selected.toList()) },
                leadingIcon = ScIcons.Check,
                enabled = selected.isNotEmpty(),
            )
            ScButton(
                text = "These are not me",
                onClick = onNotMe,
                variant = ButtonVariant.Text,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

private data class ReconcilePerson(val name: String, val expenses: List<Pair<String, Double>>)

/**
 * One claimable placeholder: the display name + a horizontally-scrolling strip of expense thumbnails
 * (each a small rounded box with the mono amount). Selected → [selectionTint] fill + 2dp
 * [selectionStroke] border + a check (color is never the sole indicator).
 */
@Composable
private fun ReconcileCard(
    person: ReconcilePerson,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val c = ShareCostTheme.colors
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
            ScCheck(checked = selected)
            ScAvatar(name = person.name, me = selected, size = AvatarSize.Sm)
            Text(
                person.name,
                color = c.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${person.expenses.size} expense${if (person.expenses.size == 1) "" else "s"}",
                color = c.ink2,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // Chip strip of expense thumbnails (amount + currency, mono).
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            person.expenses.forEach { (title, amount) ->
                ExpenseThumb(title = title, amount = amount, selected = selected)
            }
        }
    }
}

/** A small rounded thumbnail showing a receipt glyph + the mono amount — the per-expense chip. */
@Composable
private fun ExpenseThumb(title: String, amount: Double, selected: Boolean) {
    val c = ShareCostTheme.colors
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
            ScIcon(ScIcons.Receipt, size = 16.dp, tint = c.ink2)
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
                fontFamily = ShareCostTheme.monoFamily,
            )
        }
    }
}

@Preview
@Composable
private fun ReconcilePreview() {
    ShareCostTheme { ReconcileScreen() }
}
