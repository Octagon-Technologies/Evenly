package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvSectionLabel
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.screen.expense.PickedReceiptUi
import app.splitevenly.ui.screen.expense.ReceiptAttachedTile
import app.splitevenly.ui.screen.expense.TitleWithDateRow
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * "Review the receipt" — the checkpoint between a successful scan and creating the bill (frame 3). No
 * expense exists until [onSave] succeeds, so every correction here is free. Rows are compact and open an
 * editor sheet rather than showing inline fields: the job is comparing against the receipt, and a column
 * of text fields hides the numbers being compared.
 *
 * Reads and writes the same [ItemizedBillState] as the editor behind it, so Back keeps every correction.
 * A scan lands here before anyone may have picked people, so who's splitting and who paid are shown and
 * editable at the top ([ReviewWhoRows]); the sheets are the editor's. The title and date sit first, with
 * the date read off the receipt when it printed a believable one.
 *
 * Two exits instead of one "continue": "Assign items" (do it on this phone) and "Share link" (friends pick
 * their own). Asking here saves the chooser screen that used to follow the save.
 */
@Composable
fun ReceiptReviewScreen(
    state: ItemizedBillState,
    title: String,
    onTitle: (String) -> Unit,
    date: String,
    today: String,
    onDateClick: () -> Unit,
    symbol: String,
    currencyCode: String,
    attachedReceipts: List<PickedReceiptUi>,
    showErrors: Boolean,
    people: List<PickerPersonUi>,
    payerName: String,
    payerIsMe: Boolean,
    onEditPeople: () -> Unit,
    onChangePayer: () -> Unit,
    saving: Boolean,
    onOpenReceipt: (Int) -> Unit,
    onBack: () -> Unit,
    onSave: (BillNext) -> Unit,
) {
    val c = EvenlyTheme.colors
    // Index into state.items of the line being edited; -1 = the extras sheet; null = nothing open.
    var editing by remember { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.page)) {
            EvTopBar(
                title = "Review the receipt",
                subtitle = "Check items, people and prices",
                navIcon = { EvIconButton(EvIcons.Back, onBack) },
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TitleWithDateRow(date, today, onDateClick) {
                    EvTextField(title, onTitle, placeholder = "What was it for?", isError = showErrors && title.isBlank())
                }
                if (showErrors && title.isBlank()) Text("Give it a title", color = c.danger, fontSize = 12.sp)
                ReviewWhoRows(
                    people = people,
                    payerName = payerName,
                    payerIsMe = payerIsMe,
                    showErrors = showErrors,
                    onEditPeople = onEditPeople,
                    onChangePayer = onChangePayer,
                )
                if (state.showUnverifiedNotice) {
                    UnverifiedReceiptNotice(onDismiss = { state.showUnverifiedNotice = false })
                }
                if (attachedReceipts.isNotEmpty()) {
                    ReceiptAttachedTile(attachedReceipts, onClick = { onOpenReceipt(0) })
                }

                Column {
                    EvSectionLabel("Items")
                    EvCard {
                        state.items.forEachIndexed { i, item ->
                            ReviewItemRow(item, currencyCode, first = i == 0, onClick = { editing = i })
                        }
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                state.items = state.items + editBillItemUi(null, "", 1, 0L)
                                editing = state.items.lastIndex
                            }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EvIcon(EvIcons.Plus, size = 15.dp, tint = c.blueText)
                        Text("Add missing item", color = c.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (showErrors && !state.hasItem) {
                        Text("Add at least one item with a name", color = c.danger, fontSize = 12.sp)
                    }
                }

                ChargesBreakdownCard(state, currencyCode, onEdit = { editing = -1 })

                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Receipt total", color = c.ink2, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(
                        moneySubunits(state.totalSubunits, currencyCode),
                        color = c.ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = EvenlyTheme.monoFamily,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().topHairline(c.border).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EvButton(
                    "Assign items",
                    { onSave(BillNext.Assign) },
                    modifier = Modifier.weight(1f),
                    variant = ButtonVariant.Tonal,
                    enabled = !saving,
                )
                EvButton(
                    if (saving) "Saving…" else "Share link",
                    { onSave(BillNext.Share) },
                    modifier = Modifier.weight(1f),
                    variant = ButtonVariant.Primary,
                    enabled = !saving,
                    leadingIcon = EvIcons.Share,
                )
            }
        }

        // Sheets render outside the scrolling Column (ui/AGENTS.md: a sheet inside one throws on Native).
        when (val open = editing) {
            null -> {
                Unit
            }

            -1 -> {
                EvSheetScaffold(onDismiss = { editing = null }, title = "Tax and tip") {
                    ExtrasCard(
                        symbol = symbol,
                        subtotalSubunits = state.subtotalSubunits,
                        totalSubunits = state.totalSubunits,
                        currencyCode = currencyCode,
                        taxText = state.taxText,
                        onTax = { state.taxText = it },
                        gratuityText = state.gratuityText,
                        onGratuity = { state.gratuityText = it },
                        otherChargesText = state.otherChargesText,
                        onOtherCharges = { state.otherChargesText = it },
                        tipText = state.tipText,
                        onTip = { state.tipText = it },
                        discountText = state.discountText,
                        onDiscount = { state.discountText = it },
                    )
                    Box(Modifier.padding(top = 12.dp)) { EvButton("Done", { editing = null }) }
                }
            }

            else -> {
                state.items.getOrNull(open)?.let { item ->
                    EvSheetScaffold(onDismiss = { editing = null }, title = "Edit item") {
                        EvCard {
                            ItemEditorRow(
                                item = item,
                                symbol = symbol,
                                showDivider = false,
                                onChange = { updated -> state.items = state.items.toMutableList().also { it[open] = updated } },
                                onRemove = {
                                    editing = null
                                    state.items = state.items.filterIndexed { idx, _ -> idx != open }
                                },
                            )
                        }
                        Box(Modifier.padding(top = 12.dp)) { EvButton("Done", { editing = null }) }
                    }
                }
            }
        }
    }
}

