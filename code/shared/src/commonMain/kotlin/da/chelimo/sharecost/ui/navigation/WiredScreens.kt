package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.auth.NotificationPrefs
import da.chelimo.sharecost.domain.auth.OAuthProvider
import da.chelimo.sharecost.domain.auth.ThemeMode
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.NewGroup
import da.chelimo.sharecost.domain.repository.FxRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.domain.repository.ProfileRepository
import da.chelimo.sharecost.domain.settlement.PaymentApp
import da.chelimo.sharecost.platform.UrlOpener
import da.chelimo.sharecost.ui.screen.auth.MagicLinkScreen
import da.chelimo.sharecost.ui.screen.auth.MagicLinkState
import da.chelimo.sharecost.ui.screen.auth.OnboardingScreen
import da.chelimo.sharecost.ui.screen.auth.SignInScreen
import da.chelimo.sharecost.ui.screen.home.ArchivedScreen
import da.chelimo.sharecost.ui.screen.home.HomeScreen
import da.chelimo.sharecost.ui.screen.home.HomeUiState
import da.chelimo.sharecost.ui.screen.home.HomeViewModel
import da.chelimo.sharecost.ui.screen.home.JoinGroupSheet
import da.chelimo.sharecost.ui.screen.home.NewGroupSheet
import da.chelimo.sharecost.ui.screen.settings.PaymentHandlesScreen
import da.chelimo.sharecost.ui.screen.settings.ProfileScreen
import da.chelimo.sharecost.ui.screen.settle.appLabel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Stateful wrappers that bind the (stateless, previewable) screens to ViewModels / the auth session.
 * The NavHost renders these for wired destinations; the screens themselves stay free of DI so their
 * `@Preview`s keep working.
 */

/**
 * Sign-in entry: launches browser OAuth for Google/Apple/Facebook (the session arrives async via the
 * `sharecost://login-callback` redirect → [AuthSession.currentUserId]) and routes Email to the
 * magic-link/OTP screen. [onSignedIn] fires once a session exists — this also covers a restored
 * session on cold start (the user lands straight on Home).
 */
@Composable
fun SignInRoute(onEmail: () -> Unit, onSignedIn: () -> Unit) {
    val auth = koinInject<AuthSession>()
    val scope = rememberCoroutineScope()
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { if (userId != null) onSignedIn() }
    SignInScreen(onProvider = { provider ->
        when (provider) {
            "mail" -> onEmail()
            "google" -> scope.launch { auth.signInWithProvider(OAuthProvider.GOOGLE) }
            "apple" -> scope.launch { auth.signInWithProvider(OAuthProvider.APPLE) }
            "facebook" -> scope.launch { auth.signInWithProvider(OAuthProvider.FACEBOOK) }
        }
    })
}

/** Email magic-link + OTP: send a code, then verify it in-app. [onVerified] advances to onboarding. */
@Composable
fun MagicLinkRoute(onBack: () -> Unit, onVerified: () -> Unit) {
    val auth = koinInject<AuthSession>()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(MagicLinkState.Input) }
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    MagicLinkScreen(
        state = state,
        error = error,
        onBack = onBack,
        onSend = { e ->
            email = e; error = null; state = MagicLinkState.Loading
            scope.launch {
                when (auth.sendEmailOtp(e)) {
                    is AppResult.Ok -> state = MagicLinkState.Sent
                    is AppResult.Err -> { state = MagicLinkState.Input; error = "Couldn't send the code. Check the address and try again." }
                }
            }
        },
        onVerify = { code ->
            error = null
            scope.launch {
                when (auth.verifyEmailOtp(email, code)) {
                    is AppResult.Ok -> onVerified()
                    is AppResult.Err -> error = "That code didn't work — check it or resend."
                }
            }
        },
        onResend = { scope.launch { auth.sendEmailOtp(email) } },
    )
}

/** First-run profile capture: persists the chosen display name + base currency, then continues. */
@Composable
fun OnboardingRoute(onFinished: () -> Unit) {
    val profiles = koinInject<ProfileRepository>()
    val scope = rememberCoroutineScope()
    val profile by profiles.observeProfile().collectAsStateWithLifecycle(null)
    OnboardingScreen(
        initialName = profile?.displayName?.takeIf { it.isNotBlank() && it != "You" } ?: "",
        initialCurrency = profile?.baseCurrency ?: "USD",
        onFinish = { name, currency -> scope.launch { profiles.updateProfile(name, currency); onFinished() } },
    )
}

@Composable
fun HomeRoute(onOpenGroup: (String) -> Unit, onNewGroup: () -> Unit, onOpenProfile: () -> Unit, onOpenArchived: () -> Unit) {
    val vm = koinViewModel<HomeViewModel>()
    val fx = koinInject<FxRepository>()
    // Cold-start FX refresh (F2) — best-effort, so foreign-currency balances use today's rate.
    LaunchedEffect(Unit) { fx.refreshIfStale() }
    val state by vm.state.collectAsStateWithLifecycle()
    HomeScreen(state = state, onOpenGroup = onOpenGroup, onNewGroup = onNewGroup, onOpenProfile = onOpenProfile, onOpenArchived = onOpenArchived)
}

