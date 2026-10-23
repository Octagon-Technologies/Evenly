package app.splitevenly.ui.screen.expense

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.screen.bill.PeoplePickerSheet
import app.splitevenly.ui.screen.bill.PickerPersonUi
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * "Who's splitting this?" as a sheet, opened from the receipt review. New names go through the same
 * placeholder path as the editor's "Add" chip, and the editor selects them when they arrive.
 */
@Composable
internal fun ExpensePeopleSheet(
    participants: List<AddParticipantUi>,
    selected: Set<String>,
    title: String,
    onPick: (Set<String>) -> Unit,
    onAddPlaceholder: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    PeoplePickerSheet(
        title = "Who's splitting this?",
        sub = title.takeIf { it.isNotBlank() },
        people = participants.map { PickerPersonUi(it.userId, it.name, it.isMe) },
        initiallySelected = selected,
        sectionLabel = "People",
        selectedTag = null,
        summary = { n -> if (n == 1) "1 person" else "$n people" },
        onDone = { pick ->
            onPick(pick.userIds)
            pick.newNames.forEach(onAddPlaceholder)
        },
        onDismiss = onDismiss,
    )
}

/** Under the scan card: scanning jumps ahead to the review, so say who it assumes and that it can change. */
@Composable
internal fun ScanPeopleHint(count: Int) {
    if (count == 0) return
    val who = if (count == 1) "1 person" else "$count people"
    Text("Splitting with $who. You can change this after scanning.", color = EvenlyTheme.colors.ink3, fontSize = 12.sp)
}

internal val DemoParticipants =
    listOf(
        AddParticipantUi("u1", "You", true),
        AddParticipantUi("u2", "Andrew", false),
        AddParticipantUi("u3", "Bob", false),
        AddParticipantUi("u4", "Maya", false),
    )

