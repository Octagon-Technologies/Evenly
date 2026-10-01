package app.splitevenly.ui.screen.bill

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
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
import app.splitevenly.domain.expense.perUnitSubunits
import app.splitevenly.platform.PickSource
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.currencySymbol
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.screen.expense.PickedReceiptStrip
import app.splitevenly.ui.screen.expense.PickedReceiptUi
import app.splitevenly.ui.screen.expense.StagedReceiptViewer
import app.splitevenly.ui.screen.expense.format2dp
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
fun editBillItemUi(
    id: String?,
    label: String,
    quantity: Int,
    lineTotalSubunits: Long,
): EditBillItemUi =
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
data class ParticipantChipUi(
    val userId: String,
    val name: String,
    val isMe: Boolean,
)

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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BillEditScreen(
    editing: Boolean,
    initial: EditBillState?,
    scanned: EditBillState? = null,
    currencyCode: String = "USD",
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
    // Writes a snapshot and returns the saved line ids in the order of [EditBillSubmit.items], or null when
    // the write failed. The ids let the editor update the same lines on the next auto-save instead of
    // creating them again.
    onSave: suspend (EditBillSubmit) -> List<String>? = { null },
) {
    val c = EvenlyTheme.colors
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val scanning = scanState is ScanUiState.Working
    var scanSource by remember { mutableStateOf(false) }
    fun startScan(source: PickSource) = onScanReceipt(source)
    // Which scanned page the full-screen viewer is open on; null = closed.
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
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
        if (prev == null) {
            memberBaseline = ids
            return@LaunchedEffect
        }
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
    // Nothing typed or scanned yet: the Scan card leads the items section.
    val noItemsYet = items.all { it.isUntouched() }
    val subtotal = items.sumOf { priceToSubunits(it.totalText) } // each line's total is the truth
    val total =
        subtotal + priceToSubunits(taxText) + priceToSubunits(gratuityText) +
            priceToSubunits(otherChargesText) +
            priceToSubunits(tipText) - priceToSubunits(discountText)

    // ── Auto-save ───────────────────────────────────────────────────────────────────────────────────
    // Last saved version of every line by id, so a half-retyped saved line is written as it was rather than
    // dropped (dropping it would tombstone the item and its claims).
    var lastSavedById by remember { mutableStateOf<Map<String, EditBillItemUi>>(initial?.items?.mapNotNull { r -> r.id?.let { it to r } }.orEmpty().toMap()) }
    val rows = submitRows(items, lastSavedById)
    val submit =
        EditBillSubmit(
            title = title.trim(),
            items = rows.map { it.second },
            taxSubunits = priceToSubunits(taxText),
            gratuitySubunits = priceToSubunits(gratuityText),
            otherChargesSubunits = priceToSubunits(otherChargesText),
            tipSubunits = priceToSubunits(tipText),
            discountSubunits = priceToSubunits(discountText),
            participantIds = effectiveSelected,
            payerUserId = payerId.takeIf { it.isNotBlank() },
            payerOutsideName = outsidePayerName?.takeIf { it.isNotBlank() },
        )
    // The last snapshot written. For an existing bill it is captured once members have loaded, so opening
    // the editor does not count as a change.
    var lastSubmitted by remember { mutableStateOf<EditBillSubmit?>(null) }
    val saveLock = remember { Mutex() }
    // Bumped on each successful save; drives the "Changes saved" card.
    var savedSignal by remember { mutableStateOf(0) }
    var savedPinned by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var leaveNoticeText by remember { mutableStateOf<String?>(null) }

    /** Writes [snap] if it is savable and different from what is stored. Serialized so ids never race. */
    suspend fun persist(snap: EditBillSubmit, ids: List<Int>) {
        saveLock.withLock {
            if (snap == lastSubmitted || snap.title.isEmpty() || snap.items.isEmpty()) return
            val saved = onSave(snap) ?: return
            val byIndex = ids.zip(saved).toMap()
            // Hand the new ids to the rows they belong to, but only if the row is still the same line.
            items = items.mapIndexed { i, row ->
                val id = byIndex[i]
                val sent = snap.items.getOrNull(ids.indexOf(i))
                if (id != null && row.id == null && sent != null && row.label == sent.label) row.copy(id = id) else row
            }
            lastSavedById = lastSavedById + snap.items.mapIndexedNotNull { k, r -> saved.getOrNull(k)?.let { it to r.copy(id = it)  } }
            lastSubmitted = snap.copy(items = snap.items.mapIndexed { k, r -> r.copy(id = saved.getOrNull(k) ?: r.id) })
            savedPinned = scrollState.value > 0
            savedSignal++
        }
    }

    LaunchedEffect(submit, title, participants.isNotEmpty()) {
        if (lastSubmitted == null && initial != null) {
            // Existing bill: the first stable snapshot is the baseline, not a change.
            if (participants.isNotEmpty() || initialSelectedIds.isNotEmpty()) lastSubmitted = submit
            return@LaunchedEffect
        }
        if (submit == lastSubmitted || !canAutoSave(title, items)) return@LaunchedEffect
        delay(AUTO_SAVE_DEBOUNCE_MS)
        val snap = submit
        val idx = rows.map { it.first }
        scope.launch { persist(snap, idx) }
    }

    // Show the card for each save: expand, hold, collapse.
    LaunchedEffect(savedSignal) {
        if (savedSignal == 0) return@LaunchedEffect
        showSaved = true
        delay(SAVED_CARD_HOLD_MS)
        showSaved = false
    }

    /** Back: flush anything pending, then leave. Warns first when something would be lost silently. */
    fun leave(force: Boolean) {
        val notice = leaveNotice(title, items)
        if (notice != null && !force) {
            leaveNoticeText = notice
            return
        }
        val snap = submit
        val idx = rows.map { it.first }
        scope.launch {
            persist(snap, idx)
            onBack()
        }
    }
    BackHandler(enabled = true) { leave(force = false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.page)) {
            StatusBarScrim()
            EvTopBar(
                title = if (editing) "Edit bill" else "Split the bill",
                navIcon = { EvIconButton(EvIcons.Back, { leave(force = false) }) },
            )
            // At the top of the page the card is in flow and pushes the content down as it expands.
            AnimatedVisibility(
                visible = showSaved && !savedPinned,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) { SavedCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
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
                        EvTextField(title, { title = it }, placeholder = "Dinner at Tavolo", isError = leaveNoticeText != null && title.isBlank())
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
                        // Once the bill has items the card below is gone, so Scan stays reachable as a labelled entry.
                        if (!noItemsYet) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(99.dp))
                                    .border(1.dp, c.blueText, RoundedCornerShape(99.dp))
                                    .clickable(enabled = !scanning) { scanSource = true }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                EvIcon(EvIcons.Camera, size = 14.dp, tint = c.blueText)
                                Text(
                                    if (scanning) "Scanning..." else "Scan",
                                    color = c.blueText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Box(Modifier.width(8.dp))
                        }
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    items = items + editBillItemUi(null, "", 1, 0L)
                                }.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            EvIcon(EvIcons.Plus, size = 15.dp, tint = c.blueText)
                            Text("Add item", color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (noItemsYet) {
                        ScanReceiptCard(scanning = scanning, onClick = { scanSource = true })
                    }
                    EvCard {
                        items.forEachIndexed { index, item ->
                            ItemEditorRow(
                                item = item,
                                symbol = symbol,
                                showDivider = index > 0,
                                hint = if (item.isComplete() || item.isUntouched()) null else if (item.label.isBlank()) "This item needs a name" else "Add a price",
                                hintIsError = leaveNoticeText != null,
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
                    taxText = taxText,
                    onTax = { taxText = it },
                    gratuityText = gratuityText,
                    onGratuity = { gratuityText = it },
                    otherChargesText = otherChargesText,
                    onOtherCharges = { otherChargesText = it },
                    tipText = tipText,
                    onTip = { tipText = it },
                    discountText = discountText,
                    onDiscount = { discountText = it },
                )

                // Back saves, so there is no Save button. A row Back would otherwise lose is called out here.
                leaveNoticeText?.let { text ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.warningTint).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text, color = c.ink, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text(
                                "Keep editing",
                                color = c.blueText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { leaveNoticeText = null },
                            )
                            Text(
                                "Leave anyway",
                                color = c.ink2,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { leave(force = true) },
                            )
                        }
                    }
                }
            }
        }

        // Scrolled away from the top: the card pins to the viewport instead of pushing, so it is never missed.
        AnimatedVisibility(
            visible = showSaved && savedPinned,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 112.dp),
        ) { SavedCard(Modifier.padding(horizontal = 16.dp)) }

        if (scanSource) {
            EvModalScaffold(onDismiss = { scanSource = false }) {
                Text(
                    "Scan the bill",
                    color = c.ink,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    "A restaurant check or store receipt with line items. We'll pull them out for you, several pages read as one bill.",
                    color = c.ink2,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                // Gated here as well as on the top-bar button: the failed-scan sheet's "try a new photo"
                // reopens this directly, and a group can run out between the failure and the retry.
                ScanSourceRow(EvIcons.Image, "Photos") {
                    scanSource = false
                    startScan(PickSource.Photos)
                }
                ScanSourceRow(EvIcons.Archive, "Files (image or PDF)") {
                    scanSource = false
                    startScan(PickSource.Files)
                }
                ScanSourceRow(EvIcons.Camera, "Take a photo") {
                    scanSource = false
                    startScan(PickSource.Camera)
                }
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
                onAddPerson = {
                    showPicker = false
                    pickerQuery = ""
                    showAddDialog = true
                },
                onDone = {
                    showPicker = false
                    pickerQuery = ""
                },
            )
        }

        pendingRemoval?.let { removal ->
            RemoveParticipantSheet(
                removal = removal,
                onConfirm = {
                    selected = removal.next
                    pendingRemoval = null
                },
                onCancel = { pendingRemoval = null },
            )
        }

        if (showPayerSheet) {
            BillPayerSheet(
                participants = participants,
                payerUserId = if (outsidePayerName.isNullOrBlank()) payerId else "",
                outsidePayerName = outsidePayerName,
                onPickMember = { id ->
                    payerId = id
                    outsidePayerName = null
                },
                onPickOutside = { name -> outsidePayerName = name },
                onAddSomeoneNew = { showAddDialog = true },
                onDismiss = { showPayerSheet = false },
            )
        }

        if (showAddDialog) {
            var newName by remember { mutableStateOf("") }
            EvModalScaffold(onDismiss = { showAddDialog = false }) {
                Text(
                    "Add a person",
                    color = c.ink,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    "Add someone who isn't in the group yet, even if they don't have the app. They'll be on this bill.",
                    color = c.ink2,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                EvField("Name") { EvTextField(newName, { newName = it }, placeholder = "e.g. Bob") }
                Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    EvButton("Add", {
                        if (newName.isNotBlank()) {
                            onAddPerson(newName.trim())
                            showAddDialog = false
                        }
                    }, enabled = newName.isNotBlank())
                }
            }
        }

        // The scan round-trip drives its own sheet: progress while reading, a typed error card otherwise.
        when (val s = scanState) {
            is ScanUiState.Working -> {
                ScanProgressSheet(pages = s.pages, onCancel = onCancelScan)
            }

            is ScanUiState.Failed -> {
                ScanErrorSheet(
                    kind = s.kind,
                    onManual = onDismissScan,
                    onRetry = onRetryScan,
                    onPickAgain = {
                        onDismissScan()
                        scanSource = true
                    },
                )
            }

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

@Composable
internal fun ItemEditorRow(
    item: EditBillItemUi,
    symbol: String,
    showDivider: Boolean,
    onChange: (EditBillItemUi) -> Unit,
    onRemove: () -> Unit,
    hint: String? = null,
    hintIsError: Boolean = false,
) {
    val c = EvenlyTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (showDivider) Modifier.topHairline(c.border) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EvTextField(
                item.label,
                { onChange(item.copy(label = it)) },
                placeholder = "Item",
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
            EvIconButton(EvIcons.Trash, onRemove, tint = c.ink3, size = 18.dp)
        }
        if (hint != null) Text(hint, color = if (hintIsError) c.danger else c.ink3, fontSize = 12.sp)
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
private fun QtyField(
    value: Int,
    onChange: (Int) -> Unit,
) {
    val c = EvenlyTheme.colors
    // Local text so the middle field can be cleared and retyped smoothly; re-seeds when [value] changes
    // externally (via the −/+ buttons). An empty field doesn't propagate — the quantity holds at its last
    // value until a digit lands, so it never snaps to 1 mid-edit.
    var text by remember(value) { mutableStateOf(value.toString()) }
    val shape = RoundedCornerShape(8.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepBtn(EvIcons.Minus, enabled = value > 1, tint = if (value > 1) c.blue else c.ink3) { onChange((value - 1).coerceAtLeast(1)) }
        Box(
            Modifier
                .width(40.dp)
                .height(30.dp)
                .clip(shape)
                .border(1.dp, c.borderStrong, shape),
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
            Modifier
                .fillMaxWidth()
                .clip(shape)
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
                textStyle =
                    TextStyle(
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
        Modifier
            .width(96.dp)
            .clip(shape)
            .background(accentFill ?: c.page)
            .border(1.dp, accent ?: c.borderStrong, shape)
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
            textStyle =
                TextStyle(
                    color = accent ?: c.ink,
                    fontSize = 14.sp,
                    textAlign = TextAlign.End,
                    fontFamily = EvenlyTheme.monoFamily,
                ),
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
fun Stepper(
    value: Int,
    onChange: (Int) -> Unit,
    min: Int = 0,
    modifier: Modifier = Modifier,
) {
    val c = EvenlyTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StepBtn(
            EvIcons.Minus,
            enabled = value > min,
            tint =
                if (value >
                    min
                ) {
                    c.blue
                } else {
                    c.ink3
                },
        ) { onChange((value - 1).coerceAtLeast(min)) }
        Text(
            "$value",
            color =
                if (value >
                    0
                ) {
                    c.ink
                } else {
                    c.ink3
                },
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(16.dp),
            textAlign = TextAlign.Center,
        )
        StepBtn(EvIcons.Plus, enabled = true, tint = c.blueText) { onChange(value + 1) }
    }
}

@Composable
private fun StepBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(30.dp)
            .clip(shape)
            .border(1.dp, c.borderStrong, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { EvIcon(icon, size = 15.dp, tint = tint) }
}

private const val AUTO_SAVE_DEBOUNCE_MS = 700L
private const val SAVED_CARD_HOLD_MS = 2500L

/** The small centered "Changes saved..." card. Muted so it confirms without competing with the form. */
@Composable
private fun SavedCard(modifier: Modifier = Modifier) {
    val c = EvenlyTheme.colors
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(vertical = 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Changes saved...", color = c.ink2, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

/** The empty-bill entry point to scanning, above the manual rows so it is the first thing offered. */
@Composable
private fun ScanReceiptCard(
    scanning: Boolean,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.selectionTint)
            .border(1.dp, c.selectionStroke, shape)
            .clickable(enabled = !scanning, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.blue), contentAlignment = Alignment.Center) {
            EvIcon(EvIcons.Camera, size = 20.dp, tint = c.onAccent)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (scanning) "Scanning..." else "Scan a receipt",
                color = c.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text("Snap a photo or PDF and we'll fill in the items.", color = c.ink2, fontSize = 12.sp)
        }
    }
}
