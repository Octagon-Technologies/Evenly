package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.time.todayUtc
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.expense.CategoryDefaults
import app.splitevenly.domain.repository.CategoryRepository
import app.splitevenly.domain.repository.ExpenseRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.ui.screen.group.GroupOverviewTab
import app.splitevenly.ui.screen.group.buildCategorySpend
import app.splitevenly.ui.screen.group.buildGroupHistory
import app.splitevenly.ui.screen.group.buildOverview
import app.splitevenly.ui.screen.group.buildPersonalCategorySpend
import app.splitevenly.ui.screen.group.buildPersonalHistory
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Overview tab, wired: streams the group + expenses (with shares) + members + balances into the
 * aggregates and the Personal/Group spending tracker (your share vs the whole group's totals).
 */
@OptIn(ExperimentalTime::class)
@Composable
fun OverviewRoute(groupId: String, onBack: () -> Unit) {
    val expenses = koinInject<ExpenseRepository>()
    val groups = koinInject<GroupRepository>()
    val categoriesRepo = koinInject<CategoryRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val expenseList by remember(gid) { expenses.observeExpenses(gid) }.collectAsStateWithLifecycle(emptyList())
    val expensesWithShares by remember(gid) { expenses.observeExpensesWithShares(gid) }.collectAsStateWithLifecycle(emptyList())
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val balances by remember(gid) { expenses.observeBalances(gid) }.collectAsStateWithLifecycle(emptyList())
    val categories by remember(gid) { categoriesRepo.observeCategories(gid) }.collectAsStateWithLifecycle(CategoryDefaults.all)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val today = remember { Clock.System.todayUtc() }

    val ui = buildOverview(group, expenseList, members, balances, today)
    GroupOverviewTab(
        groupEmoji = ui.groupEmoji,
        groupName = ui.groupName,
        personalSpend = buildPersonalCategorySpend(expensesWithShares, userId, categories),
        groupSpend = buildCategorySpend(expenseList, categories),
        personalHistory = buildPersonalHistory(expensesWithShares, userId, categories),
        groupHistory = buildGroupHistory(expenseList, categories),
        byDay = ui.byDay,
        byMember = ui.byMember,
        totalSubunits = ui.totalSubunits,
        expenseCount = ui.expenseCount,
        perPersonSubunits = ui.perPersonSubunits,
        currencyCode = ui.currencyCode,
        balances = ui.balances,
        onBack = onBack,
    )
}
