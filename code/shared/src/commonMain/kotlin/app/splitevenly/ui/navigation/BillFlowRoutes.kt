package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.expense.progress
import app.splitevenly.domain.repository.BillRepository
import app.splitevenly.ui.screen.bill.BillChooserScreen
import app.splitevenly.ui.screen.bill.BillChooserState
import app.splitevenly.ui.screen.expense.BillPickUi
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.koinInject

/**
 * The saved-bill chooser, wired (docs/features/split-by-items-flow.md §2 step 4). [justSaved] picks the
 * first-visit copy; every other entry is a returning visit and says so. Renders the skeleton, never
 * nothing, while the bill loads.
 */
@Composable
fun BillChooserRoute(
    expenseId: String,
    justSaved: Boolean,
    onBack: () -> Unit,
    onAssignOnPhone: () -> Unit,
    onShowQr: () -> Unit,
) {
    val bills = koinInject<BillRepository>()
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val bill by remember(eid) { bills.observeBill(eid) }.collectAsStateWithLifecycle(null)
    val state =
        bill?.let { view ->
            val p = view.progress()
            BillChooserState(
                title = view.expense.title,
                currency = view.expense.currency,
                totalSubunits = view.expense.amountSubunits,
                itemCount = p.itemCount,
                peopleCount = p.peopleCount,
                resolvedCount = p.resolvedCount,
                returning = !justSaved,
            )
        }
    BillChooserScreen(state = state, onBack = onBack, onAssignOnPhone = onAssignOnPhone, onShowQr = onShowQr)
}

/** Expense details' picking card for an itemized bill; null for a plain expense or while loading. */
@Composable
fun rememberBillPick(
    expenseId: ExpenseId,
    isItemized: Boolean,
    me: UserId?,
): BillPickUi? {
    val bills = koinInject<BillRepository>()
    val bill by remember(expenseId, isItemized) {
        if (isItemized) bills.observeBill(expenseId) else flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val view = bill ?: return null
    val p = view.progress()
    val assigned = view.tabByUser.values.sum()
    return BillPickUi(
        itemCount = p.itemCount,
        resolvedCount = p.resolvedCount,
        assignedSubunits = assigned,
        notAssignedSubunits = (view.expense.amountSubunits - assigned).coerceAtLeast(0L),
        meDone = me != null && view.participants.any { it.userId == me && it.doneAt != null },
    )
}
