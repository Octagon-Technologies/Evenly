package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.expense.AssignOrder
import app.splitevenly.domain.expense.AssignSession
import app.splitevenly.domain.expense.BillView
import app.splitevenly.domain.expense.ItemStatus
import app.splitevenly.domain.expense.progress
import app.splitevenly.domain.expense.receiptOrder
import app.splitevenly.domain.expense.resolvedItemIds
import app.splitevenly.domain.repository.BillRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.platform.SecureStorage
import app.splitevenly.ui.screen.bill.AssignRowUi
import app.splitevenly.ui.screen.bill.AssignScreenState
import app.splitevenly.ui.screen.bill.BillAssignLoading
import app.splitevenly.ui.screen.bill.BillAssignScreen
import app.splitevenly.ui.screen.bill.ClaimItemUi
import app.splitevenly.ui.screen.bill.ClaimParticipantUi
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * This visit's accordion state, held on the ClaimBill back-stack entry so it survives a push (edit
 * bill, QR, progress) and Back, and is discarded when the person leaves the bill flow. A `remember` in
 * the wrapper would not: a push disposes the destination underneath (ui/AGENTS.md).
 */
class AssignSessionHolder : ViewModel() {
    var session by mutableStateOf<AssignSession?>(null)
}

/**
 * "Assign the items", wired (docs/features/split-by-items-flow.md §3). Every bill emission, local or
 * remote, is folded through [AssignOrder.reconcile], which leaves the open card where it is.
 * [focusItemId] opens that line first (the progress screen's "Assign it").
 */
