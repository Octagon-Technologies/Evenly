package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.receipt.ReceiptSource
import app.splitevenly.domain.receipt.VenueKind
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.theme.EvenlyTheme

/** Venues whose receipt is a list of things different people ordered, so splitting by item is a real option. */
private val ItemizableVenues = setOf(VenueKind.RESTAURANT, VenueKind.BAR)

/**
 * Asks once, right after the quick read finds a restaurant or bar receipt, whether to split it evenly or by
 * item. The read is the first moment we know the bill is itemized, and people who reached "Split one amount"
 * often did not know "By item" existed. Only a printed receipt qualifies: a card or payment-app screenshot of
 * the same restaurant has no lines to split. Dismissing (or "Split evenly") leaves the editor as it was.
 */
@Composable
internal fun RestaurantSplitPrompt(
    read: ReceiptReadUi,
    active: Boolean,
    amountSubunits: Long,
    people: Int,
    currency: String,
    onByItem: () -> Unit,
) {
    var asked by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var byItem by remember { mutableStateOf(false) }
    LaunchedEffect(read, active) {
        val summary = (read as? ReceiptReadUi.Read)?.summary ?: return@LaunchedEffect
        val printed = summary.source == ReceiptSource.RECEIPT || summary.source == ReceiptSource.OTHER
        // Checked once, when the read lands: with one person there is nothing to split, and people added
        // afterwards must not pop the question up in the middle of their edit.
        if (active && !asked && printed && people >= 2 && summary.kind in ItemizableVenues) {
            asked = true
            open = true
        }
    }
    if (!open) return

    val noun = if ((read as? ReceiptReadUi.Read)?.summary?.kind == VenueKind.BAR) "bar tab" else "restaurant bill"
    val each = if (amountSubunits > 0 && people > 0) moneySubunits(amountSubunits / people, currency) + " each" else null
    EvSheetScaffold(
        onDismiss = {
            open = false
            if (byItem) onByItem()
        },
        title = "How do you want to split it?",
        sub = "This looks like a $noun.",
        dimBackground = true,
        dismissRequested = closing,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SplitChoice(EvIcons.Wallet, "Split evenly", "Everyone pays the same.", trailing = each, primary = false) {
                closing = true
            }
            SplitChoice(EvIcons.Food, "Split by item", "Each person pays for what they had.", trailing = null, primary = true) {
                byItem = true
                closing = true
            }
        }
    }
}

@Composable
private fun SplitChoice(
    icon: ImageVector,
    title: String,
    caption: String,
    trailing: String?,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (primary) c.blueTint else c.surface)
            .border(if (primary) 1.5.dp else 1.dp, if (primary) c.blue else c.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(if (primary) c.blue else c.blueTint2),
            contentAlignment = Alignment.Center,
        ) { EvIcon(icon, size = 20.dp, tint = if (primary) c.onAccent else c.blueText) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(caption, color = c.ink2, fontSize = 12.5.sp)
        }
        trailing?.let { Text(it, color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = EvenlyTheme.monoFamily) }
    }
}
