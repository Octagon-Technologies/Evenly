package da.chelimo.sharecost.ui.screen.group

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** A group's unresolved "Split the bill" for the home surface. [amountLabel]/[subtitle] are pre-formatted. */
data class UnresolvedBillUi(
    val id: String,
    val title: String,
    val amountLabel: String,
    val subtitle: String,
    val youNeedToClaim: Boolean,
)

/**
 * The home surface for bills that still need claiming — claiming ≠ paying. A bill where *you* still owe a
 * claim leads with an accent "Claim your items" card; the rest read as a quiet unresolved list. Tapping
 * opens the live claim screen.
 */
@Composable
fun UnresolvedBillsSection(bills: List<UnresolvedBillUi>, onOpenBill: (String) -> Unit) {
    val c = ShareCostTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Sort the ones that need *you* to the top.
        bills.sortedByDescending { it.youNeedToClaim }.forEach { b ->
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape)
                    .background(if (b.youNeedToClaim) c.blueTint else c.page)
                    .then(if (b.youNeedToClaim) Modifier else Modifier.border(1.dp, c.border, shape))
                    .clickable { onOpenBill(b.id) }
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (b.youNeedToClaim) c.page else c.surface),
                    contentAlignment = Alignment.Center,
                ) { ScIcon(ScIcons.Receipt, size = 19.dp, tint = c.blue) }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (b.youNeedToClaim) "Claim your items" else b.title,
                        color = if (b.youNeedToClaim) c.blue else c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(if (b.youNeedToClaim) "${b.title} · you haven't picked yet" else b.subtitle, color = c.ink2, fontSize = 12.sp)
                }
                if (b.youNeedToClaim) {
                    Text("Claim", color = c.page, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.blue).padding(horizontal = 12.dp, vertical = 6.dp))
                } else {
                    Text(b.amountLabel, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                }
            }
        }
    }
}