@Composable
fun BillAssignRoute(
    groupId: String,
    expenseId: String,
    focusItemId: String?,
    // A later "Assign it" aimed at this same visit (review or progress popped back to it). Consumed once.
    focusRequest: String?,
    onFocusConsumed: () -> Unit,
    onBack: () -> Unit,
    onReviewEdits: () -> Unit,
    onReview: () -> Unit,
) {
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val storage = koinInject<SecureStorage>()
    val gid = remember(groupId) { GroupId(groupId) }
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val holder = viewModel { AssignSessionHolder() }
    val bill by remember(eid) { bills.observeBill(eid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val pendingEdits by remember(eid) { bills.observePendingEdits(eid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var changesSeenAt by remember(eid) { mutableStateOf(0L) }
    LaunchedEffect(eid, pendingEdits) {
        changesSeenAt = storage.getString(changesSeenKey(eid.value))?.toLongOrNull() ?: 0L
    }

    LaunchedEffect(bill) {
        val view = bill ?: return@LaunchedEffect
        val order = view.receiptOrder()
        val resolved = view.resolvedItemIds()
        val current = holder.session
        if (current == null) {
            holder.session = AssignOrder.start(order, resolved, focusItemId)
            return@LaunchedEffect
        }
        val next = AssignOrder.reconcile(current, order, resolved)
        if (current.activeItemId != null && next.activeItemId == null) {
            notice = "That item was removed from the bill, so it is no longer open."
        }
        holder.session = next
    }

    LaunchedEffect(focusRequest, holder.session != null, bill != null) {
        val id = focusRequest ?: return@LaunchedEffect
        val view = bill ?: return@LaunchedEffect
        val current = holder.session ?: return@LaunchedEffect
        holder.session = AssignOrder.open(current, id, view.receiptOrder(), view.resolvedItemIds())
        onFocusConsumed()
    }

    val view = bill
    val session = holder.session
    if (view == null || session == null) {
        BillAssignLoading(onBack)
        return
    }
    val me = userId
    val names = billNames(view, members.associate { it.userId.value to (it.displayName ?: "Someone") }, me)
    val order = view.receiptOrder()
    val layout = AssignOrder.layout(session, order)
    val state =
        AssignScreenState(
            title = view.expense.title,
            currency = view.expense.currency,
            totalSubunits = view.expense.amountSubunits,
            items = view.toClaimItems(names::nameOf),
            completedIds = layout.completed,
            unfinishedIds = layout.unfinished,
            activeItemId = layout.activeItemId,
            participants = names.participants,
            resolvedCount = view.progress().resolvedCount,
            groupPeople =
                members.map {
                    ClaimParticipantUi(it.userId.value, it.displayName ?: "Someone", me != null && it.userId == me)
                },
        )

    fun qtyOf(itemId: String) = view.items.firstOrNull { it.id == itemId }?.quantity ?: 1
    BillAssignScreen(
        state = state,
        onBack = onBack,
        onOpenItem = { id -> holder.session = AssignOrder.open(session, id, order, view.resolvedItemIds()) },
        onSetMembers = { itemId, memberIds ->
            val who = me ?: return@BillAssignScreen
            scope.launch {
                // Adding someone to a line another person already claimed needs the server (it converts
                // their solo claim into a shared portion). If that can't be made, the chip must not look
                // selected, so the failure is said rather than swallowed.
                val result =
                    bills.setPortion(
                        eid,
                        itemId,
                        "${itemId}__all",
                        memberIds.map { UserId(it) },
                        if (memberIds.isEmpty()) 0 else qtyOf(itemId),
                        who,
                    )
                if (result is AppResult.Err) notice = "Couldn't save that. Check your connection and tap again."
            }
        },
        // One atomic teardown + rebuild of the line's servings (BillRepository.setServings, #15).
        onSetServings = { itemId, servings ->
            val who = me ?: return@BillAssignScreen
            scope.launch {
                if (bills.setServings(eid, itemId, servings.map { slot -> slot.map { UserId(it) } }, who) is AppResult.Err) {
                    notice = "Couldn't save those servings. Check your connection and try again."
                }
            }
        },
        // The roster is the bill's participants, so a group member or a new placeholder only shows up
        // here once they join the bill. A bill with no participant rows falls back to the whole group, so
        // that roster is written down first or adding one person would hide everyone else.
        onPickPeople = { itemId, pick ->
            val who = me ?: return@BillAssignScreen
            scope.launch {
                val created = mutableListOf<UserId>()
                for (name in pick.newNames) {
                    val added = groups.addPlaceholder(gid, name, createdBy = userId)
                    if (added !is AppResult.Ok) {
                        notice = "Couldn't add $name. Check your connection and try again."
                        return@launch
                    }
                    created += added.value.userId
                }
                val ids = pick.userIds.map { UserId(it) } + created
                val onBill = names.participants.map { it.userId }.toSet()
                val joining = ids.filter { it.value !in onBill }
                if (joining.isNotEmpty() && view.participants.isEmpty()) {
                    names.participants.forEach { bills.setParticipant(eid, UserId(it.userId), included = true) }
                }
                joining.forEach { bills.setParticipant(eid, it, included = true) }
                if (qtyOf(itemId) > 1) return@launch
                if (bills.setPortion(eid, itemId, "${itemId}__all", ids, if (ids.isEmpty()) 0 else qtyOf(itemId), who) is AppResult.Err) {
                    notice = "Couldn't save that. Check your connection and tap again."
                }
            }
        },
        onReviewEdits = onReviewEdits,
        onReview = onReview,
        unseenChangeCount = pendingEdits.count { it.proposedAt > changesSeenAt },
        notice = notice,
        onDismissNotice = { notice = null },
    )
}

/** Display names for one bill: the viewer is "You", and the roster falls back to the group. */
internal class BillNames(
    val participants: List<ClaimParticipantUi>,
    private val nameByUser: Map<String, String>,
    private val me: UserId?,
) {
    fun nameOf(uid: String): String = if (me != null && uid == me.value) "You" else (nameByUser[uid] ?: "Someone")
}

internal fun billNames(
    view: BillView,
    nameByUser: Map<String, String>,
    me: UserId?,
): BillNames {
    val ids =
        view.participants
            .map { it.userId.value }
            .ifEmpty { nameByUser.keys.toList() }
            .distinct()
    val participants = ids.map { ClaimParticipantUi(it, nameByUser[it] ?: "Someone", me != null && it == me.value) }
    return BillNames(participants, nameByUser, me)
}

/** The bill's lines in receipt order as who-had-what rows, numbered as printed. */
internal fun BillView.toClaimItems(nameOf: (String) -> String): List<ClaimItemUi> {
    val claimsByItem = claims.groupBy { it.itemId }
    val portions = shares.filter { it.portionId != null }.groupBy { it.itemId to it.portionId!! }
    val statusById = reconcile.associate { it.itemId to it.status }
    val byId = items.associateBy { it.id }
    return receiptOrder().mapIndexedNotNull { index, id ->
        val item = byId[id] ?: return@mapIndexedNotNull null
        val perUnit = if (item.quantity <= 0) item.lineTotalSubunits else (item.lineTotalSubunits + item.quantity / 2) / item.quantity
        val solo =
            claimsByItem[item.id].orEmpty().map { claim ->
                AssignRowUi(null, listOf(claim.userId.value), listOf(nameOf(claim.userId.value)), claim.quantity, perUnit * claim.quantity)
            }
        val shared =
            portions.filterKeys { it.first == item.id }.map { (key, rows) ->
                AssignRowUi(
                    key.second,
                    rows.map { it.userId.value },
                    rows.map { nameOf(it.userId.value) },
                    rows.first().quantity,
                    perUnit * rows.first().quantity,
                )
            }
        ClaimItemUi(
            id = item.id,
            label = item.label,
            quantity = item.quantity,
            lineTotalSubunits = item.lineTotalSubunits,
            rows = solo + shared,
            status = statusById[item.id] ?: ItemStatus.UNCLAIMED,
            number = index + 1,
        )
    }
}
