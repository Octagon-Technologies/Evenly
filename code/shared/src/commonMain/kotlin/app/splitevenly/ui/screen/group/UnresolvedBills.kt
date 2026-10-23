package app.splitevenly.ui.screen.group

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
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/** A bill that may still need the viewer's pick, for the home surface. [subtitle] is pre-formatted claim status. */
data class UnresolvedBillUi(
    val id: String,
    val title: String,
    val subtitle: String,
    /** "3 of 5 items picked": item progress, kept apart from the viewer's own state. */
    val progressLabel: String? = null,
)

/**
 * The home surface for bills that still need claiming — claiming ≠ paying, so this is a distinct **task
 * module**, not another expense card. Everything lives in one collapsible bordered card (tinted header +
 * checklist icon + count), the bills stack as quiet hairline rows, and crucially they carry **no dollar
 * total** — the amount belongs to the ledger row below, so the two never read as the same thing. Only bills
 * where *you* might be one of the missing pickers reach this list (filtered by the caller), so every row
 * carries the same blue "Pick your items" chip.
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
    val c = EvenlyTheme.colors
    val cardShape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        Column(Modifier.fillMaxWidth().clip(cardShape).border(1.dp, c.blueTint2, cardShape)) {
            // Header — tap to collapse. The tinted strip + checklist glyph flag this as a to-do, distinct
            // from the plain expense cards below.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.blueTint)
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                EvIcon(EvIcons.CheckCircle, size = 18.dp, tint = c.blueText)
                Text(
                    "Needs your pick",
                    color = c.blueText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(c.borderStrong).padding(horizontal = 7.dp, vertical = 2.dp),
                ) { Text("${bills.size}", color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                EvIcon(if (expanded) EvIcons.ChevU else EvIcons.ChevD, size = 18.dp, tint = c.blueText)
            }
            if (expanded) {
                bills.forEach { b ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .topHairline(c.border)
                            .background(c.page)
                            .clickable { onOpenBill(b.id) }
                            .padding(horizontal = 13.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            b.progressLabel?.let {
                                Text(it.uppercase(), color = c.blueText, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(b.title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(b.subtitle, color = c.ink2, fontSize = 12.sp)
                        }
                        Row(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(c.blue).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text("Pick your items", color = c.onAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            EvIcon(EvIcons.ChevR, size = 13.dp, tint = c.onAccent)
                        }
                    }
                }
            }
        }
    }
}
