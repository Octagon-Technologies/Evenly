package app.splitevenly.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.claim.ClaimTarget
import app.splitevenly.data.claim.PlaceholderClaimCoordinator
import app.splitevenly.domain.group.ClaimLine
import app.splitevenly.domain.group.ClaimPreview
import app.splitevenly.domain.group.UnclaimedName
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCheck
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.theme.EvSelectionTintDark
import app.splitevenly.ui.theme.EvenlyTheme

/** One expense a claim would move, already formatted for display. */
data class ClaimLineUi(val title: String, val amountLabel: String)

/**
 * What the confirm sheet is asking about: the names to claim (empty for "none of these are me") and the
 * ids of the shown names that will be recorded as not you.
 */
data class IdentityAnswer(
    val claim: List<ClaimConfirmPersonUi>,
    val notMe: List<String>,
)

/** The answer for ticking [selected] out of [shown], with each claim's money loaded for the sheet. */
suspend fun GroupRepository.identityAnswer(
    groupId: GroupId,
    selected: List<UnclaimedNameUi>,
    shown: List<UnclaimedNameUi>,
): IdentityAnswer {
    val picked = selected.map { it.id }.toSet()
    return IdentityAnswer(
        claim = selected.map { claimPreview(groupId, UserId(it.id), it.name).toConfirmPerson(it.id) },
        notMe = shown.map { it.id }.filterNot { it in picked },
    )
}

/** Hand a confirmed [answer] to the coordinator. */
suspend fun PlaceholderClaimCoordinator.submit(
    groupId: GroupId,
    answer: IdentityAnswer,
    claimer: UserId,
) = answer(
    groupId = groupId,
    claim = answer.claim.map { ClaimTarget(UserId(it.id), it.name) },
    notMe = answer.notMe.map(::UserId),
    claimer = claimer,
)

/** One name in the claim confirm sheet, with the money that moves if it is claimed. */
data class ClaimConfirmPersonUi(
    val id: String,
    val name: String,
    val summary: String,
    val owed: List<ClaimLineUi>,
    val paid: List<ClaimLineUi>,
)

/**
 * One selectable name in the "Are any of these you?" checklist: a checkbox, the name, and one quiet line
 * of evidence.
 *
 * Replaced per-name "That's me" / "No" buttons (owner call, 2026-10-03): two buttons on every row was too
 * much to take in. Resting rows sit on a gray fill a step off the page so they read as tappable; a
 * selected row takes a soft blue wash and a thin blue outline, dimmer than the solid CTA blue, so a
 * selection is obvious without glowing.
 */
@Composable
fun IdentityNameRow(
    person: UnclaimedNameUi,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    // `surface` is a 6% blue wash in dark, which reads as black on the black page; the neutral selection
    // gray is the step-off-the-page fill there.
    val restFill = if (c.isDark) EvSelectionTintDark else c.surface
    val restStroke = if (c.isDark) Color.Transparent else c.border
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) (if (c.isDark) c.blueTint2 else c.blueTint) else restFill)
            .border(1.dp, if (selected) c.blueText.copy(alpha = 0.55f) else restStroke, shape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EvCheck(checked = selected)
        Column(Modifier.weight(1f)) {
            Text(
                person.name,
                color = c.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(shortEvidence(person), color = c.ink2, fontSize = 12.sp)
        }
    }
}

/**
 * The checklist's one button. It is always live and its label follows the selection: nothing picked
 * declares "none of these are me", anything picked confirms those names. One control instead of two
 * means the screen never offers two competing answers, and the switch from the quiet tonal fill to solid
 * blue is what makes it clear the list is something you pick from.
 */
@Composable
fun IdentityChecklistButton(
    selected: List<UnclaimedNameUi>,
    onConfirm: () -> Unit,
    onNoneOfThese: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (selected.size) {
        0 -> EvButton("None of these are me", onNoneOfThese, modifier, variant = ButtonVariant.Tonal)
        1 -> EvButton("Confirm ${selected.single().name}", onConfirm, modifier, variant = ButtonVariant.PrimarySolid)
        else -> EvButton("Confirm ${selected.size} names", onConfirm, modifier, variant = ButtonVariant.PrimarySolid)
    }
}

/**
 * The confirm sheet behind [IdentityChecklistButton]. Nothing is claimed or ruled out until this sheet's
 * button is pressed.
 *
 * With names picked it shows each one as a card carrying the money it moves, because a claim changes
 * everyone's balances and the number has to be visible before the tap. With nothing picked it asks
 * once more, since "none of these are me" is permanent. [othersCount] is how many listed names were left
 * unticked: confirming answers the whole list, so those are recorded as not you, and the sheet says so.
 */
