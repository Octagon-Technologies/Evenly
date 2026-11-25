package app.splitevenly.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.group.GroupKind
import app.splitevenly.domain.group.NewGroup
import app.splitevenly.domain.repository.ExpenseRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.ProfileRepository
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvEmptyState
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.screen.home.HomeUiState
import app.splitevenly.ui.screen.home.HomeViewModel
import app.splitevenly.ui.screen.home.QuickSplitRow
import app.splitevenly.ui.screen.quick.QuickSplitNamesScreen
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** The name a quick split's hidden group carries until its bill is saved and lends it a title. */
private const val QUICK_SPLIT_DEFAULT_NAME = "Quick split"

/**
 * Step 1 of "Split a bill": type the names, then create the hidden group with a typed-in name per
 * person and hand over to the ordinary expense editor for the bill itself (step 2).
 */
@Composable
fun QuickSplitNewRoute(
    onBack: () -> Unit,
    onCreated: (groupId: String) -> Unit,
) {
    val groups = koinInject<GroupRepository>()
    val profiles = koinInject<ProfileRepository>()
    val auth = koinInject<AuthSession>()
    val scope = rememberCoroutineScope()
    val profile by profiles.observeProfile().collectAsStateWithLifecycle(null)
    val names = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    QuickSplitNamesScreen(
        names = names,
        onAdd = { names += it },
        onRemove = { names.removeAt(it) },
        onBack = onBack,
        busy = busy,
        error = error,
        onNext = {
            val uid = auth.currentUserId.value ?: return@QuickSplitNamesScreen
            if (busy || names.isEmpty()) return@QuickSplitNamesScreen
            busy = true
            error = null
            scope.launch {
                val input =
                    NewGroup(
                        name = QUICK_SPLIT_DEFAULT_NAME,
                        baseCurrency = profile?.baseCurrency ?: "USD",
                        creatorUserId = uid,
                        emoji = "🧾",
                        kind = GroupKind.Quick,
                    )
                when (val created = groups.createGroup(input)) {
                    is AppResult.Ok -> {
                        // Local writes: these only fail on a validation problem the names step already ruled out.
                        names.forEach { groups.addPlaceholder(created.value.id, it, createdBy = uid) }
                        onCreated(created.value.id.value)
                    }

                    is AppResult.Err -> {
                        error = "Couldn't start the split. Please try again."
                    }
                }
                busy = false
            }
        },
    )
}

/**
 * The only door into a quick split. Resolves its one bill and lands on it, or resumes the editor when
 * the bill was never saved. [justCreated] lands on the share screen with the share sheet open (mockup
 * 4), with the bill underneath so Back from sharing shows the split.
 *
 * Also gives the hidden group the bill's title, so the invite and the web page name the bill rather
 * than "Quick split".
 */
@Composable
fun QuickSplitRoute(
    groupId: String,
    justCreated: Boolean,
    navController: NavHostController,
) {
    val groups = koinInject<GroupRepository>()
    val expenses = koinInject<ExpenseRepository>()
    val gid = remember(groupId) { GroupId(groupId) }
    LaunchedEffect(gid, justCreated) {
        val bill = expenses.observeExpenses(gid).first().minByOrNull { it.createdAt }
        val here = Route.QuickSplit(groupId, justCreated)
        if (bill == null) {
            navController.navigate(Route.AddExpense(groupId)) { popUpTo(here) { inclusive = true } }
            return@LaunchedEffect
        }
        val group = groups.observeGroup(gid).first()
        if (group != null && group.name == QUICK_SPLIT_DEFAULT_NAME && bill.title.isNotBlank()) {
            groups.renameGroup(gid, bill.title)
        }
        navController.navigate(Route.ExpenseDetail(groupId, bill.id.value)) { popUpTo(here) { inclusive = true } }
        if (justCreated) navController.navigate(Route.ShareBillLink(groupId, bill.id.value))
    }
    Box(Modifier.fillMaxSize().background(EvenlyTheme.colors.page))
}

/**
 * Opens a quick split from a card that already knows its bill: straight to the bill in one navigation.
 *
 * Going through [Route.GroupHome] instead costs three back-to-back destination changes ([QuickSplitGuard]
 * redirects to [Route.QuickSplit], which redirects to the bill), each with its own crossfade over a blank
 * page and a database read gating the next. Without a bill id (a bill never saved), [Route.QuickSplit]
 * still resolves it and resumes the editor.
 */
internal fun NavHostController.openQuickSplit(
    groupId: String,
    expenseId: String?,
) {
    if (expenseId == null) navigate(Route.QuickSplit(groupId)) else navigate(Route.ExpenseDetail(groupId, expenseId))
}

/**
 * Wraps the group screen so a quick split never shows one: every door that names a group (a Home tap,
 * a notification, finishing a join) passes through here and is redirected to [Route.QuickSplit].
 */
@Composable
fun QuickSplitGuard(
    groupId: String,
    onQuickSplit: () -> Unit,
    content: @Composable () -> Unit,
) {
    val groups = koinInject<GroupRepository>()
    // Decided once from the first read: a group never changes kind, and re-deciding on every emission
    // would flash the group screen while the stream catches up.
    var quick by remember(groupId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(groupId) { quick = groups.observeGroup(GroupId(groupId)).first()?.isQuick == true }
    when (quick) {
        true -> {
            LaunchedEffect(Unit) { onQuickSplit() }
            Box(Modifier.fillMaxSize().background(EvenlyTheme.colors.page))
        }

        false -> {
            content()
        }

        null -> {
            Box(Modifier.fillMaxSize().background(EvenlyTheme.colors.page))
        }
    }
}

/** Whether [groupId] is a quick split, false until known (a normal group is the safe default). */
@Composable
fun rememberIsQuickSplit(groupId: String): Boolean {
    val groups = koinInject<GroupRepository>()
    return remember(groupId) { groups.observeGroup(GroupId(groupId)) }.collectAsStateWithLifecycle(null).value?.isQuick == true
}

/**
 * Opens a quick-split link the app was launched with: resolve the bill token to its group, then join it
 * through the ordinary invite screen, where the friend picks their typed-in name (A3). A dead link, or
 * one that is not a quick split, falls back to the web page.
 */
@Composable
internal fun OpenPendingQuickSplit(
    navController: NavHostController,
    onFallbackToWeb: (token: String) -> Unit,
) {
    val groups = koinInject<GroupRepository>()
    val token by PendingInvite.quickSplitToken.collectAsStateWithLifecycle()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    LaunchedEffect(token, destination) {
        val t = token ?: return@LaunchedEffect
        val d = destination ?: return@LaunchedEffect
        val inAuth =
            d.hasRoute<Route.Welcome>() || d.hasRoute<Route.SignIn>() ||
                d.hasRoute<Route.MagicLink>() || d.hasRoute<Route.Onboarding>()
        if (inAuth) return@LaunchedEffect
        PendingInvite.consumeQuickSplit()
        val group = groups.findQuickSplitByBillToken(t)
        if (group == null) onFallbackToWeb(t) else navController.navigate(Route.Join(group.inviteToken))
    }
}
