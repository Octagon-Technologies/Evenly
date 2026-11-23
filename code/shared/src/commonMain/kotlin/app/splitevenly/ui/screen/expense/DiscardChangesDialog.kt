package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.theme.EvenlyTheme

/** Asks before leaving an expense editor that holds unsaved input. "Keep editing" is the safe default. */
@Composable
internal fun DiscardChangesDialog(
    editing: Boolean,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = EvenlyTheme.colors
    EvModalScaffold(onDismiss = onDismiss) {
        Text(
            if (editing) "Discard your changes?" else "Discard this expense?",
            color = c.ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (editing) "Your edits to this expense won't be saved." else "What you've entered so far won't be saved.",
            color = c.ink2,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EvButton(if (editing) "Discard changes" else "Discard", onDiscard, variant = ButtonVariant.Danger)
            EvButton("Keep editing", onDismiss, variant = ButtonVariant.Secondary)
        }
    }
}
