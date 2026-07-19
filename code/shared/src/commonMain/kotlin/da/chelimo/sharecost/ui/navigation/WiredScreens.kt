package da.chelimo.sharecost.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.auth.NotificationPrefs
import da.chelimo.sharecost.domain.auth.OAuthProvider
import da.chelimo.sharecost.domain.auth.ThemeMode
import da.chelimo.sharecost.domain.fx.FxCurrencyDefaults
import da.chelimo.sharecost.domain.group.Group
import da.chelimo.sharecost.domain.group.NewGroup
import da.chelimo.sharecost.domain.repository.FxRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.domain.repository.ProfileRepository
import da.chelimo.sharecost.domain.settlement.PaymentApp
import da.chelimo.sharecost.platform.NotificationPermission
import da.chelimo.sharecost.platform.NotificationPermissionStatus
import da.chelimo.sharecost.platform.SecureStorage
import da.chelimo.sharecost.platform.UrlOpener
import da.chelimo.sharecost.platform.isDebugBuild
import da.chelimo.sharecost.ui.screen.auth.MagicLinkScreen
import da.chelimo.sharecost.ui.screen.auth.WelcomeScreen
import da.chelimo.sharecost.ui.screen.auth.MagicLinkState
import da.chelimo.sharecost.ui.screen.auth.OnboardingScreen
import da.chelimo.sharecost.ui.screen.auth.SignInScreen
import da.chelimo.sharecost.ui.screen.home.ArchivedScreen
import da.chelimo.sharecost.ui.screen.home.HomeScreen
import da.chelimo.sharecost.ui.screen.home.HomeUiState
import da.chelimo.sharecost.ui.screen.home.HomeViewModel
import da.chelimo.sharecost.ui.screen.home.JoinByLinkSheet
import da.chelimo.sharecost.ui.screen.home.JoinGroupSheet
import da.chelimo.sharecost.ui.screen.home.JoinPlaceholderOption
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

/**
 * Email magic-link + OTP: send a code, then verify it in-app. [onVerified] fires with
 * `needsOnboarding = true` only for a brand-new account; a returning user (already has a server
 * profile) gets `false` so the NavHost can send them straight to Home and skip onboarding.
 */
@Composable
fun MagicLinkRoute(onBack: () -> Unit, onVerified: (needsOnboarding: Boolean) -> Unit) {
    val auth = koinInject<AuthSession>()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(MagicLinkState.Input) }
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    MagicLinkScreen(
        state = state,
        error = error,
        allowPassword = isDebugBuild(),
        onBack = onBack,
        onSend = { e ->
            email = e; error = null; state = MagicLinkState.Loading
            scope.launch {
                when (val r = auth.sendEmailOtp(e)) {
                    is AppResult.Ok -> state = MagicLinkState.Sent
                    is AppResult.Err -> { state = MagicLinkState.Input; error = sendErrorMessage(r.error) }
                }
            }
        },
        onVerify = { code ->
            error = null
            scope.launch {
                when (auth.verifyEmailOtp(email, code)) {
                    is AppResult.Ok -> onVerified(!auth.hasOnboardedProfile())
                    is AppResult.Err -> error = "That code didn't work. Check it or resend."
                }
            }
        },
        onResend = { scope.launch { auth.sendEmailOtp(email) } },
        onPasswordSignIn = { e, pw ->
            email = e; error = null; state = MagicLinkState.Loading
            scope.launch {
                when (auth.signInWithPassword(e, pw)) {
                    is AppResult.Ok -> onVerified(!auth.hasOnboardedProfile())
                    is AppResult.Err -> { state = MagicLinkState.Input; error = "Couldn't sign in. Check the email and password." }
                }
            }
        },
    )
}

/** Turn an email-send failure into a message the user can act on (429 throttling vs. everything else). */
private fun sendErrorMessage(error: AppError): String = when {
    error is AppError.Backend && error.status == 429 ->
        "Too many requests. Wait a minute, then try again."
    else -> "Couldn't send the code. Check the address and try again."
}

/**
 * First-launch product intro. Shows the [WelcomeScreen] carousel; on Skip/Get-started it stamps the
 * device-local [WELCOME_SEEN_KEY] so the carousel never shows again, then continues to sign-in.
 */
