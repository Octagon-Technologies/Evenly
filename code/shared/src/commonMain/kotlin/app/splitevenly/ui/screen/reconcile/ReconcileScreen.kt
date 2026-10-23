package app.splitevenly.ui.screen.reconcile

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.screen.group.IdentityChecklistPage
import app.splitevenly.ui.screen.group.UnclaimedNameUi
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Claim a name, the full-screen list.
 *
 * The same question the in-group card asks, for the cases the card deliberately doesn't cover: more
 * than three open names, and someone who came looking through Group settings. It shows the same set from
 * the same source (unclaimed names in this group, minus the ones this person has answered), so the two
 * can never disagree and the list visibly shrinks as it gets answered.
 */
@Composable
fun ReconcileScreen(
    groupName: String,
    names: List<UnclaimedNameUi>,
    onBack: () -> Unit = {},
    onConfirm: (List<UnclaimedNameUi>) -> Unit = {},
    onNoneOfThese: () -> Unit = {},
) {
    IdentityChecklistPage(
        topTitle = "Claim a name",
        topSubtitle = groupName,
        caption = null,
        names = names,
        onBack = onBack,
        onConfirm = onConfirm,
        onNoneOfThese = onNoneOfThese,
        backIcon = EvIcons.Close,
    )
}

@Preview
@Composable
private fun ReconcilePreview() {
    EvenlyTheme {
        ReconcileScreen(
            groupName = "Tulum Trip",
            names = listOf(
                UnclaimedNameUi("p1", "Alex R.", 3, "$56.50"),
                UnclaimedNameUi("p2", "A. Rivera", 2, "$21.40"),
                UnclaimedNameUi("p3", "Alejandro", 1, "$31.00"),
            ),
        )
    }
}
