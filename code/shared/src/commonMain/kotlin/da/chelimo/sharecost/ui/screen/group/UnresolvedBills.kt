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
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** A group's unresolved "Split the bill" for the home surface. [subtitle] is pre-formatted claim status. */
data class UnresolvedBillUi(
    val id: String,
    val title: String,
    val subtitle: String,
    val youNeedToClaim: Boolean,
)

/**
 * The home surface for bills that still need claiming — claiming ≠ paying, so this is a distinct **task
 * module**, not another expense card. Everything lives in one collapsible bordered card (tinted header +
 * checklist icon + count), the bills stack as quiet hairline rows, and crucially they carry **no dollar
 * total** — the amount belongs to the ledger row below, so the two never read as the same thing. Bills that
 * still need *you* lead with a blue "Claim" chip; ones you've done (waiting on others) show a quiet "Review".
 *
 * [expanded]/[onToggleExpanded] are hoisted so the collapse choice persists for the whole visit to the page.
 */
@Composable
fun UnresolvedBillsSection(
    bills: List<UnresolvedBillUi>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenBill: (String) -> Unit,
) {
    val c = ShareCostTheme.colors
    val cardShape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        Column(Modifier.fillMaxWidth().clip(cardShape).border(1.dp, c.blueTint2, cardShape)) {
            // Header — tap to collapse. The tinted strip + checklist glyph flag this as a to-do, distinct
            // from the plain expense cards below.
            Row(
                Modifier.fillMaxWidth().background(c.blueTint).clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                ScIcon(ScIcons.CheckCircle, size = 18.dp, tint = c.blue)
                Text("Claim your items", color = c.bluePressed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(99.dp)).background(c.blue).padding(horizontal = 6.dp, vertical = 2.dp),
                ) { Text("${bills.size}", color = c.onAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                ScIcon(if (expanded) ScIcons.ChevU else ScIcons.ChevD, size = 18.dp, tint = c.blue)
            }
            if (expanded) {
                // The ones that need YOU first.
                bills.sortedByDescending { it.youNeedToClaim }.forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().topHairline(c.border).background(c.page)
                            .clickable { onOpenBill(b.id) }.padding(horizontal = 13.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(b.title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (b.youNeedToClaim) "You haven't picked yet" else b.subtitle,
                                color = c.ink2, fontSize = 12.sp,
                            )
                        }
                        if (b.youNeedToClaim) {
                            Row(
                                Modifier.clip(RoundedCornerShape(999.dp)).background(c.blue).padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text("Claim", color = c.onAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                ScIcon(ScIcons.ChevR, size = 13.dp, tint = c.onAccent)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Review", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                ScIcon(ScIcons.ChevR, size = 13.dp, tint = c.ink3)
                            }
                        }
                    }
                }
            }
        }
    }
}
