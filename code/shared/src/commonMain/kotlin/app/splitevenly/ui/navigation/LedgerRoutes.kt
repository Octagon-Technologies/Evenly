package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.SettlementId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.core.time.todayUtc
import app.splitevenly.data.upload.ReceiptUploadManager
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
import app.splitevenly.domain.receipt.ReceiptDraft
import app.splitevenly.domain.receipt.ReceiptOcr
import app.splitevenly.domain.receipt.ReceiptOcrFile
import app.splitevenly.domain.receipt.ScanOutcome
import app.splitevenly.domain.repository.ActivityRepository
import app.splitevenly.domain.repository.BillRepository
import app.splitevenly.domain.repository.CategoryRepository
import app.splitevenly.domain.repository.ExpenseRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.SettlementRepository
import app.splitevenly.domain.settlement.PaymentApp
import app.splitevenly.platform.FilePicker
import app.splitevenly.platform.PdfRasterizer
import app.splitevenly.platform.PickKind
import app.splitevenly.platform.PickSource
import app.splitevenly.platform.PickedFile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.screen.expense.SplitMode
import app.splitevenly.ui.screen.expense.format2dp
import app.splitevenly.ui.screen.bill.EditBillState
import app.splitevenly.ui.screen.bill.ScanErrorKind
import app.splitevenly.ui.screen.bill.ScanPageUi
import app.splitevenly.ui.screen.bill.ScanUiState
import app.splitevenly.ui.screen.bill.editBillItemUi
import app.splitevenly.ui.screen.bill.priceToSubunits
import app.splitevenly.ui.screen.group.GroupHomeScreen
import app.splitevenly.ui.screen.group.buildGroupExpenses
import app.splitevenly.ui.screen.settle.appLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
fun AddExpenseRoute(groupId: String, onBack: () -> Unit, onSaved: () -> Unit, onCreatedBill: (String) -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val categoriesRepo = koinInject<CategoryRepository>()
    val auth = koinInject<AuthSession>()
    val filePicker = koinInject<FilePicker>()
    val ocr = koinInject<ReceiptOcr>()
    // Resilient upload pipeline (D-22). Bound only when Supabase is configured; null on the offline build —
    // and when it's null we hide the receipt strip entirely rather than offer an attach that goes nowhere.
    val koin = getKoin()
    val uploadManager = remember { koin.getOrNull<ReceiptUploadManager>() }
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val categories by remember(gid) { categoriesRepo.observeCategories(gid) }.collectAsStateWithLifecycle(CategoryDefaults.all)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    // Picked before the expense exists; enqueued against the new expense id on save.
    var pickedReceipts by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    // Itemized scan pipeline (mirrors BillEditRoute) — feeds the "By what each had" body.
    var scanState by remember { mutableStateOf<ScanUiState>(ScanUiState.Idle) }
    var scanned by remember { mutableStateOf<EditBillState?>(null) }
    var scanFiles by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var billReceipts by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    // The group's most recent expense — its participant set seeds a *new* expense's default selection
    // (whoever was actually there last time, not the whole group). null = the flow hasn't emitted yet.
    val recentExpenses by remember(gid) { expenses.observeExpensesWithShares(gid) }.collectAsStateWithLifecycle(null)
    // Wait for the group's history to load before rendering — same "gate on first load" convention as
    // BillEditRoute/EditExpenseRoute — so the default participant set is computed exactly once, correctly.
    if (recentExpenses == null) return

    val participants = members.map { AddParticipantUi(it.userId.value, it.displayName ?: "Someone", it.userId == userId) }
        .ifEmpty { listOfNotNull(userId?.let { AddParticipantUi(it.value, "You", true) }) }
    val currency = group?.baseCurrency ?: "USD"
    val lastExpenseParticipantIds: Set<String> = recentExpenses?.firstOrNull()?.shares?.mapTo(HashSet()) { it.userId.value } ?: emptySet()

    fun runScan(files: List<PickedFile>) {
        if (files.isEmpty()) return
        scanFiles = files
        scanState = ScanUiState.Working(files.map { ScanPageUi(it.mimeType.contains("pdf", ignoreCase = true)) })
        scanJob = scope.launch {
            val ocrFiles = files.map { ReceiptOcrFile(it.bytes, it.mimeType) }
            scanState = when (val outcome = ocr.extract(ocrFiles)) {
                is ScanOutcome.Success -> {
                    scanned = outcome.draft.toAddEditState()
                    billReceipts = files
                    ScanUiState.Idle
                }
                ScanOutcome.NoReceiptFound -> ScanUiState.Failed(ScanErrorKind.NoReceiptFound)
                ScanOutcome.Offline -> ScanUiState.Failed(ScanErrorKind.Offline)
                ScanOutcome.Unavailable -> ScanUiState.Failed(ScanErrorKind.Unavailable)
                is ScanOutcome.Failed -> ScanUiState.Failed(ScanErrorKind.Error)
            }
        }
    }

    AddExpenseScreen(
        participants = participants,
        categories = categories,
        currencyCode = currency,
        saving = saving,
        lastExpenseParticipantIds = lastExpenseParticipantIds,
        receipts = pickedReceipts.map { PickedReceiptUi(it.mimeType.contains("pdf", ignoreCase = true)) },
        receiptsEnabled = uploadManager != null,
        scanState = scanState,
        scanned = scanned,
        attachedReceiptCount = billReceipts.size,
        onBack = onBack,
        onScanReceipt = { source ->
            scope.launch {
                val picked = filePicker.pick(source, PickKind.ImageOrPdf)
                runScan((picked as? AppResult.Ok)?.value.orEmpty())
            }
        },
        onCancelScan = { scanJob?.cancel(); scanState = ScanUiState.Idle },
        onRetryScan = { runScan(scanFiles) },
        onDismissScan = { scanState = ScanUiState.Idle },
        onAddPlaceholder = { name -> scope.launch { groups.addPlaceholder(gid, name, createdBy = userId) } },
        onPickReceipt = { source ->
            scope.launch {
                val picked = filePicker.pick(source, PickKind.ImageOrPdf)
                if (picked is AppResult.Ok) pickedReceipts = pickedReceipts + picked.value
            }
        },
        onRemoveReceipt = { i -> pickedReceipts = pickedReceipts.filterIndexed { idx, _ -> idx != i } },
        onSave = { submit ->
            val me = userId
            if (me != null && submit.shares.isNotEmpty()) {
                saving = true
                scope.launch {
                    val shares = submit.shares.map { s ->
                        NewShare(
                            userId = UserId(s.userId),
                            owedSubunits = s.owedSubunits,
                            shareUnits = s.units,
                            sharePercentage = s.percent,
                            shareExactSubunits = s.exactSubunits,
                        )
                    }
                    val outside = submit.payerOutsideName?.takeIf { it.isNotBlank() }
                    val input = NewExpense(
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
                    )
                    when (val result = expenses.addExpense(input)) {
                        is AppResult.Ok -> {
                            // Attach whatever the user picked; the pipeline compresses + uploads in the background.
                            if (pickedReceipts.isNotEmpty()) uploadManager?.enqueue(result.value.id, gid, pickedReceipts)
                            onSaved()
                        }
                        is AppResult.Err -> saving = false
                    }
                }
            }
        },
        onSaveItemized = { submit ->
            val me = userId ?: return@AddExpenseScreen
            saving = true
            scope.launch {
                val extras = BillExtrasInput(
                    taxSubunits = submit.taxSubunits,
                    gratuitySubunits = submit.gratuitySubunits,
                    tipSubunits = submit.tipSubunits,
                    tipSplitMode = TipSplitMode.EVEN, // tip is firmly an even split
                    discountSubunits = submit.discountSubunits,
                )
                val result = bills.createBill(
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
                        if (billReceipts.isNotEmpty()) uploadManager?.enqueue(result.value, gid, billReceipts)
                        onCreatedBill(result.value.value)
                    }
                    is AppResult.Err -> saving = false
                }
            }
        },
    )
}

