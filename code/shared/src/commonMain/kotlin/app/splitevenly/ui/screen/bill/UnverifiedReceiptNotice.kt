package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Shown after a scan whose amounts the server could not reconcile against the receipt's printed total
 * (`ReceiptDraft.verified == false`). Never blocks: the items are already filled in and fully editable
 * either way, so this only asks for a closer look before saving.
 *
 * Lives here rather than inline because BOTH bill editors need it and only one of them used to have it.
 * [BillEditScreen] honoured `verified` from the start; [app.splitevenly.ui.screen.expense.AddExpenseScreen]
 * dropped it on the floor, so on the path most people take (add expense, itemize, scan) an unverified
 * draft was indistinguishable from a clean one. Sharing one composable is what keeps that from drifting
 * apart again.
 *
 * Same style as the "your change was superseded" notice on expense detail, for one consistent
 * "something needs your attention" visual language across the app.
 */
@Composable
fun UnverifiedReceiptNotice(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = EvenlyTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.warningTint)
            .border(1.dp, c.warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EvIcon(EvIcons.Info, size = 16.dp, tint = c.warning, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Couldn't verify this receipt", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "The amounts didn't quite add up to the printed total. Double-check the items and total below before saving.",
                color = c.ink2, fontSize = 12.5.sp,
            )
        }
        EvIconButton(EvIcons.Close, onDismiss, tint = c.ink3, size = 16.dp)
    }
}