@Composable
fun WelcomeRoute(onFinished: () -> Unit) {
    val storage = koinInject<SecureStorage>()
    val scope = rememberCoroutineScope()
    WelcomeScreen(onFinish = { scope.launch { storage.putString(WELCOME_SEEN_KEY, "1"); onFinished() } })
}

/** Device-local flag: set once the welcome carousel has been dismissed. */
const val WELCOME_SEEN_KEY = "welcome_seen"

/** First-run profile capture: persists the chosen display name + base currency, then continues. */
@Composable
fun OnboardingRoute(onFinished: () -> Unit) {
    val profiles = koinInject<ProfileRepository>()
    val notifications = koinInject<NotificationPermission>()
    val scope = rememberCoroutineScope()
    val profile by profiles.observeProfile().collectAsStateWithLifecycle(null)
    OnboardingScreen(
        initialName = profile?.displayName?.takeIf { it.isNotBlank() && it != "You" } ?: "",
        initialCurrency = profile?.baseCurrency ?: "USD",
        // The app's only notification-permission ask, and only on an explicit opt-in tap. The grant/refusal
        // isn't acted on here: the FCM token registers regardless, and the OS drops what it won't show.
        onEnableNotifications = { notifications.request() },
        onFinish = { name, currency -> scope.launch { profiles.updateProfile(name, currency); onFinished() } },
    )
}

