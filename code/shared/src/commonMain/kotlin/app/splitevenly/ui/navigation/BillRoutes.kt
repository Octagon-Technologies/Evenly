package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.core.time.todayUtc
import app.splitevenly.data.upload.ReceiptUploadManager
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.expense.BillExtrasInput
import app.splitevenly.domain.expense.BillView
import app.splitevenly.domain.expense.EditBill
import app.splitevenly.domain.expense.ItemStatus
import app.splitevenly.ui.screen.bill.AssignRowUi
import app.splitevenly.domain.expense.EditBillItem
import app.splitevenly.domain.expense.NewBill
import app.splitevenly.domain.expense.NewBillItem
import app.splitevenly.domain.expense.TipSplitMode
import app.splitevenly.domain.receipt.ReceiptDraft
import app.splitevenly.domain.receipt.ReceiptOcr
import app.splitevenly.domain.receipt.ReceiptOcrFile
import app.splitevenly.domain.receipt.ScanOutcome
import app.splitevenly.domain.repository.BillRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.EvAnalytics
import app.splitevenly.platform.FilePicker
import app.splitevenly.platform.PickKind
import app.splitevenly.platform.PickSource
import app.splitevenly.platform.PickedFile
import app.splitevenly.platform.SecureStorage
import app.splitevenly.ui.screen.bill.BillClaimScreen
import app.splitevenly.ui.screen.bill.BillEditScreen
import app.splitevenly.ui.screen.bill.ClaimBillState
import app.splitevenly.ui.screen.bill.ClaimItemUi
import app.splitevenly.ui.screen.bill.ClaimParticipantUi
import app.splitevenly.ui.screen.bill.EditBillState
import app.splitevenly.ui.screen.bill.ParticipantChipUi
import app.splitevenly.ui.screen.bill.ScanErrorKind
import app.splitevenly.ui.screen.bill.ScanPageUi
import app.splitevenly.ui.screen.bill.ScanUiState
import app.splitevenly.ui.screen.bill.analyticsKind
import app.splitevenly.ui.screen.bill.editBillItemUi
import app.splitevenly.ui.screen.bill.priceToSubunits
import app.splitevenly.ui.screen.bill.scanEditStats
import app.splitevenly.ui.screen.expense.format2dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private fun subunitsToText(subunits: Long): String = if (subunits == 0L) "" else format2dp(subunits / 100.0)

/** The trust metric: how much of a scan's OCR draft survived to what actually got saved. No-op when this
 *  save wasn't preceded by a successful scan. */
private fun reportScanResultEdited(
    analytics: EvAnalytics?,
    scanned: EditBillState?,
    savedItems: List<app.splitevenly.ui.screen.bill.EditBillItemUi>,
    groupId: String,
) {
    val s = scanned ?: return
    val (changed, total) = scanEditStats(s.items, savedItems)
    analytics?.capture(
        AnalyticsEvents.SCAN_RESULT_EDITED,
        mapOf("items_changed" to changed, "items_total" to total, "group_id" to groupId),
    )
}

