package da.chelimo.sharecost.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScSegmented
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.StatusBarScrim
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.currencySymbol
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.theme.ShareCostTheme
import kotlin.math.roundToLong

/** One editable line in the bill editor. [id] is null for a freshly-added line. */
data class EditBillItemUi(
    val id: String?,
    val label: String,
    val quantity: Int,
    val priceText: String,
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

/** What the editor emits on save — strings parsed to subunits by the wrapper. */
data class EditBillSubmit(
    val title: String,
    val items: List<EditBillItemUi>,
    val taxSubunits: Long,
    val gratuitySubunits: Long,
    val tipSubunits: Long,
    val tipEven: Boolean,
    val discountSubunits: Long,
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
@Composable
fun BillEditScreen(
    editing: Boolean,
    initial: EditBillState?,
    currencyCode: String = "USD",
    saving: Boolean = false,
    scanning: Boolean = false,
    onBack: () -> Unit = {},
    onScanReceipt: () -> Unit = {},
    onSave: (EditBillSubmit) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var items by remember { mutableStateOf(initial?.items ?: listOf(EditBillItemUi(null, "", 1, ""))) }
    var taxText by remember { mutableStateOf(initial?.taxText ?: "") }
    var gratuityText by remember { mutableStateOf(initial?.gratuityText ?: "") }
    var tipText by remember { mutableStateOf(initial?.tipText ?: "") }
    var tipEven by remember { mutableStateOf(initial?.tipEven ?: true) }
    var discountText by remember { mutableStateOf(initial?.discountText ?: "") }

    val symbol = currencySymbol(currencyCode)
    val subtotal = items.sumOf { it.quantity * priceToSubunits(it.priceText) }
    val total = subtotal + priceToSubunits(taxText) + priceToSubunits(gratuityText) +
        priceToSubunits(tipText) - priceToSubunits(discountText)
    val canSave = title.trim().isNotEmpty() && items.any { it.label.trim().isNotEmpty() && priceToSubunits(it.priceText) >= 0 } && !saving

    Column(Modifier.fillMaxSize().background(c.surface)) {
        StatusBarScrim()
        ScTopBar(
            title = if (editing) "Edit bill" else "Split the bill",
            navIcon = { ScIconButton(ScIcons.Close, onBack) },
            actions = {
                ScButton(
                    text = if (scanning) "Scanning…" else "Scan",
                    onClick = onScanReceipt,
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

            // Items
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Items", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            items = items + EditBillItemUi(null, "", 1, "")
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
                        ),
                    )
                },
                enabled = canSave,
            )
        }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper(
                value = item.quantity,
                onChange = { onChange(item.copy(quantity = it.coerceAtLeast(1))) },
                min = 1,
            )
            Box(Modifier.weight(1f))
            Text("each", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            PriceField(item.priceText, { onChange(item.copy(priceText = it)) }, symbol)
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

