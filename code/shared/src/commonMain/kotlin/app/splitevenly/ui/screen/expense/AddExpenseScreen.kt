package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.core.time.localToday
import app.splitevenly.domain.expense.CategoryDefaults
import app.splitevenly.domain.expense.CategoryHistoryEntry
import app.splitevenly.domain.expense.GroupCategory
import app.splitevenly.domain.expense.guessCategory
import app.splitevenly.domain.expense.trustedReceiptDate
import app.splitevenly.platform.PickSource
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvKeyboardDoneBar
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.currencySymbol
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.imeAndDoneBarPadding
import app.splitevenly.ui.screen.bill.BillNext
import app.splitevenly.ui.screen.bill.EditBillState
import app.splitevenly.ui.screen.bill.EditBillSubmit
import app.splitevenly.ui.screen.bill.ItemizedExpenseBody
import app.splitevenly.ui.screen.bill.PickerPersonUi
import app.splitevenly.ui.screen.bill.ReceiptReviewScreen
import app.splitevenly.ui.screen.bill.ScanReceiptHero
import app.splitevenly.ui.screen.bill.ScanStateSheets
import app.splitevenly.ui.screen.bill.ScanUiState
import app.splitevenly.ui.screen.bill.isUntouched
import app.splitevenly.ui.screen.bill.priceToSubunits
import app.splitevenly.ui.screen.bill.rememberItemizedBillState
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** A participant the expense can be split between (real members are passed by the route). */
data class AddParticipantUi(
    val userId: String,
    val name: String,
    val isMe: Boolean,
)

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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AddExpenseScreen(
    editing: Boolean = false,
    participants: List<AddParticipantUi> = DemoParticipants,
    categories: List<GroupCategory> = CategoryDefaults.all,
    currencyCode: String = "USD",
    saving: Boolean = false,
    /**
     * Set when a save the editor could not have predicted came back refused, so the tap says something
     * instead of just un-sticking the Save button. The one case today is re-denominating an expense that
     * already carries a recorded payment (R1) — the editor cannot gate the currency chip on it, because
     * whether anyone has paid is a fact only the repository holds.
     */
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
    prefill: AddExpensePrefill? = null,
    // Who was on the group's most recent expense — defaults a brand-new expense's participant selection
    // to "whoever was actually there last time" instead of the whole group. Ignored when [prefill] is set
    // (editing always wins) or when nobody in it is a current participant (a fresh/first expense).
    lastExpenseParticipantIds: Set<String> = emptySet(),
    receipts: List<PickedReceiptUi> = emptyList(),
    receiptsEnabled: Boolean = false,
    receiptRead: ReceiptReadUi = ReceiptReadUi.None, // fills only empty fields; see [receiptFill]
    categoryHistory: List<CategoryHistoryEntry> = emptyList(),
    // ── itemized ("By what each had") body — only used when creating (editing keeps its single mode) ──
    // The scan pipeline is driven by the route: [scanState] shows progress/errors, [scanned] delivers a
    // completed draft that pre-fills the item list, and the on* callbacks pick/cancel/retry the scan.
    scanState: ScanUiState = ScanUiState.Idle,
    scanned: EditBillState? = null,
    // The pages that were scanned. They are a receipt in their own right, kept whatever the OCR made of
    // them, so they get the same strip (and the same viewer) as a hand-picked one.
    attachedReceipts: List<PickedReceiptUi> = emptyList(),
    onRemoveAttachedReceipt: (Int) -> UndoRemoval = { {} },
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
    onRemoveReceipt: (Int) -> UndoRemoval = { {} },
    // Runs the full itemized scan on the receipt already attached here (the restaurant prompt's "By item").
    onItemizeReceipt: () -> Unit = {},
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
    // Once someone picks a category (or an edit already has one), the guess never touches it again.
    var categoryPicked by remember { mutableStateOf(prefill?.categoryId != null) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(prefill?.title ?: "") }
    val today = remember { Clock.System.localToday() }
    var expenseDate by remember { mutableStateOf(prefill?.expenseDate ?: today) }
    // A receipt's date fills in only until someone picks a date by hand (an edit counts as picked).
    var datePicked by remember { mutableStateOf(prefill?.expenseDate != null) }
    var showDateSheet by remember { mutableStateOf(false) }
    // Default selection: editing keeps its saved shares; a new expense defaults to whoever was on the
    // group's last expense (filtered to people still around); with no such signal, everyone's selected.
    var selected by remember {
        mutableStateOf(
            prefill?.selectedUserIds
                ?: lastExpenseParticipantIds
                    .filterTo(HashSet()) { id -> participants.any { it.userId == id } }
                    .takeIf { it.isNotEmpty() }
                ?: participants.map { it.userId }.toSet(),
        )
    }
    var known by remember { mutableStateOf(participants.map { it.userId }.toSet()) }
    var showAddDialog by remember { mutableStateOf(false) }
    // Set when "Add someone new" came from the payer picker: the person is created async, so the payer is
    // claimed once their placeholder member shows up in [participants].
    var pendingPayerName by remember { mutableStateOf<String?>(null) }
    var addingPayer by remember { mutableStateOf(false) }
    var payerId by remember { mutableStateOf(prefill?.payerUserId ?: "") }
    var showPayerDialog by remember { mutableStateOf(false) }
    var showScanSource by remember { mutableStateOf(false) }
    var showPeoplePicker by remember { mutableStateOf(false) }

    fun startScan(source: PickSource) = onScanReceipt(source)

    // Which body is showing. The choice is an up-front question: null = not yet chosen on a new expense
    // (show the chooser); an edit skips it and stays in its (divide) body.
    var splitApproach by remember { mutableStateOf<SplitApproach?>(if (editing) SplitApproach.Divide else null) }
    // On edit, the divide body seeds every field from the saved expense so it re-renders the exact split.
    val divide = remember { DivideSplitState(prefill) }
    val itemized = rememberItemizedBillState()
    // A completed scan pre-fills the items + extras (never the shared name/participants) and lands you in
    // the itemized body — a fresh [scanned] instance each time, so a re-scan replaces the list.
    // A fresh scan also opens "Review the receipt" over the editor: OCR is checked before anything is saved.
    var reviewingScan by remember { mutableStateOf(false) }
    LaunchedEffect(scanned) {
        scanned?.let {
            // The scan's suggested name ("Dinner at Tavolo") fills an empty Title, never a typed one.
            if (title.isBlank() && it.title.isNotBlank()) title = it.title
            if (!datePicked) it.receiptDate?.let { d -> expenseDate = d }
            itemized.applyScan(it)
            splitApproach = SplitApproach.ByItem
            reviewingScan = true
        }
    }

    val effectivePayerId =
        participants.firstOrNull { it.userId == payerId }?.userId
            ?: participants.firstOrNull { it.isMe }?.userId
            ?: participants.firstOrNull()?.userId
            ?: ""
    val payer = participants.firstOrNull { it.userId == effectivePayerId }
    val payerDisplayName = payer?.name ?: "You"
    // Auto-select members that appear after a placeholder is added, without re-selecting ones the user deselected.
    LaunchedEffect(participants) {
        val fresh = participants.map { it.userId }.toSet() - known
        if (fresh.isNotEmpty()) {
            selected = selected + fresh
            known = known + fresh
            pendingPayerName?.let { name ->
                participants.firstOrNull { it.userId in fresh && it.name.equals(name, ignoreCase = true) }?.let {
                    payerId = it.userId
                    pendingPayerName = null
                }
            }
        }
    }

    val selectedList = participants.filter { it.userId in selected }
    val ids = selectedList.map { it.userId }
    LaunchedEffect(divide.splitLabel, selected) { divide.seed(ids, selected) }

    val isItemized = splitApproach == SplitApproach.ByItem
    // A restaurant bill is always "Food & Drink" — no point asking, so the Category field is hidden
    // for this path and the category is set for the user.
    LaunchedEffect(isItemized, scanned) { if (isItemized && !categoryPicked) categoryId = scanned?.categoryKey ?: "food" }

    // A finished quick read fills what is still empty. Keyed on the read itself, so it fills once.
    LaunchedEffect(receiptRead) {
        val read = receiptRead as? ReceiptReadUi.Read ?: return@LaunchedEffect
        val fill = receiptFill(read.summary, read.suggestedTitle, title, divide.amountSubunits)
        fill.title?.let { title = it }
        fill.amountText?.let { divide.amountText = it }
        fill.currency?.let { currency = it }
        if (!datePicked) trustedReceiptDate(read.summary.printedDate, today)?.let { expenseDate = it }
    }
    // The category picks itself from the title (and the receipt read) until someone chooses one.
    val receiptCategory = (receiptRead as? ReceiptReadUi.Read)?.summary?.categoryKey
    LaunchedEffect(title, receiptCategory, categories, isItemized) {
        if (!categoryPicked && !isItemized) categoryId = guessCategory(title, categoryHistory, receiptCategory, categories)
    }

    val divideValid = divide.amountSubunits > 0 && title.isNotBlank() && selected.isNotEmpty() && divide.splitValid(ids)
    val itemizedValid = title.isNotBlank() && selected.isNotEmpty() && itemized.hasItem
    val isValid = if (isItemized) itemizedValid else divideValid

    // The Save action, shared by the top bar and the button under the totals (A4). Stays live so we can
    // validate on tap and reveal the gaps instead of leaving a dead, greyed button.
    val submit = fun(next: BillNext) {
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
                    payerUserId = effectivePayerId,
                    expenseDate = expenseDate,
                    next = next,
                ),
            )
        } else {
            onSave(
                AddExpenseSubmit(
                    amountSubunits = divide.amountSubunits,
                    title = title.trim(),
                    payerUserId = effectivePayerId,
                    mode = divide.mode,
                    currency = currency,
                    categoryId = categoryId,
                    shares = divide.shares(ids),
                    expenseDate = expenseDate,
                ),
            )
        }
    }

    // What the person has entered, minus what the editor fills in for itself (a guessed category, a default
    // date), so leaving can ask before throwing it away. Saved receipts are left out: removing one is
    // committed on its own, not on Save. The first composition's value is the baseline.
    val entered =
        listOf(
            title.trim(), expenseDate.takeIf { datePicked }, selected, effectivePayerId,
            categoryId.takeIf { categoryPicked }, currency, divide.amountSubunits, divide.mode,
            if (divide.amountSubunits > 0) divide.shares(ids) else null,
            receipts.filter { it.remoteUrl == null }.map { it.id }, attachedReceipts.map { it.id },
            itemized.items.map { it.label to it.totalText },
            listOf(itemized.taxText, itemized.gratuityText, itemized.otherChargesText, itemized.tipText, itemized.discountText),
        )
    val baseline = remember { entered }
    val unsaved = entered != baseline && !saving
    var confirmDiscard by remember { mutableStateOf(false) }
    val leave = { if (unsaved) confirmDiscard = true else onBack() }
    // System back and the iOS edge swipe would otherwise drop the route without asking. Each step back
    // mirrors its on-screen arrow; only leaving the editor altogether asks first. Sheets opened later in
    // composition register their own handler, which wins while they are open.
    BackHandler(enabled = unsaved && !confirmDiscard) {
        when {
            isItemized && reviewingScan -> reviewingScan = false
            !editing && splitApproach != null -> splitApproach = null
            else -> confirmDiscard = true
        }
    }
    // Drawn after everything else in each branch (here and at the end), so the card sits on top.
    val discardDialog: @Composable () -> Unit = {
        if (confirmDiscard) DiscardChangesDialog(editing, onDiscard = onBack, onDismiss = { confirmDiscard = false })
    }

    // Up-front split-type question: a focused editor beats a toggle you can flip by accident. Until it's
    // answered on a new expense, show the chooser; Back from the editor returns here (see navIcon below).
    if (splitApproach == null) {
        Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
            EvTopBar(title = "New expense", navIcon = { EvIconButton(EvIcons.Close, leave) })
            SplitApproachChooser(onChoose = {
                splitApproach = it
                onSplitApproachChosen(it)
            })
        }
        discardDialog()
        return
    }

    Box(Modifier.fillMaxSize()) {
        if (isItemized && reviewingScan) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(c.page)
                    .systemBarsPadding()
                    .imeAndDoneBarPadding(),
            ) {
                EditorNotice(notice, onDismissNotice)
                ReceiptReviewScreen(
                    state = itemized,
                    title = title,
                    onTitle = { title = it },
                    date = expenseDate,
                    today = today,
                    onDateClick = { showDateSheet = true },
                    symbol = symbol,
                    currencyCode = currency,
                    attachedReceipts = attachedReceipts,
                    showErrors = showErrors,
                    people = selectedList.map { PickerPersonUi(it.userId, it.name, it.isMe) },
                    payerName = payerDisplayName,
                    payerIsMe = payer?.isMe == true,
                    onEditPeople = { showPeoplePicker = true },
                    onChangePayer = { showPayerDialog = true },
                    saving = saving,
                    onOpenReceipt = { viewerIndex = it },
                    onBack = { reviewingScan = false },
                    onSave = { next -> submit(next) },
                )
            }
        } else {
            Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
                EvTopBar(
                    title = if (editing) "Edit expense" else if (isItemized) "Restaurant bill" else "Split one amount",
                    navIcon = {
                        EvIconButton(
                            if (editing) EvIcons.Close else EvIcons.Back,
                            { if (editing) leave() else splitApproach = null },
                        )
                    },
                    actions = { TopBarSaveButton(saving) { submit(BillNext.Choose) } },
                )
                EditorNotice(notice, onDismissNotice)
                Column(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
                        // Before verticalScroll: shrinking the viewport (rather than padding the content) is
                        // what makes the scroller's bring-into-view land a focused field above the Done bar.
                        .imeAndDoneBarPadding()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // A restaurant bill starts with the scan, above Title: one scan fills the title and the items.
                    // After the first scan (or a typed item) the hero drops back into the items section below.
                    val quickReadReceipt = receiptsEnabled && !isItemized && receipts.size <= 1
                    val notScannedYet = isItemized && itemized.items.all { it.isUntouched() } && attachedReceipts.isEmpty()
                    if (notScannedYet) {
                        ScanReceiptHero(onScanSource = { source -> startScan(source) })
                        ScanPeopleHint(selected.size)
                    }
                    // "Split one amount" leads with the receipt too: one read fills the title, amount and category,
                    // so it comes before the fields it fills. Compressed and staged on pick, uploaded on save.
                    if (quickReadReceipt) {
                        QuickReadReceiptSection(
                            receipt = receipts.firstOrNull(),
                            read = receiptRead,
                            onAdd = { showReceiptSource = true },
                            onRemove = { onRemoveReceipt(0) },
                            onOpen = { viewerIndex = 0 },
                            fillsDetails = !editing,
                        )
                    }

                    // ── shared header: title, category, paid by, participants — entered once, both modes ──
                    TitleWithDateRow(expenseDate, today, onDateClick = { showDateSheet = true }) {
                        EvTextField(title, { title = it }, placeholder = "What was it for?", isError = showErrors && title.isBlank())
                    }
                    if (showErrors && title.isBlank()) Text("Give it a title", color = c.danger, fontSize = 12.sp)
                    // The scanned pages sit under the bill's title, the thing they are the receipt for.
                    if (isItemized && attachedReceipts.isNotEmpty()) {
                        PickedReceiptStrip(
                            receipts = attachedReceipts,
                            label = if (attachedReceipts.size == 1) "Receipt" else "Receipt pages",
                            caption = "Saves with the bill.",
                            onAddClick = null,
                            onRemoveReceipt = onRemoveAttachedReceipt,
                            onOpenReceipt = { viewerIndex = it },
                        )
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
                    if (showErrors && selected.isEmpty()) {
                        Text("Pick who is splitting this", color = c.danger, fontSize = 12.sp)
                    }

                    // category (F2, optional) + paid by — side by side to keep the editor compact. Each is still
                    // its own tappable picker row (like before), just half-width now. A restaurant bill skips
                    // Category entirely (auto-set to Food & Drink above), so Paid by gets the full row to itself.
                    val selectedCategory = categories.firstOrNull { it.key == categoryId }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!isItemized) {
                            CategorySelectField(selectedCategory, { showCategoryDialog = true }, Modifier.weight(1f))
                        }
                        PaidByField(
                            payerDisplayName,
                            isMe = payer?.isMe == true,
                            onClick = { showPayerDialog = true },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // An expense with more than one receipt keeps them down here as a plain strip.
                    if (receiptsEnabled && !isItemized && !quickReadReceipt) {
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
                                saveLabel = "Save & continue",
                                onScanSource = { source -> startScan(source) },
                                onSave = { submit(BillNext.Choose) },
                                showScanHero = !notScannedYet,
                                showReceipts = false,
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
                                onSave = { submit(BillNext.Choose) },
                            )
                        }
                    }
                }
            }
        }
        val overlayOpen =
            showAddDialog || showReceiptSource || showCurrencyDialog || showCategoryDialog ||
                showPayerDialog || showScanSource || showPeoplePicker || showDateSheet || viewerIndex != null
        EvKeyboardDoneBar(
            onDone = { focusManager.clearFocus() },
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = !overlayOpen,
        )
    }

    if (showDateSheet) {
        ExpenseDateSheet(
            date = expenseDate,
            today = today,
            onPick = {
                expenseDate = it
                datePicked = true
            },
            onDismiss = { showDateSheet = false },
        )
    }

    if (showAddDialog) {
        AddParticipantSheet(
            title = if (addingPayer) "Who paid?" else "Add a participant",
            onAdd = { name ->
                if (addingPayer) pendingPayerName = name
                onAddPlaceholder(name)
            },
            onDismiss = {
                showAddDialog = false
                addingPayer = false
            },
        )
    }

    if (showReceiptSource) {
        ReceiptSourceSheet(onPick = onPickReceipt, onDismiss = { showReceiptSource = false })
    }

    RestaurantSplitPrompt(
        read = receiptRead,
        active = !isItemized && !editing,
        amountSubunits = divide.amountSubunits,
        people = selected.size,
        currency = currency,
        onByItem = {
            splitApproach = SplitApproach.ByItem
            onSplitApproachChosen(SplitApproach.ByItem)
            onItemizeReceipt()
        },
    )

    if (showCurrencyDialog) {
        CurrencySheet(currency = currency, onPick = { currency = it }, onDismiss = { showCurrencyDialog = false })
    }

    if (showCategoryDialog) {
        CategorySheet(
            categories = categories,
            categoryId = categoryId,
            onPick = {
                categoryId = it
                categoryPicked = true
            },
            onDismiss = { showCategoryDialog = false },
        )
    }

    if (showPayerDialog) {
        PayerSheet(
            participants = participants,
            effectivePayerId = effectivePayerId,
            onPickMember = { id ->
                payerId = id
                // A member who paid is part of the split by default — add them (still removable below).
                selected = selected + id
                known = known + id
            },
            onAddSomeoneNew = {
                addingPayer = true
                showAddDialog = true
            },
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
        ScanSourceSheet(onScan = { source -> startScan(source) }, onDismiss = { showScanSource = false })
    }
    ScanStateSheets(
        scanState = scanState,
        onCancel = onCancelScan,
        onRetry = onRetryScan,
        onManual = onDismissScan,
        onPickAgain = {
            onDismissScan()
            showScanSource = true
        },
    )

    if (showPeoplePicker) {
        ExpensePeopleSheet(
            participants = participants,
            selected = selected,
            title = title,
            onPick = { selected = it },
            onAddPlaceholder = onAddPlaceholder,
            onDismiss = { showPeoplePicker = false },
        )
    }

    discardDialog()
}

@Preview
@Composable
private fun AddExpensePreview() {
    EvenlyTheme { AddExpenseScreen() }
}
