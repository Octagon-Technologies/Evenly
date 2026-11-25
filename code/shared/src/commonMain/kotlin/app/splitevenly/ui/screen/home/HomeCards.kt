package app.splitevenly.ui.screen.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.splitevenly.platform.isIOS
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvContextMenu
import app.splitevenly.ui.components.EvMenuAction
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.EvSwipeAction
import app.splitevenly.ui.components.EvSwipeActions
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.screen.expense.WindowOrigin
import app.splitevenly.ui.screen.settings.DeleteGroupSheet
import app.splitevenly.ui.screen.settings.GroupDeleteImpactUi
import app.splitevenly.ui.screen.settings.LeaveGroupSheet
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Home's three tabs. They answer "what kind of thing is it", never "where is it in its life":
 * archived and deleted live behind the Put away row at the bottom of whichever tab is showing, so the
 * tab bar never grows past three.
 */
enum class HomeFilter(
    val label: String,
    /** Stable nav-arg value; a Route arg that is an enum crashes the NavHost on Kotlin/Native. */
    val key: String,
) {
    All("All", "all"),
    Groups("Groups", "groups"),
    OneTime("One-time", "one-time"),
    ;

    fun matches(isQuick: Boolean): Boolean =
        when (this) {
            All -> true
            Groups -> !isQuick
            OneTime -> isQuick
        }

    companion object {
        fun fromKey(key: String?): HomeFilter = entries.firstOrNull { it.key == key } ?: All
    }
}

internal fun HomeFilter.matches(card: GroupCardUi): Boolean = matches(card.quick != null)

/** Archive in slate, Delete in red: the same pair on every Home card, swiped or long-pressed. */
@Composable
internal fun homeSwipeActions(
    onArchive: () -> Unit,
    onDelete: () -> Unit,
): List<EvSwipeAction> {
    val c = EvenlyTheme.colors
    return listOf(
        EvSwipeAction("Archive", EvIcons.Archive, c.ink2, onArchive),
        EvSwipeAction("Delete", EvIcons.Trash, c.danger, onDelete),
    )
}

/**
 * A group's Home card with the same long-press menu and left swipe as a one-time bill. Each menu row
 * says who it affects, because Archive (you) and Delete (everyone) sit next to each other and the
 * labels alone cannot say so.
 */
@Composable
internal fun GroupCardWithActions(
    g: GroupCardUi,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    swipeOpen: Boolean,
    onSwipeOpenChange: (Boolean) -> Unit,
) {
    val c = EvenlyTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val highlight = menuOpen && !isIOS()
    Box {
        EvSwipeActions(
            open = swipeOpen,
            onOpenChange = onSwipeOpenChange,
            actions = homeSwipeActions(onArchive = onArchive, onDelete = onDelete),
        ) {
            EvCard(
                modifier = if (highlight) Modifier.border(1.5.dp, c.blueText, RoundedCornerShape(16.dp)) else Modifier,
                fill = true,
                bordered = true,
            ) {
                GroupRow(g, onClick = onOpen, onLongClick = { menuOpen = true })
            }
        }
        EvContextMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            actions =
                listOf(
                    EvMenuAction("Open", EvIcons.ChevR, onClick = onOpen),
                    EvMenuAction("Archive (only for you)", EvIcons.Archive, onClick = onArchive),
                    EvMenuAction("Delete for everyone", EvIcons.Trash, destructive = true, onClick = onDelete),
                ),
        )
    }
}

private enum class DeleteStep { Choose, Leave, Confirm }

/**
 * Delete from a Home card. Deleting a group removes it for every member, but most people swiping
 * "Delete" only mean "get this off my screen", so the first sheet leads with the two options that
 * affect only them. "Delete for everyone" then reuses Group settings' typed-name sheet unchanged.
 *
 * Raised in a full-window popup like `DeleteBillSheet`: Home sits inside the bottom-nav shell, where
 * an in-layout sheet would stop short of the nav bar.
 */
@Composable
internal fun HomeGroupDeleteFlow(
    g: GroupCardUi,
    loadImpact: suspend (groupId: String) -> GroupDeleteImpactUi,
    onArchive: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = EvenlyTheme.text
    var step by remember(g.id) { mutableStateOf(DeleteStep.Choose) }
    val impact by produceState(GroupDeleteImpactUi(memberCount = g.members), g.id) { value = loadImpact(g.id) }
    Popup(popupPositionProvider = WindowOrigin, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.fillMaxSize()) {
            when (step) {
                DeleteStep.Choose -> {
                    EvModalScaffold(onDismiss = onDismiss) {
                        Text("Delete ${g.name}?", style = t.sheetTitle)
                        Text(
                            if (g.members > 1) {
                                "Deleting removes it for all ${g.members} members. To only clear it from your list, archive it."
                            } else {
                                "To only clear it from your list, archive it instead."
                            },
                            style = t.description,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            EvButton("Archive instead", onArchive, variant = ButtonVariant.Primary, leadingIcon = EvIcons.Archive)
                        }
                        if (g.members > 1) {
                            Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                EvButton("Leave the group", { step = DeleteStep.Leave }, variant = ButtonVariant.Secondary)
                            }
                        }
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            EvButton("Delete for everyone", { step = DeleteStep.Confirm }, variant = ButtonVariant.Danger)
                        }
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            EvButton("Cancel", onDismiss, variant = ButtonVariant.Text)
                        }
                    }
                }

                DeleteStep.Leave -> {
                    LeaveGroupSheet(groupName = g.name, onDismiss = onDismiss, onConfirm = onLeave)
                }

                DeleteStep.Confirm -> {
                    DeleteGroupSheet(groupName = g.name, impact = impact, onDismiss = onDismiss, onConfirm = onDelete)
                }
            }
        }
    }
}
