package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.group.RecentlyDeleted
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.platform.SecureStorage
import app.splitevenly.ui.screen.home.DeletedGroupCardUi
import app.splitevenly.ui.screen.home.HomeFilter
import app.splitevenly.ui.screen.home.HomeUiState
import app.splitevenly.ui.screen.home.HomeViewModel
import app.splitevenly.ui.screen.home.PutAwayScreen
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock

/**
 * Put away, wired. Archived cards come off the Home rollup (so member counts and balances are real);
 * deleted ones are read directly because Home only carries their count. "Deleted by ..." is resolved
 * here since the screen is DI-free and cannot tell whose id it holds.
 */
@Composable
fun PutAwayRoute(
    filter: HomeFilter,
    startOnDeleted: Boolean,
    onBack: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenQuickSplit: (groupId: String, expenseId: String?) -> Unit,
) {
    val vm = koinViewModel<HomeViewModel>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val storage = koinInject<SecureStorage>()
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val deleted by remember(userId) {
        userId?.let { groups.observeDeletedGroups(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    var message by remember { mutableStateOf<String?>(null) }
    val content = state as? HomeUiState.Content
    val now = Clock.System.nowEpochMillis()

    PutAwayScreen(
        filter = filter,
        archived = content?.archived.orEmpty(),
        history = content?.history.orEmpty(),
        deleted =
            deleted.map { g ->
                val daysLeft = RecentlyDeleted.daysLeft(g.deletedAt, now)
                DeletedGroupCardUi(
                    id = g.id.value,
                    emoji = g.emoji,
                    name = g.name,
                    deletedByLabel =
                        when {
                            g.deletedBy != null && g.deletedBy == userId -> "Deleted by you"

                            // A deleter not synced yet, or anonymized by account deletion. "Deleted" alone
                            // is honest; inventing "a member" is not.
                            g.deletedByName.isNullOrBlank() -> "Deleted"

                            else -> "Deleted by ${g.deletedByName}"
                        },
                    daysLeft = daysLeft,
                    urgent = daysLeft <= URGENT_DAYS_LEFT,
                    isQuick = g.isQuick,
                )
            },
        startOnDeleted = startOnDeleted,
        onBack = onBack,
        onOpen = { g -> if (g.quick != null) onOpenQuickSplit(g.id, g.quick.expenseId) else onOpenGroup(g.id) },
        onUnarchive = { id ->
            val me = userId ?: return@PutAwayScreen
            val name = (content?.archived.orEmpty()).firstOrNull { it.id == id }?.name
            scope.launch {
                groups.setArchived(GroupId(id), me, archived = false)
                message = "${name ?: "It"} is back on Home"
            }
        },
        onRestoreDeleted = { id ->
            val me = userId ?: return@PutAwayScreen
            val name = deleted.firstOrNull { it.id.value == id }?.name
            scope.launch {
                message =
                    when (groups.restoreGroup(GroupId(id), me)) {
                        is AppResult.Ok -> "${name ?: "It"} is back for everyone"
                        is AppResult.Err -> "Couldn't restore that. Please try again."
                    }
            }
        },
        // Seeing the Recently deleted card IS the acknowledgement that clears Home's banner. Keyed on
        // the newest stamp, so a delete that lands while the card is open also counts as seen.
        onDeletedShown = {
            val newest = deleted.maxOfOrNull { it.deletedAt } ?: 0L
            if (newest > 0L) scope.launch { storage.putString(DELETES_SEEN_KEY, newest.toString()) }
        },
        message = message,
    )
}
