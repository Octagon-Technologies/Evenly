package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.expense.CategoryDefaults
import app.splitevenly.domain.expense.GroupCategory
import app.splitevenly.platform.PickSource
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvSelectField
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.currencySymbol
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.screen.bill.EditBillState
import app.splitevenly.ui.screen.bill.EditBillSubmit
import app.splitevenly.ui.screen.bill.ItemizedExpenseBody
import app.splitevenly.ui.screen.bill.ScanErrorSheet
import app.splitevenly.ui.screen.bill.ScanProgressSheet
import app.splitevenly.ui.screen.bill.ScanUiState
import app.splitevenly.ui.screen.bill.priceToSubunits
import app.splitevenly.ui.screen.bill.rememberItemizedBillState
import app.splitevenly.ui.screen.group.CategoryCatalog
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.coroutines.launch

/** A participant the expense can be split between (real members are passed by the route). */
data class AddParticipantUi(val userId: String, val name: String, val isMe: Boolean)

/**
 * 13 · Add / edit expense (design/src/screens-addexpense.jsx) — the full split editor. This file owns the
 * shell: the up-front [SplitApproachChooser], the shared header (title, participants, category, payer,
 * receipts), and the picker sheets. The two bodies live next to their own pieces — [DivideSplitBody] with
 * the split math here, [ItemizedExpenseBody] with the bill item editor in `screen/bill/`.
 *
 * Both bodies stay live and honest: [DivideSplitState.owed] (D-28) drives the per-participant preview and
 * is exactly what Save emits, and Save is gated until the split is well-formed (% sums to 100.00, Exact
 * sums to the total — AC-INV-001). The route maps the emitted [AddExpenseSubmit] straight onto `NewExpense`,
 * preserving the raw share inputs.
 */
