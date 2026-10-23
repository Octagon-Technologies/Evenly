package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.error.AppResult
import app.splitevenly.data.claim.PlaceholderClaimCoordinator
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.group.Group
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.EvAnalytics
import app.splitevenly.platform.PlaceholderClaimSources
import app.splitevenly.ui.screen.group.IdentityAnswer
import app.splitevenly.ui.screen.group.IdentityChecklistPage
import app.splitevenly.ui.screen.group.IdentityClaimConfirmSheet
import app.splitevenly.ui.screen.group.identityAnswer
import app.splitevenly.ui.screen.group.submit
import app.splitevenly.ui.screen.group.toUi
import app.splitevenly.ui.screen.home.JoinGroupSheet
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/**
 * Join-by-invite, wired: resolves the token to a group preview, asks "Are any of these you?" when the
 * group has names with no account, then joins via [GroupRepository.joinByToken].
 *
 * **The names step is required, not skippable** (owner call, 2026-10-03). Testers given a "Later" read
 * it as "I don't have to do this", so joining a group with unclaimed names means answering for every
 * one of them: either "none of these are me" or the ticked names are you and the rest are not.
 *
 * Nothing is written until the confirm sheet's button: Back from the step returns to the preview, and
 * leaving writes no membership and no answers. On confirm the join lands first, then the answer goes to
 * [PlaceholderClaimCoordinator], so a claim made while joining gets the same server first-claim-wins
 * guard and 5-second Undo (shown on the group screen this opens) as one made from the card.
 */
@Composable
fun JoinRoute(
    token: String,
    onDismiss: () -> Unit,
    onOpenGroup: (String) -> Unit,
) {
    val groups = koinInject<GroupRepository>()
    val claims = koinInject<PlaceholderClaimCoordinator>()
    val auth = koinInject<AuthSession>()
    val koin = getKoin()
    val analytics = remember { koin.getOrNull<EvAnalytics>() }
    val scope = rememberCoroutineScope()
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    var group by remember(token) { mutableStateOf<Group?>(null) }
    var resolved by remember(token) { mutableStateOf(false) }
    LaunchedEffect(token) {
        group = groups.findGroupByToken(token)
        resolved = true
    }
    val g = group
    // Existing roster (placeholders + everyone who's joined via the link) so the sheet can show the
    // member count for "is this actually my group?" confirmation. Empty until the token resolves.
    val membersFlow = remember(g?.id) { g?.id?.let { groups.observeMembers(it) } ?: flowOf(emptyList()) }
    val members by membersFlow.collectAsStateWithLifecycle(emptyList())
    // The same source as the in-group card, so a name answered here never comes back there. It works
    // before joining: it filters on the group's placeholders and this person's own answers, not on
    // membership. Empty for a group this device has never synced, which the card then catches.
    val unclaimed by remember(g?.id, userId) {
        val gid = g?.id
        val me = userId
        if (gid != null && me != null) groups.observeUnclaimedNames(gid, me) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val names = remember(unclaimed) { unclaimed.map { it.toUi() } }

    var askingNames by remember(token) { mutableStateOf(false) }
    var pendingAnswer by remember { mutableStateOf<IdentityAnswer?>(null) }

    fun join(answer: IdentityAnswer?) {
        val me = userId ?: return
        scope.launch {
            when (val r = groups.joinByToken(token, me)) {
                is AppResult.Ok -> {
                    if (answer != null) {
                        claims.submit(r.value.id, answer, me)
                        answer.claim.forEach { _ ->
                            analytics?.capture(
                                AnalyticsEvents.PLACEHOLDER_CLAIMED,
                                mapOf("group_id" to r.value.id.value, "claim_source" to PlaceholderClaimSources.GROUP_JOIN),
                            )
                        }
                    }
                    onOpenGroup(r.value.id.value)
                }

                is AppResult.Err -> Unit
            }
        }
    }

    if (askingNames && g != null) {
        IdentityChecklistPage(
            topTitle = "",
            topSubtitle = null,
            caption = "Joining ${g.name}",
            names = names,
            onBack = { askingNames = false },
            onConfirm = { selected -> scope.launch { pendingAnswer = groups.identityAnswer(g.id, selected, names) } },
            onNoneOfThese = { pendingAnswer = IdentityAnswer(emptyList(), names.map { it.id }) },
        )
    } else {
        JoinGroupSheet(
            groupName = g?.name ?: if (resolved) "" else "Checking invite…",
            emoji = g?.emoji ?: "🔗",
            memberNames = members.map { it.displayName?.takeIf { n -> n.isNotBlank() } ?: "Member" },
            found = g != null || !resolved,
            onDismiss = onDismiss,
            onJoin = { if (names.isNotEmpty()) askingNames = true else join(null) },
            onOpen = { g?.let { onOpenGroup(it.id.value) } },
        )
    }

    pendingAnswer?.let { answer ->
        IdentityClaimConfirmSheet(
            people = answer.claim,
            othersCount = if (answer.claim.isEmpty()) 0 else answer.notMe.size,
            noneConfirmLabel = "Join as a new member",
            noneSub = "You'll join as a new member. If you change your mind, you can claim a name from group settings.",
            onConfirm = {
                pendingAnswer = null
                join(answer)
            },
            onCancel = { pendingAnswer = null },
        )
    }
}
