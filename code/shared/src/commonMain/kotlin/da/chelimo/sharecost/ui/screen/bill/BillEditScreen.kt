package da.chelimo.sharecost.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.platform.PickSource
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScModalScaffold
import da.chelimo.sharecost.ui.components.ScSegmented
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.StatusBarScrim
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.currencySymbol
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.screen.expense.format2dp
import da.chelimo.sharecost.domain.expense.perUnitSubunits
import da.chelimo.sharecost.ui.theme.ShareCostTheme
import kotlin.math.roundToLong

/** Which price the user is entering — the per-unit ("each") or the whole-line ("total"). */
enum class PriceDriver { EACH, TOTAL }

/**
 * One editable line in the bill editor. [id] is null for a freshly-added line. The line's **total** is
 * the source of truth ([totalText]); [eachText] is the per-unit mirror. You can type either — the other
 * derives — and [driver] records which one you last touched (so a quantity change recomputes the other).
 */
data class EditBillItemUi(
    val id: String?,
    val label: String,
    val quantity: Int,
    val eachText: String,
    val totalText: String,
    val driver: PriceDriver = PriceDriver.TOTAL,
)

/** subunits → an editable "18.50" string; blank for zero (so the field shows its placeholder). */
private fun subunitsToText(subunits: Long): String = if (subunits == 0L) "" else format2dp(subunits / 100.0)

/** Build an editor line from its line total, deriving the per-unit mirror (used by scan + existing bills). */
fun editBillItemUi(id: String?, label: String, quantity: Int, lineTotalSubunits: Long): EditBillItemUi =
    EditBillItemUi(
        id = id,
        label = label,
        quantity = quantity,
        eachText = subunitsToText(perUnitSubunits(lineTotalSubunits, quantity)),
        totalText = subunitsToText(lineTotalSubunits),
        driver = PriceDriver.TOTAL,
    )

/** Initial contents of the editor (null builds a blank new bill). */
data class EditBillState(
    val title: String,
    val items: List<EditBillItemUi>,
    val taxText: String,
    val gratuityText: String,
    val tipText: String,
    val tipEven: Boolean,
    val discountText: String,
)

/** A group member shown as a selectable participant chip on the bill. */
data class ParticipantChipUi(val userId: String, val name: String, val isMe: Boolean)

/** What the editor emits on save — strings parsed to subunits by the wrapper. */
data class EditBillSubmit(
    val title: String,
    val items: List<EditBillItemUi>,
    val taxSubunits: Long,
    val gratuitySubunits: Long,
    val tipSubunits: Long,
    val tipEven: Boolean,
    val discountSubunits: Long,
    val participantIds: Set<String>,
)

/** Parse a "18.50" style string to integer minor units; blank/garbage → 0. */
fun priceToSubunits(text: String): Long {
    val v = text.trim().toDoubleOrNull() ?: return 0L
    return (v * 100.0).roundToLong()
}