/**
 * The receipt's charges on the screen itself, not behind Edit: a lone "Tax and tip $73.60" on a $252
 * subtotal reads as a mistake until you see it is tax plus an 18% gratuity. Zero lines are left out.
 */
@Composable
private fun ChargesBreakdownCard(
    state: ItemizedBillState,
    currencyCode: String,
    onEdit: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val lines =
        listOf(
            "Tax" to priceToSubunits(state.taxText),
            "Gratuity" to priceToSubunits(state.gratuityText),
            "Other charges" to priceToSubunits(state.otherChargesText),
            "Tip" to priceToSubunits(state.tipText),
            "Discount" to -priceToSubunits(state.discountText),
        ).filter { it.second != 0L }
    EvCard {
        Column(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tax and tip", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Edit", color = c.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                EvIcon(EvIcons.ChevR, size = 18.dp, tint = c.ink)
            }
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChargeLine("Subtotal", moneySubunits(state.subtotalSubunits, currencyCode))
                if (lines.isEmpty()) {
                    Text("No tax, tip or fees added", color = c.ink3, fontSize = 13.sp)
                }
                lines.forEach { (label, amount) ->
                    val text = moneySubunits(kotlin.math.abs(amount), currencyCode)
                    ChargeLine(label, if (amount < 0) "−$text" else text, discount = amount < 0)
                }
            }
        }
    }
}

@Composable
private fun ChargeLine(
    label: String,
    amount: String,
    discount: Boolean = false,
) {
    val c = EvenlyTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.ink2, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(amount, color = if (discount) c.settled else c.ink, fontSize = 14.sp, fontFamily = EvenlyTheme.monoFamily)
    }
}

@Composable
private fun ReviewItemRow(
    item: EditBillItemUi,
    currencyCode: String,
    first: Boolean,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val blank = item.label.isBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (first) Modifier else Modifier.topHairline(c.border))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (blank) "Unnamed item" else item.label,
            color = if (blank) c.ink3 else c.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // A filled chip, not grey text: "×4" next to "$96.00" was the easiest number on the screen to miss.
        if (item.quantity > 1) QuantityChip(item.quantity)
        Text(
            moneySubunits(priceToSubunits(item.totalText), currencyCode),
            color = c.ink,
            fontSize = 14.sp,
            fontFamily = EvenlyTheme.monoFamily,
        )
        EvIcon(EvIcons.ChevR, size = 18.dp, tint = c.ink)
    }
}

@Composable
private fun QuantityChip(quantity: Int) {
    val c = EvenlyTheme.colors
    Box(
        Modifier
            .heightIn(min = 26.dp)
            .widthIn(min = 26.dp)
            .clip(CircleShape)
            .background(c.blue)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("×$quantity", color = c.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Preview
@Composable
private fun ReceiptReviewPreview() {
    val state =
        ItemizedBillState().apply {
            items =
                listOf(
                    editBillItemUi(null, "Chicken Tikka Masala", 1, 2400),
                    editBillItemUi(null, "24k Gold Garlic Naan", 2, 1200),
                    editBillItemUi(null, "Butter Chicken", 1, 2400),
                )
        }
    EvenlyTheme {
        ReceiptReviewScreen(
            state = state,
            title = "Friday dinner",
            onTitle = {},
            date = "2026-11-20",
            today = "2026-11-22",
            onDateClick = {},
            symbol = "$",
            currencyCode = "USD",
            attachedReceipts = emptyList(),
            showErrors = false,
            people = listOf(PickerPersonUi("u1", "Andrew", true), PickerPersonUi("u2", "Purity", false)),
            payerName = "You",
            payerIsMe = true,
            onEditPeople = {},
            onChangePayer = {},
            saving = false,
            onOpenReceipt = {},
            onBack = {},
            onSave = {},
        )
    }
}