/** Join-by-invite, wired: resolves the token to a group preview, then joins via [GroupRepository.joinByToken]. */
@Composable
fun JoinRoute(token: String, onDismiss: () -> Unit, onOpenGroup: (String) -> Unit) {
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val scope = rememberCoroutineScope()
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    var group by remember(token) { mutableStateOf<Group?>(null) }
    var resolved by remember(token) { mutableStateOf(false) }
    LaunchedEffect(token) { group = groups.findGroupByToken(token); resolved = true }
    val g = group
    JoinGroupSheet(
        groupName = g?.name ?: if (resolved) "" else "Checking invite…",
        emoji = g?.emoji ?: "🔗",
        found = g != null || !resolved,
        onDismiss = onDismiss,
        onJoin = {
            userId?.let { me ->
                scope.launch {
                    when (val r = groups.joinByToken(token, me)) {
                        is AppResult.Ok -> onOpenGroup(r.value.id.value)
                        is AppResult.Err -> Unit
                    }
                }
            }
        },
        onOpen = { g?.let { onOpenGroup(it.id.value) } },
    )
}

/** Archived groups, wired: reuses the Home rollup (so member counts are real) and unarchives in place. */
@Composable
fun ArchivedRoute(onBack: () -> Unit) {
    val vm = koinViewModel<HomeViewModel>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val archived = (state as? HomeUiState.Content)?.archived ?: emptyList()
    ArchivedScreen(
        onBack = onBack,
        groups = archived,
        onUnarchive = { id -> userId?.let { me -> scope.launch { groups.setArchived(GroupId(id), me, archived = false) } } },
    )
}

@Composable
fun NewGroupRoute(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val scope = rememberCoroutineScope()
    NewGroupSheet(
        onDismiss = onDismiss,
        onCreate = { name, emoji ->
            val uid = auth.currentUserId.value ?: return@NewGroupSheet
            scope.launch {
                val input = NewGroup(name = name.ifBlank { "New group" }, baseCurrency = "USD", creatorUserId = uid, emoji = emoji)
                when (val result = groups.createGroup(input)) {
                    is AppResult.Ok -> onCreated(result.value.id.value)
                    is AppResult.Err -> Unit
                }
            }
        },
    )
}

@Composable
fun ProfileRoute(onBack: () -> Unit, onSignedOut: () -> Unit, onEditPaymentApps: () -> Unit) {
    val auth = koinInject<AuthSession>()
    val profiles = koinInject<ProfileRepository>()
    val urlOpener = koinInject<UrlOpener>()
    val scope = rememberCoroutineScope()
    val profile by profiles.observeProfile().collectAsStateWithLifecycle(null)
    val handles = profile?.paymentHandles ?: emptyMap()
    ProfileScreen(
        displayName = profile?.displayName ?: "You",
        email = profile?.email ?: "",
        baseCurrency = profile?.baseCurrency ?: "USD",
        paymentAppsSummary = paymentAppsSummary(handles),
        onBack = onBack,
        onSignOut = { auth.signOut(); onSignedOut() },
        onEditPaymentApps = onEditPaymentApps,
        onEditName = { name -> scope.launch { profiles.updateDisplayName(name) } },
        onSendFeedback = { urlOpener.open("mailto:feedback@sharecost.app?subject=ShareCost%20feedback") },
        onPrivacy = { urlOpener.open("https://sharecost.app/privacy") },
        onTerms = { urlOpener.open("https://sharecost.app/terms") },
        notifications = profile?.notifications ?: NotificationPrefs(),
        onNotificationsChange = { prefs -> scope.launch { profiles.updateNotificationPrefs(prefs) } },
        themeMode = profile?.themeMode ?: ThemeMode.System,
        onThemeModeChange = { mode -> scope.launch { profiles.updateThemeMode(mode) } },
    )
}

/** Payment-handles editor, wired: streams the current account's handles and saves edits in place. */
@Composable
fun PaymentHandlesRoute(onBack: () -> Unit) {
    val profiles = koinInject<ProfileRepository>()
    val scope = rememberCoroutineScope()
    val profile by profiles.observeProfile().collectAsStateWithLifecycle(null)
    PaymentHandlesScreen(
        initial = profile?.paymentHandles ?: emptyMap(),
        onBack = onBack,
        onSave = { handles -> scope.launch { profiles.updatePaymentHandles(handles); onBack() } },
    )
}

/** "Venmo +2" / "Not set" — the Profile row preview of how many payment apps are configured. */
private fun paymentAppsSummary(handles: Map<PaymentApp, String>): String {
    if (handles.isEmpty()) return "Not set"
    val first = handles.keys.first().appLabel
    val extra = handles.size - 1
    return if (extra > 0) "$first +$extra" else first
}
