package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.dao.ShareDao
import app.splitevenly.data.db.dao.UserDao
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.ui.screen.reconcile.ReconcilePerson
import app.splitevenly.ui.screen.reconcile.ReconcileScreen
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Reconcile, wired (03 §8): lists the group's placeholder identities + the expenses booked under each,
 * and claims the selected ones onto the current user via [GroupRepository.reconcilePlaceholder] (which
 * reassigns their shares/paid expenses and retires the placeholder).
 */
@Composable
fun ReconcileRoute(groupId: String, onBack: () -> Unit, onDone: () -> Unit) {
    val groups = koinInject<GroupRepository>()
    val userDao = koinInject<UserDao>()
    val shareDao = koinInject<ShareDao>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val placeholders by remember(groupId) { userDao.observePlaceholdersInGroup(groupId) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    // Each card = a placeholder + the expenses it's booked into (one suspend fetch per placeholder).
    var people by remember { mutableStateOf<List<ReconcilePerson>>(emptyList()) }
    LaunchedEffect(placeholders) {
        people = placeholders.map { p ->
            val expenses = shareDao.expensesForUser(groupId, p.id).map { it.title to (it.amountSubunits / 100.0) }
            ReconcilePerson(id = p.id, name = p.displayName, expenses = expenses)
        }
    }

    ReconcileScreen(
        groupName = group?.name ?: "",
        people = people,
        onBack = onBack,
        onConfirm = { ids ->
            userId?.let { me ->
                scope.launch {
                    ids.forEach { placeholderId -> groups.reconcilePlaceholder(gid, UserId(placeholderId), me) }
                    onDone()
                }
            }
        },
        onNotMe = onBack,
    )
}
