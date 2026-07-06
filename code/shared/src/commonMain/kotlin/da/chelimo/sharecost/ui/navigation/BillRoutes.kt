package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.newId
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.todayUtc
import da.chelimo.sharecost.data.upload.ReceiptUploadManager
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.expense.BillExtrasInput
import da.chelimo.sharecost.domain.expense.BillView
import da.chelimo.sharecost.domain.expense.EditBill
import da.chelimo.sharecost.domain.expense.ItemStatus
import da.chelimo.sharecost.ui.screen.bill.AssignRowUi
import da.chelimo.sharecost.domain.expense.EditBillItem
import da.chelimo.sharecost.domain.expense.NewBill
import da.chelimo.sharecost.domain.expense.NewBillItem
import da.chelimo.sharecost.domain.expense.TipSplitMode
import da.chelimo.sharecost.domain.receipt.ReceiptDraft
import da.chelimo.sharecost.domain.receipt.ReceiptOcr
import da.chelimo.sharecost.domain.receipt.ReceiptOcrFile
import da.chelimo.sharecost.domain.receipt.ScanOutcome
import da.chelimo.sharecost.domain.repository.BillRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.platform.FilePicker
import da.chelimo.sharecost.platform.PickKind
import da.chelimo.sharecost.platform.PickedFile
import da.chelimo.sharecost.ui.screen.bill.BillClaimScreen
import da.chelimo.sharecost.ui.screen.bill.BillEditScreen
import da.chelimo.sharecost.ui.screen.bill.ClaimBillState
import da.chelimo.sharecost.ui.screen.bill.ClaimItemUi
import da.chelimo.sharecost.ui.screen.bill.ClaimParticipantUi
import da.chelimo.sharecost.ui.screen.bill.EditBillState
import da.chelimo.sharecost.ui.screen.bill.ParticipantChipUi
import da.chelimo.sharecost.ui.screen.bill.ScanErrorKind
import da.chelimo.sharecost.ui.screen.bill.ScanPageUi
import da.chelimo.sharecost.ui.screen.bill.ScanUiState
import da.chelimo.sharecost.ui.screen.bill.editBillItemUi
import da.chelimo.sharecost.ui.screen.bill.priceToSubunits
import da.chelimo.sharecost.ui.screen.expense.format2dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private fun subunitsToText(subunits: Long): String = if (subunits == 0L) "" else format2dp(subunits / 100.0)

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
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var scanState by remember { mutableStateOf<ScanUiState>(ScanUiState.Idle) }
    var scanned by remember { mutableStateOf<EditBillState?>(null) }
    // The exact pages the user picked. Retained so a failed scan can retry, and — on success — handed to
    // the upload pipeline on Save so the scanned receipt becomes a normal attachment on the expense.
    var scanFiles by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var attachedReceipts by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    val existing = if (expenseId != null) {
        remember(expenseId) { bills.observeBill(ExpenseId(expenseId)) }.collectAsStateWithLifecycle(null).value
    } else null

    // For edit, wait until the bill loads so the editor's initial state is correct.
    if (expenseId != null && existing == null) return

    // Run the pick → OCR round-trip as a cancellable job, mapping the typed ScanOutcome to sheet state.
    fun runScan(files: List<PickedFile>) {
        if (files.isEmpty()) return
        scanFiles = files
        scanState = ScanUiState.Working(files.map { ScanPageUi(it.mimeType.contains("pdf", ignoreCase = true)) })
        scanJob = scope.launch {
            val ocrFiles = files.map { ReceiptOcrFile(it.bytes, it.mimeType) }
            scanState = when (val outcome = ocr.extract(ocrFiles)) {
                is ScanOutcome.Success -> {
                    scanned = outcome.draft.toEditState()
                    attachedReceipts = files
                    ScanUiState.Idle
                }
                ScanOutcome.NoReceiptFound -> ScanUiState.Failed(ScanErrorKind.NoReceiptFound)
                ScanOutcome.Offline -> ScanUiState.Failed(ScanErrorKind.Offline)
                ScanOutcome.Unavailable -> ScanUiState.Failed(ScanErrorKind.Unavailable)
                is ScanOutcome.Failed -> ScanUiState.Failed(ScanErrorKind.Error)
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
            scope.launch {
                val picked = filePicker.pick(source, PickKind.ImageOrPdf)
                runScan((picked as? AppResult.Ok)?.value.orEmpty())
            }
        },
        onCancelScan = {
            scanJob?.cancel()
            scanState = ScanUiState.Idle
        },
        onRetryScan = { runScan(scanFiles) },
        onDismissScan = { scanState = ScanUiState.Idle },
        onAddPerson = { name -> scope.launch { groups.addPlaceholder(gid, name) } },
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
fun BillClaimRoute(groupId: String, expenseId: String, onBack: () -> Unit, onEditBill: () -> Unit, onAskGroup: () -> Unit) {
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val bill by remember(eid) { bills.observeBill(eid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

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
            scope.launch { bills.setPortion(eid, itemId, "${itemId}__all", memberIds.map { UserId(it) }, qtyOf(itemId), who) }
        },
        onSetSolo = { itemId, uid, quantity -> scope.launch { bills.setClaim(eid, itemId, UserId(uid), quantity) } },
        onAddPortion = { itemId, memberIds, quantity ->
            val who = me ?: return@BillClaimScreen
            scope.launch { bills.setPortion(eid, itemId, newId(), memberIds.map { UserId(it) }, quantity, who) }
        },
        onEditPortion = { itemId, pid, memberIds, quantity ->
            val who = me ?: return@BillClaimScreen
            scope.launch { bills.setPortion(eid, itemId, pid, memberIds.map { UserId(it) }, quantity, who) }
        },
        onRemovePortion = { itemId, pid ->
            val who = me ?: return@BillClaimScreen
            scope.launch { bills.setPortion(eid, itemId, pid, emptyList(), 0, who) }
        },
        onRemoveSolo = { itemId, uid -> scope.launch { bills.setClaim(eid, itemId, UserId(uid), 0) } },
        onClearEveryone = { itemId ->
            val who = me ?: return@BillClaimScreen
            scope.launch { bills.setPortion(eid, itemId, "${itemId}__all", emptyList(), 0, who) }
        },
        onAddPerson = { name -> scope.launch { groups.addPlaceholder(gid, name) } },
        onEditBill = onEditBill,
        onDone = {
            val who = me ?: return@BillClaimScreen
            scope.launch { bills.markDone(eid, who, true); onBack() }
        },
    )
}

private fun ReceiptDraft.toEditState(): EditBillState = EditBillState(
    title = "",
    // OCR reports a per-unit price; the editor's truth is the line total (unit × quantity).
    items = items.map { editBillItemUi(null, it.label, it.quantity, it.unitPriceSubunits * it.quantity) }
        .ifEmpty { listOf(editBillItemUi(null, "", 1, 0L)) },
    taxText = subunitsToText(taxSubunits),
    gratuityText = subunitsToText(gratuitySubunits),
    tipText = subunitsToText(tipSubunits),
    discountText = subunitsToText(discountSubunits),
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
