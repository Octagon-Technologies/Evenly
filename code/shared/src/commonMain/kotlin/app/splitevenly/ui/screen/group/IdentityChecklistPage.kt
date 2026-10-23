package app.splitevenly.ui.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * The full-screen "Are any of these you?" checklist, shared by the join step and the "Claim a name"
 * screen so the two can never ask the question differently.
 *
 * The button is pinned below the scroll and is the only way forward: it answers the whole list, either
 * "none of these are me" or "these ticked ones are me". Back writes nothing.
 */
@Composable
fun IdentityChecklistPage(
    topTitle: String,
    topSubtitle: String?,
    caption: String?,
    names: List<UnclaimedNameUi>,
    onBack: () -> Unit,
    onConfirm: (selected: List<UnclaimedNameUi>) -> Unit,
    onNoneOfThese: () -> Unit,
    backIcon: ImageVector = EvIcons.Back,
) {
    val c = EvenlyTheme.colors
    var selectedIds by remember(names.map { it.id }) { mutableStateOf(emptySet<String>()) }
    val selected = names.filter { it.id in selectedIds }

    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(
            title = topTitle,
            subtitle = topSubtitle,
            navIcon = { EvIconButton(backIcon, onClick = onBack) },
            showDivider = false,
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (caption != null) Text(caption, color = c.ink3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Are any of these you?",
                    color = c.ink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                )
                Text(
                    "Before you joined, the group logged expenses under these names. Pick yours so that history counts as yours.",
                    color = c.ink2,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 21.sp,
                )
                if (names.isNotEmpty()) {
                    Text("Select all that apply", color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (names.isEmpty()) {
                Text(
                    "Nothing left to claim. You've been through every name in this group.",
                    color = c.ink2,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            names.forEach { person ->
                IdentityNameRow(
                    person = person,
                    selected = person.id in selectedIds,
                    onToggle = {
                        selectedIds = if (person.id in selectedIds) selectedIds - person.id else selectedIds + person.id
                    },
                )
            }
        }
        // Hidden once there is nothing left to answer, so the empty state doesn't offer a button that
        // would do nothing.
        if (names.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().topHairline(c.border).background(c.page).padding(16.dp)) {
                IdentityChecklistButton(selected, onConfirm = { onConfirm(selected) }, onNoneOfThese = onNoneOfThese)
            }
        }
    }
}

@Preview
@Composable
private fun IdentityChecklistPagePreview() {
    EvenlyTheme {
        IdentityChecklistPage(
            topTitle = "",
            topSubtitle = null,
            caption = "Joining Tulum Trip",
            names = listOf(
                UnclaimedNameUi("p1", "Mamba", 1, "$21.94"),
                UnclaimedNameUi("p2", "Mslow", 1, "$8.78"),
                UnclaimedNameUi("p3", "Chelimo", 3, "$56.50"),
            ),
            onBack = {},
            onConfirm = {},
            onNoneOfThese = {},
        )
    }
}
