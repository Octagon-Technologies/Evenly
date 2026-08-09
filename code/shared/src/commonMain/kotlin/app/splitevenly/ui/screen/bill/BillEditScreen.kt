package app.splitevenly.ui.screen.bill

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.platform.PickSource
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.currencySymbol
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.screen.expense.PickedReceiptUi
import app.splitevenly.ui.screen.expense.PickedReceiptStrip
import app.splitevenly.ui.screen.expense.StagedReceiptViewer
import app.splitevenly.ui.screen.expense.format2dp
import app.splitevenly.domain.expense.perUnitSubunits
import app.splitevenly.ui.theme.EvenlyTheme
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
    // Delivery fee, bottle deposit, card surcharge. Splits proportionally like tax (domain/AGENTS.md).
    val otherChargesText: String = "",
    val tipText: String,
    val discountText: String,
    // False when the OCR scan that produced this state exhausted the server's Haiku->Sonnet->Opus
    // cascade without a draft that reconciled against the receipt's printed total — see
    // ReceiptDraft.verified. Irrelevant (defaults true) for a manually-entered or already-saved bill.
    val verified: Boolean = true,
)

/** A group member shown as a selectable participant chip on the bill. */
data class ParticipantChipUi(val userId: String, val name: String, val isMe: Boolean)

