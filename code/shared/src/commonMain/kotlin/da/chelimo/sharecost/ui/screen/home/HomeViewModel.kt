package da.chelimo.sharecost.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.NewGroup
import da.chelimo.sharecost.domain.repository.ExpenseRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Home group-list state (06 §3.3). Reactively maps the current user's groups (local-first `Flow` off
 * Room) into [HomeUiState]; [createGroup] writes through [GroupRepository]. Each card's member count +
 * the viewer's net balance are rolled up live from the members/balances streams (F4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val groups: GroupRepository,
    private val expenses: ExpenseRepository,
    private val auth: AuthSession,
) : ViewModel() {

    val state: StateFlow<HomeUiState> =
        auth.currentUserId
            .flatMapLatest { uid ->
                if (uid == null) {
                    flowOf(HomeUiState.Empty)
                } else {
                    combine(cardsFlow(uid, archived = false), cardsFlow(uid, archived = true)) { active, archived ->
                        if (active.isEmpty() && archived.isEmpty()) HomeUiState.Empty
                        else HomeUiState.Content(active = active, archived = archived)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    /** Live cards for the user's active (or archived) groups, each rolled up from its members + balances. */
    private fun cardsFlow(uid: UserId, archived: Boolean): Flow<List<GroupCardUi>> {
        val source = if (archived) groups.observeArchivedGroupsForUser(uid) else groups.observeGroupsForUser(uid)
        return source.flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.map { cardFlow(it, uid, archived) }) { it.toList() }
        }
    }

    /** A live card for one group: real member count + the viewer's net (in the group base currency). */
    private fun cardFlow(group: Group, uid: UserId, archived: Boolean): Flow<GroupCardUi> =
        combine(
            groups.observeMembers(group.id),
            expenses.observeBalances(group.id),
            expenses.observeExpenses(group.id),
        ) { members, debts, groupExpenses ->
            // creditor==you → owed to you (+); debtor==you → you owe (−). Sum nets across peers.
            val netSubunits = if (archived) 0L else debts.sumOf { d ->
                when (uid) {
                    d.creditorUserId -> d.amountSubunits
                    d.debtorUserId -> -d.amountSubunits
                    else -> 0L
                }
            }
            group.toCard(
                memberCount = members.size.coerceAtLeast(1),
                netSubunits = netSubunits,
                hasExpenses = groupExpenses.isNotEmpty(),
            )
        }

    private val _errors = Channel<String>(Channel.BUFFERED)
    val errors = _errors.receiveAsFlow()

    fun createGroup(name: String, emoji: String, onCreated: (String) -> Unit) {
        val uid = auth.currentUserId.value ?: return
        viewModelScope.launch {
            val input = NewGroup(name = name.ifBlank { "New group" }, baseCurrency = "USD", creatorUserId = uid, emoji = emoji)
            when (val result = groups.createGroup(input)) {
                is AppResult.Ok -> onCreated(result.value.id.value)
                is AppResult.Err -> _errors.send("Couldn't create the group. Please try again.")
            }
        }
    }
}

private fun Group.toCard(memberCount: Int, netSubunits: Long, hasExpenses: Boolean): GroupCardUi = GroupCardUi(
    id = id.value,
    emoji = emoji,
    name = name,
    members = memberCount,
    // A group with no expenses yet isn't "settled" — there's nothing to settle. Empty is its own state:
    // no chip, and an honest subtitle rather than a misleading "All settled up".
    status = when {
        !hasExpenses -> GroupBalanceStatus.Empty
        netSubunits > 0L -> GroupBalanceStatus.Owed
        netSubunits < 0L -> GroupBalanceStatus.Owe
        else -> GroupBalanceStatus.Settled
    },
    amount = if (netSubunits == 0L) null else abs(netSubunits) / 100.0,
    last = when {
        !hasExpenses -> "No expenses yet"
        netSubunits > 0L -> "You're owed"
        netSubunits < 0L -> "You owe"
        else -> "All settled up"
    },
)
