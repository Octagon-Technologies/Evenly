package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.bringIntoViewOnFocus
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * The bill's subtotal, tax, gratuity, other charges, tip and discount, ending in the total. Shared by the
 * bill editor and the scan review's "Tax and tip" sheet.
 */
@Composable
internal fun ExtrasCard(
    symbol: String,
    subtotalSubunits: Long,
    totalSubunits: Long,
    currencyCode: String,
    taxText: String,
    onTax: (String) -> Unit,
    gratuityText: String,
    onGratuity: (String) -> Unit,
    otherChargesText: String,
    onOtherCharges: (String) -> Unit,
    tipText: String,
    onTip: (String) -> Unit,
    discountText: String,
    onDiscount: (String) -> Unit,
) {
    val c = EvenlyTheme.colors
    EvCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExtraRow("Subtotal") {
                Text(
                    moneySubunits(subtotalSubunits, currencyCode),
                    color = c.ink,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = EvenlyTheme.monoFamily,
                )
            }
            ExtraRow("Tax") { PriceField(taxText, onTax, symbol) }
            ExtraRow("Gratuity") { PriceField(gratuityText, onGratuity, symbol) }
            // Anything the receipt charges that isn't tax, gratuity, a tip, or a discount. The caption
            // names examples because "Other charges" alone doesn't tell you what belongs in it.
            ExtraRow("Other charges", "Delivery, deposits, card fees") {
                PriceField(otherChargesText, onOtherCharges, symbol)
            }
            // Tip is firmly an even split now — no per-bill toggle; the caption just states it.
            ExtraRow("Tip", "Split evenly") { PriceField(tipText, onTip, symbol) }
            // Discount is the one line that *reduces* the total — a green field + leading "−" make that
            // unmistakable while typing (green = money coming back off).
            ExtraRow("Discount", "Comes off the total") {
                PriceField(discountText, onDiscount, symbol, sign = "−", accent = c.settled, accentFill = c.settledTint)
            }
            Box(Modifier.fillMaxWidth().topHairline(c.border).padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Total", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        moneySubunits(totalSubunits, currencyCode),
                        color = c.ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = EvenlyTheme.monoFamily,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtraRow(
    label: String,
    hint: String? = null,
    trailing: @Composable () -> Unit,
) {
    val c = EvenlyTheme.colors
    Row(Modifier.fillMaxWidth().bringIntoViewOnFocus(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = c.ink, fontSize = 14.sp)
            hint?.let { Text(it, color = c.ink3, fontSize = 11.sp) }
        }
        trailing()
    }
}
