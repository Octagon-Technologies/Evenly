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
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.localHour
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.core.time.todayUtc
import app.splitevenly.data.upload.ReceiptUploadManager
import app.splitevenly.data.upload.StagedReceipt
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.expense.BillExtrasInput
import app.splitevenly.domain.expense.BillView
import app.splitevenly.domain.expense.EditBill
import app.splitevenly.domain.expense.EditBillItem
import app.splitevenly.domain.expense.ItemStatus
import app.splitevenly.domain.expense.NewBill
import app.splitevenly.domain.expense.NewBillItem
import app.splitevenly.domain.expense.TipSplitMode
import app.splitevenly.domain.receipt.ReceiptDraft
import app.splitevenly.domain.receipt.ReceiptOcr
import app.splitevenly.domain.receipt.ReceiptOcrFile
import app.splitevenly.domain.receipt.ScanOutcome
import app.splitevenly.domain.receipt.printedHourOf
import app.splitevenly.domain.receipt.suggestBillTitle
import app.splitevenly.domain.repository.ActivityRepository
import app.splitevenly.domain.repository.BillRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.EvAnalytics
import app.splitevenly.platform.FilePicker
import app.splitevenly.platform.PdfRasterizer
import app.splitevenly.platform.PickKind
import app.splitevenly.platform.PickSource
import app.splitevenly.platform.PickedFile
import app.splitevenly.platform.SecureStorage
import app.splitevenly.ui.screen.bill.AssignRowUi
import app.splitevenly.ui.screen.bill.BillEditScreen
import app.splitevenly.ui.screen.bill.ClaimItemUi
import app.splitevenly.ui.screen.bill.ClaimParticipantUi
import app.splitevenly.ui.screen.bill.EditBillState
import app.splitevenly.ui.screen.bill.EditBillSubmit
import app.splitevenly.ui.screen.bill.ParticipantChipUi
import app.splitevenly.ui.screen.bill.ScanErrorKind
import app.splitevenly.ui.screen.bill.ScanPageUi
import app.splitevenly.ui.screen.bill.ScanUiState
import app.splitevenly.ui.screen.bill.analyticsKind
import app.splitevenly.ui.screen.bill.editBillItemUi
import app.splitevenly.ui.screen.bill.priceToSubunits
import app.splitevenly.ui.screen.bill.scanEditStats
import app.splitevenly.ui.screen.expense.PickedReceiptUi
import app.splitevenly.ui.screen.expense.format2dp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
fun BillEditRoute(
    groupId: String,
    expenseId: String?,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val filePicker = koinInject<FilePicker>()
    val cameraGate = rememberCameraPermissionGate()
    val ocr = koinInject<ReceiptOcr>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val currency = group?.baseCurrency ?: "USD"
    // Resilient upload pipeline (D-22). Bound only when Supabase is configured; null on the offline build.
    val koin = getKoin()
    val uploadManager = remember { koin.getOrNull<ReceiptUploadManager>() }
    val stagedPdf = rememberStagedPdfRenderers(uploadManager)
    // Pages already saved to the bill, so the scan surface shows what a re-scan would be adding to.
    val activity = koinInject<ActivityRepository>()
    val http = koinInject<HttpClient>()
    val rasterizer = remember { PdfRasterizer() }
    val remotePdfBytes = remember { mutableMapOf<String, ByteArray>() }
    suspend fun remotePdf(url: String): ByteArray? =
        remotePdfBytes[url] ?: runCatching { http.get(url).readRawBytes() }.getOrNull()?.also { remotePdfBytes[url] = it }
    val analytics = remember { koin.getOrNull<EvAnalytics>() }
    val scope = rememberCoroutineScope()
    // A new bill is created by its first valid auto-save; every later save edits that same bill.
    var billId by remember { mutableStateOf(expenseId) }
    var createdHere by remember { mutableStateOf(false) }
    val savedReceipts by remember(billId) {
        billId?.let { activity.observeReceipts(ExpenseId(it)) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val pendingUploadIds by remember(billId) {
        val id = billId
        val flow = if (id == null) null else uploadManager?.observe(ExpenseId(id))
        flow?.map { list -> list.mapTo(HashSet<String>()) { it.id } } ?: flowOf(emptySet<String>())
    }.collectAsStateWithLifecycle(emptySet<String>())
    var lastSubmit by remember { mutableStateOf<EditBillSubmit?>(null) }
    // Staged receipts already handed to the upload pipeline, so a later save never re-queues one.
    val attachedIds = remember { mutableSetOf<String>() }
    var scanState by remember { mutableStateOf<ScanUiState>(ScanUiState.Idle) }
    var scanned by remember { mutableStateOf<EditBillState?>(null) }
    // The exact pages the user picked. Retained so a failed scan can retry, and — on success — handed to
    // the upload pipeline on Save so the scanned receipt becomes a normal attachment on the expense.
    var scanFiles by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var attachedReceipts by remember { mutableStateOf<List<StagedReceipt>>(emptyList()) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    // Scan-funnel analytics bookkeeping — see AddExpenseRoute's twin of this pipeline.
    var scanSource by remember { mutableStateOf<PickSource?>(null) }
    var scanStartedAt by remember { mutableStateOf(0L) }

    // Staged bytes belong to this editor until a save attaches them. There is no BackHandler here, so
    // system back and swipe-back leave without ever reaching onBack — clean up on dispose instead, or an
    // abandoned scan sits in the sandbox forever.
    DisposableEffect(Unit) {
        onDispose { uploadManager?.discardStagedDetached(attachedReceipts.filter { it.id !in attachedIds }) }
    }

    val existing =
        if (expenseId != null) {
            remember(expenseId) { bills.observeBill(ExpenseId(expenseId)) }.collectAsStateWithLifecycle(null).value
        } else {
            null
        }

    // For edit, wait until the bill loads so the editor's initial state is correct.
    if (expenseId != null && existing == null) return

    // Run the pick → OCR round-trip as a cancellable job, mapping the typed ScanOutcome to sheet state.
    fun runScan(
        files: List<PickedFile>,
        source: PickSource,
        restage: Boolean = true,
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
        if (restage) scope.launch {
            // Pages from earlier scans stay: a second scan adds a page, it never throws the first away.
            attachedReceipts = attachedReceipts + uploadManager?.stage(files).orEmpty()
        }
        scanJob =
            scope.launch {
                val ocrFiles = files.map { ReceiptOcrFile(it.bytes, it.mimeType) }
                val outcome = ocr.extract(ocrFiles, groupId = gid.value)
                val durationMs = Clock.System.nowEpochMillis() - scanStartedAt
                scanState =
                    when (outcome) {
                        is ScanOutcome.Success -> {
                            scanned = outcome.draft.toEditState()
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

    /** Creates the bill on the first valid save, edits it after; returns the saved line ids in order. */
    // A page already handed to the upload pipeline shows as a saved receipt once it lands, so stop drawing the
    // local copy then, or the same page would appear twice.
    val displayAttached = attachedReceipts.filter { it.id !in attachedIds || it.id in pendingUploadIds }

    suspend fun saveBill(submit: EditBillSubmit): List<String>? {
        val me = userId ?: return null
        val extras =
            BillExtrasInput(
                taxSubunits = submit.taxSubunits,
                gratuitySubunits = submit.gratuitySubunits,
                tipSubunits = submit.tipSubunits,
                tipSplitMode = TipSplitMode.EVEN, // tip is firmly an even split
                discountSubunits = submit.discountSubunits,
                otherChargesSubunits = submit.otherChargesSubunits,
            )
        val outsidePayer = submit.payerOutsideName?.takeIf { it.isNotBlank() }
        val payer = if (outsidePayer != null) null else submit.payerUserId?.let { UserId(it) }
        // Neither set means the editor had no "Paid by" row at all, so the bill keeps its payer.
        val keepPayer = outsidePayer == null && payer == null
        val current = billId
        val saved: ExpenseId =
            if (current == null) {
                val result =
                    bills.createBill(
                        NewBill(
                            groupId = gid,
                            title = submit.title,
                            currency = currency,
                            expenseDate = Clock.System.todayUtc(),
                            payerUserId = if (outsidePayer != null) null else (payer ?: me),
                            payerOutsideName = outsidePayer,
                            createdBy = me,
                            items = submit.items.map { NewBillItem(it.label.trim(), it.quantity, priceToSubunits(it.totalText)) },
                            extras = extras,
                            participantUserIds = submit.participantIds.map { UserId(it) },
                        ),
                    )
                val id = (result as? AppResult.Ok)?.value ?: return null
                billId = id.value
                createdHere = true
                id
            } else {
                val eid = ExpenseId(current)
                val before = bills.observeBill(eid).first()
                val result =
                    bills.editBill(
                    eid,
                    EditBill(
                        title = submit.title,
                        expenseDate = before?.expense?.expenseDate ?: existing?.expense?.expenseDate ?: Clock.System.todayUtc(),
                        payerUserId = if (keepPayer) (before?.expense?.payerUserId ?: me) else payer,
                        payerOutsideName = if (keepPayer) before?.expense?.payerOutsideName else outsidePayer,
                        items = submit.items.map { EditBillItem(it.id, it.label.trim(), it.quantity, priceToSubunits(it.totalText)) },
                        extras = extras,
                        participantUserIds = submit.participantIds.map { UserId(it) },
                        editedBy = me,
                    ),
                )
                // A rejected edit must not light up "Changes saved"; the editor keeps the state and retries.
                if (result is AppResult.Err) return null
                eid
            }
        // Hand any newly staged pages to the upload pipeline exactly once.
        val fresh = attachedReceipts.filter { it.id !in attachedIds }
        if (fresh.isNotEmpty()) {
            attachedIds += fresh.map { it.id }
            uploadManager?.attach(saved, gid, fresh)
        }
        lastSubmit = submit
        return bills.observeBill(saved).first()?.items?.map { it.id }
    }

    BillEditScreen(
        editing = expenseId != null,
        initial = existing?.toEditState(),
        scanned = scanned,
        currencyCode = currency,
        scanState = scanState,
        attachedReceipts = displayAttached.map { it.toUi() },
        onRemoveAttachedReceipt = { i ->
            val dropped = displayAttached.getOrNull(i)
            attachedReceipts = attachedReceipts.filter { it.id != dropped?.id }
            dropped?.let {
                scope.launch {
                    // Already queued for upload: cancel the outbox row too, or it would fail on a missing file.
                    if (attachedIds.remove(it.id)) uploadManager?.cancel(it.id) else uploadManager?.discardStaged(listOf(it))
                }
            }
        },
        savedReceipts =
            savedReceipts.map {
                PickedReceiptUi(
                    id = it.id.value,
                    localPath = "",
                    model = if (it.isPdf) null else it.url,
                    isPdf = it.isPdf,
                    remoteUrl = it.url,
                    removable = false,
                )
            },
        // Staged pages are local files; saved ones are remote, and a PDF has to be downloaded to rasterize.
        loadPdfPageCount = { url ->
            if (url.startsWith("file://")) stagedPdf.pageCount(url) else remotePdf(url)?.let { rasterizer.pageCount(it) } ?: 0
        },
        renderPdfPage = { url, page, width ->
            if (url.startsWith("file://")) stagedPdf.renderPage(url, page, width) else remotePdf(url)?.let { rasterizer.renderPage(it, page, width) }
        },
        participants =
            members.map {
                ParticipantChipUi(
                    it.userId.value,
                    if (it.userId ==
                        userId
                    ) {
                        "You"
                    } else {
                        (it.displayName ?: "Someone")
                    },
                    it.userId == userId,
                )
            },
        initialSelectedIds = existing?.participants?.mapTo(HashSet()) { it.userId.value } ?: emptySet(),
        // A new bill is paid by whoever is entering it, which is who is holding the receipt.
        initialPayerId = existing?.expense?.payerUserId?.value ?: userId?.value.orEmpty(),
        initialOutsidePayerName = existing?.expense?.payerOutsideName,
        claimedItemsByUser = existing?.claimedItemsByUser().orEmpty(),
        onBack = {
            // Only a scan that was actually saved has a result to compare against.
            lastSubmit?.let { reportScanResultEdited(analytics, scanned, it.items, gid.value) }
            val id = billId
            if (createdHere && id != null) onCreated(id) else onBack()
        },
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
        onRetryScan = { scanSource?.let { runScan(scanFiles, it, restage = false) } },
        onDismissScan = { scanState = ScanUiState.Idle },
        onAddPerson = { name -> scope.launch { groups.addPlaceholder(gid, name, createdBy = userId) } },
        onSave = { submit -> saveBill(submit) },
    )

    // Last, so the gate lands over the screen rather than under it.
    CameraPermissionGateHost(cameraGate)
}

@OptIn(ExperimentalTime::class)
private fun ReceiptDraft.toEditState(): EditBillState =
    EditBillState(
        // A suggestion only: the screen applies it when the user has not typed a name yet.
        title = suggestBillTitle(merchantName, venueKind, printedHourOf(printedTime), Clock.System.localHour()).orEmpty(),
        // OCR now reports the line total directly (already inclusive of quantity) — pass it straight through.
        items =
            items
                .map { editBillItemUi(null, it.label, it.quantity, it.lineTotalSubunits) }
                .ifEmpty { listOf(editBillItemUi(null, "", 1, 0L)) },
        taxText = subunitsToText(taxSubunits),
        gratuityText = subunitsToText(gratuitySubunits),
        tipText = subunitsToText(tipSubunits),
        discountText = subunitsToText(discountSubunits),
        otherChargesText = subunitsToText(otherChargesSubunits),
        verified = verified,
    )

private fun BillView.toEditState(): EditBillState =
    EditBillState(
        title = expense.title,
        items =
            items
                .map { editBillItemUi(it.id, it.label, it.quantity, it.lineTotalSubunits) }
                .ifEmpty { listOf(editBillItemUi(null, "", 1, 0L)) },
        taxText = subunitsToText(extras.taxSubunits),
        gratuityText = subunitsToText(extras.gratuitySubunits),
        tipText = subunitsToText(extras.tipSubunits),
        discountText = subunitsToText(extras.discountSubunits),
        otherChargesText = subunitsToText(extras.otherChargesSubunits),
    )

/** How many distinct lines each person holds, counting a solo claim and a shared slice the same. It
 *  answers one question for the editor: does taking this person off the bill throw anything away? */
private fun BillView.claimedItemsByUser(): Map<String, Int> {
    val lines = HashMap<String, MutableSet<String>>()
    claims.forEach { lines.getOrPut(it.userId.value) { HashSet() }.add(it.itemId) }
    shares.forEach { lines.getOrPut(it.userId.value) { HashSet() }.add(it.itemId) }
    return lines.mapValues { (_, items) -> items.size }
}
