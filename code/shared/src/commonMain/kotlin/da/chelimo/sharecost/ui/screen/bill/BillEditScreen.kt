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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScAvatarStack
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScCheck
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScModalScaffold
import da.chelimo.sharecost.ui.components.ScSheetScaffold
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/** Initial contents of the editor (null builds a blank new bill). Tip always splits evenly. */
data class EditBillState(
    val title: String,
    val items: List<EditBillItemUi>,
    val taxText: String,
    val gratuityText: String,
    val tipText: String,
    val discountText: String,
)

/** A group member shown as a selectable participant chip on the bill. */
data class ParticipantChipUi(val userId: String, val name: String, val isMe: Boolean)

/** What the editor emits on save — strings parsed to subunits by the wrapper. Tip always splits evenly. */
data class EditBillSubmit(
    val title: String,
    val items: List<EditBillItemUi>,
    val taxSubunits: Long,
    val gratuitySubunits: Long,
    val tipSubunits: Long,
    val discountSubunits: Long,
    val participantIds: Set<String>,
    // Who paid — set by the unified add-expense editor's shared header; null when the bill editor (which
    // has no payer picker) is used for editing, in which case the wrapper keeps the existing payer.
    val payerUserId: String? = null,
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
    scanned: EditBillState? = null,
    currencyCode: String = "USD",
    saving: Boolean = false,
    scanState: ScanUiState = ScanUiState.Idle,
    attachedReceiptCount: Int = 0,
    participants: List<ParticipantChipUi> = emptyList(),
    initialSelectedIds: Set<String> = emptySet(),
    onBack: () -> Unit = {},
    onScanReceipt: (PickSource) -> Unit = {},
    onCancelScan: () -> Unit = {},
    onRetryScan: () -> Unit = {},
    onDismissScan: () -> Unit = {},
    onSave: (EditBillSubmit) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val scanning = scanState is ScanUiState.Working
    var scanSource by remember { mutableStateOf(false) }
    // Flips true the first time Save is tapped while incomplete — then the missing fields turn red.
    var showErrors by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var items by remember { mutableStateOf(initial?.items ?: listOf(editBillItemUi(null, "", 1, 0L))) }
    var taxText by remember { mutableStateOf(initial?.taxText ?: "") }
    var gratuityText by remember { mutableStateOf(initial?.gratuityText ?: "") }
    var tipText by remember { mutableStateOf(initial?.tipText ?: "") }
    var discountText by remember { mutableStateOf(initial?.discountText ?: "") }
    // Null = "everyone" (the default, resilient to members loading async); an explicit set once the user
    // deselects. So a fresh bill defaults to the whole group and the creator pares it down.
    var selected by remember { mutableStateOf(initialSelectedIds.takeIf { it.isNotEmpty() }) }
    val allIds = participants.mapTo(LinkedHashSet()) { it.userId }
    val effectiveSelected: Set<String> = selected ?: allIds
    var showPicker by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }

    // A completed scan pre-fills ONLY the item list + extras — never the name or who's-on-the-bill the
    // user already typed. Applied as an update (not by recreating the editor, which is what used to wipe
    // the name). A re-scan replaces items again because [scanned] is a fresh instance each time.
    LaunchedEffect(scanned) {
        scanned?.let { s ->
            items = s.items
            taxText = s.taxText
            gratuityText = s.gratuityText
            tipText = s.tipText
            discountText = s.discountText
        }
    }

    val symbol = currencySymbol(currencyCode)
    val subtotal = items.sumOf { priceToSubunits(it.totalText) } // each line's total is the truth
    val total = subtotal + priceToSubunits(taxText) + priceToSubunits(gratuityText) +
        priceToSubunits(tipText) - priceToSubunits(discountText)
    val nameValid = title.trim().isNotEmpty()
    val hasItem = items.any { it.label.trim().isNotEmpty() }
    val isValid = nameValid && hasItem

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
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // A scanned receipt rides along as the expense's attachment — tell the user it'll be saved.
            if (attachedReceiptCount > 0) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.blueTint).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScIcon(ScIcons.Receipt, size = 16.dp, tint = c.blue)
                    Text(
                        if (attachedReceiptCount == 1) "Receipt attached — saves with the bill"
                        else "$attachedReceiptCount receipt pages attached — save with the bill",
                        color = c.blue, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }

            ScField("Name") {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    ScTextField(title, { title = it }, placeholder = "Dinner at Tavolo", isError = showErrors && !nameValid)
                    if (showErrors && !nameValid) {
                        Text("Give the bill a name", color = c.danger, fontSize = 12.sp)
                    }
                }
            }

            // Who's on this bill — defaults to everyone; deselect anyone who wasn't there. Small groups
            // fit as inline chips; larger ones collapse to a summary that opens a searchable picker.
            if (participants.isNotEmpty()) {
                if (participants.size <= 6) {
                    ParticipantChips(
                        participants = participants,
                        selected = effectiveSelected,
                        allIds = allIds,
                        onToggle = { id -> selected = if (id in effectiveSelected) effectiveSelected - id else effectiveSelected + id },
                        onSelectAll = { selected = allIds },
                        onDeselectAll = { selected = emptySet() },
                    )
                } else {
                    ParticipantSummary(
                        participants = participants,
                        selected = effectiveSelected,
                        allCount = allIds.size,
                        onEdit = { showPicker = true },
                    )
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
                if (showErrors && !hasItem) {
                    Text("Add at least one item with a name", color = c.danger, fontSize = 12.sp)
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
                discountText = discountText, onDiscount = { discountText = it },
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The button stays live. Tapping while incomplete reddens the gaps and scrolls back to
                // them (the contextual errors under Name/Items), so Save is never a silent dead end.
                ScButton(
                    text = if (saving) "Saving…" else "Save bill",
                    onClick = {
                        if (!isValid) {
                            showErrors = true
                            scope.launch { scrollState.animateScrollTo(0) }
                        } else {
                            onSave(
                                EditBillSubmit(
                                    title = title.trim(),
                                    items = items.filter { it.label.trim().isNotEmpty() },
                                    taxSubunits = priceToSubunits(taxText),
                                    gratuitySubunits = priceToSubunits(gratuityText),
                                    tipSubunits = priceToSubunits(tipText),
                                    discountSubunits = priceToSubunits(discountText),
                                    participantIds = effectiveSelected,
                                ),
                            )
                        }
                    },
                    enabled = !saving,
                )
            }
        }
    }

        if (scanSource) {
            ScModalScaffold(onDismiss = { scanSource = false }) {
                Text("Scan the bill", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                Text("A restaurant check or store receipt with line items — we'll pull them out for you. Several pages read as one bill.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                ScanSourceRow(ScIcons.Image, "Photos") { scanSource = false; onScanReceipt(PickSource.Photos) }
                ScanSourceRow(ScIcons.Archive, "Files (image or PDF)") { scanSource = false; onScanReceipt(PickSource.Files) }
                ScanSourceRow(ScIcons.Camera, "Take a photo") { scanSource = false; onScanReceipt(PickSource.Camera) }
            }
        }

        if (showPicker) {
            ParticipantPickerSheet(
                participants = participants,
                selected = effectiveSelected,
                allCount = allIds.size,
                query = pickerQuery,
                onQuery = { pickerQuery = it },
                onToggle = { id -> selected = if (id in effectiveSelected) effectiveSelected - id else effectiveSelected + id },
                onSelectAll = { selected = allIds },
                onDeselectAll = { selected = emptySet() },
                onDone = { showPicker = false; pickerQuery = "" },
            )
        }

        // The scan round-trip drives its own sheet: progress while reading, a typed error card otherwise.
        when (val s = scanState) {
            is ScanUiState.Working -> ScanProgressSheet(pages = s.pages, onCancel = onCancelScan)
            is ScanUiState.Failed -> ScanErrorSheet(
                kind = s.kind,
                onManual = onDismissScan,
                onRetry = onRetryScan,
                onPickAgain = { onDismissScan(); scanSource = true },
            )
            ScanUiState.Idle -> {}
        }
    }
}

