package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import da.chelimo.sharecost.ui.screen.settle.SettlePersonScreen
import da.chelimo.sharecost.ui.screen.settle.SettleShareUi
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settle a person, wired: settles the net amount the current user owes the peer (debtor→creditor) via
 * [SettlementRepository.applySettlement], which distributes the payment across outstanding shares
 * oldest-first and recomputes status. "Open in <app>" also fires a best-effort payment deep link
 * (real per-payee handles + the confirm sheet are a follow-up; here it records the payment optimistically).
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

    val peerName = members.firstOrNull { it.userId == peerId }?.displayName ?: "Someone"
    val currency = group?.baseCurrency ?: "USD"
    val owed = debts.firstOrNull { it.debtorUserId == userId && it.creditorUserId == peerId }?.amountSubunits ?: 0L
    val shares = if (owed > 0) listOf(SettleShareUi("Outstanding balance", "", owed)) else emptyList()

    fun settle(amount: Long, openApp: Boolean) {
        val me = userId ?: return
        if (amount <= 0) return
        scope.launch {
            if (openApp) {
                val handle = "@" + peerName.lowercase().replace(" ", "")
                buildDeepLink(PaymentApp.VENMO, handle, amount, group?.name ?: "ShareCost", "balance").url?.let { urlOpener.open(it) }
            }
            settlements.applySettlement(
                NewSettlement(groupId = gid, fromUserId = me, toUserId = peerId, paymentCurrency = currency, paymentAmountSubunits = amount, createdBy = me),
            )
            onSettled()
        }
    }

    SettlePersonScreen(
        peerName = peerName,
        currencyCode = currency,
        shares = shares,
        onBack = onBack,
        onOpenApp = { settle(it, openApp = true) },
        onMarkPaid = { settle(it, openApp = false) },
    )
}
