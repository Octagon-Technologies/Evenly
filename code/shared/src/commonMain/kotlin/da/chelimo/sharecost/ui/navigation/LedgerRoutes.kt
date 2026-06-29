package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.core.time.todayUtc
import da.chelimo.sharecost.data.upload.ReceiptUploadManager
import da.chelimo.sharecost.domain.activity.HistoryEvent
import da.chelimo.sharecost.domain.activity.HistoryEventType
import da.chelimo.sharecost.domain.activity.ReceiptUploadStatus
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.expense.CategoryDefaults
import da.chelimo.sharecost.domain.expense.EditExpense
import da.chelimo.sharecost.domain.expense.NewExpense
import da.chelimo.sharecost.domain.expense.NewShare
import da.chelimo.sharecost.domain.repository.ActivityRepository
import da.chelimo.sharecost.domain.repository.CategoryRepository
import da.chelimo.sharecost.domain.repository.ExpenseRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.platform.FilePicker
import da.chelimo.sharecost.platform.PdfRasterizer
import da.chelimo.sharecost.platform.PickKind
import da.chelimo.sharecost.platform.PickSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import da.chelimo.sharecost.ui.screen.expense.AddExpensePrefill
import da.chelimo.sharecost.ui.screen.expense.AddExpenseScreen
import da.chelimo.sharecost.ui.screen.expense.AddParticipantUi
import da.chelimo.sharecost.ui.screen.expense.CommentUi
import da.chelimo.sharecost.ui.screen.expense.DetailShareUi
import da.chelimo.sharecost.ui.screen.expense.ExpenseDetailScreen
import da.chelimo.sharecost.ui.screen.expense.ExpenseDetailState
import da.chelimo.sharecost.ui.screen.expense.HistoryUi
import da.chelimo.sharecost.ui.screen.expense.ReceiptUi
import da.chelimo.sharecost.ui.screen.expense.ReceiptUploadUi
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.screen.expense.SplitMode
import da.chelimo.sharecost.ui.screen.expense.format2dp
import da.chelimo.sharecost.ui.screen.group.GroupHomeScreen
import da.chelimo.sharecost.ui.screen.group.buildGroupExpenses
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Add expense, wired: real members as participants; Save persists the split the editor computed. */
@OptIn(ExperimentalTime::class)
@Composable
fun AddExpenseRoute(groupId: String, onBack: () -> Unit, onSaved: () -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val categoriesRepo = koinInject<CategoryRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val categories by remember(gid) { categoriesRepo.observeCategories(gid) }.collectAsStateWithLifecycle(CategoryDefaults.all)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    val participants = members.map { AddParticipantUi(it.userId.value, it.displayName ?: "Someone", it.userId == userId) }
        .ifEmpty { listOfNotNull(userId?.let { AddParticipantUi(it.value, "You", true) }) }
    val currency = group?.baseCurrency ?: "USD"

    AddExpenseScreen(
        participants = participants,
        categories = categories,
        currencyCode = currency,
        saving = saving,
        onBack = onBack,
        onAddPlaceholder = { name -> scope.launch { groups.addPlaceholder(gid, name) } },
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
                    when (expenses.addExpense(input)) {
                        is AppResult.Ok -> onSaved()
                        is AppResult.Err -> saving = false
                    }
                }
            }
        },
    )
}

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
        onAddPlaceholder = { name -> scope.launch { groups.addPlaceholder(gid, name) } },
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
    onDeleted: () -> Unit = {},
) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
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
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val comments by remember(eid) { activity.observeComments(eid) }.collectAsStateWithLifecycle(emptyList())
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
    val rows = ews.shares.map { s ->
        DetailShareUi(
            name = nameOf(s.userId),
            owedSubunits = s.owedSubunits,
            paidSubunits = s.owedSubunits - s.remainingSubunits,
            remainingSubunits = s.remainingSubunits,
            me = s.userId == userId,
            payer = s.userId == e.payerUserId,
        )
    }

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

    ExpenseDetailScreen(
        state = ExpenseDetailState.Content,
        title = e.title,
        category = "",
        payerName = if (e.payerUserId == null) (e.payerOutsideName ?: "Someone") else nameOf(e.payerUserId),
        dateLabel = e.expenseDate,
        amountSubunits = e.amountSubunits,
        remainingSubunits = ews.shares.sumOf { it.remainingSubunits },
        currencyCode = e.currency,
        splitLabel = "Split between ${ews.shares.size} · ${e.splitMode.lowercase()}",
        splitRows = rows,
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
        onEdit = onEdit,
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