/** OCR draft → the itemized editor's initial items + extras (never a name — the shared header owns that). */
private fun ReceiptDraft.toAddEditState(): EditBillState = EditBillState(
    title = "",
    items = items.map { editBillItemUi(null, it.label, it.quantity, it.lineTotalSubunits) }
        .ifEmpty { listOf(editBillItemUi(null, "", 1, 0L)) },
    taxText = if (taxSubunits == 0L) "" else format2dp(taxSubunits / 100.0),
    gratuityText = if (gratuitySubunits == 0L) "" else format2dp(gratuitySubunits / 100.0),
    tipText = if (tipSubunits == 0L) "" else format2dp(tipSubunits / 100.0),
    discountText = if (discountSubunits == 0L) "" else format2dp(discountSubunits / 100.0),
    verified = verified,
)

/** Edit expense, wired: prefills the split editor from the saved expense; Save replaces its shares. */
@OptIn(ExperimentalTime::class)
@Composable
fun EditExpenseRoute(groupId: String, expenseId: String, onBack: () -> Unit, onSaved: () -> Unit) {
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

    val ews = detail
    if (ews == null) {
        ExpenseDetailScreen(state = ExpenseDetailState.Loading, onBack = onBack)
        return
    }
    val e = ews.expense
    val currency = group?.baseCurrency ?: e.currency
    // Real members drive the participant chips; fall back to the share roster while members still load.
    val participants = members.map { AddParticipantUi(it.userId.value, it.displayName ?: "Someone", it.userId == userId) }
        .ifEmpty { ews.shares.map { AddParticipantUi(it.userId.value, "Someone", it.userId == userId) } }

    val prefill = remember(ews) {
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

    AddExpenseScreen(
        editing = true,
        participants = participants,
        categories = categories,
        currencyCode = currency,
        saving = saving,
        prefill = prefill,
        onBack = onBack,
        onAddPlaceholder = { name -> scope.launch { groups.addPlaceholder(gid, name, createdBy = userId) } },
        onSave = { submit ->
            if (submit.shares.isNotEmpty()) {
                saving = true
                scope.launch {
                    val shares = submit.shares.map { s ->
                        NewShare(
                            userId = UserId(s.userId),
                            owedSubunits = s.owedSubunits,
                            shareUnits = s.units,
                            sharePercentage = s.percent,
                            shareExactSubunits = s.exactSubunits,
                        )
                    }
                    val outside = submit.payerOutsideName?.takeIf { it.isNotBlank() }
                    val input = EditExpense(
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
                    when (expenses.editExpense(eid, input)) {
                        is AppResult.Ok -> onSaved()
                        is AppResult.Err -> saving = false
                    }
                }
            }
        },
    )
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
    fun nameOf(id: UserId?): String = when {
        id == null -> "Someone"
        id == userId -> "You"
        else -> nameByUser[id.value] ?: "Someone"
    }
    // An itemized bill's shares only cover what's been claimed *so far*; the rest of the bill total isn't
    // yet anyone's. Surface that gap as an explicit "Unclaimed" row so the split still sums to the total —
    // e.g. of 5 diners, if only Andrew + Bob have claimed, the detail shows Andrew, Bob, then Unclaimed.
    val isItemized = e.splitMode == SPLIT_MODE_ITEMIZED
    val unclaimedSubunits = if (isItemized) (e.amountSubunits - ews.shares.sumOf { it.owedSubunits }).coerceAtLeast(0L) else 0L
    val rows = ews.shares.map { s ->
        DetailShareUi(
            name = nameOf(s.userId),
            owedSubunits = s.owedSubunits,
            paidSubunits = s.owedSubunits - s.remainingSubunits,
            remainingSubunits = s.remainingSubunits,
            me = s.userId == userId,
            payer = s.userId == e.payerUserId,
        )
    } + if (unclaimedSubunits > 0L) {
        listOf(DetailShareUi(name = "Unclaimed", owedSubunits = unclaimedSubunits, paidSubunits = 0L, remainingSubunits = unclaimedSubunits))
    } else emptyList()

    // One "now" per data change keeps the relative stamps ("2h") stable within a frame.
    val now = remember(comments, receipts, history) { Clock.System.nowEpochMillis() }
    val commentUi = comments.map { cm ->
        CommentUi(cm.id.value, nameOf(cm.authorUserId), cm.body, relativeTimeLabel(cm.createdAt, now), me = cm.authorUserId == userId)
    }
    val receiptUi = receipts.map { r -> ReceiptUi(r.id.value, r.url, r.isPdf) }
    val pendingUi = pendingUploads.map { u ->
        ReceiptUploadUi(
            id = u.id,
            model = if (u.isPdf) null else "file://${u.localPath}",
            isPdf = u.isPdf,
            fraction = u.fraction,
            failed = u.status == ReceiptUploadStatus.FAILED,
        )
    }
    val historyUi = history.map { ev -> HistoryUi(historyText(ev, e.currency) { nameOf(it) }, relativeTimeLabel(ev.createdAt, now)) }
    val paymentUi = payments.map { s ->
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
private fun historyText(ev: HistoryEvent, currency: String, nameOf: (UserId?) -> String): String {
    val verb = when (ev.type) {
        HistoryEventType.CREATED -> "added this expense"
        HistoryEventType.EDITED -> "edited this expense"
        HistoryEventType.SETTLED -> "settled"
        HistoryEventType.SETTLEMENT_EDITED -> "corrected a payment"
        HistoryEventType.COMMENTED -> "commented"
        HistoryEventType.RECEIPT_ADDED -> "added a receipt"
        HistoryEventType.DELETED -> "deleted this expense"
    }
    val passive = when (ev.type) {
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
private fun renderDetail(detail: String, currency: String): String {
    val parts = detail.split(":")
    return when (parts.firstOrNull()) {
        "amt" -> parts.getOrNull(1)?.toLongOrNull()?.let { moneySubunits(it, currency) } ?: detail
        "edit" -> {
            val old = parts.getOrNull(1)?.toLongOrNull()
            val new = parts.getOrNull(2)?.toLongOrNull()
            if (old != null && new != null) "${moneySubunits(old, currency)} → ${moneySubunits(new, currency)}" else detail
        }
        else -> detail
    }
}

/** Short relative time ("now", "5m", "2h", "3d", "2w") for activity stamps. */
private fun relativeTimeLabel(thenMs: Long, nowMs: Long): String {
    val minutes = ((nowMs - thenMs).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 1_440 -> "${minutes / 60}h"
        minutes < 10_080 -> "${minutes / 1_440}d"
        else -> "${minutes / 10_080}w"
    }
}
