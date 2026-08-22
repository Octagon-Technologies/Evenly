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
import app.splitevenly.core.id.SettlementId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.core.time.todayUtc
import app.splitevenly.data.upload.ReceiptUploadManager
import app.splitevenly.data.upload.StagedReceipt
import app.splitevenly.domain.activity.HistoryEvent
import app.splitevenly.domain.activity.HistoryEventType
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
import app.splitevenly.domain.pro.ProBilling
import app.splitevenly.domain.pro.scanMeterFor
import app.splitevenly.domain.receipt.ReceiptDraft
import app.splitevenly.domain.receipt.ReceiptOcr
import app.splitevenly.domain.receipt.ReceiptOcrFile
import app.splitevenly.domain.receipt.ScanOutcome
import app.splitevenly.domain.repository.ActivityRepository
import app.splitevenly.domain.repository.BillRepository
import app.splitevenly.domain.repository.CategoryRepository
import app.splitevenly.domain.repository.ExpenseRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.ProRepository
import app.splitevenly.domain.repository.SettlementRepository
import app.splitevenly.domain.settlement.PaymentApp
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.EvAnalytics
import app.splitevenly.platform.FilePicker
import app.splitevenly.platform.PdfRasterizer
import app.splitevenly.platform.PickKind
import app.splitevenly.platform.PickSource
import app.splitevenly.platform.PickedFile
import app.splitevenly.platform.ProTriggers
import app.splitevenly.ui.components.moneySubunits
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
import app.splitevenly.ui.screen.expense.PaymentUi
import app.splitevenly.ui.screen.expense.PickedReceiptUi
import app.splitevenly.ui.screen.expense.ReceiptUi
import app.splitevenly.ui.screen.expense.ReceiptUploadUi
import app.splitevenly.ui.screen.expense.SplitApproach
import app.splitevenly.ui.screen.expense.SplitMode
import app.splitevenly.ui.screen.expense.format2dp
import app.splitevenly.ui.screen.group.GroupHomeScreen
import app.splitevenly.ui.screen.group.buildGroupExpenses
import app.splitevenly.ui.screen.settle.appLabel
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
 * then hands off to its live claim screen ([onCreatedBill]).
 */
