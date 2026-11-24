package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** What deleting a bill from a list costs, said the same way the bill's own screen says it. */
internal const val DELETE_BILL_BODY = "It will move to Recently deleted for 30 days. Balances will update for everyone in the group."

/** Deleting a one-time split removes its card for everyone in it, not just the bill inside. */
internal const val DELETE_QUICK_SPLIT_BODY = "It will move to Recently deleted for 30 days and disappear for everyone splitting it."

/**
 * The "Delete this bill?" confirmation for a long-press delete. It matches the expense screen's own
 * sheet, but is raised in a full-window popup because a list row sits deep inside the layout, where an
 * in-layout sheet would be clipped to the row and drawn under its neighbours.
 */
@Composable
internal fun DeleteBillSheet(
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Popup(popupPositionProvider = WindowOrigin, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.fillMaxSize()) {
            EvSheetScaffold(onDismiss = onDismiss, dimBackground = true) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(c.dangerTint),
                        contentAlignment = Alignment.Center,
                    ) {
                        EvIcon(EvIcons.Trash, size = 25.dp, tint = c.danger)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Delete this bill?", color = c.ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(
                            body,
                            color = c.ink2,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EvButton("Delete bill", onConfirm, variant = ButtonVariant.Danger, leadingIcon = EvIcons.Trash)
                        EvButton("Cancel", onDismiss, variant = ButtonVariant.Secondary)
                    }
                }
            }
        }
    }
}

internal object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}
