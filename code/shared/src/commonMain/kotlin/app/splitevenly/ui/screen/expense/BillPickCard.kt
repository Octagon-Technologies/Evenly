package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/** An itemized bill's picking state, for expense details. */
data class BillPickUi(
    val itemCount: Int,
    val resolvedCount: Int,
    val assignedSubunits: Long,
    val notAssignedSubunits: Long,
    val meDone: Boolean,
)

/**
 * The durable door back into picking on a saved bill (frame 8). While lines remain it says how many and
 * splits the money into assigned vs not, keeping the viewer's own done state separate from the bill's:
 * being done with your picks does not mean the bill is. Leaving the bill never implies it is paid.
 */
@Composable
internal fun BillPickCard(
    pick: BillPickUi,
    currencyCode: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    val left = pick.itemCount - pick.resolvedCount
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (left > 0) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(c.warningTint)
                    .border(1.dp, c.warning.copy(alpha = 0.3f), shape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    if (left == 1) "1 item still needs someone" else "$left items still need someone",
                    color = c.warning,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (pick.meDone) {
                        "You have assigned your items. Others can pick theirs later."
                    } else {
                        "Pick yours when you're ready. Others can pick theirs too."
                    },
                    color = c.ink2,
                    fontSize = 12.5.sp,
                )
                MoneyLine("Assigned so far", moneySubunits(pick.assignedSubunits, currencyCode), Modifier.padding(top = 6.dp))
                MoneyLine("Not assigned yet", moneySubunits(pick.notAssignedSubunits, currencyCode))
            }
        }
        EvCard {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Pick items on this bill", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${pick.resolvedCount} of ${pick.itemCount} ${if (pick.itemCount == 1) "item" else "items"} assigned",
                        color = c.ink3,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    if (left >
                        0
                    ) {
                        "Claim items"
                    } else {
                        "Change items"
                    },
                    color = c.blueText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                EvIcon(EvIcons.ChevR, size = 14.dp, tint = c.blueText)
            }
        }
    }
}

@Composable
private fun MoneyLine(
    label: String,
    amount: String,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    Row(modifier.fillMaxWidth().topHairline(c.warning.copy(alpha = 0.2f)).padding(top = 6.dp)) {
        Text(label, color = c.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(amount, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = EvenlyTheme.monoFamily)
    }
}
