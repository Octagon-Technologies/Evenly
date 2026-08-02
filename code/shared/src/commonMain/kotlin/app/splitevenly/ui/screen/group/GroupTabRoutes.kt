package app.splitevenly.ui.screen.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.SettlementId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.shortDate
import app.splitevenly.core.time.todayUtc
import app.splitevenly.data.claim.ClaimStatus
import app.splitevenly.data.claim.IdentityPromptSnooze
import app.splitevenly.data.claim.PlaceholderClaimCoordinator
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.balance.Debt
import app.splitevenly.domain.balance.OutstandingItem
import app.splitevenly.domain.balance.Overpayment
import app.splitevenly.domain.expense.ConflictSide
import app.splitevenly.domain.group.ClaimPreview
import app.splitevenly.domain.group.Member
import app.splitevenly.domain.repository.BillRepository
import app.splitevenly.domain.repository.ExpenseRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.SettlementRepository
import app.splitevenly.domain.settlement.SettlementRecord
import app.splitevenly.platform.PlatformShare
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.screen.reconcile.ClaimLineUi
import app.splitevenly.ui.screen.reconcile.ReconcileConfirmModal
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Maps the current user's bilateral [Debt]s to display rows (one per counterparty + currency),
 * attaching the per-expense breakdown ([items], filtered to the row's direction + currency). Only the
 * user's own balances are shown — what they owe or are owed — not the full who-owes-whom log. Each
 * breakdown line is in its own expense's currency, matched to the (per-currency) debt it belongs to.
 */
fun buildBalances(
    debts: List<Debt>,
    items: List<OutstandingItem>,
    members: List<Member>,
    currentUserId: UserId?,
    today: String,
): List<DebtUi> {
    val me = currentUserId ?: return emptyList()
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    fun name(id: UserId): String = nameByUser[id.value] ?: "Someone"
    return debts.filter { it.debtorUserId == me || it.creditorUserId == me }.map { d ->
        val owedToYou = d.creditorUserId == me
        val peer = if (owedToYou) d.debtorUserId else d.creditorUserId
        val lines = items
            .filter {
                it.currency == d.currency &&
                    it.debtorUserId == (if (owedToYou) peer else me) &&
                    it.creditorUserId == (if (owedToYou) me else peer)
            }
            .map { BalanceLineUi(it.expenseId.value, it.title, dayLabel(it.expenseDate, today), moneySubunits(it.remainingSubunits, it.currency)) }
        DebtUi(
            peerUserId = peer.value,
            peerName = name(peer),
            amountText = moneySubunits(d.amountSubunits, d.currency),
            owedToYou = owedToYou,
            lines = lines,
        )
    }
}

/**
 * Maps the viewer's over-paid pairs (P1 #9) to banner UI. For each pair the viewer is in, it attaches the
 * payments between the two of them (from the group's settlements) so the "Review payments" expansion can
 * offer to void the duplicate. [youOverpaid] is true when the viewer is the over-paying debtor.
 */
fun buildOverpayments(
    overpayments: List<Overpayment>,
    settlements: List<SettlementRecord>,
    members: List<Member>,
    currentUserId: UserId?,
    coveredTitles: Map<SettlementId, List<String>> = emptyMap(),
): List<OverpaymentUi> {
    val me = currentUserId ?: return emptyList()
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    fun name(id: UserId): String = nameByUser[id.value] ?: "Someone"
    return overpayments.mapNotNull { o ->
        if (o.debtorUserId != me && o.creditorUserId != me) return@mapNotNull null
        val youOverpaid = o.debtorUserId == me
        val peer = if (youOverpaid) o.creditorUserId else o.debtorUserId
        val payments = settlements
            .filter {
                it.paymentCurrency == o.currency &&
                    ((it.fromUserId == me && it.toUserId == peer) || (it.fromUserId == peer && it.toUserId == me))
            }
            .map { s ->
                val fromMe = s.fromUserId == me
                // "Who entered this" is what tells two duplicate payments apart, so lead the caption with
                // it, then when, then which app was used.
                val loggedBy = when {
                    s.createdBy == null -> null
                    s.createdBy == me -> "you"
                    else -> name(s.createdBy)
                }
                val app = s.paymentApp?.lowercase()?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotBlank() }
                val sub = listOfNotNull(
                    loggedBy?.let { "Added by $it" },
                    shortDate(s.settledAt),
                    app,
                ).joinToString(" · ")
                // What the payment paid toward, so the user can see whether the two look like the same debt.
                val titles = coveredTitles[s.id].orEmpty()
                val covers = when {
                    titles.isEmpty() -> ""
                    titles.size <= 3 -> "For ${titles.joinToString(", ")}"
                    else -> "For ${titles.take(2).joinToString(", ")} +${titles.size - 2} more"
                }
                PaymentReviewUi(
                    settlementId = s.id.value,
                    label = if (fromMe) "You paid ${name(peer)}" else "${name(peer)} paid you",
                    sub = sub,
                    amountText = moneySubunits(s.paymentAmountSubunits, s.paymentCurrency),
                    covers = covers,
                )
            }
        OverpaymentUi(
            peerUserId = peer.value,
            peerName = name(peer),
            overpaidText = moneySubunits(o.overpaidSubunits, o.currency),
            youOverpaid = youOverpaid,
            payments = payments,
        )
    }
}