/** The bottom sheet shown while OCR runs: the pages being read, an indeterminate bar, and rotating copy. */
@Composable
internal fun ScanProgressSheet(pages: List<ScanPageUi>, onCancel: () -> Unit) {
    val c = ShareCostTheme.colors
    val phrases = remember { listOf("Reading the receipt…", "Finding the items…", "Adding up the totals…", "Almost there…") }
    var phraseIdx by remember { mutableStateOf(0) }
    // Cycle the copy on a timer. It's honest reassurance, not real progress — the round-trip is one call.
    LaunchedEffect(Unit) {
        while (true) { delay(1500); phraseIdx = (phraseIdx + 1) % phrases.size }
    }
    val pageLine = if (pages.size == 1) "1 page · this usually takes a few seconds"
    else "${pages.size} pages · this usually takes a few seconds"
    ScSheetScaffold(onDismiss = onCancel, title = "Scanning your receipt", sub = pageLine) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.take(4).forEach { ScanPageTile(it) }
            if (pages.size > 4) {
                Text("+${pages.size - 4}", color = c.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(99.dp)),
            color = c.blue,
            trackColor = c.blueTint,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScIcon(ScIcons.Sparkle, size = 15.dp, tint = c.blue)
            Text(phrases[phraseIdx], color = c.ink2, fontSize = 13.sp)
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                "Cancel",
                color = c.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ScanPageTile(page: ScanPageUi) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(width = 56.dp, height = 72.dp).clip(shape).background(c.blueTint).border(1.dp, c.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        ScIcon(if (page.isPdf) ScIcons.Receipt else ScIcons.Image, size = 22.dp, tint = c.blue)
    }
}

/** The bottom sheet for a scan that couldn't finish. Copy + actions vary by [kind]; manual entry is always here. */
@Composable
internal fun ScanErrorSheet(
    kind: ScanErrorKind,
    onManual: () -> Unit,
    onRetry: () -> Unit,
    onPickAgain: () -> Unit,
) {
    val c = ShareCostTheme.colors
    val icon = when (kind) {
        ScanErrorKind.Offline -> ScIcons.WifiOff
        ScanErrorKind.NoReceiptFound -> ScIcons.Receipt
        ScanErrorKind.Unavailable -> ScIcons.Info
        ScanErrorKind.Error -> ScIcons.Alert
    }
    val tint = when (kind) {
        ScanErrorKind.Offline -> c.warning
        ScanErrorKind.Error -> c.danger
        else -> c.ink2
    }
    val heading = when (kind) {
        ScanErrorKind.Offline -> "You're offline"
        ScanErrorKind.NoReceiptFound -> "Couldn't read it"
        ScanErrorKind.Unavailable -> "Scanning isn't available"
        ScanErrorKind.Error -> "Something went wrong"
    }
    val body = when (kind) {
        ScanErrorKind.Offline -> "Scanning needs a connection. You can still type the bill in now."
        ScanErrorKind.NoReceiptFound -> "No items found — the photo may be blurry or not a receipt."
        ScanErrorKind.Unavailable -> "Receipt scanning isn't set up here. Add the bill by hand."
        ScanErrorKind.Error -> "The scan failed. Give it another try, or type it in."
    }
    ScSheetScaffold(onDismiss = onManual) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(52.dp).clip(RoundedCornerShape(99.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { ScIcon(icon, size = 24.dp, tint = tint) }
        Text(
            heading, Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
            color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Text(
            body, Modifier.fillMaxWidth().padding(bottom = 20.dp),
            color = c.ink2, fontSize = 14.sp, textAlign = TextAlign.Center,
        )
        // Error → offer "Try again" as the hero; the rest lead with manual entry.
        when (kind) {
            ScanErrorKind.Error -> {
                ScButton(text = "Try again", onClick = onRetry)
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    ScButton(text = "Enter manually", onClick = onManual, variant = ButtonVariant.Text)
                }
            }
            ScanErrorKind.Unavailable -> {
                ScButton(text = "Enter manually", onClick = onManual)
            }
            else -> {
                ScButton(text = "Enter manually", onClick = onManual)
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    ScButton(
                        text = if (kind == ScanErrorKind.NoReceiptFound) "Try a new photo" else "Retry",
                        onClick = if (kind == ScanErrorKind.NoReceiptFound) onPickAgain else onRetry,
                        variant = ButtonVariant.Text,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScanSourceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
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
internal fun ItemEditorRow(
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
            // Qty is always shown and labeled (and typable) so it's obvious that raising it reveals the
            // "each / total of N" pair — the discoverability the old hidden stepper lacked.
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Qty", color = c.ink3, fontSize = 11.sp)
                QtyField(value = item.quantity, onChange = { onChange(item.withQuantity(it)) })
            }
            Box(Modifier.weight(1f))
            // Both "each" and "total" show on EVERY item (not just qty>1), so you can always type whichever
            // the receipt lists — never dividing by hand. Editing one derives the other; the highlighted box
            // is the source of truth. At qty 1 they mirror each other; past that the "total of N" earns its keep.
            LabeledPriceField(
                label = "each",
                text = item.eachText,
                active = item.driver == PriceDriver.EACH,
                symbol = symbol,
                onChange = { onChange(item.withEach(it)) },
                modifier = Modifier.width(94.dp),
            )
            LabeledPriceField(
                label = if (item.quantity > 1) "total of ${item.quantity}" else "total",
                text = item.totalText,
                active = item.driver == PriceDriver.TOTAL,
                symbol = symbol,
                onChange = { onChange(item.withTotal(it)) },
                modifier = Modifier.width(94.dp),
            )
        }
    }
}

/** A −/typable-number/+ quantity control for the item editor. Typing avoids tapping "+" many times. */
@Composable
private fun QtyField(value: Int, onChange: (Int) -> Unit) {
    val c = ShareCostTheme.colors
    // Local text so the middle field can be cleared and retyped smoothly; re-seeds when [value] changes
    // externally (via the −/+ buttons). An empty field doesn't propagate — the quantity holds at its last
    // value until a digit lands, so it never snaps to 1 mid-edit.
    var text by remember(value) { mutableStateOf(value.toString()) }
    val shape = RoundedCornerShape(8.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepBtn(ScIcons.Minus, enabled = value > 1, tint = if (value > 1) c.blue else c.ink3) { onChange((value - 1).coerceAtLeast(1)) }
        Box(
            Modifier.width(40.dp).height(30.dp).clip(shape).border(1.dp, c.borderStrong, shape),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(3)
                    text = digits
                    digits.toIntOrNull()?.let { if (it >= 1) onChange(it) }
                },
                singleLine = true,
                textStyle = TextStyle(color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(c.blue),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(36.dp),
            )
        }
        StepBtn(ScIcons.Plus, enabled = true, tint = c.blue) { onChange(value + 1) }
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
internal fun ExtrasCard(
    symbol: String,
    subtotalSubunits: Long,
    totalSubunits: Long,
    currencyCode: String,
    taxText: String, onTax: (String) -> Unit,
    gratuityText: String, onGratuity: (String) -> Unit,
    tipText: String, onTip: (String) -> Unit,
    discountText: String, onDiscount: (String) -> Unit,
) {
    val c = ShareCostTheme.colors
    ScCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExtraRow("Subtotal") {
                Text(moneySubunits(subtotalSubunits, currencyCode), color = c.ink, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
            }
            ExtraRow("Tax") { PriceField(taxText, onTax, symbol) }
            ExtraRow("Gratuity") { PriceField(gratuityText, onGratuity, symbol) }
            // Tip is firmly an even split now — no per-bill toggle; the caption just states it.
            ExtraRow("Tip", "Split evenly") { PriceField(tipText, onTip, symbol) }
            // Discount is the one line that *reduces* the total — the leading "−" + caption make that clear.
            ExtraRow("Discount", "Comes off the total") { PriceField(discountText, onDiscount, symbol, sign = "−") }
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

/** Small-group "who's on this bill": every member as an inline toggle chip, plus select/deselect all. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParticipantChips(
    participants: List<ParticipantChipUi>,
    selected: Set<String>,
    allIds: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
) {
    val c = ShareCostTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Who's on this bill", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val allOn = selected.size >= allIds.size
            Text(
                if (allOn) "Deselect all" else "Select all",
                color = c.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { if (allOn) onDeselectAll() else onSelectAll() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            participants.forEach { p ->
                val on = p.userId in selected
                val shape = RoundedCornerShape(999.dp)
                Row(
                    Modifier.clip(shape)
                        .background(if (on) c.blueTint else c.page)
                        .then(if (on) Modifier else Modifier.border(1.dp, c.borderStrong, shape))
                        .clickable { onToggle(p.userId) }
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

/** Large-group "who's on this bill": a compact summary (avatars + count) that opens the picker sheet. */
@Composable
private fun ParticipantSummary(
    participants: List<ParticipantChipUi>,
    selected: Set<String>,
    allCount: Int,
    onEdit: () -> Unit,
) {
    val c = ShareCostTheme.colors
    val chosen = participants.filter { it.userId in selected }
    val count = chosen.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Who's on this bill", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        ScCard {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (count > 0) {
                    ScAvatarStack(names = chosen.take(4).map { if (it.isMe) "You" else it.name }, size = AvatarSize.Sm)
                    if (count > 4) Text("+${count - 4}", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.weight(1f))
                Text(
                    when {
                        count == 0 -> "Add people"
                        count >= allCount -> "Everyone · $allCount"
                        else -> "$count of $allCount"
                    },
                    color = c.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
                ScIcon(ScIcons.ChevR, size = 18.dp, tint = c.blue)
            }
        }
    }
}

/** The searchable member picker for larger groups — one tappable row each, with select/deselect all. */
@Composable
private fun ParticipantPickerSheet(
    participants: List<ParticipantChipUi>,
    selected: Set<String>,
    allCount: Int,
    query: String,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onDone: () -> Unit,
) {
    val c = ShareCostTheme.colors
    val allOn = selected.size >= allCount
    val filtered = participants.filter {
        val label = if (it.isMe) "You" else it.name
        query.isBlank() || label.contains(query.trim(), ignoreCase = true)
    }
    ScSheetScaffold(
        onDismiss = onDone,
        title = "Who's on this bill",
        sub = "${selected.size} of $allCount selected",
    ) {
        ScTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = "Search members",
            leading = { ScIcon(ScIcons.Search, size = 18.dp, tint = c.ink3) },
            minHeight = 44.dp,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${filtered.size} ${if (filtered.size == 1) "member" else "members"}",
                color = c.ink3, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            Text(
                if (allOn) "Deselect all" else "Select all",
                color = c.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { if (allOn) onDeselectAll() else onSelectAll() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            filtered.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onToggle(p.userId) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScAvatar(name = if (p.isMe) "You" else p.name, me = p.isMe, size = AvatarSize.Sm)
                    Text(if (p.isMe) "You" else p.name, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    ScCheck(checked = p.userId in selected, onCheckedChange = { onToggle(p.userId) })
                }
            }
            if (filtered.isEmpty()) {
                Text("No one matches your search", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            ScButton(text = "Done", onClick = onDone)
        }
    }
}

/**
 * A compact right-aligned price input ("$ 0.00") used for item prices and extras. [sign] is an optional
 * leading glyph (e.g. "−" for the discount) that flags the amount as a deduction without recoloring it.
 */
@Composable
private fun PriceField(text: String, onChange: (String) -> Unit, symbol: String, sign: String = "") {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.width(96.dp).clip(shape).background(c.page).border(1.dp, c.borderStrong, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (sign.isNotEmpty()) Text(sign, color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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

