package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.repository.ExpenseRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.SettlementRepository
import app.splitevenly.domain.settlement.NewSettlement
import app.splitevenly.domain.settlement.PaymentApp
import app.splitevenly.domain.settlement.buildDeepLink
import app.splitevenly.platform.UrlOpener
import app.splitevenly.ui.screen.settle.PeerPaymentHandle
import app.splitevenly.ui.screen.settle.SettlePersonScreen
import app.splitevenly.ui.screen.settle.SettleShareUi
import app.splitevenly.ui.screen.settle.SettleSingleSheet
import app.splitevenly.ui.screen.settle.appLabel
import app.splitevenly.core.time.todayUtc
import app.splitevenly.ui.screen.group.dayLabel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Settle a person, wired: settles the net amount the current user owes the peer (debtor→creditor) via
 * [SettlementRepository.applySettlement], which distributes the payment across outstanding shares
 * oldest-first and recomputes status. Step 2 lists the *payee's* real payment handles; "Open in <app>"
 * fires the deep link (or copies the fallback) and the confirm sheet records the payment only once the
 * user confirms it went through. "Mark paid" records directly with no deep link attempted.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun SettlePersonRoute(groupId: String, peerUserId: String, onBack: () -> Unit, onSettled: () -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val settlements = koinInject<SettlementRepository>()
    val auth = koinInject<AuthSession>()
    val urlOpener = koinInject<UrlOpener>()

    val gid = remember(groupId) { GroupId(groupId) }
    val peerId = remember(peerUserId) { UserId(peerUserId) }
    val items by remember(gid) { expenses.observeOutstandingItems(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val today = remember { Clock.System.todayUtc() }

    val peer = members.firstOrNull { it.userId == peerId }
    val peerName = peer?.displayName ?: "Someone"
    val currency = group?.baseCurrency ?: "USD"
    // What the current user still owes this peer, per expense (oldest first) — the checkable settle list.
    val shares = items
        .filter { it.debtorUserId == userId && it.creditorUserId == peerId }
        .map { SettleShareUi(it.expenseId.value, it.title, dayLabel(it.expenseDate, today), it.remainingSubunits) }
    // The payee's real handles drive the "Pay with" choices; their preferred one is highlighted.
    val handles = PaymentApp.entries.mapNotNull { app ->
        peer?.paymentHandles?.get(app)?.let { PeerPaymentHandle(app, app.appLabel, it) }
    }

    /** Records the payment, confined to the ticked expenses, optionally noting a confirmed deep link. */
    fun record(amount: Long, app: PaymentApp?, linkConfirmed: Boolean, expenseIds: List<String>) {
        val me = userId ?: return
        if (amount <= 0 || expenseIds.isEmpty()) return
        scope.launch {
            settlements.applySettlement(
                NewSettlement(
                    groupId = gid,
                    fromUserId = me,
                    toUserId = peerId,
                    paymentCurrency = currency,
                    paymentAmountSubunits = amount,
                    createdBy = me,
                    // Scope to exactly what the user ticked, so paying the Uber clears the Uber (not the
                    // oldest outstanding expense to this peer).
                    expenseIds = expenseIds.map { ExpenseId(it) },
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
        preferredApp = peer?.preferredPaymentApp,
        onBack = onBack,
        onOpenApp = { amount, app, handle, _ ->
            // Fire the deep link best-effort; the clipboard fallback lives on the confirm sheet's "Copy".
            buildDeepLink(app, handle, amount, group?.name ?: "Evenly", "balance").url?.let { urlOpener.open(it) }
        },
        onConfirmPaid = { amount, app, expenseIds -> record(amount, app, linkConfirmed = true, expenseIds) },
        onMarkPaid = { amount, expenseIds -> record(amount, app = null, linkConfirmed = false, expenseIds) },
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
            buildDeepLink(app, handle, amount, group?.name ?: "Evenly", "expense").url?.let { urlOpener.open(it) }
        },
        onConfirmPaid = { amount, app -> record(amount, app, linkConfirmed = true) },
        onMarkPaid = { amount -> record(amount, app = null, linkConfirmed = false) },
    )
}