@Composable
fun AddExpenseScreen(
    editing: Boolean = false,
    participants: List<AddParticipantUi> = DemoParticipants,
    categories: List<GroupCategory> = CategoryDefaults.all,
    currencyCode: String = "USD",
    saving: Boolean = false,
    prefill: AddExpensePrefill? = null,
    // Who was on the group's most recent expense — defaults a brand-new expense's participant selection
    // to "whoever was actually there last time" instead of the whole group. Ignored when [prefill] is set
    // (editing always wins) or when nobody in it is a current participant (a fresh/first expense).
    lastExpenseParticipantIds: Set<String> = emptySet(),
    receipts: List<PickedReceiptUi> = emptyList(),
    receiptsEnabled: Boolean = false,
    // ── itemized ("By what each had") body — only used when creating (editing keeps its single mode) ──
    // The scan pipeline is driven by the route: [scanState] shows progress/errors, [scanned] delivers a
    // completed draft that pre-fills the item list, and the on* callbacks pick/cancel/retry the scan.
    scanState: ScanUiState = ScanUiState.Idle,
    scanned: EditBillState? = null,
    // The pages that were scanned. They are a receipt in their own right, kept whatever the OCR made of
    // them, so they get the same strip (and the same viewer) as a hand-picked one.
    attachedReceipts: List<PickedReceiptUi> = emptyList(),
    onRemoveAttachedReceipt: (Int) -> Unit = {},
    // Local-file PDF rendering for the staged viewer, wired by the route (mirrors ExpenseDetailScreen).
    loadPdfPageCount: suspend (url: String) -> Int = { 0 },
    renderPdfPage: suspend (url: String, page: Int, widthPx: Int) -> ImageBitmap? = { _, _, _ -> null },
    onBack: () -> Unit = {},
    onSave: (AddExpenseSubmit) -> Unit = {},
    onSaveItemized: (EditBillSubmit) -> Unit = {},
    onScanReceipt: (PickSource) -> Unit = {},
    onCancelScan: () -> Unit = {},
    onRetryScan: () -> Unit = {},
    onDismissScan: () -> Unit = {},
    onAddPlaceholder: (String) -> Unit = {},
    onPickReceipt: (PickSource) -> Unit = {},
    onRemoveReceipt: (Int) -> Unit = {},
    // Analytics-only hook (split_approach_chosen) — fired the moment the up-front chooser is answered.
    onSplitApproachChosen: (SplitApproach) -> Unit = {},
) {
    val c = EvenlyTheme.colors
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    // Flips true the first time Save is tapped while incomplete — the gaps then turn red.
    var showErrors by remember { mutableStateOf(false) }
    var showReceiptSource by remember { mutableStateOf(false) }
    // Which staged receipt the full-screen viewer is open on; null = closed.
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    // Currency is editable (F2): seed from the group base, let the user pick a foreign currency.
    var currency by remember { mutableStateOf(currencyCode) }
    val symbol = currencySymbol(currency)
    var showCurrencyDialog by remember { mutableStateOf(false) }
    var categoryId by remember { mutableStateOf(prefill?.categoryId) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(prefill?.title ?: "") }
    // Default selection: editing keeps its saved shares; a new expense defaults to whoever was on the
    // group's last expense (filtered to people still around); with no such signal, everyone's selected.
    var selected by remember {
        mutableStateOf(
            prefill?.selectedUserIds
                ?: lastExpenseParticipantIds.filterTo(HashSet()) { id -> participants.any { it.userId == id } }
                    .takeIf { it.isNotEmpty() }
                ?: participants.map { it.userId }.toSet(),
        )
    }
    var known by remember { mutableStateOf(participants.map { it.userId }.toSet()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var payerId by remember { mutableStateOf(prefill?.payerUserId ?: "") }
    // Non-blank when an outside party paid (no group member). Such a payer is not part of the split.
    var outsidePayerName by remember { mutableStateOf(prefill?.payerOutsideName) }
    var showPayerDialog by remember { mutableStateOf(false) }
    var showScanSource by remember { mutableStateOf(false) }

    // Which body is showing. The choice is an up-front question: null = not yet chosen on a new expense
    // (show the chooser); an edit skips it and stays in its (divide) body.
    var splitApproach by remember { mutableStateOf<SplitApproach?>(if (editing) SplitApproach.Divide else null) }
    // On edit, the divide body seeds every field from the saved expense so it re-renders the exact split.
    val divide = remember { DivideSplitState(prefill) }
    val itemized = rememberItemizedBillState()
    // A completed scan pre-fills the items + extras (never the shared name/participants) and lands you in
    // the itemized body — a fresh [scanned] instance each time, so a re-scan replaces the list.
    LaunchedEffect(scanned) {
        scanned?.let {
            itemized.applyScan(it)
            splitApproach = SplitApproach.ByItem
        }
    }

    val effectivePayerId = participants.firstOrNull { it.userId == payerId }?.userId
        ?: participants.firstOrNull { it.isMe }?.userId
        ?: participants.firstOrNull()?.userId
        ?: ""
    val payer = participants.firstOrNull { it.userId == effectivePayerId }
    val isOutsidePayer = !outsidePayerName.isNullOrBlank()
    val payerDisplayName = if (isOutsidePayer) outsidePayerName!! else (payer?.name ?: "You")
    // Auto-select members that appear after a placeholder is added, without re-selecting ones the user deselected.
    LaunchedEffect(participants) {
        val fresh = participants.map { it.userId }.toSet() - known
        if (fresh.isNotEmpty()) { selected = selected + fresh; known = known + fresh }
    }

    val selectedList = participants.filter { it.userId in selected }
    val ids = selectedList.map { it.userId }
    LaunchedEffect(divide.splitLabel, selected) { divide.seed(ids, selected) }

    val isItemized = splitApproach == SplitApproach.ByItem
    // A restaurant bill is always "Food & Drink" — no point asking, so the Category field is hidden
    // for this path and the category is set for the user.
    LaunchedEffect(isItemized) { if (isItemized) categoryId = "food" }

    val divideValid = divide.amountSubunits > 0 && title.isNotBlank() && selected.isNotEmpty() && divide.splitValid(ids)
    val itemizedValid = title.isNotBlank() && itemized.hasItem
    val isValid = if (isItemized) itemizedValid else divideValid

    // The Save action, shared by the top bar and the button under the totals (A4). Stays live so we can
    // validate on tap and reveal the gaps instead of leaving a dead, greyed button.
    val submit = fun() {
        if (!isValid) {
            showErrors = true
            scope.launch { scrollState.animateScrollTo(0) }
            return
        }
        if (isItemized) {
            onSaveItemized(
                EditBillSubmit(
                    title = title.trim(),
                    items = itemized.namedItems(),
                    taxSubunits = priceToSubunits(itemized.taxText),
                    gratuitySubunits = priceToSubunits(itemized.gratuityText),
                    otherChargesSubunits = priceToSubunits(itemized.otherChargesText),
                    tipSubunits = priceToSubunits(itemized.tipText),
                    discountSubunits = priceToSubunits(itemized.discountText),
                    participantIds = selected,
                    payerUserId = if (isOutsidePayer) null else effectivePayerId,
                ),
            )
        } else onSave(
            AddExpenseSubmit(
                amountSubunits = divide.amountSubunits,
                title = title.trim(),
                payerUserId = if (isOutsidePayer) "" else effectivePayerId,
                payerOutsideName = if (isOutsidePayer) outsidePayerName else null,
                mode = divide.mode,
                currency = currency,
                categoryId = categoryId,
                shares = divide.shares(ids),
            ),
        )
    }

    // Up-front split-type question: a focused editor beats a toggle you can flip by accident. Until it's
    // answered on a new expense, show the chooser; Back from the editor returns here (see navIcon below).
    if (splitApproach == null) {
        Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
            EvTopBar(title = "New expense", navIcon = { EvIconButton(EvIcons.Close, onBack) })
            SplitApproachChooser(onChoose = { splitApproach = it; onSplitApproachChosen(it) })
        }
        return
    }

    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(
            title = if (editing) "Edit expense" else if (isItemized) "Restaurant bill" else "Split one amount",
            navIcon = { EvIconButton(if (editing) EvIcons.Close else EvIcons.Back, { if (editing) onBack() else splitApproach = null }) },
            actions = {
                // Button stays live; validate on tap and reveal the gaps rather than sitting dead + greyed.
                val active = !saving
                val bg = if (active) c.blue else c.blueTint2
                val fg = if (active) c.onAccent else c.disabledInk
                Box(
                    Modifier.clip(RoundedCornerShape(11.dp)).background(bg)
                        .then(if (active) Modifier.clickable { submit() } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(if (saving) "Saving…" else "Save", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            },
        )
        Column(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
                .verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── shared header: title, category, paid by, participants — entered once, both modes ──
            EvField("Title") {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    EvTextField(title, { title = it }, placeholder = "What was it for?", isError = showErrors && title.isBlank())
                    if (showErrors && title.isBlank()) {
                        Text("Give it a title", color = c.danger, fontSize = 12.sp)
                    }
                }
            }

            // Who's splitting this — asked FIRST, before "Paid by": pick the people, THEN the payer. Testers
            // reached for the payer before adding anyone, so the payer picker misread as "who's in the split".
            ParticipantsField(
                participants = participants,
                selected = selected,
                onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                onSelectAll = { selected = participants.map { it.userId }.toSet() },
                onDeselectAll = { selected = emptySet() },
                onAddClick = { showAddDialog = true },
            )

            // category (F2, optional) + paid by — side by side to keep the editor compact. Each is still
            // its own tappable picker row (like before), just half-width now. A restaurant bill skips
            // Category entirely (auto-set to Food & Drink above), so Paid by gets the full row to itself.
            val selectedCategory = categories.firstOrNull { it.key == categoryId }
            val paidByField: @Composable RowScope.() -> Unit = {
                EvField("Paid by", modifier = Modifier.weight(1f)) {
                    EvSelectField(
                        payerDisplayName,
                        { showPayerDialog = true },
                        leading = {
                            if (isOutsidePayer) {
                                Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                                    EvIcon(EvIcons.User, size = 15.dp, tint = c.blueText)
                                }
                            } else {
                                EvAvatar(payerDisplayName, me = payer?.isMe == true, size = AvatarSize.Sm)
                            }
                        },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isItemized) {
                    EvField("Category", modifier = Modifier.weight(1f)) {
                        EvSelectField(
                            selectedCategory?.label ?: "Add category",
                            { showCategoryDialog = true },
                            valueColor = if (selectedCategory != null) c.ink else c.ink3,
                            leading = {
                                if (selectedCategory != null) {
                                    val catColor = Color(selectedCategory.colorHex)
                                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(catColor.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                                        EvIcon(CategoryCatalog.icon(selectedCategory.iconToken), size = 15.dp, tint = catColor)
                                    }
                                } else {
                                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                                        EvIcon(EvIcons.Tag, size = 15.dp, tint = c.blueText)
                                    }
                                }
                            },
                        )
                    }
                }
                paidByField()
            }

            // receipt — compressed and on disk the moment it is picked, so it previews here and uploads
            // once the expense exists. Only in the divide flow; the itemized body shows the scanned pages.
            if (receiptsEnabled && !isItemized) {
                PickedReceiptStrip(
                    receipts = receipts,
                    label = "Receipt",
                    caption = "Uploads when you save.",
                    onAddClick = { showReceiptSource = true },
                    onRemoveReceipt = onRemoveReceipt,
                    onOpenReceipt = { viewerIndex = it },
                )
            }

            // ── the split body (the divide-vs-itemize choice was made up front, so no in-editor toggle) ──
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isItemized) {
                    ItemizedExpenseBody(
                        state = itemized,
                        symbol = symbol,
                        currencyCode = currency,
                        attachedReceipts = attachedReceipts,
                        onRemoveAttachedReceipt = onRemoveAttachedReceipt,
                        onOpenAttachedReceipt = { viewerIndex = it },
                        showErrors = showErrors,
                        saving = saving,
                        saveLabel = "Save & assign items",
                        onScanClick = { showScanSource = true },
                        onSave = { submit() },
                    )
                } else {
                    DivideSplitBody(
                        state = divide,
                        selectedList = selectedList,
                        currency = currency,
                        symbol = symbol,
                        showErrors = showErrors,
                        saving = saving,
                        onCurrencyClick = { showCurrencyDialog = true },
                        onSave = { submit() },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddParticipantSheet(onAdd = onAddPlaceholder, onDismiss = { showAddDialog = false })
    }

    if (showReceiptSource) {
        ReceiptSourceSheet(onPick = onPickReceipt, onDismiss = { showReceiptSource = false })
    }

    if (showCurrencyDialog) {
        CurrencySheet(currency = currency, onPick = { currency = it }, onDismiss = { showCurrencyDialog = false })
    }

    if (showCategoryDialog) {
        CategorySheet(
            categories = categories,
            categoryId = categoryId,
            onPick = { categoryId = it },
            onDismiss = { showCategoryDialog = false },
        )
    }

    if (showPayerDialog) {
        PayerSheet(
            participants = participants,
            effectivePayerId = effectivePayerId,
            isOutsidePayer = isOutsidePayer,
            outsidePayerName = outsidePayerName,
            onPickMember = { id ->
                payerId = id
                outsidePayerName = null
                // A member who paid is part of the split by default — add them (still removable below).
                selected = selected + id
                known = known + id
            },
            onPickOutside = { name ->
                outsidePayerName = name
                payerId = ""
            },
            onAddSomeoneNew = { showAddDialog = true },
            onDismiss = { showPayerDialog = false },
        )
    }

    // The staged viewer covers the editor, so it renders last and outside the scrolling Column. Which list
    // it shows follows the body in view: the divide flow's attachment or the itemized flow's scanned pages.
    val viewable = if (isItemized) attachedReceipts else receipts
    // Close it if the receipt it was showing got removed underneath it.
    LaunchedEffect(viewable.size) {
        if ((viewerIndex ?: -1) >= viewable.size) viewerIndex = null
    }
    viewerIndex?.takeIf { it in viewable.indices }?.let { index ->
        StagedReceiptViewer(
            receipts = viewable,
            initialIndex = index,
            loadPdfPageCount = loadPdfPageCount,
            renderPdfPage = renderPdfPage,
            onClose = { viewerIndex = null },
        )
    }

    // ── itemized scan sheets: source picker + progress/error, driven by the route's scan state ──
    if (showScanSource) {
        ScanSourceSheet(onScan = onScanReceipt, onDismiss = { showScanSource = false })
    }
    when (val s = scanState) {
        is ScanUiState.Working -> ScanProgressSheet(pages = s.pages, onCancel = onCancelScan)
        is ScanUiState.Failed -> ScanErrorSheet(
            kind = s.kind,
            onManual = onDismissScan,
            onRetry = onRetryScan,
            onPickAgain = { onDismissScan(); showScanSource = true },
        )
        ScanUiState.Idle -> {}
    }
}

private val DemoParticipants = listOf(
    AddParticipantUi("u1", "You", true),
    AddParticipantUi("u2", "Andrew", false),
    AddParticipantUi("u3", "Bob", false),
    AddParticipantUi("u4", "Maya", false),
)

@Preview
@Composable
private fun AddExpensePreview() {
    EvenlyTheme { AddExpenseScreen() }
}
