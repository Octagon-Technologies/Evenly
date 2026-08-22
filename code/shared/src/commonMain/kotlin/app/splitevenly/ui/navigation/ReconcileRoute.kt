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
import app.splitevenly.data.claim.IdentityPromptSnooze
import app.splitevenly.data.db.dao.ShareDao
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.group.ClaimPreview
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.EvAnalytics
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.screen.reconcile.ClaimLineUi
import app.splitevenly.ui.screen.reconcile.ReconcileConfirmModal
import app.splitevenly.ui.screen.reconcile.ReconcilePerson
import app.splitevenly.ui.screen.reconcile.ReconcileScreen
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/**
 * "Claim a name", wired — the full-screen list behind the card's "See all N" and the Group settings
 * row.
 *
 * Reads the **same** source as the card ([GroupRepository.observeUnclaimedNames]): unclaimed names in
 * this group, minus the ones this member has already answered, minus the ones they created. That is
 * what makes the list narrow as it gets answered instead of needing its own dismissed state.
 *
 * A claim from here goes through the same confirm sheet as the card, because the money has to be
 * visible before it moves. It commits immediately rather than behind the undo window: this screen is
 * the deliberate, went-looking path, and it closes on confirm, which would flush the window anyway.
 */
@Composable
fun ReconcileRoute(
    groupId: String,
    source: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val groups = koinInject<GroupRepository>()
    val shareDao = koinInject<ShareDao>()
    val snooze = koinInject<IdentityPromptSnooze>()
    val auth = koinInject<AuthSession>()
    val koin = getKoin()
    val analytics = remember { koin.getOrNull<EvAnalytics>() }
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val unclaimed by remember(gid, userId) {
        userId?.let { groups.observeUnclaimedNames(gid, it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    // Each card = a name + the expenses it's booked into (one suspend fetch per name).
    var people by remember { mutableStateOf<List<ReconcilePerson>>(emptyList()) }
    LaunchedEffect(unclaimed) {
        people =
            unclaimed.map { n ->
                val expenses = shareDao.expensesForUser(groupId, n.userId.value).map { it.title to (it.amountSubunits / 100.0) }
                ReconcilePerson(id = n.userId.value, name = n.displayName, expenses = expenses)
            }
    }

    var pendingConfirm by remember { mutableStateOf<ClaimPreview?>(null) }
    var pendingIds by remember { mutableStateOf<List<String>>(emptyList()) }

    ReconcileScreen(
        groupName = group?.name ?: "",
        people = people,
        onBack = onBack,
        onConfirm = { ids ->
            scope.launch {
                val first = unclaimed.firstOrNull { it.userId.value == ids.firstOrNull() } ?: return@launch
                pendingIds = ids
                // The sheet previews the first name; claiming several at once is rare enough that
                // showing one preview and naming the rest beats stacking sheets.
                pendingConfirm = groups.claimPreview(gid, first.userId, first.displayName)
            }
        },
        onNotMe = { id ->
            userId?.let { me ->
                scope.launch { groups.answerNotMe(gid, listOf(UserId(id)), me) }
                snooze.markFinished(gid)
            }
        },
        onNoneOfThese = {
            // Only this explicit tap writes the answers. Backing out of the screen writes nothing,
            // because "opened and left" also means "I'm not sure" and "I mis-tapped back".
            userId?.let { me ->
                scope.launch {
                    groups.answerNotMe(gid, unclaimed.map { it.userId }, me)
                    snooze.markFinished(gid)
                    onDone()
                }
            }
        },
    )

    pendingConfirm?.let { preview ->
        ReconcileConfirmModal(
            name = preview.name,
            owed = preview.owed.map { ClaimLineUi(it.title, moneySubunits(it.amountSubunits, it.currency)) },
            paid = preview.paid.map { ClaimLineUi(it.title, moneySubunits(it.amountSubunits, it.currency)) },
            owedTotalLabel =
                preview.currency
                    ?.takeIf { preview.owed.isNotEmpty() }
                    ?.let { moneySubunits(preview.owedTotalSubunits, it) },
            onConfirm = {
                val me = userId
                val ids = pendingIds
                pendingConfirm = null
                pendingIds = emptyList()
                if (me != null) {
                    scope.launch {
                        ids.forEach {
                            groups.reconcilePlaceholder(gid, UserId(it), me)
                            analytics?.capture(
                                AnalyticsEvents.PLACEHOLDER_CLAIMED,
                                mapOf("group_id" to groupId, "claim_source" to source),
                            )
                        }
                        snooze.markFinished(gid)
                        onDone()
                    }
                }
            },
            onCancel = {
                pendingConfirm = null
                pendingIds = emptyList()
            },
        )
    }
}
