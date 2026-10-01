package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme

/** An itemized bill's picking state, for expense details. */
data class BillPickUi(
    val itemCount: Int,
    val resolvedCount: Int,
    /** The viewer holds at least one claim or shared portion, whoever assigned it. */
    val mePicked: Boolean,
)

/**
 * "Who ordered what": the bill's hub for the three frequent jobs (pick your own, pick for someone else, share
 * the QR). The title never changes so people learn where it lives; the primary button walks three stages
 * whose fill steps down as the work shrinks: solid blue "Pick your items" (you haven't picked), tonal blue
 * "Change picks" (you have, others haven't), neutral "Change picks" (every item claimed). Share gets its own
 * button because it is the one job nobody guesses lives behind a "change" action.
 */
@Composable
internal fun BillPickCard(
    pick: BillPickUi,
    onPick: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    val allClaimed = pick.itemCount > 0 && pick.resolvedCount >= pick.itemCount
    val fraction = if (pick.itemCount == 0) 0f else pick.resolvedCount.toFloat() / pick.itemCount
    EvCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.blueTint),
                    contentAlignment = Alignment.Center,
                ) { EvIcon(EvIcons.Receipt, size = 18.dp, tint = c.blueText) }
                Column(Modifier.weight(1f)) {
                    Text("Who ordered what", color = c.ink, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${pick.resolvedCount} of ${pick.itemCount} claimed",
                        color = if (allClaimed) c.settled else c.blueText,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Box(
                Modifier.padding(vertical = 12.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(999.dp)).background(c.border),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (allClaimed) c.settled else c.blue),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !pick.mePicked -> PickButton("Pick your items", EvIcons.User, c.blue, c.onAccent, Color.Transparent, onPick)
                    !allClaimed -> PickButton("Change picks", EvIcons.Users, c.blueTint, c.blueText, Color.Transparent, onPick)
                    else -> PickButton("Change picks", EvIcons.Users, c.surface, c.ink, c.border, onPick)
                }
            }
            // Full width and labelled with the outcome: a bare "Share" chip was mistaken for sharing the
            // group, and the tester sent the group invite QR instead of this bill's.
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, c.border, RoundedCornerShape(8.dp))
                    .clickable(onClick = onShare),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            ) {
                EvIcon(EvIcons.Qr, size = 16.dp, tint = c.blueText)
                Text("Let friends pick their items", color = c.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Opens a QR for this bill only. No app or account needed.",
                color = c.ink2,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RowScope.PickButton(
    label: String,
    icon: ImageVector,
    bg: Color,
    fg: Color,
    border: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .weight(1f)
            .height(42.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        EvIcon(icon, size = 16.dp, tint = fg)
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
