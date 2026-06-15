package da.chelimo.sharecost.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.NewGroup
import da.chelimo.sharecost.domain.repository.GroupRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home group-list state (06 §3.3). Reactively maps the current user's groups (local-first `Flow` off
 * Room) into [HomeUiState]; [createGroup] writes through [GroupRepository]. Balance/member rollups
 * for each card are a follow-up slice — a fresh group has no debts, so it reads as "Settled".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val groups: GroupRepository,
    private val auth: AuthSession,
) : ViewModel() {

    val state: StateFlow<HomeUiState> =
        auth.currentUserId
            .flatMapLatest { uid ->
                if (uid == null) {
                    flowOf(HomeUiState.Empty)
                } else {
                    groups.observeGroupsForUser(uid).map { list ->
                        if (list.isEmpty()) HomeUiState.Empty
                        else HomeUiState.Content(active = list.map { it.toCard() }, archived = emptyList())
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

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

private fun Group.toCard(): GroupCardUi = GroupCardUi(
    id = id.value,
    emoji = emoji,
    name = name,
    members = 1,
    status = GroupBalanceStatus.Settled,
    amount = null,
    last = "Tap to open",
)