@OptIn(ExperimentalTime::class)
@Composable
fun AddExpenseRoute(
    groupId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onCreatedBill: (String) -> Unit,
) {
    val expenses = koinInject<ExpenseRepository>()
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val categoriesRepo = koinInject<CategoryRepository>()
    val auth = koinInject<AuthSession>()
    val filePicker = koinInject<FilePicker>()
    val ocr = koinInject<ReceiptOcr>()
    val pro = koinInject<ProRepository>()
    // Resilient upload pipeline (D-22). Bound only when Supabase is configured; null on the offline build —
    // and when it's null we hide the receipt strip entirely rather than offer an attach that goes nowhere.
    val koin = getKoin()
    val uploadManager = remember { koin.getOrNull<ReceiptUploadManager>() }
    val stagedPdf = rememberStagedPdfRenderers(uploadManager)
    val analytics = remember { koin.getOrNull<EvAnalytics>() }
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val categories by remember(gid) { categoriesRepo.observeCategories(gid) }.collectAsStateWithLifecycle(CategoryDefaults.all)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    // Picked before the expense exists: compressed and written to the sandbox straight away so the editor
    // can show the real image, then attached to the new expense id on save.
    var pickedReceipts by remember { mutableStateOf<List<StagedReceipt>>(emptyList()) }
    // Itemized scan pipeline (mirrors BillEditRoute) — feeds the "By what each had" body.
    var scanState by remember { mutableStateOf<ScanUiState>(ScanUiState.Idle) }
    var scanned by remember { mutableStateOf<EditBillState?>(null) }
    var scanFiles by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var billReceipts by remember { mutableStateOf<List<StagedReceipt>>(emptyList()) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    // Evenly Pro: the free-scan meter under the scan hero (PRO_PASS_SPEC.md §8.1). Refreshed when the
    // editor opens and again after every scan attempt, since a successful scan is what moves the count.
    val proState by remember(gid) { pro.observe(gid.value) }.collectAsStateWithLifecycle(null)
    LaunchedEffect(gid) { pro.refresh(gid.value) }
    val scanMeter = proState?.let { scanMeterFor(it.status, it.freeUsed, it.freeLimit) }
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
                            // The group is out of free scans and holds no pass, which no amount of waiting
                            // fixes. Every other Blocked reason (the hourly rate limit today) does.
                            val blockedKind =
                                if (outcome.reason == "quota_exhausted") {
                                    ScanErrorKind.OutOfScans
                                } else {
                                    ScanErrorKind.Blocked
                                }
                            ScanUiState.Failed(blockedKind)
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
                // A successful scan is what moves the count, so re-read it rather than decrementing
                // locally: the server is the only place that knows what actually counted. That refreshed
                // number is also the only honest source for `free_scan_used` (PRO_PASS_SPEC.md §12) —
                // computing it from the pre-scan value would report a scan the server may not have counted.
                pro.refresh(gid.value)?.let { count ->
                    analytics?.capture(
                        AnalyticsEvents.FREE_SCAN_USED,
                        mapOf(
                            "group_id" to gid.value,
                            "scans_used" to count.used,
                            "scans_remaining" to count.remaining,
                        ),
                    )
                }
            }
    }

    // The scan sheet's Pro door (PRO_PASS_SPEC.md §8.1). Opens the pass sheet in place rather than
    // navigating away: the person is mid-expense, and leaving this screen to buy would lose the draft.
    val proBilling = koinInject<ProBilling>()
    var showPassSheet by remember { mutableStateOf(false) }

    AddExpenseScreen(
        groupName = group?.name,
        onGetPro = if (proBilling.isAvailable) ({ showPassSheet = true }) else null,
        participants = participants,
        categories = categories,
        currencyCode = currency,
        saving = saving,
        lastExpenseParticipantIds = lastExpenseParticipantIds,
        receipts = pickedReceipts.map { it.toUi() },
        receiptsEnabled = uploadManager != null,
        scanMeter = scanMeter,
        scanState = scanState,
        scanned = scanned,
        attachedReceipts = billReceipts.map { it.toUi() },
        onRemoveAttachedReceipt = { i ->
            val dropped = billReceipts.getOrNull(i)
            billReceipts = billReceipts.filterIndexed { idx, _ -> idx != i }
            dropped?.let { scope.launch { uploadManager?.discardStaged(listOf(it)) } }
        },
        loadPdfPageCount = stagedPdf.pageCount,
        renderPdfPage = stagedPdf.renderPage,
        onBack = onBack,
        onScanReceipt = { source ->
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
        onDismissScan = {
            // The honest counterpart to conversion rate: how many people the gate pushed onto the slow
            // path. Only fired for the quota refusal, never for an offline or unreadable-photo dismissal.
            if ((scanState as? ScanUiState.Failed)?.kind == ScanErrorKind.OutOfScans) {
                analytics?.capture(AnalyticsEvents.MANUAL_ENTRY_AFTER_PAYWALL, mapOf("group_id" to gid.value))
            }
            scanState = ScanUiState.Idle
        },
        onSplitApproachChosen = { approach ->
            analytics?.capture(
                AnalyticsEvents.SPLIT_APPROACH_CHOSEN,
                mapOf("approach" to if (approach == SplitApproach.ByItem) "by_item" else "divide"),
            )
        },
        onAddPlaceholder = { name -> scope.launch { groups.addPlaceholder(gid, name, createdBy = userId) } },
        onPickReceipt = { source ->
            scope.launch {
                val picked = filePicker.pick(source, PickKind.ImageOrPdf)
                // Compress + persist off the main thread, then the thumbnail appears. No wait for save.
                if (picked is AppResult.Ok) pickedReceipts = pickedReceipts + uploadManager?.stage(picked.value).orEmpty()
            }
        },
        onRemoveReceipt = { i ->
            val dropped = pickedReceipts.getOrNull(i)
            pickedReceipts = pickedReceipts.filterIndexed { idx, _ -> idx != i }
            dropped?.let { scope.launch { uploadManager?.discardStaged(listOf(it)) } }
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
                    val outside = submit.payerOutsideName?.takeIf { it.isNotBlank() }
                    val input =
                        NewExpense(
                            groupId = gid,
                            title = submit.title,
                            amountSubunits = submit.amountSubunits,
                            currency = submit.currency,
                            expenseDate = Clock.System.todayUtc(),
                            payerUserId = if (outside != null) null else UserId(submit.payerUserId),
                            payerOutsideName = outside,
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
                            expenseDate = Clock.System.todayUtc(),
                            // The shared header's "Paid by" carries over; fall back to me if it's blank/outside.
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
                        onCreatedBill(result.value.value)
                    }

                    is AppResult.Err -> {
                        saving = false
                    }
                }
            }
        },
    )

    if (showPassSheet) {
        PassSheetHost(
            groupId = gid.value,
            groupName = group?.name ?: "this group",
            trigger = ProTriggers.SCAN,
            // No subscription link from inside an editor: leaving a half-typed bill to browse a
            // recurring plan would lose the draft, and a link that costs someone their work is worse
            // than one that isn't there. The Profile row is the door for that.
            onSeeSubscription = null,
            onDismiss = { showPassSheet = false },
        )
    }
}

