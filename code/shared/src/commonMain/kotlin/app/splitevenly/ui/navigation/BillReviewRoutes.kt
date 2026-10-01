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
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.expense.BillView
import app.splitevenly.domain.expense.progress
import app.splitevenly.domain.expense.receiptOrder
import app.splitevenly.domain.expense.unresolvedLines
import app.splitevenly.domain.repository.BillRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.ui.screen.bill.BillReviewScreen
import app.splitevenly.ui.screen.bill.BillReviewState
import app.splitevenly.ui.screen.bill.ReviewPersonUi
import app.splitevenly.ui.screen.bill.UnresolvedLineUi
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * "Review assignments", wired (docs/features/split-by-items-flow.md §4). Both partial exits and
 * `Finish splitting` go to [onExit] (expense details over group home). Only `I've picked all mine` and
 * `Finish splitting` write, and only the viewer's own done marker; navigation waits for that write, so a
 * failure stays here with a retry instead of reporting success first.
 */
@Composable
fun BillReviewRoute(
    groupId: String,
    expenseId: String,
    onBack: () -> Unit,
    onAssign: (itemId: String?) -> Unit,
    onExit: () -> Unit,
) {
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val bill by remember(eid) { bills.observeBill(eid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val me = userId
    val state = bill?.let { view -> reviewState(view, members.associate { it.userId.value to (it.displayName ?: "Someone") }, me) }

    fun markMineDone() {
        val who = me ?: return
        busy = true
        notice = null
        scope.launch {
            when (bills.markDone(eid, who, true)) {
                is AppResult.Ok -> {
                    onExit()
                }

                is AppResult.Err -> {
                    busy = false
                    notice = "Couldn't save that you're done. Your picks are safe. Tap to try again."
                }
            }
        }
    }

    BillReviewScreen(
        state = state,
        onBack = onBack,
        onAssign = onAssign,
        onPickMoreLater = onExit,
        onPickedAllMine = ::markMineDone,
        onFinish = ::markMineDone,
        busy = busy,
        notice = notice,
        onDismissNotice = { notice = null },
    )
}

private fun reviewState(
    view: BillView,
    nameByUser: Map<String, String>,
    me: UserId?,
): BillReviewState {
    val names = billNames(view, nameByUser, me)
    val items = view.toClaimItems(names::nameOf)
    val itemsById = items.associateBy { it.id }
    val doneIds = view.participants.filter { it.doneAt != null }.mapTo(HashSet()) { it.userId.value }
    val people =
        names.participants.map { p ->
            ReviewPersonUi(
                userId = p.userId,
                name = if (p.isMe) "You" else p.name,
                isMe = p.isMe,
                amountSubunits = view.tabByUser[UserId(p.userId)] ?: 0L,
                itemsLine = holdingsLine(view, p.userId),
                done = p.userId in doneIds,
            )
        }
    return BillReviewState(
        title = view.expense.title,
        currency = view.expense.currency,
        totalSubunits = view.expense.amountSubunits,
        items = items,
        unresolved =
            view.unresolvedLines().mapNotNull { line ->
                itemsById[line.itemId]?.let { UnresolvedLineUi(it, line.unassignedSubunits) }
            },
        people = people,
        notAssignedSubunits = (view.expense.amountSubunits - view.tabByUser.values.sum()).coerceAtLeast(0L),
        resolvedCount = view.progress().resolvedCount,
    )
}

/** "Garlic noodles, half of Spicy tuna rolls, 1 of Three lemonades": what makes up one person's total. */
private fun holdingsLine(
    view: BillView,
    uid: String,
): String {
    val byId = view.items.associateBy { it.id }
    return view
        .receiptOrder()
        .mapNotNull { id ->
            val item = byId[id] ?: return@mapNotNull null
            val solo = view.claims.filter { it.itemId == id && it.userId.value == uid }.sumOf { it.quantity }
            val portions =
                view.shares
                    .filter { it.itemId == id && it.portionId != null }
                    .groupBy { it.portionId }
                    .values
                    .filter { rows -> rows.any { it.userId.value == uid } }
            when {
                solo == 0 && portions.isEmpty() -> null
                portions.isEmpty() && solo >= item.quantity -> item.label
                portions.isEmpty() -> "$solo of ${item.label}"
                solo == 0 && portions.size == 1 && portions[0].first().quantity >= item.quantity && portions[0].size == 1 -> item.label
                solo == 0 && portions.size == 1 && portions[0].first().quantity >= item.quantity && portions[0].size == 2 -> "half of ${item.label}"
                else -> "share of ${item.label}"
            }
        }.joinToString(", ")
}