/** What the editor emits on save — strings parsed to subunits by the wrapper. Tip always splits evenly. */
data class EditBillSubmit(
    val title: String,
    val items: List<EditBillItemUi>,
    val taxSubunits: Long,
    val gratuitySubunits: Long,
    val otherChargesSubunits: Long,
    val tipSubunits: Long,
    val discountSubunits: Long,
    val participantIds: Set<String>,
    // Who paid. Either the add-expense editor's shared header or the bill editor's own "Paid by" row sets
    // it; null only if neither ran, in which case the wrapper keeps the existing payer.
    val payerUserId: String? = null,
    /** Non-blank when someone outside the group paid. They are not part of the split. */
    val payerOutsideName: String? = null,
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
    scanned: EditBillState? = null,
    currencyCode: String = "USD",
    saving: Boolean = false,
    scanState: ScanUiState = ScanUiState.Idle,
    attachedReceipts: List<PickedReceiptUi> = emptyList(),
    onRemoveAttachedReceipt: (Int) -> Unit = {},
    // Local-file PDF rendering for the staged viewer, wired by the route (mirrors ExpenseDetailScreen).
    loadPdfPageCount: suspend (url: String) -> Int = { 0 },
    renderPdfPage: suspend (url: String, page: Int, widthPx: Int) -> ImageBitmap? = { _, _, _ -> null },
    participants: List<ParticipantChipUi> = emptyList(),
    initialSelectedIds: Set<String> = emptySet(),
    initialPayerId: String = "",
    initialOutsidePayerName: String? = null,
    // How many lines each person currently holds. Only used to decide whether taking them off the bill
    // needs confirming, since that discards their claims.
    claimedItemsByUser: Map<String, Int> = emptyMap(),
    onBack: () -> Unit = {},
    onScanReceipt: (PickSource) -> Unit = {},
    onCancelScan: () -> Unit = {},
    onRetryScan: () -> Unit = {},
    onDismissScan: () -> Unit = {},
    // Add someone who isn't in the group yet (a placeholder member) straight from the bill — so a bill can
    // include a friend without the app. The new member arrives via [participants] and is auto-selected.
    onAddPerson: (String) -> Unit = {},
    onSave: (EditBillSubmit) -> Unit = {},
) {
    val c = EvenlyTheme.colors
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val scanning = scanState is ScanUiState.Working
    var scanSource by remember { mutableStateOf(false) }
    // Which scanned page the full-screen viewer is open on; null = closed.
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    // Flips true the first time Save is tapped while incomplete — then the missing fields turn red.
    var showErrors by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var items by remember { mutableStateOf(initial?.items ?: listOf(editBillItemUi(null, "", 1, 0L))) }
    var taxText by remember { mutableStateOf(initial?.taxText ?: "") }
    var gratuityText by remember { mutableStateOf(initial?.gratuityText ?: "") }
    var otherChargesText by remember { mutableStateOf(initial?.otherChargesText ?: "") }
    var tipText by remember { mutableStateOf(initial?.tipText ?: "") }
    var discountText by remember { mutableStateOf(initial?.discountText ?: "") }
    // Shows the "couldn't verify" banner after a scan that didn't reconcile; dismissible for this
    // editing session only (a fresh re-scan re-arms it via the LaunchedEffect below).
    var showUnverifiedNotice by remember { mutableStateOf(false) }
    // Null = "everyone" (the default, resilient to members loading async); an explicit set once the user
    // deselects. So a fresh bill defaults to the whole group and the creator pares it down.
    var selected by remember { mutableStateOf(initialSelectedIds.takeIf { it.isNotEmpty() }) }
    val allIds = participants.mapTo(LinkedHashSet()) { it.userId }
    val effectiveSelected: Set<String> = selected ?: allIds
    var showAddDialog by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    var showPayerSheet by remember { mutableStateOf(false) }
    var payerId by remember { mutableStateOf(initialPayerId) }
    var outsidePayerName by remember { mutableStateOf(initialOutsidePayerName) }
    // Set while a removal that would discard someone's claims waits on the confirm sheet.
    var pendingRemoval by remember { mutableStateOf<PendingRemoval?>(null) }

    /** Apply a new roster, or park it on the confirm sheet when it would throw claims away. */
    fun selectTo(next: Set<String>) {
        val gate = pendingRemovalFor(participants, effectiveSelected, next, claimedItemsByUser)
        if (gate == null) selected = next else pendingRemoval = gate
    }
    // Auto-select a person added mid-edit (a placeholder), without disturbing the current selection. The
    // baseline is set on the FIRST non-empty member load so a pared-down [initialSelectedIds] survives the
    // async load; only members that appear *after* that (i.e. just added) are folded into an explicit set.
    // A null selection already means "everyone", so a newcomer is included with no change needed.
    var memberBaseline by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(participants) {
        if (participants.isEmpty()) return@LaunchedEffect
        val ids = participants.mapTo(HashSet()) { it.userId }
        val prev = memberBaseline
        if (prev == null) { memberBaseline = ids; return@LaunchedEffect }
        val fresh = ids - prev
        if (fresh.isNotEmpty()) {
            selected = selected?.plus(fresh)
            memberBaseline = ids
        }
    }

    // A completed scan pre-fills ONLY the item list + extras — never the name or who's-on-the-bill the
    // user already typed. Applied as an update (not by recreating the editor, which is what used to wipe
    // the name). A re-scan replaces items again because [scanned] is a fresh instance each time.
    LaunchedEffect(scanned) {
        scanned?.let { s ->
            items = s.items
            taxText = s.taxText
            gratuityText = s.gratuityText
            otherChargesText = s.otherChargesText
            tipText = s.tipText
            discountText = s.discountText
            showUnverifiedNotice = !s.verified
        }
    }

    val symbol = currencySymbol(currencyCode)
    val subtotal = items.sumOf { priceToSubunits(it.totalText) } // each line's total is the truth
    val total = subtotal + priceToSubunits(taxText) + priceToSubunits(gratuityText) +
        priceToSubunits(otherChargesText) +
        priceToSubunits(tipText) - priceToSubunits(discountText)
    val nameValid = title.trim().isNotEmpty()
    val hasItem = items.any { it.label.trim().isNotEmpty() }
    val isValid = nameValid && hasItem

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim()
        EvTopBar(
            title = if (editing) "Edit bill" else "Split the bill",
            navIcon = { EvIconButton(EvIcons.Close, onBack) },
            actions = {
                EvButton(
                    text = if (scanning) "Scanning…" else "Scan",
                    onClick = { scanSource = true },
                    variant = ButtonVariant.Text,
                    leadingIcon = EvIcons.Camera,
                    small = true,
                    enabled = !scanning,
                )
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The scan exhausted its passes and still couldn't reconcile the draft against the receipt's
            // printed total. Markup lives in UnverifiedReceiptNotice so AddExpenseScreen shows the same
            // banner from the same state instead of quietly showing none.
            if (showUnverifiedNotice) {
                UnverifiedReceiptNotice(onDismiss = { showUnverifiedNotice = false })
            }

            // A scanned receipt rides along as the expense's attachment, previewable before it is saved.
            if (attachedReceipts.isNotEmpty()) {
                PickedReceiptStrip(
                    receipts = attachedReceipts,
                    label = if (attachedReceipts.size == 1) "Receipt" else "Receipt pages",
                    caption = "Saves with the bill.",
                    onAddClick = null,
                    onRemoveReceipt = onRemoveAttachedReceipt,
                    onOpenReceipt = { viewerIndex = it },
                )
            }

            EvField("Name") {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    EvTextField(title, { title = it }, placeholder = "Dinner at Tavolo", isError = showErrors && !nameValid)
                    if (showErrors && !nameValid) {
                        Text("Give the bill a name", color = c.danger, fontSize = 12.sp)
                    }
                }
            }

            // Who's on this bill — defaults to everyone; take off anyone who wasn't there. Taking off
            // someone who has claimed goes through the confirm sheet, since it discards their claims.
            BillPeopleRow(
                participants = participants,
                selected = effectiveSelected,
                allIds = allIds,
                onToggle = { id -> selectTo(if (id in effectiveSelected) effectiveSelected - id else effectiveSelected + id) },
                onSelectAll = { selected = allIds },
                onDeselectAll = { selectTo(emptySet()) },
                onAddPersonClick = { showAddDialog = true },
                onOpenPicker = { showPicker = true },
            )

            // Paid by comes AFTER the people, like the add-expense editor: pick who was there, then who
            // covered it. Asked the other way round, the payer picker reads as "who's in the split".
            if (participants.isNotEmpty()) {
                BillPaidByRow(
                    participants = participants,
                    selected = effectiveSelected,
                    payerUserId = if (outsidePayerName.isNullOrBlank()) payerId else "",
                    outsidePayerName = outsidePayerName,
                    onOpenSheet = { showPayerSheet = true },
                )
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
                        EvIcon(EvIcons.Plus, size = 15.dp, tint = c.blueText)
                        Text("Add item", color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                EvCard {
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
                otherChargesText = otherChargesText, onOtherCharges = { otherChargesText = it },
                tipText = tipText, onTip = { tipText = it },
                discountText = discountText, onDiscount = { discountText = it },
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The button stays live. Tapping while incomplete reddens the gaps and scrolls back to
                // them (the contextual errors under Name/Items), so Save is never a silent dead end.
                EvButton(
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
                        otherChargesSubunits = priceToSubunits(otherChargesText),
                                    tipSubunits = priceToSubunits(tipText),
                                    discountSubunits = priceToSubunits(discountText),
                                    participantIds = effectiveSelected,
                                    payerUserId = payerId.takeIf { it.isNotBlank() },
                                    payerOutsideName = outsidePayerName?.takeIf { it.isNotBlank() },
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
            EvModalScaffold(onDismiss = { scanSource = false }) {
                Text("Scan the bill", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                Text("A restaurant check or store receipt with line items. We'll pull them out for you, several pages read as one bill.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                ScanSourceRow(EvIcons.Image, "Photos") { scanSource = false; onScanReceipt(PickSource.Photos) }
                ScanSourceRow(EvIcons.Archive, "Files (image or PDF)") { scanSource = false; onScanReceipt(PickSource.Files) }
                ScanSourceRow(EvIcons.Camera, "Take a photo") { scanSource = false; onScanReceipt(PickSource.Camera) }
            }
        }

        if (showPicker) {
            ParticipantPickerSheet(
                participants = participants,
                selected = effectiveSelected,
                allCount = allIds.size,
                query = pickerQuery,
                onQuery = { pickerQuery = it },
                onToggle = { id -> selectTo(if (id in effectiveSelected) effectiveSelected - id else effectiveSelected + id) },
                onSelectAll = { selected = allIds },
                onDeselectAll = { selectTo(emptySet()) },
                onAddPerson = { showPicker = false; pickerQuery = ""; showAddDialog = true },
                onDone = { showPicker = false; pickerQuery = "" },
            )
        }

        pendingRemoval?.let { removal ->
            RemoveParticipantSheet(
                removal = removal,
                onConfirm = { selected = removal.next; pendingRemoval = null },
                onCancel = { pendingRemoval = null },
            )
        }

        if (showPayerSheet) {
            BillPayerSheet(
                participants = participants,
                payerUserId = if (outsidePayerName.isNullOrBlank()) payerId else "",
                outsidePayerName = outsidePayerName,
                onPickMember = { id -> payerId = id; outsidePayerName = null },
                onPickOutside = { name -> outsidePayerName = name },
                onAddSomeoneNew = { showAddDialog = true },
                onDismiss = { showPayerSheet = false },
            )
        }

        if (showAddDialog) {
            var newName by remember { mutableStateOf("") }
            EvModalScaffold(onDismiss = { showAddDialog = false }) {
                Text("Add a person", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                Text("Add someone who isn't in the group yet, even if they don't have the app. They'll be on this bill.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                EvField("Name") { EvTextField(newName, { newName = it }, placeholder = "e.g. Bob") }
                Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    EvButton("Add", { if (newName.isNotBlank()) { onAddPerson(newName.trim()); showAddDialog = false } }, enabled = newName.isNotBlank())
                }
            }
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

        // Covers the editor, so it renders last and outside the scrolling Column.
        LaunchedEffect(attachedReceipts.size) {
            if ((viewerIndex ?: -1) >= attachedReceipts.size) viewerIndex = null
        }
        viewerIndex?.takeIf { it in attachedReceipts.indices }?.let { index ->
            StagedReceiptViewer(
                receipts = attachedReceipts,
                initialIndex = index,
                loadPdfPageCount = loadPdfPageCount,
                renderPdfPage = renderPdfPage,
                onClose = { viewerIndex = null },
            )
        }
    }
}

/**
 * The bottom sheet shown while OCR runs: a single centered animated scan icon, an indeterminate bar,
 * and rotating copy. Replaces the old row-of-page-tiles — [pages] is now only used to compute the
 * "page N of M" subtitle text, not rendered per-tile.
 */
@Composable
internal fun ScanProgressSheet(pages: List<ScanPageUi>, onCancel: () -> Unit) {
    val c = EvenlyTheme.colors
    val phrases = remember { listOf("Reading the receipt…", "Finding the items…", "Adding up the totals…", "Almost there…") }
    var phraseIdx by remember { mutableStateOf(0) }
    // Cycle the copy on a timer. It's honest reassurance, not real progress — the round-trip is one call.
    LaunchedEffect(Unit) {
        while (true) { delay(1500); phraseIdx = (phraseIdx + 1) % phrases.size }
    }
    val pageLine = if (pages.size == 1) "1 page · this usually takes a few seconds"
    else "${pages.size} pages · this usually takes a few seconds"
    EvSheetScaffold(onDismiss = onCancel, title = "Scanning your receipt", sub = pageLine) {
        Box(Modifier.fillMaxWidth().padding(bottom = 20.dp), contentAlignment = Alignment.Center) {
            ScanPulseTile()
        }
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(99.dp)),
            color = c.blueText,
            trackColor = c.selectionTint,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvIcon(EvIcons.Sparkle, size = 15.dp, tint = c.blueText)
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

/** A rounded-square tile with a receipt icon and a thin blue "scan line" sweeping top-to-bottom-to-top. */
@Composable
private fun ScanPulseTile() {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val transition = rememberInfiniteTransition(label = "scanLine")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scanLineSweep",
    )
    Box(
        Modifier.size(88.dp).clip(shape).background(c.selectionTint).border(1.dp, c.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        EvIcon(EvIcons.Receipt, size = 34.dp, tint = c.blueText)
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .offset(y = 42.dp * (sweep * 2f - 1f))
                .background(c.blue.copy(alpha = 0.7f)),
        )
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
    val c = EvenlyTheme.colors
    val icon = when (kind) {
        ScanErrorKind.Offline -> EvIcons.WifiOff
        ScanErrorKind.NoReceiptFound -> EvIcons.Receipt
        ScanErrorKind.Unavailable -> EvIcons.Info
        ScanErrorKind.Error -> EvIcons.Alert
        ScanErrorKind.Blocked -> EvIcons.Info
        ScanErrorKind.OutOfScans -> EvIcons.Receipt
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
        ScanErrorKind.Blocked -> "Too many scans"
        ScanErrorKind.OutOfScans -> "Out of free scans"
    }
    val body = when (kind) {
        ScanErrorKind.Offline -> "Scanning needs a connection. You can still type the bill in now."
        ScanErrorKind.NoReceiptFound -> "No items found. The photo may be blurry or not a receipt."
        ScanErrorKind.Unavailable -> "Receipt scanning isn't set up here. Add the bill by hand."
        ScanErrorKind.Error -> "The scan failed. Give it another try, or type it in."
        ScanErrorKind.Blocked -> "You've hit the scan limit for now. Try again in a bit, or type it in."
        // No "buy a pass" action yet (step 5). Until then this says what happened and keeps the free
        // path in front of the user, rather than naming a door that isn't built.
        ScanErrorKind.OutOfScans -> "This group has used its free scans. You can still type the bill in."
    }
    EvSheetScaffold(onDismiss = onManual) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(52.dp).clip(RoundedCornerShape(99.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { EvIcon(icon, size = 24.dp, tint = tint) }
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
                EvButton(text = "Try again", onClick = onRetry)
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    EvButton(text = "Enter manually", onClick = onManual, variant = ButtonVariant.Text)
                }
            }
            // Neither of these offers Retry: scanning is not coming back on this tap, so a Retry button
            // would be a control that cannot do what it says.
            ScanErrorKind.Unavailable, ScanErrorKind.OutOfScans -> {
                EvButton(text = "Enter manually", onClick = onManual)
            }
            else -> {
                EvButton(text = "Enter manually", onClick = onManual)
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    EvButton(
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
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EvIcon(icon, size = 20.dp, tint = c.blueText)
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
    val c = EvenlyTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .then(if (showDivider) Modifier.topHairline(c.border) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EvTextField(item.label, { onChange(item.copy(label = it)) }, placeholder = "Item", modifier = Modifier.weight(1f), minHeight = 44.dp)
            EvIconButton(EvIcons.Trash, onRemove, tint = c.ink3, size = 18.dp)
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
    val c = EvenlyTheme.colors
    // Local text so the middle field can be cleared and retyped smoothly; re-seeds when [value] changes
    // externally (via the −/+ buttons). An empty field doesn't propagate — the quantity holds at its last
    // value until a digit lands, so it never snaps to 1 mid-edit.
    var text by remember(value) { mutableStateOf(value.toString()) }
    val shape = RoundedCornerShape(8.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepBtn(EvIcons.Minus, enabled = value > 1, tint = if (value > 1) c.blue else c.ink3) { onChange((value - 1).coerceAtLeast(1)) }
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
        StepBtn(EvIcons.Plus, enabled = true, tint = c.blueText) { onChange(value + 1) }
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
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // Active (source-of-truth) is signalled by the blue ring + subtle fill + a bolder white label —
        // NOT by colouring the value blue. Blue digits on the dark field read poorly; white stays legible
        // and the border carries the "this is the one I'm editing" cue.
        Text(
            label,
            color = if (active) c.ink else c.ink3,
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
            Text(symbol, color = if (active) c.ink2 else c.ink3, fontSize = 13.sp)
            BasicTextField(
                value = text,
                onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '.' }) },
                singleLine = true,
                textStyle = TextStyle(
                    color = c.ink,
                    fontSize = 14.sp,
                    textAlign = TextAlign.End,
                    fontFamily = EvenlyTheme.monoFamily,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                ),
                cursorBrush = SolidColor(c.blue),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        if (text.isEmpty()) Text("0.00", color = c.ink3, fontSize = 14.sp, fontFamily = EvenlyTheme.monoFamily)
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
    otherChargesText: String, onOtherCharges: (String) -> Unit,
    tipText: String, onTip: (String) -> Unit,
    discountText: String, onDiscount: (String) -> Unit,
) {
    val c = EvenlyTheme.colors
    EvCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExtraRow("Subtotal") {
                Text(moneySubunits(subtotalSubunits, currencyCode), color = c.ink, fontWeight = FontWeight.SemiBold, fontFamily = EvenlyTheme.monoFamily)
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
                    Text(moneySubunits(totalSubunits, currencyCode), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = EvenlyTheme.monoFamily)
                }
            }
        }
    }
}

@Composable
private fun ExtraRow(label: String, hint: String? = null, trailing: @Composable () -> Unit) {
    val c = EvenlyTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = c.ink, fontSize = 14.sp)
            hint?.let { Text(it, color = c.ink3, fontSize = 11.sp) }
        }
        trailing()
    }
}


/**
 * A compact right-aligned price input ("$ 0.00") used for item prices and extras. [sign] is an optional
 * leading glyph (e.g. "−" for the discount) that flags the amount as a deduction without recoloring it.
 */
@Composable
// [accent]/[accentFill] tint the whole field (border, sign, symbol, value) one colour — used to paint
// the discount GREEN, so the "−" and the box read as "this reduces the total" while you type.
private fun PriceField(
    text: String,
    onChange: (String) -> Unit,
    symbol: String,
    sign: String = "",
    accent: Color? = null,
    accentFill: Color? = null,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.width(96.dp).clip(shape).background(accentFill ?: c.page).border(1.dp, accent ?: c.borderStrong, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (sign.isNotEmpty()) Text(sign, color = accent ?: c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(symbol, color = accent ?: c.ink3, fontSize = 13.sp)
        BasicTextField(
            value = text,
            onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '.' }) },
            singleLine = true,
            textStyle = TextStyle(color = accent ?: c.ink, fontSize = 14.sp, textAlign = TextAlign.End, fontFamily = EvenlyTheme.monoFamily),
            cursorBrush = SolidColor(accent ?: c.blue),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (text.isEmpty()) Text("0.00", color = c.ink3, fontSize = 14.sp, fontFamily = EvenlyTheme.monoFamily)
                    inner()
                }
            },
        )
    }
}

/** A −/value/+ stepper. [min] clamps the bottom (1 for item quantity, 0 for claim counters). */
@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, min: Int = 0, modifier: Modifier = Modifier) {
    val c = EvenlyTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StepBtn(EvIcons.Minus, enabled = value > min, tint = if (value > min) c.blue else c.ink3) { onChange((value - 1).coerceAtLeast(min)) }
        Text("$value", color = if (value > 0) c.ink else c.ink3, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(16.dp), textAlign = TextAlign.Center)
        StepBtn(EvIcons.Plus, enabled = true, tint = c.blueText) { onChange(value + 1) }
    }
}

@Composable
private fun StepBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(30.dp).clip(shape).border(1.dp, c.borderStrong, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { EvIcon(icon, size = 15.dp, tint = tint) }
}