/** OCR draft → the itemized editor's initial items + extras (never a name — the shared header owns that). */
private fun ReceiptDraft.toAddEditState(): EditBillState =
    EditBillState(
        title = "",
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
                payerOutsideName = e.payerOutsideName,
            )
        }

    // The scan sheet's Pro door (PRO_PASS_SPEC.md §8.1). Opens the pass sheet in place rather than
    // navigating away: the person is mid-expense, and leaving this screen to buy would lose the draft.
    val proBilling = koinInject<ProBilling>()
    var showPassSheet by remember { mutableStateOf(false) }

    AddExpenseScreen(
        groupName = group?.name,
        onGetPro = if (proBilling.isAvailable) ({ showPassSheet = true }) else null,
        editing = true,
        participants = participants,
        categories = categories,
        currencyCode = currency,
        saving = saving,
        notice = notice,
        onDismissNotice = { notice = null },
        prefill = prefill,
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
                    val outside = submit.payerOutsideName?.takeIf { it.isNotBlank() }
                    val input =
                        EditExpense(
                            title = submit.title,
                            amountSubunits = submit.amountSubunits,
                            currency = submit.currency,
                            expenseDate = e.expenseDate,
                            payerUserId = if (outside != null) null else UserId(submit.payerUserId),
                            splitMode = submit.mode.wire,
                            shares = shares,
                            payerOutsideName = outside,
                            notes = e.notes,
                            categoryId = submit.categoryId,
                            editedBy = userId,
                        )
                    when (val saved = expenses.editExpense(eid, input)) {
                        is AppResult.Ok -> {
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

    if (showPassSheet) {
        PassSheetHost(
            groupId = gid.value,
            groupName = group?.name ?: "this group",
            trigger = ProTriggers.SCAN,
            // No subscription link from inside an editor: leaving a half-typed bill to browse a
            // recurring plan would lose the draft, and a link that costs someone their work is worse
            // than one that isn't there. The Profile row is the door for that.
            onSeeSubscription = null,
            onDismiss = { showPassSheet = false },
        )
    }
}

/**
 * Expense detail, wired: streams the expense + its shares into the read-only detail screen, plus the
 * F5 activity layer — comment thread, receipt strip (pick → compress → upload), and the history feed.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun ExpenseDetailRoute(
    groupId: String,
    expenseId: String,
    onBack: () -> Unit,
    onSettleThis: () -> Unit,
    onEdit: () -> Unit = {},
    // Where "Edit" goes for an ITEMIZED bill — the live claim screen, not the percent/exact/even editor
    // (which is meaningless when the split is derived from items). Wired to Route.ClaimBill.
    onOpenClaim: () -> Unit = {},
    // The itemized bill's menu/items/extras editor (Route.SplitBill) — surfaced as the ⋯ "Edit bill" row.
    onEditBill: () -> Unit = {},
    onDeleted: () -> Unit = {},
) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val settlements = koinInject<SettlementRepository>()
    val auth = koinInject<AuthSession>()
    val activity = koinInject<ActivityRepository>()
    val filePicker = koinInject<FilePicker>()
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
    val payments by remember(eid) { settlements.observePaymentsForExpense(eid) }.collectAsStateWithLifecycle(emptyList())
    val receipts by remember(eid) { activity.observeReceipts(eid) }.collectAsStateWithLifecycle(emptyList())
    val history by remember(eid) { activity.observeHistory(eid) }.collectAsStateWithLifecycle(emptyList())
    val pendingUploads by remember(eid) {
        uploadManager?.observe(eid) ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }

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
    val unclaimedSubunits = if (isItemized) (e.amountSubunits - ews.shares.sumOf { it.owedSubunits }).coerceAtLeast(0L) else 0L
    val rows =
        ews.shares.map { s ->
            DetailShareUi(
                name = nameOf(s.userId),
                owedSubunits = s.owedSubunits,
                paidSubunits = s.owedSubunits - s.remainingSubunits,
                remainingSubunits = s.remainingSubunits,
                me = s.userId == userId,
                payer = s.userId == e.payerUserId,
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
    val historyUi = history.map { ev -> HistoryUi(historyText(ev, e.currency) { nameOf(it) }, relativeTimeLabel(ev.createdAt, now)) }
    val paymentUi =
        payments.map { s ->
            // What an edit can raise this payment to: what the payer owes on this expense (their share). The
            // repository enforces the precise ceiling — this only drives the inline hint in the editor.
            val owedByPayer = ews.shares.firstOrNull { it.userId == s.fromUserId }?.owedSubunits ?: s.paymentAmountSubunits
            PaymentUi(
                id = s.id.value,
                payerName = nameOf(s.fromUserId),
                byMe = s.fromUserId == userId,
                amountSubunits = s.paymentAmountSubunits,
                maxSubunits = owedByPayer,
                app = s.paymentApp?.let { name -> runCatching { PaymentApp.valueOf(name).appLabel }.getOrDefault(name) },
                dateLabel = relativeTimeLabel(s.settledAt, now),
            )
        }

    ExpenseDetailScreen(
        state = ExpenseDetailState.Content,
        title = e.title,
        category = "",
        payerName = if (e.payerUserId == null) (e.payerOutsideName ?: "Someone") else nameOf(e.payerUserId),
        dateLabel = e.expenseDate,
        amountSubunits = e.amountSubunits,
        remainingSubunits = ews.shares.sumOf { it.remainingSubunits } + unclaimedSubunits,
        currencyCode = e.currency,
        splitLabel = if (isItemized) "Split by items · ${ews.shares.size} claimed" else "Split between ${ews.shares.size} · ${e.splitMode.lowercase()}",
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
        onPickReceipts = { source ->
            // Pick (possibly many) → hand to the durable outbox. The manager compresses, persists to disk,
            // and the background uploader carries them up; the UI reacts to the outbox, not to this call.
            scope.launch {
                val picked = filePicker.pick(source, PickKind.ImageOrPdf)
                if (picked is AppResult.Ok && picked.value.isNotEmpty()) {
                    uploadManager?.enqueue(eid, gid, picked.value)
                }
            }
        },
        onRetryUpload = { id -> scope.launch { uploadManager?.retry(id) } },
        onCancelUpload = { id -> scope.launch { uploadManager?.cancel(id) } },
        loadPdfPageCount = { url -> getPdfBytes(url)?.let { rasterizer.pageCount(it) } ?: 0 },
        renderPdfPage = { url, page, w -> getPdfBytes(url)?.let { rasterizer.renderPage(it, page, w) } },
        onBack = onBack,
        onSettleThis = onSettleThis,
        onEditPayment = { id, amount -> scope.launch { settlements.editSettlement(SettlementId(id), amount, userId) } },
        onRemovePayment = { id -> scope.launch { settlements.voidSettlement(SettlementId(id)) } },
        // A plain expense edits via the percent/exact/even editor. An itemized bill splits two ways: the
        // ⋯ "Edit bill" opens the menu/items editor, and the prominent button below opens the claim screen.
        onEdit = onEdit,
        onClaimItems = if (isItemized) onOpenClaim else null,
        onEditBill = if (isItemized) onEditBill else null,
        supersededNotice = supersededNotice,
        onDismissSupersededNotice = { scope.launch { expenses.dismissSupersededNotice(eid) } },
        onDelete = { scope.launch { if (expenses.deleteExpense(eid) is AppResult.Ok) onDeleted() } },
    )
}

/**
 * Render a history event into a viewer-relative sentence (actor name resolved live). [detail] carries
 * raw amount tokens the repo stored ("amt:<subunits>", "edit:<old>:<new>") so the money is formatted
 * here in the expense's [currency] — keeping currency formatting out of the data layer.
 */
private fun historyText(
    ev: HistoryEvent,
    currency: String,
    nameOf: (UserId?) -> String,
): String {
    val verb =
        when (ev.type) {
            HistoryEventType.CREATED -> "added this expense"
            HistoryEventType.EDITED -> "edited this expense"
            HistoryEventType.SETTLED -> "settled"
            HistoryEventType.SETTLEMENT_EDITED -> "corrected a payment"
            HistoryEventType.COMMENTED -> "commented"
            HistoryEventType.RECEIPT_ADDED -> "added a receipt"
            HistoryEventType.DELETED -> "deleted this expense"
        }
    val passive =
        when (ev.type) {
            HistoryEventType.CREATED -> "created"
            HistoryEventType.EDITED -> "edited"
            HistoryEventType.SETTLED -> "settled"
            HistoryEventType.SETTLEMENT_EDITED -> "corrected"
            HistoryEventType.COMMENTED -> "commented on"
            HistoryEventType.RECEIPT_ADDED -> "updated"
            HistoryEventType.DELETED -> "deleted"
        }
    val base = if (ev.actorUserId != null) "${nameOf(ev.actorUserId)} $verb" else "This expense was $passive"
    return ev.detail?.let { "$base · ${renderDetail(it, currency)}" } ?: base
}

/** Expand a stored detail token into human money text; pass through plain (non-token) details unchanged. */
private fun renderDetail(
    detail: String,
    currency: String,
): String {
    val parts = detail.split(":")
    return when (parts.firstOrNull()) {
        "amt" -> {
            parts.getOrNull(1)?.toLongOrNull()?.let { moneySubunits(it, currency) } ?: detail
        }

        "edit" -> {
            val old = parts.getOrNull(1)?.toLongOrNull()
            val new = parts.getOrNull(2)?.toLongOrNull()
            if (old != null && new != null) "${moneySubunits(old, currency)} → ${moneySubunits(new, currency)}" else detail
        }

        else -> {
            detail
        }
    }
}

/** Short relative time ("now", "5m", "2h", "3d", "2w") for activity stamps. */
private fun relativeTimeLabel(
    thenMs: Long,
    nowMs: Long,
): String {
    val minutes = ((nowMs - thenMs).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 1_440 -> "${minutes / 60}h"
        minutes < 10_080 -> "${minutes / 1_440}d"
        else -> "${minutes / 10_080}w"
    }
}