@Composable
fun HomeRoute(
    onOpenGroup: (String) -> Unit,
    onNewGroup: () -> Unit,
    onJoin: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val vm = koinViewModel<HomeViewModel>()
    val fx = koinInject<FxRepository>()
    val profiles = koinInject<ProfileRepository>()
    // Cold-start FX refresh (F2) — best-effort, so foreign-currency balances use today's rate.
    LaunchedEffect(Unit) { fx.refreshIfStale() }
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by profiles.observeProfile().collectAsStateWithLifecycle(null)
    val name = profile?.displayName?.takeIf { it.isNotBlank() && it != "You" } ?: "there"
    HomeScreen(
        state = state,
        userName = name,
        onOpenGroup = onOpenGroup,
        onNewGroup = onNewGroup,
        onJoin = onJoin,
        onOpenArchived = onOpenArchived,
        onOpenSettings = onOpenSettings,
    )
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
    // Existing roster (placeholders + everyone who's joined via the link) so the sheet can show the
    // member count for "is this actually my group?" confirmation. Empty until the token resolves.
    val membersFlow = remember(g?.id) { g?.id?.let { groups.observeMembers(it) } ?: flowOf(emptyList()) }
    val members by membersFlow.collectAsStateWithLifecycle(emptyList())
    // Claimable identities = the still-active placeholders in this roster (already filtered to
    // unclaimed by observeMembers). Reusing the roster flow avoids a second query/injection.
    val placeholderOptions = members
        .filter { it.isPlaceholder }
        .map { JoinPlaceholderOption(it.userId.value, it.displayName?.takeIf { n -> n.isNotBlank() } ?: "Member") }
    JoinGroupSheet(
        groupName = g?.name ?: if (resolved) "" else "Checking invite…",
        emoji = g?.emoji ?: "🔗",
        memberNames = members.map { it.displayName?.takeIf { n -> n.isNotBlank() } ?: "Member" },
        placeholders = placeholderOptions,
        found = g != null || !resolved,
        onDismiss = onDismiss,
        onJoin = { claimId ->
            userId?.let { me ->
                scope.launch {
                    when (val r = groups.joinByToken(token, me, claimId?.let(::UserId))) {
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
fun NewGroupRoute(
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val fx = koinInject<FxRepository>()
    val scope = rememberCoroutineScope()
    // The FX provider's currency list is effectively static (fetched once, cached locally by the
    // repository) — loaded here so the DI-free NewGroupSheet stays a plain-callback screen.
    var currencies by remember { mutableStateOf(FxCurrencyDefaults.fallback) }
    LaunchedEffect(Unit) { currencies = fx.currencies() }
    NewGroupSheet(
        onDismiss = onDismiss,
        currencies = currencies,
        onCreate = { name, emoji, baseCurrency ->
            val uid = auth.currentUserId.value ?: return@NewGroupSheet
            scope.launch {
                val input = NewGroup(name = name.ifBlank { "New group" }, baseCurrency = baseCurrency, creatorUserId = uid, emoji = emoji)
                when (val result = groups.createGroup(input)) {
                    is AppResult.Ok -> onCreated(result.value.id.value)
                    is AppResult.Err -> Unit
                }
            }
        },
    )
}

/** Manual "Join with a link" sheet: parse the pasted invite, then hand off to the resolving [Route.Join]. */
@Composable
fun JoinByLinkRoute(onDismiss: () -> Unit, onResolved: (token: String) -> Unit) {
    JoinByLinkSheet(onDismiss = onDismiss, onSubmit = onResolved)
}

@Composable
fun ProfileRoute(onBack: () -> Unit, onSignedOut: () -> Unit, onSignIn: () -> Unit, onEditPaymentApps: () -> Unit) {
    val auth = koinInject<AuthSession>()
    val profiles = koinInject<ProfileRepository>()
    val urlOpener = koinInject<UrlOpener>()
    val notificationPermission = koinInject<NotificationPermission>()
    val scope = rememberCoroutineScope()
    val profile by profiles.observeProfile().collectAsStateWithLifecycle(null)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val handles = profile?.paymentHandles ?: emptyMap()
    var notifStatus by remember { mutableStateOf(NotificationPermissionStatus.NotDetermined) }
    LaunchedEffect(Unit) { notifStatus = notificationPermission.status() }
    ProfileScreen(
        displayName = profile?.displayName ?: "You",
        email = profile?.email ?: "",
        baseCurrency = profile?.baseCurrency ?: "USD",
        paymentAppsSummary = paymentAppsSummary(handles),
        paymentAppsSet = handles.isNotEmpty(),
        isSignedIn = userId != null,
        onSignIn = onSignIn,
        onBack = onBack,
        onSignOut = { auth.signOut(); onSignedOut() },
        onEditPaymentApps = onEditPaymentApps,
        onEditName = { name -> scope.launch { profiles.updateDisplayName(name) } },
        onSendFeedback = { urlOpener.open("mailto:feedback@sharecost.app?subject=ShareCost%20feedback") },
        onPrivacy = { urlOpener.open("https://sharecost.app/privacy") },
        onTerms = { urlOpener.open("https://sharecost.app/terms") },
        notifications = profile?.notifications ?: NotificationPrefs(),
        notificationsBlocked = notifStatus == NotificationPermissionStatus.Denied,
        onNotificationsChange = { prefs ->
            val current = profile?.notifications ?: NotificationPrefs()
            val turnedOn = (prefs.newExpenses && !current.newExpenses) || (prefs.payments && !current.payments)
            scope.launch {
                // A toggle switching ON needs the OS permission first — request() shows the one-time
                // system prompt if it hasn't fired yet, or just reports the existing grant/refusal. If
                // refused, the pref stays at its previous (off) value instead of silently persisting "on".
                val toApply = if (turnedOn) {
                    val granted = notifStatus == NotificationPermissionStatus.Granted || notificationPermission.request()
                    notifStatus = notificationPermission.status()
                    if (granted) prefs else current
                } else {
                    prefs
                }
                profiles.updateNotificationPrefs(toApply)
            }
        },
        themeMode = profile?.themeMode ?: ThemeMode.System,
        onThemeModeChange = { mode -> scope.launch { profiles.updateThemeMode(mode) } },
        onDeleteAccount = { scope.launch { auth.deleteAccount(); onSignedOut() } },
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
        initialPreferred = profile?.preferredPaymentApp,
        onBack = onBack,
        onSave = { handles, preferred ->
            scope.launch {
                profiles.updatePaymentHandles(handles)
                profiles.updatePreferredPaymentApp(preferred)
                onBack()
            }
        },
    )
}

/** "Venmo +2" / "Not set" — the Profile row preview of how many payment apps are configured. */
private fun paymentAppsSummary(handles: Map<PaymentApp, String>): String {
    if (handles.isEmpty()) return "Not set"
    val first = handles.keys.first().appLabel
    val extra = handles.size - 1
    return if (extra > 0) "$first +$extra" else first
}