/**
 * The "Split the bill" editor (the creator-owned menu + extras). Available for a new bill and any time
 * after — saving re-derives shares without disturbing claims. DI-free; the wrapper supplies state and
 * handles persistence + the optional receipt scan.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BillEditScreen(
    editing: Boolean,
    initial: EditBillState?,
    currencyCode: String = "USD",
    saving: Boolean = false,
    scanning: Boolean = false,
    participants: List<ParticipantChipUi> = emptyList(),
    initialSelectedIds: Set<String> = emptySet(),
    onBack: () -> Unit = {},
    onScanReceipt: (PickSource) -> Unit = {},
    onSave: (EditBillSubmit) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var scanSource by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var items by remember { mutableStateOf(initial?.items ?: listOf(editBillItemUi(null, "", 1, 0L))) }
    var taxText by remember { mutableStateOf(initial?.taxText ?: "") }
    var gratuityText by remember { mutableStateOf(initial?.gratuityText ?: "") }
    var tipText by remember { mutableStateOf(initial?.tipText ?: "") }
    var tipEven by remember { mutableStateOf(initial?.tipEven ?: true) }
    var discountText by remember { mutableStateOf(initial?.discountText ?: "") }
    // Null = "everyone" (the default, resilient to members loading async); an explicit set once the user
    // deselects. So a fresh bill defaults to the whole group and the creator pares it down.
    var selected by remember { mutableStateOf(initialSelectedIds.takeIf { it.isNotEmpty() }) }
    val allIds = participants.mapTo(LinkedHashSet()) { it.userId }
    val effectiveSelected: Set<String> = selected ?: allIds

    val symbol = currencySymbol(currencyCode)
    val subtotal = items.sumOf { priceToSubunits(it.totalText) } // each line's total is the truth
    val total = subtotal + priceToSubunits(taxText) + priceToSubunits(gratuityText) +
        priceToSubunits(tipText) - priceToSubunits(discountText)
    val canSave = title.trim().isNotEmpty() && items.any { it.label.trim().isNotEmpty() } && !saving

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(c.surface)) {
        StatusBarScrim()
        ScTopBar(
            title = if (editing) "Edit bill" else "Split the bill",
            navIcon = { ScIconButton(ScIcons.Close, onBack) },
            actions = {
                ScButton(
                    text = if (scanning) "Scanning…" else "Scan",
                    onClick = { scanSource = true },
                    variant = ButtonVariant.Text,
                    leadingIcon = ScIcons.Camera,
                    small = true,
                    enabled = !scanning,
                )
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScField("Name") {
                ScTextField(title, { title = it }, placeholder = "Dinner at Tavolo")
            }

            // Who's on this bill — defaults to everyone; deselect anyone who wasn't there.
            if (participants.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Who's on this bill", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        val allOn = effectiveSelected.size >= allIds.size
                        Text(
                            if (allOn) "Deselect all" else "Select all",
                            color = c.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable { selected = if (allOn) emptySet() else allIds }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        participants.forEach { p ->
                            val on = p.userId in effectiveSelected
                            val shape = RoundedCornerShape(999.dp)
                            Row(
                                Modifier.clip(shape)
                                    .background(if (on) c.blueTint else c.page)
                                    .then(if (on) Modifier else Modifier.border(1.dp, c.borderStrong, shape))
                                    .clickable { selected = if (on) effectiveSelected - p.userId else effectiveSelected + p.userId }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (on) ScIcon(ScIcons.Check, size = 14.dp, tint = c.blue)
                                Text(if (p.isMe) "You" else p.name, color = if (on) c.blue else c.ink, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }

            // Items
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Items", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            items = items + editBillItemUi(null, "", 1, 0L)
                        }.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ScIcon(ScIcons.Plus, size = 15.dp, tint = c.blue)
                        Text("Add item", color = c.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                ScCard {
                    items.forEachIndexed { index, item ->
                        ItemEditorRow(
                            item = item,
                            symbol = symbol,
                            showDivider = index > 0,
                            onChange = { updated -> items = items.toMutableList().also { it[index] = updated } },
                            onRemove = { items = items.filterIndexed { i, _ -> i != index } },
                        )
                    }
                }
            }

            // Extras
            ExtrasCard(
                symbol = symbol,
                subtotalSubunits = subtotal,
                totalSubunits = total,
                currencyCode = currencyCode,
                taxText = taxText, onTax = { taxText = it },
                gratuityText = gratuityText, onGratuity = { gratuityText = it },
                tipText = tipText, onTip = { tipText = it },
                tipEven = tipEven, onTipEven = { tipEven = it },
                discountText = discountText, onDiscount = { discountText = it },
            )

            ScButton(
                text = if (saving) "Saving…" else "Save bill",
                onClick = {
                    onSave(
                        EditBillSubmit(
                            title = title.trim(),
                            items = items.filter { it.label.trim().isNotEmpty() },
                            taxSubunits = priceToSubunits(taxText),
                            gratuitySubunits = priceToSubunits(gratuityText),
                            tipSubunits = priceToSubunits(tipText),
                            tipEven = tipEven,
                            discountSubunits = priceToSubunits(discountText),
                            participantIds = effectiveSelected,
                        ),
                    )
                },
                enabled = canSave,
            )
        }
    }

        if (scanSource) {
            ScModalScaffold(onDismiss = { scanSource = false }) {
                Text("Scan a receipt", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                Text("Pick photos or PDFs — several pages read as one bill — or snap a fresh photo.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                ScanSourceRow(ScIcons.Image, "Photos") { scanSource = false; onScanReceipt(PickSource.Photos) }
                ScanSourceRow(ScIcons.Archive, "Files (image or PDF)") { scanSource = false; onScanReceipt(PickSource.Files) }
                ScanSourceRow(ScIcons.Camera, "Take a photo") { scanSource = false; onScanReceipt(PickSource.Camera) }
            }
        }
    }
}

@Composable
private fun ScanSourceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = ShareCostTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScIcon(icon, size = 20.dp, tint = c.blue)
        Text(label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ItemEditorRow(
    item: EditBillItemUi,
    symbol: String,
    showDivider: Boolean,
    onChange: (EditBillItemUi) -> Unit,
    onRemove: () -> Unit,
) {
    val c = ShareCostTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .then(if (showDivider) Modifier.topHairline(c.border) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScTextField(item.label, { onChange(item.copy(label = it)) }, placeholder = "Item", modifier = Modifier.weight(1f), minHeight = 44.dp)
            ScIconButton(ScIcons.Trash, onRemove, tint = c.ink3, size = 18.dp)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.padding(bottom = 8.dp)) {
                Stepper(
                    value = item.quantity,
                    onChange = { onChange(item.withQuantity(it)) },
                    min = 1,
                )
            }
            Box(Modifier.weight(1f))
            if (item.quantity > 1) {
                // Type whichever the receipt shows — the other derives. The highlighted box is the truth.
                LabeledPriceField(
                    label = "each",
                    text = item.eachText,
                    active = item.driver == PriceDriver.EACH,
                    symbol = symbol,
                    onChange = { onChange(item.withEach(it)) },
                    modifier = Modifier.width(100.dp),
                )
                LabeledPriceField(
                    label = "total of ${item.quantity}",
                    text = item.totalText,
                    active = item.driver == PriceDriver.TOTAL,
                    symbol = symbol,
                    onChange = { onChange(item.withTotal(it)) },
                    modifier = Modifier.width(100.dp),
                )
            } else {
                // Single unit — each and total are the same, so just one plain price box (kept clean).
                Text("price", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp, bottom = 8.dp))
                Box(Modifier.padding(bottom = 8.dp)) {
                    PriceField(item.totalText, { onChange(item.withTotal(it)) }, symbol)
                }
            }
        }
    }
}

/** Derive the sibling price whenever one field (or the quantity) changes; the line total is the truth. */
private fun EditBillItemUi.withEach(text: String): EditBillItemUi =
    copy(eachText = text, totalText = subunitsToText(priceToSubunits(text) * quantity), driver = PriceDriver.EACH)

