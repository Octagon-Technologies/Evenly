package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.screen.expense.PickedReceiptStrip
import app.splitevenly.ui.screen.expense.PickedReceiptUi
import app.splitevenly.domain.pro.ScanMeter
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Editor state for the "By what each had" body: a typed-or-scanned item list plus the bill-level extras.
 * Shared by the add-expense editor and anything else that needs the same item-list surface.
 */
@Stable
class ItemizedBillState {
    var items by mutableStateOf(listOf(editBillItemUi(null, "", 1, 0L)))
    var taxText by mutableStateOf("")
    var gratuityText by mutableStateOf("")
    var otherChargesText by mutableStateOf("")
    var tipText by mutableStateOf("")
    var discountText by mutableStateOf("")

    /**
     * Shows the "couldn't verify" banner after a scan whose amounts didn't reconcile against the receipt's
     * printed total; dismissible for this editing session only (a fresh re-scan re-arms it). BillEditScreen
     * has always honoured [EditBillState.verified]; the add-expense editor silently dropped it, so on the
     * path most people actually take (add expense, itemize, scan) an unverified draft looked clean.
     */
    var showUnverifiedNotice by mutableStateOf(false)

    /** Each line's own total is the truth (the bill editor takes either price and derives the other). */
    val subtotalSubunits: Long get() = items.sumOf { priceToSubunits(it.totalText) }

    val totalSubunits: Long
        get() = subtotalSubunits + priceToSubunits(taxText) + priceToSubunits(gratuityText) +
            priceToSubunits(otherChargesText) + priceToSubunits(tipText) - priceToSubunits(discountText)

    /** Save is gated on this: a bill needs at least one named line. */
    val hasItem: Boolean get() = items.any { it.label.trim().isNotEmpty() }

    /** A completed scan replaces the items + extras (never the shared name/participants). */
    fun applyScan(scanned: EditBillState) {
        items = scanned.items
        taxText = scanned.taxText
        gratuityText = scanned.gratuityText
        otherChargesText = scanned.otherChargesText
        tipText = scanned.tipText
        discountText = scanned.discountText
        showUnverifiedNotice = !scanned.verified
    }

    /** The named lines only, in the shape [EditBillSubmit] expects. */
    fun namedItems(): List<EditBillItemUi> = items.filter { it.label.trim().isNotEmpty() }
}

@Composable
fun rememberItemizedBillState(): ItemizedBillState = remember { ItemizedBillState() }

/**
 * "By what each had": the scan hero card, the unverified-receipt notice, the item editor rows, the bill
 * extras and the derived total. Emits its rows straight into the caller's `Column`, so the caller's
 * arrangement still spaces them.
 */
@Composable
fun ItemizedExpenseBody(
    state: ItemizedBillState,
    symbol: String,
    currencyCode: String,
    attachedReceipts: List<PickedReceiptUi>,
    onRemoveAttachedReceipt: (Int) -> Unit,
    onOpenAttachedReceipt: (Int) -> Unit,
    showErrors: Boolean,
    saving: Boolean,
    saveLabel: String,
    onScanClick: () -> Unit,
    onSave: () -> Unit,
    // Null on every path that has no group context yet, and on Pro groups — see scanMeterFor.
    scanMeter: ScanMeter? = null,
) {
    val c = EvenlyTheme.colors
    // Scan is the marquee action — a hero card at the top. A whisper-soft neutral shadow
    // (same restraint as EvButton/EvFab: no colored glow) lifts it off the page as the
    // primary tap target; the icon chip gets its own matching lift so it reads as a button,
    // not just a decorative glyph next to a chevron.
    val scanCardShape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth()
            .shadow(elevation = 1.dp, shape = scanCardShape, clip = false)
            .clip(scanCardShape).background(c.page)
            .border(1.dp, c.blue, scanCardShape).clickable(onClick = onScanClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(46.dp).shadow(elevation = 2.dp, shape = RoundedCornerShape(13.dp), clip = false)
                .clip(RoundedCornerShape(13.dp)).background(c.blue),
            contentAlignment = Alignment.Center,
        ) {
            EvIcon(EvIcons.Camera, size = 22.dp, tint = c.onAccent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Scan the receipt", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Snap a photo or PDF and we'll fill in the items.", color = c.ink2, fontSize = 12.sp, lineHeight = 16.sp)
        }
        EvIcon(EvIcons.ChevR, size = 18.dp, tint = c.blueText)
    }
    // Under the card, not on it: the count is a fact about the group, not a property of the button.
    ScanQuotaMeter(scanMeter)
    // Sits directly under the scan card, above the items it is talking about.
    if (state.showUnverifiedNotice) {
        UnverifiedReceiptNotice(onDismiss = { state.showUnverifiedNotice = false })
    }
    if (attachedReceipts.isNotEmpty()) {
        PickedReceiptStrip(
            receipts = attachedReceipts,
            label = if (attachedReceipts.size == 1) "Receipt" else "Receipt pages",
            caption = "Saves with the bill.",
            onAddClick = null, // pages come from the scan above, so there is no second way to add one
            onRemoveReceipt = onRemoveAttachedReceipt,
            onOpenReceipt = onOpenAttachedReceipt,
        )
    }
    // A quiet "or add items by hand" divider under the scan hero, then the item list.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(c.border))
        Text("or add items by hand", color = c.ink3, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Box(Modifier.weight(1f).height(1.dp).background(c.border))
    }
    Text("Items", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    EvCard {
        state.items.forEachIndexed { i, item ->
            ItemEditorRow(
                item = item,
                symbol = symbol,
                showDivider = i > 0,
                onChange = { updated -> state.items = state.items.toMutableList().also { it[i] = updated } },
                onRemove = { state.items = state.items.filterIndexed { idx, _ -> idx != i } },
            )
        }
    }
    // Add item sits below the item cards — that's where a new one actually lands.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { state.items = state.items + editBillItemUi(null, "", 1, 0L) }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvIcon(EvIcons.Plus, size = 15.dp, tint = c.blueText)
        Text("Add item", color = c.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    if (showErrors && !state.hasItem) {
        Text("Add at least one item with a name", color = c.danger, fontSize = 12.sp)
    }
    ExtrasCard(
        symbol = symbol,
        subtotalSubunits = state.subtotalSubunits,
        totalSubunits = state.totalSubunits,
        currencyCode = currencyCode,
        taxText = state.taxText, onTax = { state.taxText = it },
        gratuityText = state.gratuityText, onGratuity = { state.gratuityText = it },
        otherChargesText = state.otherChargesText, onOtherCharges = { state.otherChargesText = it },
        tipText = state.tipText, onTip = { state.tipText = it },
        discountText = state.discountText, onDiscount = { state.discountText = it },
    )
    // Save right under the total — where you look when you're done (A4).
    EvButton(if (saving) "Saving…" else saveLabel, onSave, enabled = !saving)
}
