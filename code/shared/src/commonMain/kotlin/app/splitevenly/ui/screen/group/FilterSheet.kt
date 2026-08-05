package app.splitevenly.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.expense.ExpenseCategory
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvDivider
import app.splitevenly.ui.components.EvParticipantChip
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.theme.EvenlyTheme

/** A member offered in the filter sheet's "Paid by" group. */
data class FilterMemberUi(val id: String, val name: String, val me: Boolean = false)

/**
 * 7 · Filter sheet (design/src/screens-group2.jsx) — wired (F6). Edits a [GroupFilter] draft; [countFor]
 * gives the live feed count for the Apply label so the user sees the impact before committing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    members: List<FilterMemberUi> = emptyList(),
    initial: GroupFilter = GroupFilter(),
    countFor: (GroupFilter) -> Int = { 0 },
    onDismiss: () -> Unit = {},
    onReset: () -> Unit = {},
    onApply: (GroupFilter) -> Unit = {},
) {
    val c = EvenlyTheme.colors
    var draft by remember { mutableStateOf(initial) }
    val count = countFor(draft)

    Box(Modifier.fillMaxSize().background(c.page)) {
        EvSheetScaffold(onDismiss, title = "Filter") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (members.isNotEmpty()) {
                    FilterGroup("Paid by") {
                        members.forEach { m ->
                            EvParticipantChip(
                                m.name,
                                selected = draft.payerUserId == m.id,
                                leading = { EvAvatar(m.name, me = m.me, size = AvatarSize.Xs) },
                                onClick = { draft = draft.copy(payerUserId = if (draft.payerUserId == m.id) null else m.id) },
                            )
                        }
                    }
                    EvDivider()
                }
                FilterGroup("Category") {
                    ExpenseCategory.entries.forEach { cat ->
                        EvParticipantChip(
                            cat.label,
                            selected = draft.categoryId == cat.id,
                            leading = { EvIcon(categoryIcon(cat), size = 15.dp) },
                            onClick = { draft = draft.copy(categoryId = if (draft.categoryId == cat.id) null else cat.id) },
                        )
                    }
                }
                FilterGroup("Date range") {
                    DateRange.entries.forEach { r ->
                        EvParticipantChip(r.label, selected = draft.dateRange == r, onClick = { draft = draft.copy(dateRange = r) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EvButton("Reset", { draft = GroupFilter(); onReset() }, variant = ButtonVariant.Secondary, modifier = Modifier.weight(1f))
                    EvButton("Show $count ${if (count == 1) "result" else "results"}", { onApply(draft) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(label: String, content: @Composable FlowRowScope.() -> Unit) {
    val c = EvenlyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Preview
@Composable
private fun FilterPreview() {
    EvenlyTheme {
        FilterSheet(
            members = listOf(FilterMemberUi("u1", "You", me = true), FilterMemberUi("u2", "Andrew")),
            countFor = { 14 },
        )
    }
}