private fun EditBillItemUi.withTotal(text: String): EditBillItemUi =
    copy(totalText = text, eachText = subunitsToText(perUnitSubunits(priceToSubunits(text), quantity)), driver = PriceDriver.TOTAL)

private fun EditBillItemUi.withQuantity(raw: Int): EditBillItemUi {
    val qty = raw.coerceAtLeast(1)
    return if (driver == PriceDriver.EACH) {
        copy(quantity = qty, totalText = subunitsToText(priceToSubunits(eachText) * qty))
    } else {
        copy(quantity = qty, eachText = subunitsToText(perUnitSubunits(priceToSubunits(totalText), qty)))
    }
}

/**
 * A price box with a small label above ("each" / "total of N"). The [active] one — the field you last
 * typed in, i.e. the source of truth — is accented; the other reads as the derived value.
 */
@Composable
private fun LabeledPriceField(
    label: String,
    text: String,
    active: Boolean,
    symbol: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            label,
            color = if (active) c.blue else c.ink3,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .background(if (active) c.blueTint else c.page)
                .border(if (active) 2.dp else 1.dp, if (active) c.blue else c.borderStrong, shape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(symbol, color = if (active) c.blue else c.ink3, fontSize = 13.sp)
            BasicTextField(
                value = text,
                onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '.' }) },
                singleLine = true,
                textStyle = TextStyle(
                    color = if (active) c.blue else c.ink,
                    fontSize = 14.sp,
                    textAlign = TextAlign.End,
                    fontFamily = ShareCostTheme.monoFamily,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                ),
                cursorBrush = SolidColor(c.blue),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        if (text.isEmpty()) Text("0.00", color = c.ink3, fontSize = 14.sp, fontFamily = ShareCostTheme.monoFamily)
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
private fun ExtrasCard(
    symbol: String,
    subtotalSubunits: Long,
    totalSubunits: Long,
    currencyCode: String,
    taxText: String, onTax: (String) -> Unit,
    gratuityText: String, onGratuity: (String) -> Unit,
    tipText: String, onTip: (String) -> Unit,
    tipEven: Boolean, onTipEven: (Boolean) -> Unit,
    discountText: String, onDiscount: (String) -> Unit,
) {
    val c = ShareCostTheme.colors
    ScCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExtraRow("Subtotal") {
                Text(moneySubunits(subtotalSubunits, currencyCode), color = c.ink, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
            }
            ExtraRow("Tax", "by share") { PriceField(taxText, onTax, symbol) }
            ExtraRow("Gratuity", "by share") { PriceField(gratuityText, onGratuity, symbol) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExtraRow("Tip") { PriceField(tipText, onTip, symbol) }
                ScSegmented(
                    options = listOf("Even", "By share"),
                    selected = if (tipEven) "Even" else "By share",
                    onSelect = { onTipEven(it == "Even") },
                )
            }
            ExtraRow("Discount") { PriceField(discountText, onDiscount, symbol) }
            Box(Modifier.fillMaxWidth().topHairline(c.border).padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Total", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(moneySubunits(totalSubunits, currencyCode), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
                }
            }
        }
    }
}

