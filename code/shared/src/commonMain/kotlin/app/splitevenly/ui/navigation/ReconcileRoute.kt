package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.id.GroupId
import app.splitevenly.data.claim.IdentityPromptSnooze
import app.splitevenly.data.claim.PlaceholderClaimCoordinator
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.EvAnalytics
import app.splitevenly.ui.screen.group.IdentityAnswer
import app.splitevenly.ui.screen.group.IdentityClaimConfirmSheet
import app.splitevenly.ui.screen.group.identityAnswer
import app.splitevenly.ui.screen.group.submit
import app.splitevenly.ui.screen.group.toUi
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
 * visible before it moves, and through the same [PlaceholderClaimCoordinator], so it gets the server's
 * first-claim-wins guard. It is flushed at once rather than left behind the undo window: this screen is
 * the deliberate, went-looking path, and it closes on confirm.
 */
@Composable
fun ReconcileRoute(
    groupId: String,
    source: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val groups = koinInject<GroupRepository>()
    val claims = koinInject<PlaceholderClaimCoordinator>()
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
    val names = remember(unclaimed) { unclaimed.map { it.toUi() } }
    val scope = rememberCoroutineScope()
    var pendingAnswer by remember { mutableStateOf<IdentityAnswer?>(null) }

    ReconcileScreen(
        groupName = group?.name ?: "",
        names = names,
        onBack = onBack,
        onConfirm = { selected -> scope.launch { pendingAnswer = groups.identityAnswer(gid, selected, names) } },
        onNoneOfThese = { pendingAnswer = IdentityAnswer(emptyList(), names.map { it.id }) },
    )

    pendingAnswer?.let { answer ->
        IdentityClaimConfirmSheet(
            people = answer.claim,
            othersCount = if (answer.claim.isEmpty()) 0 else answer.notMe.size,
            noneConfirmLabel = "None of these are me",
            noneSub = "We won't ask about these names again.",
            onConfirm = {
                pendingAnswer = null
                // Only this explicit tap writes anything. Backing out writes nothing, because "opened and
                // left" also means "I'm not sure" and "I mis-tapped back".
                val me = userId ?: return@IdentityClaimConfirmSheet
                scope.launch {
                    claims.submit(gid, answer, me)
                    // Flushed at once rather than left behind the undo window: this screen closes on
                    // confirm, so there is nowhere left to show an Undo.
                    claims.flush()
                    answer.claim.forEach { _ ->
                        analytics?.capture(
                            AnalyticsEvents.PLACEHOLDER_CLAIMED,
                            mapOf("group_id" to groupId, "claim_source" to source),
                        )
                    }
                    snooze.markFinished(gid)
                    onDone()
                }
            },
            onCancel = { pendingAnswer = null },
        )
    }
}
