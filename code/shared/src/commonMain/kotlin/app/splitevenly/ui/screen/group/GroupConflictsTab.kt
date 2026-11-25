package app.splitevenly.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.BannerVariant
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * One parked edit-collision: two people edited the same expense from the same base version. The card
 * diffs the canonical (saved) side vs the parked side field-by-field so the user can see exactly what
 * changed — leading with their OWN share — before picking a side. We never auto-merge two splits.
 *
 * [currentColLabel]/[rejectedColLabel] head the two value columns (the two people, or "Saved" when the
 * winner is unknown). [yourShare] is the highlighted own-share comparison; [rows] are the other fields
 * that differ. [keepLabel]/[useLabel] name the actions ("Keep Bob's" / "Use yours").
 */
data class EditConflictUi(
    val conflictId: String,
    val headline: String,
    val expenseTitle: String,
    val subhead: String,
    val currentColLabel: String,
    val rejectedColLabel: String,
    val yourShare: ConflictCompareUi?,
    val rows: List<ConflictRowUi>,
    val keepLabel: String,
    val useLabel: String,
)

/** A field that differs between the two versions: its value on the saved side vs the parked side. */
data class ConflictRowUi(val label: String, val currentText: String, val rejectedText: String)

/** The viewing user's own share on each side; [changed] drives the highlight. */
data class ConflictCompareUi(val currentText: String, val rejectedText: String, val changed: Boolean)

/** 10 · Group · Conflicts tab (design/src/screens-group2.jsx): edit collisions waiting on a pick. */
@Composable
fun GroupConflictsTab(
    editConflicts: List<EditConflictUi> = DemoEditConflicts,
    onBack: () -> Unit = {},
    onKeepCurrent: (EditConflictUi) -> Unit = {},
    onUseRejected: (EditConflictUi) -> Unit = {},
) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxSize().background(c.page)) {
        EvTopBar("Review", subtitle = "Two people edited the same expense", navIcon = { EvIconButton(EvIcons.Back, onBack) })
        EvBanner("Pick which version to keep.", variant = BannerVariant.Amber, leadingIcon = EvIcons.Alert)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            editConflicts.forEach { item -> EditConflictCard(item, onKeepCurrent, onUseRejected) }
        }
    }
}

@Composable
private fun EditConflictCard(
    item: EditConflictUi,
    onKeepCurrent: (EditConflictUi) -> Unit,
    onUseRejected: (EditConflictUi) -> Unit,
) {
    val c = EvenlyTheme.colors
    EvCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.headline, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(item.expenseTitle, color = c.ink, fontSize = 14.sp)
            Text(item.subhead, color = c.ink2, fontSize = 12.sp)

            // Two value columns headed by who owns each version (saved vs the parked edit).
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("", Modifier.weight(0.30f))
                Text(item.currentColLabel, Modifier.weight(0.35f), color = c.ink2, fontSize = 11.sp, textAlign = TextAlign.End)
                Text(item.rejectedColLabel, Modifier.weight(0.35f), color = c.ink2, fontSize = 11.sp, textAlign = TextAlign.End)
            }

            item.yourShare?.let { ys ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (ys.changed) c.blueTint else c.surface, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Your share", Modifier.weight(0.30f), color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(ys.currentText, Modifier.weight(0.35f), color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
                    Text(ys.rejectedText, Modifier.weight(0.35f), color = if (ys.changed) c.blueText else c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
                }
            }

            item.rows.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.label, Modifier.weight(0.30f), color = c.ink2, fontSize = 13.sp)
                    Text(r.currentText, Modifier.weight(0.35f), color = c.ink, fontSize = 13.sp, textAlign = TextAlign.End)
                    Text(r.rejectedText, Modifier.weight(0.35f), color = c.ink, fontSize = 13.sp, textAlign = TextAlign.End)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                EvButton(item.keepLabel, { onKeepCurrent(item) }, modifier = Modifier.weight(1f))
                EvButton(item.useLabel, { onUseRejected(item) }, variant = ButtonVariant.Text)
            }
        }
    }
}

private val DemoEditConflicts = listOf(
    EditConflictUi(
        conflictId = "ec1",
        headline = "Bob edited this while you did too",
        expenseTitle = "Acme Shopping",
        subhead = "Bob's version is currently saved",
        currentColLabel = "Bob",
        rejectedColLabel = "You",
        yourShare = ConflictCompareUi("$70.00", "$50.00", changed = true),
        rows = listOf(
            ConflictRowUi("Split", "Exact", "Even"),
            ConflictRowUi("Total", "$100.00", "$100.00"),
        ),
        keepLabel = "Keep Bob's",
        useLabel = "Use yours",
    ),
)

@Preview
@Composable
private fun GroupConflictsPreview() {
    EvenlyTheme { GroupConflictsTab() }
}