@Composable
private fun ExtraRow(label: String, hint: String? = null, trailing: @Composable () -> Unit) {
    val c = ShareCostTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = c.ink, fontSize = 14.sp)
            hint?.let { Text(it, color = c.ink3, fontSize = 11.sp) }
        }
        trailing()
    }
}

/** A compact right-aligned price input ("$ 0.00") used for item prices and extras. */
@Composable
private fun PriceField(text: String, onChange: (String) -> Unit, symbol: String) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.width(96.dp).clip(shape).background(c.page).border(1.dp, c.borderStrong, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(symbol, color = c.ink3, fontSize = 13.sp)
        BasicTextField(
            value = text,
            onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '.' }) },
            singleLine = true,
            textStyle = TextStyle(color = c.ink, fontSize = 14.sp, textAlign = TextAlign.End, fontFamily = ShareCostTheme.monoFamily),
            cursorBrush = SolidColor(c.blue),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (text.isEmpty()) Text("0.00", color = c.ink3, fontSize = 14.sp, fontFamily = ShareCostTheme.monoFamily)
                    inner()
                }
            },
        )
    }
}

/** A −/value/+ stepper. [min] clamps the bottom (1 for item quantity, 0 for claim counters). */
@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, min: Int = 0, modifier: Modifier = Modifier) {
    val c = ShareCostTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StepBtn(ScIcons.Minus, enabled = value > min, tint = if (value > min) c.blue else c.ink3) { onChange((value - 1).coerceAtLeast(min)) }
        Text("$value", color = if (value > 0) c.ink else c.ink3, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(16.dp), textAlign = TextAlign.Center)
        StepBtn(ScIcons.Plus, enabled = true, tint = c.blue) { onChange(value + 1) }
    }
}

@Composable
private fun StepBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(30.dp).clip(shape).border(1.dp, c.borderStrong, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { ScIcon(icon, size = 15.dp, tint = tint) }
}