/** Human label for a stored `split_mode` token, for the edit-conflict diff. */
private fun humanizeSplitMode(mode: String): String = when (mode.uppercase()) {
    "EVEN" -> "Even"
    "EXACT" -> "Exact"
    "PERCENT" -> "Percent"
    "SHARE" -> "Shares"
    "ITEMIZED" -> "Itemized"
    else -> mode.lowercase().replaceFirstChar { it.uppercase() }
}

/** Expenses tab content, wired. */
@OptIn(ExperimentalTime::class)
@Composable
fun GroupExpensesRoute(
    groupId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onAdd: () -> Unit,
    onOpenExpense: (String) -> Unit,
    onOpenBill: (String) -> Unit,
    onSearch: () -> Unit,
    onClaimNames: () -> Unit = {},
) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val bills = koinInject<BillRepository>()
    val auth = koinInject<AuthSession>()
    var showFilter by remember { mutableStateOf(false) }
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val expenseList by remember(gid) { expenses.observeExpenses(gid) }.collectAsStateWithLifecycle(emptyList())
    val withShares by remember(gid) { expenses.observeExpensesWithShares(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val store = koinInject<GroupFilterStore>()
    val filter by remember(groupId) { store.filterFor(groupId) }.collectAsStateWithLifecycle()
    val today = remember { Clock.System.todayUtc() }
    val filtered = applyFilter(expenseList, filter, today)
    val sharesById = remember(withShares) { withShares.associate { it.expense.id.value to it.shares } }
    val ui = buildGroupExpenses(group, filtered, members, userId, today, sharesById)
    val unresolved by remember(gid, userId) { bills.observeUnresolvedBills(gid, userId) }.collectAsStateWithLifecycle(emptyList())
    val unresolvedUi = unresolved.map { u ->
        val sub = if (u.unclaimedCount > 0) "${u.unclaimedCount} ${if (u.unclaimedCount == 1) "dish" else "dishes"} still need someone"
            else "${u.stillToClaimCount} still to claim"
        UnresolvedBillUi(u.expenseId.value, u.title, sub, u.youNeedToClaim)
    }
    // Invite link (same source/format as Group settings): the token resolves once the group has synced.
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val share = koinInject<PlatformShare>()
    val inviteToken = group?.inviteToken
    val inviteLink = inviteToken?.let { "split-evenly.app/j/$it" } ?: "Generating link…"

    // ── "Is this you?" ───────────────────────────────────────────────────────────────────────────
    val claims = koinInject<PlaceholderClaimCoordinator>()
    val snooze = koinInject<IdentityPromptSnooze>()
    val claimStatus by claims.status.collectAsStateWithLifecycle()
    val snoozed by snooze.snoozed.collectAsStateWithLifecycle()
    val finished by snooze.finished.collectAsStateWithLifecycle()
    val unclaimed by remember(gid, userId) {
        userId?.let { groups.observeUnclaimedNames(gid, it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    // A claim in flight is not a question any more: hide the name being claimed so the card can't
    // offer it again while its 5 seconds run down.
    val pendingName = (claimStatus as? ClaimStatus.Undoable)?.placeholderUserId?.value
    val myName = members.firstOrNull { it.userId == userId }?.displayName.orEmpty()
    val identityNames = remember(unclaimed, pendingName, myName, group?.baseCurrency) {
        unclaimed.filter { it.userId.value != pendingName }
            // Matching only ever changes ORDERING, never whether we ask: the motivating case is someone
            // added as "Chelimo" who signed up as "Andrew", which matches nothing and is exactly the
            // case that must not be missed.
            .sortedByDescending { plausiblyMe(it.displayName, myName) }
            .map { n ->
                UnclaimedNameUi(
                    id = n.userId.value,
                    name = n.displayName,
                    expenseCount = n.expenseCount,
                    amountLabel = n.currency?.let { moneySubunits(n.owedSubunits, it) },
                )
            }
    }
    var pendingConfirm by remember { mutableStateOf<ClaimPreview?>(null) }
    // The card is the only thing that hides on "Later"; the closing note is the receipt for the tap
    // that emptied the list, so it only shows to whoever just did the emptying.
    val showCard = identityNames.isNotEmpty() && groupId !in snoozed
    val undoText = (claimStatus as? ClaimStatus.Undoable)
        ?.takeIf { it.groupId.value == groupId }
        ?.let { "${it.name} is now you" }
    // Losing the race or failing to reach the guard both mean nothing was written, so both say so and
    // leave the card in place. Silence here would read as "it worked" and the money wouldn't have moved.
    val claimNotice = when (val s = claimStatus) {
        is ClaimStatus.Lost -> s.winnerName
            ?.let { "Someone else already claimed this name. ${s.name} now belongs to $it." }
            ?: "Someone else already claimed this name."
        is ClaimStatus.Failed -> "Couldn't confirm that claim. Check your connection and try again."
        else -> null
    }?.takeIf { (claimStatus as? ClaimStatus.Lost)?.groupId?.value == groupId || (claimStatus as? ClaimStatus.Failed)?.groupId?.value == groupId }

    // Leaving the tab commits a pending claim rather than leaving it hanging: the user has moved on,
    // and a claim that is never written is a claim that silently didn't happen.
    DisposableEffect(claims) {
        onDispose { scope.launch { claims.flush() } }
    }
    // A successful claim needs no toast of its own (the undo toast already said what happened), but it
    // counts as answering, so the closing note can appear once it was the last open name.
    LaunchedEffect(claimStatus) {
        if (claimStatus is ClaimStatus.Claimed) {
            snooze.markFinished(gid)
            claims.acknowledge()
        }
    }

    GroupExpensesTab(
        groupEmoji = ui.groupEmoji, groupName = ui.groupName, state = ui.state, days = ui.days, drafts = 0,
        filterActive = filter.isActive,
        inviteLink = inviteLink,
        onBack = onBack, onOpenSettings = onOpenSettings, onAdd = onAdd, onOpenExpense = onOpenExpense, onSearch = onSearch, onFilter = { showFilter = true },
        onClearFilter = { store.clear(groupId) },
        onCopyInvite = { inviteToken?.let { clipboard.setText(AnnotatedString("split-evenly.app/j/$it")) } },
        onShareInvite = { inviteToken?.let { share.shareText("Join my group on Evenly: split-evenly.app/j/$it", "Join my Evenly group") } },
        onRotateInvite = { scope.launch { groups.rotateInviteToken(gid) } },
        unresolvedBills = unresolvedUi,
        onOpenBill = onOpenBill,
        identityNames = if (showCard) identityNames else emptyList(),
        // The closing note is the receipt for the tap that emptied the list, so it needs both: this
        // person answered something here, and there is nothing left to ask.
        identityFinished = groupId in finished && identityNames.isEmpty(),
        onIdentityThatsMe = { id ->
            // Never merge straight off the card: the confirm sheet has to show the money first.
            scope.launch {
                val n = unclaimed.firstOrNull { it.userId.value == id } ?: return@launch
                pendingConfirm = groups.claimPreview(gid, n.userId, n.displayName)
            }
        },
        onIdentityNotMe = { id ->
            userId?.let { me ->
                scope.launch { groups.answerNotMe(gid, listOf(UserId(id)), me) }
                snooze.markFinished(gid)
            }
        },
        onIdentityNoneOfThese = {
            // Only this explicit tap writes the answers. Closing the card or navigating away writes
            // nothing, because "opened and left" also means "I'm not sure" and "I mis-tapped back".
            userId?.let { me ->
                scope.launch { groups.answerNotMe(gid, identityNames.map { UserId(it.id) }, me) }
                snooze.markFinished(gid)
            }
        },
        onIdentityLater = { snooze.snooze(gid) },
        onIdentitySeeAll = onClaimNames,
        undoToastText = undoText,
        onUndoClaim = { scope.launch { claims.undo() } },
        claimNoticeText = claimNotice,
        onDismissClaimNotice = { claims.acknowledge() },
    )

    pendingConfirm?.let { preview ->
        ReconcileConfirmModal(
            name = preview.name,
            owed = preview.owed.map { ClaimLineUi(it.title, moneySubunits(it.amountSubunits, it.currency)) },
            paid = preview.paid.map { ClaimLineUi(it.title, moneySubunits(it.amountSubunits, it.currency)) },
            owedTotalLabel = preview.currency?.takeIf { preview.owed.isNotEmpty() }
                ?.let { moneySubunits(preview.owedTotalSubunits, it) },
            onConfirm = {
                val me = userId
                val target = unclaimed.firstOrNull { it.displayName == preview.name }
                pendingConfirm = null
                if (me != null && target != null) {
                    scope.launch { claims.confirm(gid, target.userId, preview.name, me) }
                }
            },
            onCancel = { pendingConfirm = null },
        )
    }

    // Shown as an overlay on top of the tab, not a separate route push — matches the sheet pattern
    // used elsewhere (e.g. HomeScreen's "Create or join a group" sheet).
    if (showFilter) {
        FilterRoute(groupId = groupId, onDismiss = { showFilter = false })
    }
}

/**
 * Does this name look like it could be the viewer? Used ONLY to order the list so a likely match is
 * first. It never decides whether a name is offered, because the case worth catching is the one that
 * matches nothing at all.
 */
private fun plausiblyMe(candidate: String, myName: String): Boolean {
    if (myName.isBlank()) return false
    val a = candidate.trim().lowercase()
    val b = myName.trim().lowercase()
    if (a == b) return true
    val myParts = b.split(' ').filter { it.length > 2 }
    return myParts.any { a.contains(it) } || (a.length > 2 && b.contains(a))
}

/** Balances tab content, wired: the current user's pairwise debts (converted to the group base, F2),
 *  split into owe / owed sections with a per-expense breakdown behind each row (F2). */
@OptIn(ExperimentalTime::class)
@Composable
fun GroupBalancesRoute(groupId: String, onBack: () -> Unit, onSettleNav: (String) -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val settlements = koinInject<SettlementRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val debts by remember(gid) { expenses.observeBalances(gid) }.collectAsStateWithLifecycle(emptyList())
    val items by remember(gid) { expenses.observeOutstandingItems(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    // Double-payment detection (P1 #9): pairs whose derived remaining went negative, + the payments behind
    // them (from the group's settlements) so a duplicate can be voided from the banner.
    val overpaymentsRaw by remember(gid, userId) { expenses.observeOverpayments(gid, userId) }.collectAsStateWithLifecycle(emptyList())
    val settlementList by remember(gid) { settlements.observeSettlements(gid) }.collectAsStateWithLifecycle(emptyList())
    val coveredTitles by remember(gid) { settlements.observeCoveredExpenseTitles(gid) }.collectAsStateWithLifecycle(emptyMap())
    val today = remember { Clock.System.todayUtc() }
    val rows = buildBalances(debts, items, members, userId, today)
    val overpaymentsUi = buildOverpayments(overpaymentsRaw, settlementList, members, userId, coveredTitles)
    val scope = rememberCoroutineScope()
    GroupBalancesTab(
        debts = rows,
        overpayments = overpaymentsUi,
        onBack = onBack,
        onSettle = { onSettleNav(it.peerUserId) },
        onVoidPayment = { id -> scope.launch { settlements.voidSettlement(SettlementId(id)) } },
    )
}

/** Conflicts tab content, wired: streams unresolved conflicts; Skip dismisses, Include opens the sheet. */
@Composable
fun GroupConflictsRoute(
    groupId: String,
    onBack: () -> Unit,
    onIncludeNav: (conflictId: String, expenseId: String, memberUserId: String) -> Unit,
) {
    val groups = koinInject<GroupRepository>()
    val expenses = koinInject<ExpenseRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val conflicts by remember(gid) { groups.observeConflicts(gid) }.collectAsStateWithLifecycle(emptyList())
    val editConflicts by remember(gid) { expenses.observeEditConflicts(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    fun nameOf(id: UserId): String = if (id == userId) "You" else nameByUser[id.value] ?: "Someone"

    val uis = conflicts.map { conf ->
        ConflictUi(
            conflictId = conf.id,
            expenseId = conf.expenseId.value,
            memberUserId = conf.addedUserId.value,
            memberName = nameOf(conf.addedUserId),
            title = conf.expenseTitle,
            amount = conf.amountSubunits / 100.0,
            by = nameOf(conf.triggeredByUserId),
        )
    }
    val editUis = editConflicts.map { ec ->
        val cur = ec.current
        val rej = ec.rejected
        val currency = ec.currency
        val loserName = nameOf(ec.rejectedBy)
        val winnerName = ec.winnerBy?.let { nameOf(it) }
        // Name the OTHER party involved (the one who isn't you): if you lost, that's the winner; if you
        // won, that's the loser. Fixes the misleading "You edited this while you did too" that came from
        // labelling the card by rejected_by (always the local pusher) with no record of who actually won.
        val otherName = when {
            ec.winnerBy == userId -> loserName
            ec.rejectedBy == userId -> winnerName ?: "Someone"
            else -> winnerName ?: loserName
        }
        fun possessive(name: String) = if (name == "You") "yours" else "$name's"
        fun payerLabel(side: ConflictSide) = side.payerUserId?.let { nameOf(it) } ?: side.payerOutsideName ?: "—"

        // Only surface fields that actually differ between the two versions — that's the decision surface.
        val rows = buildList {
            if (cur.amountSubunits != rej.amountSubunits)
                add(ConflictRowUi("Total", moneySubunits(cur.amountSubunits, currency), moneySubunits(rej.amountSubunits, currency)))
            if (cur.splitMode != rej.splitMode)
                add(ConflictRowUi("Split", humanizeSplitMode(cur.splitMode), humanizeSplitMode(rej.splitMode)))
            if (cur.payerUserId != rej.payerUserId || cur.payerOutsideName != rej.payerOutsideName)
                add(ConflictRowUi("Paid by", payerLabel(cur), payerLabel(rej)))
            if (cur.title != rej.title)
                add(ConflictRowUi("Title", cur.title, rej.title))
            // Other participants whose owed amount changed (the viewing user is in the highlighted row).
            (cur.shares.keys + rej.shares.keys).filter { it != userId }.distinct().forEach { uid ->
                val a = cur.shares[uid]
                val b = rej.shares[uid]
                if (a != b) add(
                    ConflictRowUi(
                        nameOf(uid),
                        a?.let { moneySubunits(it, currency) } ?: "—",
                        b?.let { moneySubunits(it, currency) } ?: "—",
                    ),
                )
            }
        }
        val yourCurrent = userId?.let { cur.shares[it] }
        val yourRejected = userId?.let { rej.shares[it] }
        val yourShare = if (yourCurrent != null || yourRejected != null) ConflictCompareUi(
            currentText = yourCurrent?.let { moneySubunits(it, currency) } ?: "—",
            rejectedText = yourRejected?.let { moneySubunits(it, currency) } ?: "—",
            changed = yourCurrent != yourRejected,
        ) else null

        EditConflictUi(
            conflictId = ec.id,
            headline = "$otherName edited this while you did too",
            expenseTitle = cur.title,
            subhead = when {
                winnerName == null -> "This version stays unless you switch"
                winnerName == "You" -> "Your version is currently saved"
                else -> "$winnerName's version is currently saved"
            },
            currentColLabel = winnerName ?: "Saved",
            rejectedColLabel = loserName,
            yourShare = yourShare,
            rows = rows,
            keepLabel = if (winnerName == null) "Keep current" else "Keep ${possessive(winnerName)}",
            useLabel = "Use ${possessive(loserName)}",
        )
    }
    GroupConflictsTab(
        memberName = conflicts.firstOrNull()?.let { nameOf(it.addedUserId) } ?: "New members",
        conflicts = uis,
        editConflicts = editUis,
        onBack = onBack,
        onInclude = { ui -> onIncludeNav(ui.conflictId, ui.expenseId, ui.memberUserId) },
        onSkip = { ui -> scope.launch { groups.resolveConflict(ui.conflictId, include = false) } },
        onKeepCurrent = { ui -> scope.launch { expenses.resolveEditConflict(ui.conflictId, useRejected = false, resolvedBy = userId) } },
        onUseRejected = { ui -> scope.launch { expenses.resolveEditConflict(ui.conflictId, useRejected = true, resolvedBy = userId) } },
    )
}

/** Include-member sheet, wired: loads the conflicted expense + split, resolves the conflict on confirm. */
@Composable
fun IncludeMemberRoute(
    groupId: String,
    conflictId: String,
    expenseId: String,
    memberUserId: String,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val gid = remember(groupId) { GroupId(groupId) }
    val detail by remember(eid) { expenses.observeExpense(eid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    val ews = detail ?: return // brief blank scrim while the conflicted expense loads
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    fun nameOf(id: UserId): String = if (id == userId) "You" else nameByUser[id.value] ?: "Someone"

    IncludeMemberSheet(
        memberName = nameOf(UserId(memberUserId)),
        expenseTitle = ews.expense.title,
        expenseAmountSubunits = ews.expense.amountSubunits,
        currencyCode = ews.expense.currency,
        currentSplit = ews.shares.map { nameOf(it.userId) to it.owedSubunits },
        saving = saving,
        onDismiss = onDismiss,
        onConfirm = { share ->
            saving = true
            scope.launch {
                if (groups.resolveConflict(conflictId, include = true, newShareSubunits = share) is AppResult.Ok) onConfirmed() else saving = false
            }
        },
    )
}
