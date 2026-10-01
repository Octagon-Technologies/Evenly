package app.splitevenly.ui.screen.bill

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
import androidx.compose.foundation.rememberScrollState
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
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvField
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
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * "Review the receipt" — the checkpoint between a successful scan and creating the bill (frame 3). No
 * expense exists until [onSave] succeeds, so every correction here is free. Rows are compact and open an
 * editor sheet rather than showing inline fields: the job is comparing against the receipt, and a column
 * of text fields hides the numbers being compared.
 *
 * Reads and writes the same [ItemizedBillState] as the editor behind it, so Back keeps every correction.
 * The title and people live on that editor; when they are missing on tap, the title is asked for here
 * and the missing-people case points back, rather than leaving a live button that silently does nothing.
 */
@Composable
fun ReceiptReviewScreen(
    state: ItemizedBillState,
    title: String,
    onTitle: (String) -> Unit,
    symbol: String,
    currencyCode: String,
    attachedReceipts: List<PickedReceiptUi>,
    showErrors: Boolean,
    peopleMissing: Boolean,
    saving: Boolean,
    onOpenReceipt: (Int) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val c = EvenlyTheme.colors
    // Index into state.items of the line being edited; -1 = the extras sheet; null = nothing open.
    var editing by remember { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.page)) {
            EvTopBar(
                title = "Review the receipt",
                subtitle = "Check items and prices",
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
                if (showErrors && title.isBlank()) {
                    EvField("Title") {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            EvTextField(title, onTitle, placeholder = "What was it for?", isError = true)
                            Text("Give it a title", color = c.danger, fontSize = 12.sp)
                        }
                    }
                }
                if (showErrors && peopleMissing) {
                    Text(
                        "Pick who is splitting this. Go back to add people.",
                        color = c.danger,
                        fontSize = 12.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onBack).padding(vertical = 2.dp),
                    )
                }
                if (state.showUnverifiedNotice) {
                    UnverifiedReceiptNotice(onDismiss = { state.showUnverifiedNotice = false })
                }
                if (attachedReceipts.isNotEmpty()) {
                    ReceiptTile(attachedReceipts.size, onClick = { onOpenReceipt(0) })
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

                EvCard {
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = -1 }.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Tax and tip",
                            color = c.ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        val extras = state.totalSubunits - state.subtotalSubunits
                        if (extras != 0L) {
                            Text(moneySubunits(extras, currencyCode), color = c.ink2, fontSize = 13.sp, fontFamily = EvenlyTheme.monoFamily)
                        }
                        Text("Edit", color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        EvIcon(EvIcons.ChevR, size = 14.dp, tint = c.ink3)
                    }
                }

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
            Box(Modifier.fillMaxWidth().topHairline(c.border).padding(16.dp)) {
                EvButton(if (saving) "Saving…" else "Save & continue", onSave, enabled = !saving)
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

@Composable
private fun ReceiptTile(
    pages: Int,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.page)
                .border(1.dp, c.border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { EvIcon(EvIcons.Receipt, size = 18.dp, tint = c.ink2) }
        Column(Modifier.weight(1f)) {
            Text(
                if (pages ==
                    1
                ) {
                    "Receipt attached"
                } else {
                    "$pages receipt pages attached"
                },
                color = c.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text("Tap to compare with the items", color = c.ink3, fontSize = 12.sp)
        }
        EvIcon(EvIcons.ChevR, size = 16.dp, tint = c.ink3)
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
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
        if (item.quantity > 1) Text("×${item.quantity}", color = c.ink3, fontSize = 12.5.sp, fontFamily = EvenlyTheme.monoFamily)
        Text(
            moneySubunits(priceToSubunits(item.totalText), currencyCode),
            color = c.ink,
            fontSize = 14.sp,
            fontFamily = EvenlyTheme.monoFamily,
        )
        EvIcon(EvIcons.ChevR, size = 14.dp, tint = c.ink3)
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
            symbol = "$",
            currencyCode = "USD",
            attachedReceipts = emptyList(),
            showErrors = false,
            peopleMissing = false,
            saving = false,
            onOpenReceipt = {},
            onBack = {},
            onSave = {},
        )
    }
}
