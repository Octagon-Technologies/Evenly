package da.chelimo.sharecost.ui.screen.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.todayUtc
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.balance.Debt
import da.chelimo.sharecost.domain.group.Member
import da.chelimo.sharecost.domain.repository.ExpenseRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Maps bilateral [Debt]s to display rows, resolving names (current user → "You") and the settle peer.
 * Shows only the current user's own balances — what they owe or are owed — not the full who-owes-whom log.
 */
fun buildBalances(debts: List<Debt>, members: List<Member>, currentUserId: UserId?): List<DebtUi> {
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    fun name(id: UserId): String = if (id == currentUserId) "You" else (nameByUser[id.value] ?: "Someone")
    return debts.filter { it.debtorUserId == currentUserId || it.creditorUserId == currentUserId }.map { d ->
        val peer = if (d.debtorUserId == currentUserId) d.creditorUserId else d.debtorUserId
        DebtUi(
            from = name(d.debtorUserId),
            to = name(d.creditorUserId),
            amount = d.amountSubunits / 100.0,
            owedToYou = d.creditorUserId == currentUserId,
            peerUserId = peer.value,
        )
    }
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
    onSearch: () -> Unit,
    onFilter: () -> Unit,
) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
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
    // Invite link (same source/format as Group settings): the token resolves once the group has synced.
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val inviteToken = group?.inviteToken
    val inviteLink = inviteToken?.let { "sharecost.app/j/$it" } ?: "Generating link…"
    GroupExpensesTab(
        groupEmoji = ui.groupEmoji, groupName = ui.groupName, state = ui.state, days = ui.days, drafts = 0,
        filterActive = filter.isActive,
        inviteLink = inviteLink,
        onBack = onBack, onOpenSettings = onOpenSettings, onAdd = onAdd, onOpenExpense = onOpenExpense, onSearch = onSearch, onFilter = onFilter,
        onClearFilter = { store.clear(groupId) },
        onCopyInvite = { inviteToken?.let { clipboard.setText(AnnotatedString("sharecost.app/j/$it")) } },
        onRotateInvite = { scope.launch { groups.rotateInviteToken(gid) } },
    )
}

/** Balances tab content, wired: the current user's pairwise debts (converted to the group base, F2). */
@Composable
fun GroupBalancesRoute(groupId: String, onBack: () -> Unit, onSettleNav: (String) -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val debts by remember(gid) { expenses.observeBalances(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val rows = buildBalances(debts, members, userId)
    GroupBalancesTab(empty = rows.isEmpty(), debts = rows, onBack = onBack, onSettle = { onSettleNav(it.peerUserId) })
}

/** Conflicts tab content, wired: streams unresolved conflicts; Skip dismisses, Include opens the sheet. */
@Composable
fun GroupConflictsRoute(
    groupId: String,
    onBack: () -> Unit,
    onIncludeNav: (conflictId: String, expenseId: String, memberUserId: String) -> Unit,
) {
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val conflicts by remember(gid) { groups.observeConflicts(gid) }.collectAsStateWithLifecycle(emptyList())
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
    GroupConflictsTab(
        memberName = conflicts.firstOrNull()?.let { nameOf(it.addedUserId) } ?: "New members",
        conflicts = uis,
        onBack = onBack,
        onInclude = { ui -> onIncludeNav(ui.conflictId, ui.expenseId, ui.memberUserId) },
        onSkip = { ui -> scope.launch { groups.resolveConflict(ui.conflictId, include = false) } },
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
