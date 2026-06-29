package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.repository.ExpenseRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.domain.repository.SettlementRepository
import da.chelimo.sharecost.domain.settlement.NewSettlement
import da.chelimo.sharecost.domain.settlement.PaymentApp
import da.chelimo.sharecost.domain.settlement.buildDeepLink
import da.chelimo.sharecost.platform.UrlOpener
import da.chelimo.sharecost.ui.screen.settle.PeerPaymentHandle
import da.chelimo.sharecost.ui.screen.settle.SettlePersonScreen
import da.chelimo.sharecost.ui.screen.settle.SettleShareUi
import da.chelimo.sharecost.ui.screen.settle.SettleSingleSheet
import da.chelimo.sharecost.ui.screen.settle.appLabel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settle a person, wired: settles the net amount the current user owes the peer (debtor→creditor) via
 * [SettlementRepository.applySettlement], which distributes the payment across outstanding shares
 * oldest-first and recomputes status. Step 2 lists the *payee's* real payment handles; "Open in <app>"
 * fires the deep link (or copies the fallback) and the confirm sheet records the payment only once the
 * user confirms it went through. "Mark paid" records directly with no deep link attempted.
 */
@Composable
fun SettlePersonRoute(groupId: String, peerUserId: String, onBack: () -> Unit, onSettled: () -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val settlements = koinInject<SettlementRepository>()
    val auth = koinInject<AuthSession>()
    val urlOpener = koinInject<UrlOpener>()

    val gid = remember(groupId) { GroupId(groupId) }
    val peerId = remember(peerUserId) { UserId(peerUserId) }
    val debts by remember(gid) { expenses.observeBalances(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val peer = members.firstOrNull { it.userId == peerId }
    val peerName = peer?.displayName ?: "Someone"
    val currency = group?.baseCurrency ?: "USD"
    val owed = debts.firstOrNull { it.debtorUserId == userId && it.creditorUserId == peerId }?.amountSubunits ?: 0L
    val shares = if (owed > 0) listOf(SettleShareUi("Outstanding balance", "", owed)) else emptyList()
    // The payee's real handles drive the "Pay with" choices; ordered VENMO, CASH_APP, PAYPAL, ZELLE.
    val handles = PaymentApp.entries.mapNotNull { app ->
        peer?.paymentHandles?.get(app)?.let { PeerPaymentHandle(app, app.appLabel, it) }
    }

    /** Records the payment, optionally noting that a confirmed deep link was used. */
    fun record(amount: Long, app: PaymentApp?, linkConfirmed: Boolean) {
        val me = userId ?: return
        if (amount <= 0) return
        scope.launch {
            settlements.applySettlement(
                NewSettlement(
                    groupId = gid,
                    fromUserId = me,
                    toUserId = peerId,
                    paymentCurrency = currency,
                    paymentAmountSubunits = amount,
                    createdBy = me,
                    paymentApp = app?.name,
                    deepLinkAttempted = app != null,
                    deepLinkSucceeded = if (app != null) linkConfirmed else null,
                ),
            )
            onSettled()
        }
    }

    SettlePersonScreen(
        peerName = peerName,
        currencyCode = currency,
        shares = shares,
        handles = handles,
        onBack = onBack,
        onOpenApp = { amount, app, handle ->
            // Fire the deep link best-effort; the clipboard fallback lives on the confirm sheet's "Copy".
            buildDeepLink(app, handle, amount, group?.name ?: "ShareCost", "balance").url?.let { urlOpener.open(it) }
        },
        onConfirmPaid = { amount, app -> record(amount, app, linkConfirmed = true) },
        onMarkPaid = { amount -> record(amount, app = null, linkConfirmed = false) },
    )
}

/**
 * Settle a single expense, wired: settles the current user's remaining share of one expense to its
 * payer via [SettlementRepository.applySettlement] (oldest-first allocation pays this balance down).
 * "Open in <app>" fires the deep link then the sheet's in-place confirm records on "Yes"; "Mark paid"
 * records directly. No-ops cleanly when the expense has an outside payer or nothing is owed.
 */
@Composable
fun SettleSingleRoute(groupId: String, expenseId: String, onBack: () -> Unit, onSettled: () -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val settlements = koinInject<SettlementRepository>()
    val auth = koinInject<AuthSession>()
    val urlOpener = koinInject<UrlOpener>()

    val gid = remember(groupId) { GroupId(groupId) }
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val detail by remember(eid) { expenses.observeExpense(eid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val ews = detail ?: return // brief blank scrim while the expense loads
    val e = ews.expense
    val payerId = e.payerUserId
    val myRemaining = ews.shares.firstOrNull { it.userId == userId }?.remainingSubunits ?: 0L
    val payer = members.firstOrNull { it.userId == payerId }
    val payeeName = payer?.displayName ?: e.payerOutsideName ?: "Someone"
    val handles = PaymentApp.entries.mapNotNull { app ->
        payer?.paymentHandles?.get(app)?.let { PeerPaymentHandle(app, app.appLabel, it) }
    }

    fun record(amount: Long, app: PaymentApp?, linkConfirmed: Boolean) {
        val me = userId ?: return
        if (payerId == null || amount <= 0) { onSettled(); return }
        scope.launch {
            settlements.applySettlement(
                NewSettlement(
                    groupId = gid,
                    fromUserId = me,
                    toUserId = payerId,
                    paymentCurrency = e.currency,
                    paymentAmountSubunits = amount,
                    createdBy = me,
                    // Single-expense settle: confine the payment to this expense so a partial pay
                    // visibly pays *this* expense down (not the oldest one to the payer).
                    expenseId = eid,
                    paymentApp = app?.name,
                    deepLinkAttempted = app != null,
                    deepLinkSucceeded = if (app != null) linkConfirmed else null,
                ),
            )
            onSettled()
        }
    }

    SettleSingleSheet(
        expenseTitle = e.title,
        payee = payeeName,
        shareAmountSubunits = myRemaining,
        currency = e.currency,
        handles = handles,
        onDismiss = onBack,
        onOpenApp = { amount, app, handle ->
            buildDeepLink(app, handle, amount, group?.name ?: "ShareCost", "expense").url?.let { urlOpener.open(it) }
        },
        onConfirmPaid = { amount, app -> record(amount, app, linkConfirmed = true) },
        onMarkPaid = { amount -> record(amount, app = null, linkConfirmed = false) },
    )
}
