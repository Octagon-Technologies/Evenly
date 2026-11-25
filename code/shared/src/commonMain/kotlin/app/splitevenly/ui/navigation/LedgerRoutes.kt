package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.ReceiptId
import app.splitevenly.core.id.SettlementId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.localHour
import app.splitevenly.core.time.localIsoDate
import app.splitevenly.core.time.localNoonEpochMillis
import app.splitevenly.core.time.localToday
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.data.upload.ReceiptUploadManager
import app.splitevenly.data.upload.StagedReceipt
import app.splitevenly.domain.activity.ReceiptUploadStatus
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.expense.BillExtrasInput
import app.splitevenly.domain.expense.CategoryDefaults
import app.splitevenly.domain.expense.EditExpense
import app.splitevenly.domain.expense.NewBill
import app.splitevenly.domain.expense.NewBillItem
import app.splitevenly.domain.expense.NewExpense
import app.splitevenly.domain.expense.NewShare
import app.splitevenly.domain.expense.SPLIT_MODE_ITEMIZED
import app.splitevenly.domain.expense.TipSplitMode
import app.splitevenly.domain.expense.expenseDateChipLabel
import app.splitevenly.domain.expense.trustedReceiptDate
import app.splitevenly.domain.receipt.ReceiptDraft
import app.splitevenly.domain.receipt.ReceiptOcr
import app.splitevenly.domain.receipt.ReceiptOcrFile
import app.splitevenly.domain.receipt.ScanOutcome
import app.splitevenly.domain.receipt.VenueKind
import app.splitevenly.domain.receipt.printedHourOf
import app.splitevenly.domain.receipt.suggestBillTitle
import app.splitevenly.domain.repository.ActivityRepository
import app.splitevenly.domain.repository.BillRepository
import app.splitevenly.domain.repository.CategoryRepository
import app.splitevenly.domain.repository.ExpenseRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.SettlementRepository
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.EvAnalytics
import app.splitevenly.platform.FilePicker
import app.splitevenly.platform.PdfRasterizer
import app.splitevenly.platform.PickKind
import app.splitevenly.platform.PickSource
import app.splitevenly.platform.PickedFile
import app.splitevenly.platform.SecureStorage
import app.splitevenly.ui.screen.bill.BillNext
import app.splitevenly.ui.screen.bill.EditBillState
import app.splitevenly.ui.screen.bill.ScanErrorKind
import app.splitevenly.ui.screen.bill.ScanPageUi
import app.splitevenly.ui.screen.bill.ScanUiState
import app.splitevenly.ui.screen.bill.analyticsKind
import app.splitevenly.ui.screen.bill.editBillItemUi
import app.splitevenly.ui.screen.bill.priceToSubunits
import app.splitevenly.ui.screen.bill.scanEditStats
import app.splitevenly.ui.screen.expense.AddExpensePrefill
import app.splitevenly.ui.screen.expense.AddExpenseScreen
import app.splitevenly.ui.screen.expense.AddParticipantUi
import app.splitevenly.ui.screen.expense.CommentUi
import app.splitevenly.ui.screen.expense.DetailShareUi
import app.splitevenly.ui.screen.expense.ExpenseDetailScreen
import app.splitevenly.ui.screen.expense.ExpenseDetailState
import app.splitevenly.ui.screen.expense.HistoryUi
import app.splitevenly.ui.screen.expense.ReceiptUi
import app.splitevenly.ui.screen.expense.ReceiptUploadUi
import app.splitevenly.ui.screen.expense.SplitApproach
import app.splitevenly.ui.screen.expense.SplitMode
import app.splitevenly.ui.screen.expense.format2dp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Add expense, wired. One screen, two split approaches: "Divide the total" persists a normal expense with
 * the split the editor computed; "By what each had" runs the scan pipeline and creates an itemized bill,
 * then hands off to its live claim screen ([onCreatedBill]). The review screen's two buttons say where:
 * [BillNext.Choose] is the editor's own save, which still goes through the chooser.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun AddExpenseRoute(
    groupId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onCreatedBill: (String, BillNext) -> Unit,
) {
    val expenses = koinInject<ExpenseRepository>()
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val categoriesRepo = koinInject<CategoryRepository>()
    val auth = koinInject<AuthSession>()
    val filePicker = koinInject<FilePicker>()
    val cameraGate = rememberCameraPermissionGate()
    val ocr = koinInject<ReceiptOcr>()
    // Resilient upload pipeline (D-22). Bound only when Supabase is configured; null on the offline build —
    // and when it's null we hide the receipt strip entirely rather than offer an attach that goes nowhere.
    val koin = getKoin()
    val uploadManager = remember { koin.getOrNull<ReceiptUploadManager>() }
    val stagedPdf = rememberStagedPdfRenderers(uploadManager)
    val analytics = remember { koin.getOrNull<EvAnalytics>() }
    val quickRead = rememberReceiptQuickRead(ocr, groupId)
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val categories by remember(gid) { categoriesRepo.observeCategories(gid) }.collectAsStateWithLifecycle(CategoryDefaults.all)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Picked before the expense exists: compressed and written to the sandbox straight away so the editor
    // can show the real image, then attached to the new expense id on save.
    var pickedReceipts by remember { mutableStateOf<List<StagedReceipt>>(emptyList()) }
    // Itemized scan pipeline (mirrors BillEditRoute) — feeds the "By what each had" body.
    var scanState by remember { mutableStateOf<ScanUiState>(ScanUiState.Idle) }
    var scanned by remember { mutableStateOf<EditBillState?>(null) }
    var scanFiles by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var billReceipts by remember { mutableStateOf<List<StagedReceipt>>(emptyList()) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    // Scan-funnel analytics bookkeeping: the source of the in-flight scan (for scan_started/scan_cancelled)
    // and its start time (for duration_ms). Neither is user-facing state, just event properties.
    var scanSource by remember { mutableStateOf<PickSource?>(null) }
    var scanStartedAt by remember { mutableStateOf(0L) }
    // The group's most recent expense — its participant set seeds a *new* expense's default selection
    // (whoever was actually there last time, not the whole group). null = the flow hasn't emitted yet.
    // Staged bytes belong to this editor until a save attaches them. There is no BackHandler here, so
    // system back and swipe-back leave without ever reaching onBack — clean up on dispose instead, or an
    // abandoned pick sits in the sandbox forever.
    var attached by remember { mutableStateOf(false) }
    val deferred = remember { DeferredRemovals() }
    DisposableEffect(Unit) {
        onDispose { if (!attached) uploadManager?.discardStagedDetached(pickedReceipts + billReceipts) }
    }
    val recentExpenses by remember(gid) { expenses.observeExpensesWithShares(gid) }.collectAsStateWithLifecycle(null)
    // Wait for the group's history to load before rendering — same "gate on first load" convention as
    // BillEditRoute/EditExpenseRoute — so the default participant set is computed exactly once, correctly.
    if (recentExpenses == null) return

    val participants =
        members
            .map { AddParticipantUi(it.userId.value, it.displayName ?: "Someone", it.userId == userId) }
            .ifEmpty { listOfNotNull(userId?.let { AddParticipantUi(it.value, "You", true) }) }
    val currency = group?.baseCurrency ?: "USD"
    val lastExpenseParticipantIds: Set<String> = recentExpenses?.firstOrNull()?.shares?.mapTo(HashSet()) { it.userId.value } ?: emptySet()

    fun runScan(
        files: List<PickedFile>,
        source: PickSource,
    ) {
        if (files.isEmpty()) return
        scanFiles = files
        scanSource = source
        scanStartedAt = Clock.System.nowEpochMillis()
        scanState = ScanUiState.Working(files.map { ScanPageUi(it.mimeType.contains("pdf", ignoreCase = true)) })
        analytics?.capture(
            AnalyticsEvents.SCAN_STARTED,
            mapOf("page_count" to files.size, "source" to source.name.lowercase(), "group_id" to gid.value),
        )
        // The pages the user just photographed ARE the receipt, whatever the OCR makes of them. Stage them
        // on a job of their own so a cancelled, failed or blocked scan still leaves the receipt attached —
        // scanJob gets cancelled, and this must not go with it.
        scope.launch {
            val superseded = billReceipts
            billReceipts = uploadManager?.stage(files).orEmpty()
            uploadManager?.discardStaged(superseded)
        }
        scanJob =
            scope.launch {
                val ocrFiles = files.map { ReceiptOcrFile(it.bytes, it.mimeType) }
                val outcome = ocr.extract(ocrFiles, groupId = gid.value)
                val durationMs = Clock.System.nowEpochMillis() - scanStartedAt
                scanState =
                    when (outcome) {
                        is ScanOutcome.Success -> {
                            scanned = outcome.draft.toAddEditState()
                            analytics?.capture(
                                AnalyticsEvents.SCAN_COMPLETED,
                                buildMap {
                                    put("page_count", files.size)
                                    put("item_count", outcome.draft.items.size)
                                    put("duration_ms", durationMs)
                                    put("group_id", gid.value)
                                    outcome.scanId?.let { put("scan_id", it) }
                                },
                            )
                            ScanUiState.Idle
                        }

                        is ScanOutcome.Blocked -> {
                            analytics?.capture(
                                AnalyticsEvents.SCAN_BLOCKED,
                                mapOf("reason" to outcome.reason, "group_id" to gid.value),
                            )
                            ScanUiState.Failed(ScanErrorKind.Blocked)
                        }

                        else -> {
                            val kind =
                                when (outcome) {
                                    ScanOutcome.NoReceiptFound -> ScanErrorKind.NoReceiptFound
                                    ScanOutcome.Offline -> ScanErrorKind.Offline
                                    ScanOutcome.Unavailable -> ScanErrorKind.Unavailable
                                    is ScanOutcome.Failed -> ScanErrorKind.Error
                                    is ScanOutcome.Success, is ScanOutcome.Blocked -> ScanErrorKind.Error
                                }
                            analytics?.capture(
                                AnalyticsEvents.SCAN_FAILED,
                                mapOf(
                                    "kind" to kind.analyticsKind(),
                                    "page_count" to files.size,
                                    "duration_ms" to durationMs,
                                    "group_id" to gid.value,
                                ),
                            )
                            ScanUiState.Failed(kind)
                        }
                    }
            }
    }

    AddExpenseScreen(
        participants = participants,
        categories = categories,
        currencyCode = currency,
        saving = saving,
        lastExpenseParticipantIds = lastExpenseParticipantIds,
        receipts = pickedReceipts.map { it.toUi() },
        receiptsEnabled = uploadManager != null,
        receiptRead = quickRead.state,
        categoryHistory = remember(recentExpenses) { categoryHistoryOf(recentExpenses) },
        scanState = scanState,
        scanned = scanned,
        notice = notice,
        onDismissNotice = { notice = null },
        attachedReceipts = billReceipts.map { it.toUi() },
        onRemoveAttachedReceipt = { i ->
            removeStaged({ billReceipts }, i, deferred, uploadManager) { billReceipts = it }
        },
        loadPdfPageCount = stagedPdf.pageCount,
        renderPdfPage = stagedPdf.renderPage,
        onBack = onBack,
        onScanReceipt =
            cameraGate.wrap { source ->
                // Fires before any cost is incurred — even if the user backs out of the file picker next.
                analytics?.capture(
                    AnalyticsEvents.SCAN_SOURCE_CHOSEN,
                    mapOf("source" to source.name.lowercase(), "group_id" to gid.value),
                )
                scope.launch {
                    val picked = filePicker.pick(source, PickKind.ImageOrPdf)
                    runScan((picked as? AppResult.Ok)?.value.orEmpty(), source)
                }
            },
        onCancelScan = {
            if (scanState is ScanUiState.Working) {
                analytics?.capture(
                    AnalyticsEvents.SCAN_CANCELLED,
                    mapOf(
                        "page_count" to scanFiles.size,
                        "duration_ms" to (Clock.System.nowEpochMillis() - scanStartedAt),
                        "group_id" to gid.value,
                    ),
                )
            }
            scanJob?.cancel()
            scanState = ScanUiState.Idle
        },
        onRetryScan = { scanSource?.let { runScan(scanFiles, it) } },
        onDismissScan = { scanState = ScanUiState.Idle },
        onSplitApproachChosen = { approach ->
            analytics?.capture(
                AnalyticsEvents.SPLIT_APPROACH_CHOSEN,
                mapOf("approach" to if (approach == SplitApproach.ByItem) "by_item" else "divide"),
            )
        },
        onAddPlaceholder = { name -> scope.launch { groups.addPlaceholder(gid, name, createdBy = userId) } },
        onPickReceipt =
            cameraGate.wrap { source ->
                scope.launch {
                    val picked = filePicker.pick(source, PickKind.ImageOrPdf)
                    // One receipt per expense here, so a pick replaces the last one and starts a fresh quick read.
                    // Compress + persist off the main thread, then the thumbnail appears. No wait for save.
                    if (picked is AppResult.Ok && picked.value.isNotEmpty()) {
                        val superseded = pickedReceipts
                        quickRead.start(picked.value, source)
                        pickedReceipts = uploadManager?.stage(picked.value).orEmpty()
                        uploadManager?.discardStaged(superseded)
                    }
                }
            },
        // The restaurant prompt's "Split by item": the attached receipt becomes the bill's scan, so it moves
        // from the divide flow's slot to the scanned pages instead of being staged twice.
        onItemizeReceipt = {
            val files = quickRead.files
            quickRead.source?.let { source ->
                val superseded = pickedReceipts
                pickedReceipts = emptyList()
                scope.launch { uploadManager?.discardStaged(superseded) }
                quickRead.clear()
                runScan(files, source)
            }
        },
        onRemoveReceipt = { i ->
            quickRead.clear()
            removeStaged({ pickedReceipts }, i, deferred, uploadManager) { pickedReceipts = it }
        },
        onSave = { submit ->
            val me = userId
            if (me != null && submit.shares.isNotEmpty()) {
                saving = true
                scope.launch {
                    val shares =
                        submit.shares.map { s ->
                            NewShare(
                                userId = UserId(s.userId),
                                owedSubunits = s.owedSubunits,
                                shareUnits = s.units,
                                sharePercentage = s.percent,
                                shareExactSubunits = s.exactSubunits,
                            )
                        }
                    val input =
                        NewExpense(
                            groupId = gid,
                            title = submit.title,
                            amountSubunits = submit.amountSubunits,
                            currency = submit.currency,
                            expenseDate = submit.expenseDate,
                            payerUserId = UserId(submit.payerUserId),
                            splitMode = submit.mode.wire,
                            createdBy = me,
                            shares = shares,
                            categoryId = submit.categoryId,
                            // `scanned` is set once a scan draft is loaded and never cleared afterward
                            // in this composable, so it stays the record of how the items got here even
                            // if the user went on to edit them by hand.
                            fromScan = scanned != null,
                        )
                    when (val result = expenses.addExpense(input)) {
                        is AppResult.Ok -> {
                            // Bytes are already on disk; this only records them against the new expense.
                            // billReceipts rides along too: scanning and then switching to Divide must not
                            // throw away the receipt the user already photographed.
                            attached = true
                            uploadManager?.attach(result.value.id, gid, pickedReceipts + billReceipts)
                            onSaved()
                        }

                        is AppResult.Err -> {
                            saving = false
                        }
                    }
                }
            }
        },
        onSaveItemized = { submit ->
            val me = userId ?: return@AddExpenseScreen
            saving = true
            notice = null
            scope.launch {
                val extras =
                    BillExtrasInput(
                        taxSubunits = submit.taxSubunits,
                        gratuitySubunits = submit.gratuitySubunits,
                        tipSubunits = submit.tipSubunits,
                        tipSplitMode = TipSplitMode.EVEN, // tip is firmly an even split
                        discountSubunits = submit.discountSubunits,
                        otherChargesSubunits = submit.otherChargesSubunits,
                    )
                val result =
                    bills.createBill(
                        NewBill(
                            groupId = gid,
                            title = submit.title,
                            currency = currency,
                            expenseDate = submit.expenseDate ?: Clock.System.localToday(),
                            // The shared header's "Paid by" carries over; fall back to me if it's blank.
                            payerUserId = submit.payerUserId?.takeIf { it.isNotBlank() }?.let { UserId(it) } ?: me,
                            createdBy = me,
                            items = submit.items.map { NewBillItem(it.label.trim(), it.quantity, priceToSubunits(it.totalText)) },
                            extras = extras,
                            participantUserIds = submit.participantIds.map { UserId(it) },
                        ),
                    )
                when (result) {
                    is AppResult.Ok -> {
                        // The scanned pages ride along as the bill's receipt (background upload).
                        attached = true
                        uploadManager?.attach(result.value, gid, billReceipts + pickedReceipts)
                        // The trust metric: how much of the OCR draft survived to what actually got saved.
                        scanned?.let { s ->
                            val (changed, total) = scanEditStats(s.items, submit.items)
                            analytics?.capture(
                                AnalyticsEvents.SCAN_RESULT_EDITED,
                                mapOf("items_changed" to changed, "items_total" to total, "group_id" to gid.value),
                            )
                        }
                        onCreatedBill(result.value.value, submit.next)
                    }

                    is AppResult.Err -> {
                        saving = false
                        notice = "Couldn't save the bill. Nothing was lost, so tap Save & continue to try again."
                    }
                }
            }
        },
    )

    // Last, so the gate lands over the screen rather than under it.
    CameraPermissionGateHost(cameraGate)
}

/** OCR draft → the itemized editor's initial items + extras (never a name — the shared header owns that). */
@OptIn(ExperimentalTime::class)
private fun ReceiptDraft.toAddEditState(): EditBillState =
    EditBillState(
        // A suggestion only: the screen applies it when the user has not typed a name yet.
        title = suggestBillTitle(merchantName, venueKind, printedHourOf(printedTime), Clock.System.localHour()).orEmpty(),
        items =
            items
                .map { editBillItemUi(null, it.label, it.quantity, it.lineTotalSubunits) }
                .ifEmpty { listOf(editBillItemUi(null, "", 1, 0L)) },
        taxText = if (taxSubunits == 0L) "" else format2dp(taxSubunits / 100.0),
        gratuityText = if (gratuitySubunits == 0L) "" else format2dp(gratuitySubunits / 100.0),
        tipText = if (tipSubunits == 0L) "" else format2dp(tipSubunits / 100.0),
        discountText = if (discountSubunits == 0L) "" else format2dp(discountSubunits / 100.0),
        otherChargesText = if (otherChargesSubunits == 0L) "" else format2dp(otherChargesSubunits / 100.0),
        verified = verified,
        categoryKey = if (venueKind == VenueKind.GROCERY) "groceries" else "food",
        receiptDate = trustedReceiptDate(printedDate, Clock.System.localToday()),
    )

/** Edit expense, wired: prefills the split editor from the saved expense; Save replaces its shares. */
@OptIn(ExperimentalTime::class)
@Composable
fun EditExpenseRoute(
    groupId: String,
    expenseId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val categoriesRepo = koinInject<CategoryRepository>()
    val auth = koinInject<AuthSession>()
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val gid = remember(groupId) { GroupId(groupId) }
    val detail by remember(eid) { expenses.observeExpense(eid) }.collectAsStateWithLifecycle(null)
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val categories by remember(gid) { categoriesRepo.observeCategories(gid) }.collectAsStateWithLifecycle(CategoryDefaults.all)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val cameraGate = rememberCameraPermissionGate()
    val receipts = rememberEditExpenseReceipts(eid, gid)

    val ews = detail
    if (ews == null) {
        ExpenseDetailScreen(state = ExpenseDetailState.Loading, onBack = onBack)
        return
    }
    val e = ews.expense
    val currency = group?.baseCurrency ?: e.currency
    // Real members drive the participant chips; fall back to the share roster while members still load.
    val participants =
        members
            .map { AddParticipantUi(it.userId.value, it.displayName ?: "Someone", it.userId == userId) }
            .ifEmpty { ews.shares.map { AddParticipantUi(it.userId.value, "Someone", it.userId == userId) } }

    val prefill =
        remember(ews) {
            AddExpensePrefill(
                amountSubunits = e.amountSubunits,
                title = e.title,
                payerUserId = e.payerUserId?.value ?: "",
                selectedUserIds = ews.shares.map { it.userId.value }.toSet(),
                mode = SplitMode.fromWire(e.splitMode),
                shareUnits = ews.shares.mapNotNull { s -> s.shareUnits?.let { s.userId.value to it } }.toMap(),
                percentText = ews.shares.mapNotNull { s -> s.sharePercentage?.let { s.userId.value to format2dp(it) } }.toMap(),
                exactText = ews.shares.mapNotNull { s -> s.shareExactSubunits?.let { s.userId.value to format2dp(it / 100.0) } }.toMap(),
                categoryId = e.categoryId,
                expenseDate = e.expenseDate,
            )
        }

    AddExpenseScreen(
        editing = true,
        participants = participants,
        categories = categories,
        currencyCode = currency,
        saving = saving,
        notice = notice,
        onDismissNotice = { notice = null },
        prefill = prefill,
        receipts = receipts.ui,
        receiptsEnabled = receipts.enabled,
        onPickReceipt = cameraGate.wrap(receipts.pick),
        onRemoveReceipt = receipts.remove,
        loadPdfPageCount = receipts.loadPdfPageCount,
        renderPdfPage = receipts.renderPdfPage,
        onBack = onBack,
        onAddPlaceholder = { name -> scope.launch { groups.addPlaceholder(gid, name, createdBy = userId) } },
        onSave = { submit ->
            if (submit.shares.isNotEmpty()) {
                saving = true
                scope.launch {
                    val shares =
                        submit.shares.map { s ->
                            NewShare(
                                userId = UserId(s.userId),
                                owedSubunits = s.owedSubunits,
                                shareUnits = s.units,
                                sharePercentage = s.percent,
                                shareExactSubunits = s.exactSubunits,
                            )
                        }
                    val input =
                        EditExpense(
                            title = submit.title,
                            amountSubunits = submit.amountSubunits,
                            currency = submit.currency,
                            expenseDate = submit.expenseDate,
                            payerUserId = UserId(submit.payerUserId),
                            splitMode = submit.mode.wire,
                            shares = shares,
                            notes = e.notes,
                            categoryId = submit.categoryId,
                            editedBy = userId,
                        )
                    when (val saved = expenses.editExpense(eid, input)) {
                        is AppResult.Ok -> {
                            receipts.attachOnSave()
                            onSaved()
                        }

                        is AppResult.Err -> {
                            saving = false
                            // The editor cannot know this one up front: whether anyone has paid against
                            // the expense is the repository's fact, not the form's. Say it, rather than
                            // letting Save quietly go back to reading "Save" (R1).
                            notice =
                                (saved.error as? AppError.Validation)
                                    ?.fieldErrors
                                    ?.get("currency")
                                    ?.let {
                                        "A payment is already recorded on this expense, so its currency " +
                                            "can't change. Remove the payment first."
                                    }
                        }
                    }
                }
            }
        },
    )
    CameraPermissionGateHost(cameraGate)
}

/**
 * Expense detail, wired: streams the expense + its shares into the read-only detail screen, plus the
 * F5 activity layer — comment thread, saved receipts, and the history feed.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun ExpenseDetailRoute(
    groupId: String,
    expenseId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit = {},
    // Where "Pick your items" / "Change picks" goes for an ITEMIZED bill: the assign screen (Route.ClaimBill).
    onOpenClaim: () -> Unit = {},
    // The bill's QR / share-link sheet (Route.ShareBillLink); a quick split's even bill offers it too.
    onShareItems: () -> Unit = {},
    // The itemized bill's menu/items/extras editor (Route.SplitBill), surfaced as the top-bar "Edit".
    onEditBill: () -> Unit = {},
    onDeleted: () -> Unit = {},
) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val settlements = koinInject<SettlementRepository>()
    val auth = koinInject<AuthSession>()
    val activity = koinInject<ActivityRepository>()
    // Resilient upload pipeline (D-22). Bound only when Supabase is configured; null on the offline build.
    val koin = getKoin()
    val uploadManager = remember { koin.getOrNull<ReceiptUploadManager>() }
    // In-app receipt viewer plumbing: PDFs are downloaded once + rasterized natively (no external browser).
    val http = koinInject<HttpClient>()
    val rasterizer = remember { PdfRasterizer() }
    val pdfBytes = remember { mutableMapOf<String, ByteArray>() }
    val pdfBytesLock = remember { Mutex() }
    val getPdfBytes: suspend (String) -> ByteArray? = { url ->
        pdfBytesLock.withLock {
            pdfBytes[url] ?: runCatching { http.get(url).readRawBytes() }.getOrNull()?.also { pdfBytes[url] = it }
        }
    }
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val gid = remember(groupId) { GroupId(groupId) }
    val detail by remember(eid) { expenses.observeExpense(eid) }.collectAsStateWithLifecycle(null)
    // Track F one-sided nudge: this device's split edit was superseded while it was behind.
    val supersededNotice by remember(eid) { expenses.observeSupersededNotice(eid) }.collectAsStateWithLifecycle(false)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val comments by remember(eid) { activity.observeComments(eid) }.collectAsStateWithLifecycle(emptyList())
    val payments by remember(eid) { settlements.observeExpensePayments(eid) }.collectAsStateWithLifecycle(emptyList())
    val receipts by remember(eid) { activity.observeReceipts(eid) }.collectAsStateWithLifecycle(emptyList())
    val history by remember(eid) { activity.observeHistory(eid) }.collectAsStateWithLifecycle(emptyList())
    val pendingUploads by remember(eid) {
        uploadManager?.observe(eid) ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val proofs = rememberPaymentProofs(gid, userId)
    var draft by remember { mutableStateOf("") }
    // The method last used on this device, pre-picked next time. Device-local: it's a convenience, and a
    // wrong guess costs one tap.
    val storage = koinInject<SecureStorage>()
    var lastMethod by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { lastMethod = storage.getString(LAST_PAYMENT_METHOD_KEY) }
    val today = remember { Clock.System.localToday() }

    val ews = detail
    if (ews == null) {
        ExpenseDetailScreen(state = ExpenseDetailState.Loading, onBack = onBack)
        return
    }
    val e = ews.expense
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }

    fun nameOf(id: UserId?): String =
        when {
            id == null -> "Someone"
            id == userId -> "You"
            else -> nameByUser[id.value] ?: "Someone"
        }
    // An itemized bill's shares only cover what's been claimed *so far*; the rest of the bill total isn't
    // yet anyone's. Surface that gap as an explicit "Unclaimed" row so the split still sums to the total —
    // e.g. of 5 diners, if only Andrew + Bob have claimed, the detail shows Andrew, Bob, then Unclaimed.
    val isItemized = e.splitMode == SPLIT_MODE_ITEMIZED
    val quickSplit = remember(groupId) { groups.observeGroup(GroupId(groupId)) }.collectAsStateWithLifecycle(null).value?.isQuick == true
    val billPick = rememberBillPick(eid, isItemized, userId)
    val unclaimedSubunits = if (isItemized) (e.amountSubunits - ews.shares.sumOf { it.owedSubunits }).coerceAtLeast(0L) else 0L
    val paymentUi = payments.map { p -> proofs.decorate(paymentUi(p.record, userId, p.appliedHereSubunits, p.billCount) { nameOf(it) }) }
    // Whose payments on this bill carry a screenshot, for the paperclip on their row.
    val provedPayers = payments.zip(paymentUi).filter { (_, ui) -> ui.hasProof }.map { (p, _) -> p.record.fromUserId }.toSet()
    val rows =
        ews.shares.map { s ->
            DetailShareUi(
                name = nameOf(s.userId),
                owedSubunits = s.owedSubunits,
                paidSubunits = s.owedSubunits - s.remainingSubunits,
                remainingSubunits = s.remainingSubunits,
                me = s.userId == userId,
                payer = s.userId == e.payerUserId,
                userId = s.userId.value,
                // Only an in-app payer can be paid back here; the payer records for anyone, others for themselves.
                canRecord = e.payerUserId != null && s.userId != e.payerUserId && (userId == e.payerUserId || s.userId == userId),
                hasProof = s.userId in provedPayers,
            )
        } +
            if (unclaimedSubunits > 0L) {
                listOf(
                    DetailShareUi(
                        name = "Unclaimed",
                        owedSubunits = unclaimedSubunits,
                        paidSubunits = 0L,
                        remainingSubunits = unclaimedSubunits,
                    ),
                )
            } else {
                emptyList()
            }

    // One "now" per data change keeps the relative stamps ("2h") stable within a frame.
    val now = remember(comments, receipts, history) { Clock.System.nowEpochMillis() }
    val commentUi =
        comments.map { cm ->
            CommentUi(cm.id.value, nameOf(cm.authorUserId), cm.body, relativeTimeLabel(cm.createdAt, now), me = cm.authorUserId == userId)
        }
    val receiptUi = receipts.map { r -> ReceiptUi(r.id.value, r.url, r.isPdf) }
    val pendingUi =
        pendingUploads.map { u ->
            ReceiptUploadUi(
                id = u.id,
                model = if (u.isPdf) null else "file://${u.localPath}",
                isPdf = u.isPdf,
                fraction = u.fraction,
                failed = u.status == ReceiptUploadStatus.FAILED,
            )
        }
    val historyUi =
        history.map { ev ->
            HistoryUi(historyText(ev, e.currency, userId) { nameOf(it) }, relativeTimeLabel(ev.createdAt, now))
        }
    // On a bill's screen a payment shows (and edits) only what it put on this bill; a "Settle up"
    // payment's other bills are left alone (one-time-splits Part B).

    ExpenseDetailScreen(
        state = ExpenseDetailState.Content,
        title = e.title,
        category = "",
        payerName = e.payerUserId?.let { nameOf(it) } ?: "Someone",
        dateLabel = expenseDateChipLabel(e.expenseDate, today),
        amountSubunits = e.amountSubunits,
        remainingSubunits = ews.shares.sumOf { it.remainingSubunits } + unclaimedSubunits,
        currencyCode = e.currency,
        splitLabel = if (isItemized) "What each person owes" else "Split between ${ews.shares.size} · ${e.splitMode.lowercase()}",
        splitRows = rows,
        payments = paymentUi,
        receipts = receiptUi,
        pendingUploads = pendingUi,
        comments = commentUi,
        historyEvents = historyUi,
        commentDraft = draft,
        onCommentDraftChange = { draft = it },
        onSendComment = {
            val body = draft.trim()
            if (body.isNotEmpty()) {
                draft = ""
                scope.launch { activity.postComment(eid, gid, body) }
            }
        },
        onRetryUpload = { id -> scope.launch { uploadManager?.retry(id) } },
        onCancelUpload = { id -> scope.launch { uploadManager?.cancel(id) } },
        onDeleteReceipt = { id -> scope.launch { activity.deleteReceipt(ReceiptId(id)) } },
        loadPdfPageCount = { url -> getPdfBytes(url)?.let { rasterizer.pageCount(it) } ?: 0 },
        renderPdfPage = { url, page, w -> getPdfBytes(url)?.let { rasterizer.renderPage(it, page, w) } },
        onBack = onBack,
        paymentPayeeName = nameOf(e.payerUserId),
        today = today,
        defaultPaymentMethod = lastMethod,
        onRecordPayment = { debtor, entry ->
            val payer = e.payerUserId
            if (payer == null) {
                "This bill has no payer yet. Pick who paid in Edit bill."
            } else {
                recordBillPayment(
                    settlements,
                    gid,
                    eid,
                    e.currency,
                    from = UserId(debtor),
                    to = payer,
                    actor = userId,
                    amountSubunits = entry.amountSubunits,
                    method = entry.method,
                    date = entry.date,
                    today = today,
                ).also { refused ->
                    if (refused == null && entry.method != null) {
                        lastMethod = entry.method
                        storage.putString(LAST_PAYMENT_METHOD_KEY, entry.method)
                    }
                }
            }
        },
        previewPayment = { id, amount -> paymentPreview(settlements, id, amount, e.currency, scopeExpenseId = eid) },
        onSavePayment = { id, entry ->
            val old = payments.firstOrNull { it.record.id.value == id }?.record
            val dateChanged = old == null || localIsoDate(old.settledAt) != entry.date
            savePaymentEdit(
                settlements,
                id,
                entry.amountSubunits,
                userId,
                scopeExpenseId = eid,
                paymentApp = entry.method,
                settledAt = if (dateChanged) localNoonEpochMillis(entry.date) else null,
            )
        },
        onRemovePayment = { id -> scope.launch { settlements.voidSettlement(SettlementId(id), userId) } },
        onAddProof = { p -> scope.launch { proofs.pickAndAttach(p.chainId) } },
        onRemoveProof = { p -> p.proofId?.let { id -> scope.launch { proofs.remove(id) } } },
        // A plain expense edits via the percent/exact/even editor. An itemized bill splits two ways: the
        // summary-card "Edit bill" action opens the menu/items editor, and the prominent button below opens
        // the claim screen.
        onEdit = onEdit,
        onClaimItems = if (isItemized) onOpenClaim else null,
        billPick = billPick,
        onShareItems = onShareItems,
        onShareSplit = if (quickSplit && !isItemized) onShareItems else null,
        onEditBill = if (isItemized) onEditBill else null,
        supersededNotice = supersededNotice,
        onDismissSupersededNotice = { scope.launch { expenses.dismissSupersededNotice(eid) } },
        onDelete = { scope.launch { if (expenses.deleteExpense(eid) is AppResult.Ok) onDeleted() } },
    )
}

/** SecureStorage key for the payment method this device used last. */
private const val LAST_PAYMENT_METHOD_KEY = "last_payment_method"