/** Create or edit a bill (the menu + extras). On create, lands on the live claim screen. */
@OptIn(ExperimentalTime::class)
@Composable
fun BillEditRoute(groupId: String, expenseId: String?, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val filePicker = koinInject<FilePicker>()
    val ocr = koinInject<ReceiptOcr>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val currency = group?.baseCurrency ?: "USD"
    // Resilient upload pipeline (D-22). Bound only when Supabase is configured; null on the offline build.
    val koin = getKoin()
    val uploadManager = remember { koin.getOrNull<ReceiptUploadManager>() }
    val analytics = remember { koin.getOrNull<EvAnalytics>() }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var scanState by remember { mutableStateOf<ScanUiState>(ScanUiState.Idle) }
    var scanned by remember { mutableStateOf<EditBillState?>(null) }
    // The exact pages the user picked. Retained so a failed scan can retry, and — on success — handed to
    // the upload pipeline on Save so the scanned receipt becomes a normal attachment on the expense.
    var scanFiles by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var attachedReceipts by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    // Scan-funnel analytics bookkeeping — see AddExpenseRoute's twin of this pipeline.
    var scanSource by remember { mutableStateOf<PickSource?>(null) }
    var scanStartedAt by remember { mutableStateOf(0L) }

    val existing = if (expenseId != null) {
        remember(expenseId) { bills.observeBill(ExpenseId(expenseId)) }.collectAsStateWithLifecycle(null).value
    } else null

    // For edit, wait until the bill loads so the editor's initial state is correct.
    if (expenseId != null && existing == null) return

    // Run the pick → OCR round-trip as a cancellable job, mapping the typed ScanOutcome to sheet state.
    fun runScan(files: List<PickedFile>, source: PickSource) {
        if (files.isEmpty()) return
        scanFiles = files
        scanSource = source
        scanStartedAt = Clock.System.nowEpochMillis()
        scanState = ScanUiState.Working(files.map { ScanPageUi(it.mimeType.contains("pdf", ignoreCase = true)) })
        analytics?.capture(
            AnalyticsEvents.SCAN_STARTED,
            mapOf("page_count" to files.size, "source" to source.name.lowercase(), "group_id" to gid.value),
        )
        scanJob = scope.launch {
            val ocrFiles = files.map { ReceiptOcrFile(it.bytes, it.mimeType) }
            val outcome = ocr.extract(ocrFiles, groupId = gid.value)
            val durationMs = Clock.System.nowEpochMillis() - scanStartedAt
            scanState = when (outcome) {
                is ScanOutcome.Success -> {
                    scanned = outcome.draft.toEditState()
                    attachedReceipts = files
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
                    val kind = when (outcome) {
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

    BillEditScreen(
        editing = expenseId != null,
        initial = existing?.toEditState(),
        scanned = scanned,
        currencyCode = currency,
        saving = saving,
        scanState = scanState,
        attachedReceiptCount = attachedReceipts.size,
        participants = members.map { ParticipantChipUi(it.userId.value, if (it.userId == userId) "You" else (it.displayName ?: "Someone"), it.userId == userId) },
        initialSelectedIds = existing?.participants?.mapTo(HashSet()) { it.userId.value } ?: emptySet(),
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
        onDismissScan = { scanState = ScanUiState.Idle },
        onAddPerson = { name -> scope.launch { groups.addPlaceholder(gid, name, createdBy = userId) } },
        onSave = { submit ->
            val me = userId ?: return@BillEditScreen
            saving = true
            scope.launch {
                val extras = BillExtrasInput(
                    taxSubunits = submit.taxSubunits,
                    gratuitySubunits = submit.gratuitySubunits,
                    tipSubunits = submit.tipSubunits,
                    tipSplitMode = TipSplitMode.EVEN, // tip is firmly an even split
                    discountSubunits = submit.discountSubunits,
                )
                if (expenseId == null) {
                    val result = bills.createBill(
                        NewBill(
                            groupId = gid,
                            title = submit.title,
                            currency = currency,
                            expenseDate = Clock.System.todayUtc(),
                            payerUserId = me,
                            createdBy = me,
                            items = submit.items.map { NewBillItem(it.label.trim(), it.quantity, priceToSubunits(it.totalText)) },
                            extras = extras,
                            participantUserIds = submit.participantIds.map { UserId(it) },
                        ),
                    )
                    when (result) {
                        is AppResult.Ok -> {
                            // The scanned pages ride along as the expense's receipt (background upload).
                            if (attachedReceipts.isNotEmpty()) uploadManager?.enqueue(result.value, gid, attachedReceipts)
                            reportScanResultEdited(analytics, scanned, submit.items, gid.value)
                            onCreated(result.value.value)
                        }
                        is AppResult.Err -> saving = false
                    }
                } else {
                    bills.editBill(
                        ExpenseId(expenseId),
                        EditBill(
                            title = submit.title,
                            expenseDate = existing?.expense?.expenseDate ?: Clock.System.todayUtc(),
                            payerUserId = existing?.expense?.payerUserId ?: me,
                            items = submit.items.map { EditBillItem(it.id, it.label.trim(), it.quantity, priceToSubunits(it.totalText)) },
                            extras = extras,
                            participantUserIds = submit.participantIds.map { UserId(it) },
                            editedBy = me,
                        ),
                    )
                    if (attachedReceipts.isNotEmpty()) uploadManager?.enqueue(ExpenseId(expenseId), gid, attachedReceipts)
                    reportScanResultEdited(analytics, scanned, submit.items, gid.value)
                    onBack()
                }
            }
        },
    )
}

/** The assign screen — "who had what?". Assignments (incl. for people without the app) go through the
 *  conflict-free claim/portion writes; the tab + per-line amounts derive in real time. [onAskGroup] is
 *  retired but kept in the signature to avoid churning the NavHost. */
@Composable
fun BillClaimRoute(
    groupId: String,
    expenseId: String,
    onBack: () -> Unit,
    onEditBill: () -> Unit,
    onAskGroup: () -> Unit,
    onReviewEdits: () -> Unit = {},
    onWhoIsLeft: () -> Unit = {},
    onShareLink: () -> Unit = {},
) {
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val storage = koinInject<SecureStorage>()
    val gid = remember(groupId) { GroupId(groupId) }
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val bill by remember(eid) { bills.observeBill(eid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    // Web guests' menu changes (WEB_CLAIM_SPEC.md §2.7). All of them have already applied, so the
    // banner counts what this device has not SEEN, not what is undecided: there is nothing to decide.
    val pendingEdits by remember(eid) { bills.observePendingEdits(eid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Device-local (not synced) "how many times has this device opened the assign screen" — the how-to
    // guide auto-expands only on the first couple of visits, then recedes to a one-liner.
    var guideAutoOpen by remember { mutableStateOf(false) }
    // Set when an assignment didn't land, so the screen can say so instead of looking like it worked.
    var assignNotice by remember { mutableStateOf<String?>(null) }
    // Device-local read marker for the changes banner, written by BillReviewEditsRoute on the way out.
    // Re-read on every resume so the banner clears when the payer comes back from that screen.
    var changesSeenAt by remember(eid) { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val seen = storage.getString(CLAIM_GUIDE_OPENS_KEY)?.toIntOrNull() ?: 0
        guideAutoOpen = seen < 2
        storage.putString(CLAIM_GUIDE_OPENS_KEY, (seen + 1).toString())
    }
    LaunchedEffect(eid, pendingEdits) {
        changesSeenAt = storage.getString(changesSeenKey(eid.value))?.toLongOrNull() ?: 0L
    }

    val view = bill ?: return
    val me = userId
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    fun nameOf(uid: String): String = if (me != null && uid == me.value) "You" else (nameByUser[uid] ?: "Someone")

    val participants = view.participants.map { it.userId.value }
        .ifEmpty { members.map { it.userId.value } }
        .distinct()
        .map { ClaimParticipantUi(it, nameByUser[it] ?: "Someone", me != null && it == me.value) }

    val claimsByItem = view.claims.groupBy { it.itemId }
    val portionsByItemPortion = view.shares.filter { it.portionId != null }.groupBy { it.itemId to it.portionId!! }

    val items = view.items.map { item ->
        val perUnit = if (item.quantity <= 0) item.lineTotalSubunits else (item.lineTotalSubunits + item.quantity / 2) / item.quantity
        val soloRows = claimsByItem[item.id].orEmpty().map { claim ->
            AssignRowUi(null, listOf(claim.userId.value), listOf(nameOf(claim.userId.value)), claim.quantity, perUnit * claim.quantity)
        }
        val portionRows = portionsByItemPortion.filterKeys { it.first == item.id }.map { (key, rows) ->
            AssignRowUi(key.second, rows.map { it.userId.value }, rows.map { nameOf(it.userId.value) }, rows.first().quantity, perUnit * rows.first().quantity)
        }
        ClaimItemUi(
            id = item.id,
            label = item.label,
            quantity = item.quantity,
            lineTotalSubunits = item.lineTotalSubunits,
            rows = soloRows + portionRows,
            status = view.reconcile.firstOrNull { it.itemId == item.id }?.status ?: ItemStatus.UNCLAIMED,
        )
    }

    val totals = participants.map { p -> p to (view.tabByUser[UserId(p.userId)] ?: 0L) }
    val state = ClaimBillState(
        title = view.expense.title,
        currency = view.expense.currency,
        totals = totals,
        items = items,
        participants = participants,
        myUserId = me?.value,
    )

    fun qtyOf(itemId: String) = view.items.firstOrNull { it.id == itemId }?.quantity ?: 1
    BillClaimScreen(
        state = state,
        onBack = onBack,
        onSetEveryone = { itemId, memberIds ->
            val who = me ?: return@BillClaimScreen
            scope.launch {
                // Adding someone to a line another person already claimed has to go through the server
                // (it converts their solo claim into a shared portion). If that call can't be made, the
                // person is NOT on the line, and saying nothing would leave the tap looking like it worked.
                if (bills.setPortion(eid, itemId, "${itemId}__all", memberIds.map { UserId(it) }, qtyOf(itemId), who) is AppResult.Err) {
                    assignNotice = "Couldn't save that. Check your connection and tap again."
                }
            }
        },
        onClearEveryone = { itemId ->
            val who = me ?: return@BillClaimScreen
            scope.launch { bills.setPortion(eid, itemId, "${itemId}__all", emptyList(), 0, who) }
        },
        notice = assignNotice,
        onDismissNotice = { assignNotice = null },
        // Full teardown + rebuild of the item's assignment, now a SINGLE atomic repo call (#15): the repo
        // tears down claims/portions and writes one quantity-1 portion per serving in ONE DB transaction,
        // so navigating away mid-flight can't leave the item wiped half-way (the old per-slot sequence of
        // separate calls could cancel between teardown and rebuild).
        onSetServings = { itemId, servings ->
            val who = me ?: return@BillClaimScreen
            scope.launch { bills.setServings(eid, itemId, servings.map { slot -> slot.map { UserId(it) } }, who) }
        },
        onAddPerson = { name -> scope.launch { groups.addPlaceholder(gid, name, createdBy = userId) } },
        onEditBill = onEditBill,
        onDone = {
            val who = me ?: return@BillClaimScreen
            scope.launch { bills.markDone(eid, who, true); onBack() }
        },
        guideAutoOpen = guideAutoOpen,
        onShareLink = onShareLink,
        onWhoIsLeft = onWhoIsLeft,
        onReviewEdits = onReviewEdits,
        unseenChangeCount = pendingEdits.count { it.proposedAt > changesSeenAt },
        changeCount = pendingEdits.size,
        // "Claiming" here means a claim or a portion membership exists, NOT that they tapped "I'm done":
        // done is a nudge-silencer, not a resolution (data/AGENTS.md), and a payer looking for who still
        // owes them an answer wants the people with nothing on the bill.
        stillToClaimCount = view.participants.count { p ->
            view.claims.none { it.userId == p.userId } && view.shares.none { it.userId == p.userId }
        },
    )
}

/** Device-local key counting how many times the assign screen has been opened (gates the how-to guide). */
private const val CLAIM_GUIDE_OPENS_KEY = "claim_guide_opens"

private fun ReceiptDraft.toEditState(): EditBillState = EditBillState(
    title = "",
    // OCR now reports the line total directly (already inclusive of quantity) — pass it straight through.
    items = items.map { editBillItemUi(null, it.label, it.quantity, it.lineTotalSubunits) }
        .ifEmpty { listOf(editBillItemUi(null, "", 1, 0L)) },
    taxText = subunitsToText(taxSubunits),
    gratuityText = subunitsToText(gratuitySubunits),
    tipText = subunitsToText(tipSubunits),
    discountText = subunitsToText(discountSubunits),
    verified = verified,
)

private fun BillView.toEditState(): EditBillState = EditBillState(
    title = expense.title,
    items = items.map { editBillItemUi(it.id, it.label, it.quantity, it.lineTotalSubunits) }
        .ifEmpty { listOf(editBillItemUi(null, "", 1, 0L)) },
    taxText = subunitsToText(extras.taxSubunits),
    gratuityText = subunitsToText(extras.gratuitySubunits),
    tipText = subunitsToText(extras.tipSubunits),
    discountText = subunitsToText(extras.discountSubunits),
)
