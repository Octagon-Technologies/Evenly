package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.allocate
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.todayUtc
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.expense.NewExpense
import da.chelimo.sharecost.domain.expense.NewShare
import da.chelimo.sharecost.domain.repository.ExpenseRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.ui.screen.expense.AddExpenseScreen
import da.chelimo.sharecost.ui.screen.expense.AddParticipantUi
import da.chelimo.sharecost.ui.screen.expense.DetailShareUi
import da.chelimo.sharecost.ui.screen.expense.ExpenseDetailScreen
import da.chelimo.sharecost.ui.screen.expense.ExpenseDetailState
import da.chelimo.sharecost.ui.screen.group.GroupHomeScreen
import da.chelimo.sharecost.ui.screen.group.buildGroupExpenses
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Group home, wired: streams the group + its expenses into the Expenses tab. */
@OptIn(ExperimentalTime::class)
@Composable
fun GroupHomeRoute(
    groupId: String,
    initialTab: GroupTab,
    onAdd: () -> Unit,
    onOpenExpense: (String) -> Unit,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
    onOpenSettings: () -> Unit,
    onSettleNav: (String) -> Unit,
    onIncludeNav: () -> Unit,
    onExport: () -> Unit,
) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val expenseList by remember(gid) { expenses.observeExpenses(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val today = remember { Clock.System.todayUtc() }

    val ui = buildGroupExpenses(group, expenseList, members, userId, today)
    GroupHomeScreen(
        groupId = groupId,
        initialTab = initialTab,
        conflictCount = 0,
        groupName = ui.groupName,
        groupEmoji = ui.groupEmoji,
        expensesState = ui.state,
        expenseDays = ui.days,
        onAdd = onAdd,
        onOpenExpense = onOpenExpense,
        onSearch = onSearch,
        onFilter = onFilter,
        onOpenSettings = onOpenSettings,
        onSettle = { onSettleNav(it.to) },
        onInclude = { onIncludeNav() },
        onExport = onExport,
    )
}

/** Add expense, wired: real members as participants; Save runs the allocator and persists (EVEN). */
@OptIn(ExperimentalTime::class)
@Composable
fun AddExpenseRoute(groupId: String, onBack: () -> Unit, onSaved: () -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    val participants = members.map { AddParticipantUi(it.userId.value, it.displayName ?: "Someone", it.userId == userId) }
        .ifEmpty { listOfNotNull(userId?.let { AddParticipantUi(it.value, "You", true) }) }
    val currency = group?.baseCurrency ?: "USD"

    AddExpenseScreen(
        participants = participants,
        currencyCode = currency,
        saving = saving,
        onBack = onBack,
        onSave = { amountSubunits, title, selectedIds ->
            val payer = userId
            if (payer != null && selectedIds.isNotEmpty()) {
                saving = true
                scope.launch {
                    val shares = allocate(amountSubunits, selectedIds.map { UserId(it) to 1L })
                        .map { (id, owed) -> NewShare(id, owed) }
                    val input = NewExpense(
                        groupId = gid,
                        title = title,
                        amountSubunits = amountSubunits,
                        currency = currency,
                        expenseDate = Clock.System.todayUtc(),
                        payerUserId = payer,
                        splitMode = "EVEN",
                        createdBy = payer,
                        shares = shares,
                    )
                    when (expenses.addExpense(input)) {
                        is AppResult.Ok -> onSaved()
                        is AppResult.Err -> saving = false
                    }
                }
            }
        },
    )
}

/** Expense detail, wired: streams the expense + its shares into the read-only detail screen. */
@Composable
fun ExpenseDetailRoute(groupId: String, expenseId: String, onBack: () -> Unit, onSettleThis: () -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val gid = remember(groupId) { GroupId(groupId) }
    val detail by remember(eid) { expenses.observeExpense(eid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()

    val ews = detail
    if (ews == null) {
        ExpenseDetailScreen(state = ExpenseDetailState.Loading, onBack = onBack)
        return
    }
    val e = ews.expense
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    fun nameOf(id: UserId?): String = when {
        id == null -> "Someone"
        id == userId -> "You"
        else -> nameByUser[id.value] ?: "Someone"
    }
    val rows = ews.shares.map { s ->
        DetailShareUi(
            name = nameOf(s.userId),
            owedSubunits = s.owedSubunits,
            paidSubunits = s.owedSubunits - s.remainingSubunits,
            remainingSubunits = s.remainingSubunits,
            me = s.userId == userId,
            payer = s.userId == e.payerUserId,
        )
    }
    ExpenseDetailScreen(
        state = ExpenseDetailState.Content,
        title = e.title,
        category = "",
        payerName = if (e.payerUserId == null) (e.payerOutsideName ?: "Someone") else nameOf(e.payerUserId),
        dateLabel = e.expenseDate,
        amountSubunits = e.amountSubunits,
        remainingSubunits = ews.shares.sumOf { it.remainingSubunits },
        currencyCode = e.currency,
        splitLabel = "Split between ${ews.shares.size} · ${e.splitMode.lowercase()}",
        splitRows = rows,
        onBack = onBack,
        onSettleThis = onSettleThis,
    )
}