@Composable
fun IdentityClaimConfirmSheet(
    people: List<ClaimConfirmPersonUi>,
    othersCount: Int,
    noneConfirmLabel: String,
    noneSub: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val title =
        when (people.size) {
            0 -> "None of these are you?"
            1 -> "You're ${people.single().name}?"
            else -> "You're these ${people.size} people?"
        }
    val sub =
        when {
            people.isEmpty() -> noneSub
            othersCount == 0 -> "Their expenses move to your account, and everyone's balances change."
            othersCount == 1 -> "Their expenses move to your account, and everyone's balances change. We won't ask about the other name again."
            else -> "Their expenses move to your account, and everyone's balances change. We won't ask about the other $othersCount names again."
        }
    EvSheetScaffold(onDismiss = onCancel, title = title, sub = sub, dimBackground = true) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (people.isNotEmpty()) {
                Column(
                    // Long histories scroll inside the sheet instead of pushing the buttons off screen.
                    Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    people.forEach { ConfirmPersonCard(it) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EvButton(
                    when (people.size) {
                        0 -> noneConfirmLabel
                        1 -> "Yes, that's me"
                        else -> "Yes, these are me"
                    },
                    onConfirm,
                    variant = ButtonVariant.PrimarySolid,
                )
                EvButton("Go back", onCancel, Modifier.align(Alignment.CenterHorizontally), variant = ButtonVariant.Text)
            }
        }
    }
}

@Composable
private fun ConfirmPersonCard(person: ClaimConfirmPersonUi) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (c.isDark) EvSelectionTintDark else c.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(person.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(person.summary, color = c.ink2, fontSize = 12.sp)
        }
        if (person.owed.isNotEmpty() || person.paid.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                person.owed.forEach { LineChip(it.title, it.amountLabel) }
                person.paid.forEach { LineChip("Paid for ${it.title}", it.amountLabel) }
            }
        }
    }
}

@Composable
private fun LineChip(title: String, amount: String) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.clip(shape).background(c.page).border(1.dp, c.border, shape).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(amount, color = c.ink2, fontSize = 12.sp, fontFamily = EvenlyTheme.monoFamily)
    }
}

/** The checklist row for one unclaimed name. A multi-currency name has no amount, only its count. */
fun UnclaimedName.toUi(): UnclaimedNameUi =
    UnclaimedNameUi(
        id = userId.value,
        name = displayName,
        expenseCount = expenseCount,
        amountLabel = currency?.let { moneySubunits(owedSubunits, it) },
    )

/** The confirm-sheet card for one name: what claiming it adds to what you owe, then each line. */
fun ClaimPreview.toConfirmPerson(id: String): ClaimConfirmPersonUi {
    val total = currency?.takeIf { owed.isNotEmpty() }?.let { moneySubunits(owedTotalSubunits, it) }
    val summary =
        when {
            isEmpty -> "Nothing has been logged under this name yet"
            total != null -> "Adds $total to what you owe"
            owed.isNotEmpty() -> if (owed.size == 1) "1 expense to take on" else "${owed.size} expenses to take on"
            else -> paidSummary(paid)
        }
    return ClaimConfirmPersonUi(
        id = id,
        name = name,
        summary = summary,
        owed = owed.map { ClaimLineUi(it.title, moneySubunits(it.amountSubunits, it.currency)) },
        paid = paid.map { ClaimLineUi(it.title, moneySubunits(it.amountSubunits, it.currency)) },
    )
}

/**
 * A name that only paid moves no debt onto you, so the summary says what it paid instead. A total is
 * only stated when every line shares one currency.
 */
private fun paidSummary(paid: List<ClaimLine>): String {
    val count = if (paid.size == 1) "1 expense" else "${paid.size} expenses"
    val currency = paid.map { it.currency }.distinct().singleOrNull()
    return if (currency != null) {
        "Paid ${moneySubunits(paid.sumOf { it.amountSubunits }, currency)} for $count. You'll be recorded as the payer."
    } else {
        "Paid for $count. You'll be recorded as the payer."
    }
}

/** The row's evidence, short enough for one line under the name. */
private fun shortEvidence(person: UnclaimedNameUi): String = when {
    person.expenseCount == 0 -> "No expenses yet"
    person.amountLabel == null -> "${person.expenseCount} expenses"
    person.expenseCount == 1 -> "1 expense · ${person.amountLabel}"
    else -> "${person.expenseCount} expenses · ${person.amountLabel}"
}

@Preview
@Composable
private fun IdentityNameRowPreview() {
    EvenlyTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IdentityNameRow(UnclaimedNameUi("p1", "Mamba", 1, "$21.94"), selected = true, onToggle = {})
            IdentityNameRow(UnclaimedNameUi("p2", "Mslow", 1, "$8.78"), selected = false, onToggle = {})
            IdentityChecklistButton(listOf(UnclaimedNameUi("p1", "Mamba", 1, "$21.94")), {}, {})
        }
    }
}

@Preview
@Composable
private fun IdentityClaimConfirmSheetPreview() {
    EvenlyTheme {
        IdentityClaimConfirmSheet(
            people = listOf(
                ClaimConfirmPersonUi("p1", "Mamba", "Adds $21.94 to what you owe", listOf(ClaimLineUi("Groceries", "$21.94")), emptyList()),
                ClaimConfirmPersonUi("p2", "Mslow", "Adds $8.78 to what you owe", listOf(ClaimLineUi("Coffee run", "$8.78")), emptyList()),
            ),
            othersCount = 3,
            noneConfirmLabel = "None of these are me",
            noneSub = "",
            onConfirm = {},
            onCancel = {},
        )
    }
}
