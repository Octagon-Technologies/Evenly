package app.splitevenly.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.core.time.nowEpochMillis
import app.splitevenly.core.time.shortDate
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.auth.NotificationPrefs
import app.splitevenly.domain.auth.OAuthProvider
import app.splitevenly.domain.auth.ThemeMode
import app.splitevenly.domain.feedback.FeedbackSubmitter
import app.splitevenly.domain.fx.FxCurrencyDefaults
import app.splitevenly.domain.group.DeletedGroup
import app.splitevenly.domain.group.Group
import app.splitevenly.domain.group.NewGroup
import app.splitevenly.domain.group.RecentlyDeleted
import app.splitevenly.domain.repository.FxRepository
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.ProfileRepository
import app.splitevenly.domain.settlement.PaymentApp
import app.splitevenly.domain.settlement.resolvePreferredPaymentApp
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.AppleSignIn
import app.splitevenly.platform.EvAnalytics
import app.splitevenly.platform.NotificationPermission
import app.splitevenly.platform.NotificationPermissionStatus
import app.splitevenly.platform.SecureStorage
import app.splitevenly.platform.UrlOpener
import app.splitevenly.platform.isDebugBuild
import app.splitevenly.platform.isIOS
import app.splitevenly.ui.screen.auth.HomeGateState
import app.splitevenly.ui.screen.auth.HomeGateViewModel
import app.splitevenly.ui.screen.auth.MagicLinkScreen
import app.splitevenly.ui.screen.auth.MagicLinkState
import app.splitevenly.ui.screen.auth.OnboardingScreen
import app.splitevenly.ui.screen.auth.PendingDeletionScreen
import app.splitevenly.ui.screen.auth.SignInScreen
import app.splitevenly.ui.screen.auth.WelcomeScreen
import app.splitevenly.ui.screen.group.GroupDeletedScreen
import app.splitevenly.ui.screen.home.ArchivedScreen
import app.splitevenly.ui.screen.home.DeletedGroupCardUi
import app.splitevenly.ui.screen.home.HomeScreen
import app.splitevenly.ui.screen.home.HomeUiState
import app.splitevenly.ui.screen.home.HomeViewModel
import app.splitevenly.ui.screen.home.JoinByLinkSheet
import app.splitevenly.ui.screen.home.JoinGroupSheet
import app.splitevenly.ui.screen.home.JoinPlaceholderOption
import app.splitevenly.ui.screen.home.NewGroupSheet
import app.splitevenly.ui.screen.home.RecentlyDeletedScreen
import app.splitevenly.ui.screen.settings.PaymentHandlesScreen
import app.splitevenly.ui.screen.settings.ProfileScreen
import app.splitevenly.ui.screen.settle.appLabel
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock

/**
 * Stateful wrappers that bind the (stateless, previewable) screens to ViewModels / the auth session.
 * The NavHost renders these for wired destinations; the screens themselves stay free of DI so their
 * `@Preview`s keep working.
 */

/**
 * Sign-in entry: launches browser OAuth for Google/Apple/Facebook (the session arrives async via the
 * `splitevenly://login-callback` redirect → [AuthSession.currentUserId]) and routes Email to the
 * magic-link/OTP screen. [onSignedIn] fires once a session exists — this also covers a restored
 * session on cold start (the user lands straight on Home).
 */
@Composable
fun SignInRoute(
    onEmail: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val auth = koinInject<AuthSession>()
    val appleSignIn = koinInject<AppleSignIn>()
    val scope = rememberCoroutineScope()
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(userId) { if (userId != null) onSignedIn() }
    SignInScreen(error = error, onProvider = { provider ->
        error = null
        when (provider) {
            "mail" -> {
                onEmail()
            }

            "google" -> {
                scope.launch { auth.signInWithProvider(OAuthProvider.GOOGLE) }
            }

            // iOS: native ASAuthorizationController sheet, no browser redirect. Android has no native
            // API, so it keeps the browser OAuth path (platform/AGENTS.md's expect/actual boundary).
            "apple" -> {
                if (isIOS()) {
                    scope.launch {
                        when (val native = appleSignIn.signIn()) {
                            is AppResult.Ok -> {
                                // The native sheet succeeding (Face ID passes) says nothing about whether
                                // Supabase then accepts the resulting ID token — a server-side provider
                                // misconfiguration (Apple not enabled for the id_token grant, or app.splitevenly
                                // missing from Authorized Client IDs) fails here silently otherwise, leaving
                                // the user staring at a sheet that just closed with no feedback.
                                when (
                                    val result =
                                        auth.signInWithAppleIdToken(
                                            idToken = native.value.identityToken,
                                            rawNonce = native.value.rawNonce,
                                            fullName = native.value.fullName,
                                            authorizationCode = native.value.authorizationCode,
                                        )
                                ) {
                                    is AppResult.Ok -> Unit
                                    is AppResult.Err -> error = "Couldn't sign in with Apple. Try again in a moment."
                                }
                            }

                            // The sheet itself failing is almost always the user cancelling (no Apple ID
                            // signed in, tapped away) — not worth an error message.
                            is AppResult.Err -> {
                                Unit
                            }
                        }
                    }
                } else {
                    scope.launch { auth.signInWithProvider(OAuthProvider.APPLE) }
                }
            }

            "facebook" -> {
                scope.launch { auth.signInWithProvider(OAuthProvider.FACEBOOK) }
            }
        }
    })
}

/**
 * Email magic-link + OTP: send a code, then verify it in-app. [onVerified] fires with
 * `needsOnboarding = true` only for a brand-new account; a returning user (already has a server
 * profile) gets `false` so the NavHost can send them straight to Home and skip onboarding.
 */
@Composable
fun MagicLinkRoute(
    onBack: () -> Unit,
    onVerified: (needsOnboarding: Boolean) -> Unit,
) {
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
            email = e
            error = null
            state = MagicLinkState.Loading
            scope.launch {
                when (val r = auth.sendEmailOtp(e)) {
                    is AppResult.Ok -> {
                        state = MagicLinkState.Sent
                    }

                    is AppResult.Err -> {
                        state = MagicLinkState.Input
                        error = sendErrorMessage(r.error)
                    }
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
            email = e
            error = null
            state = MagicLinkState.Loading
            scope.launch {
                when (auth.signInWithPassword(e, pw)) {
                    is AppResult.Ok -> {
                        onVerified(!auth.hasOnboardedProfile())
                    }

                    is AppResult.Err -> {
                        state = MagicLinkState.Input
                        error = "Couldn't sign in. Check the email and password."
                    }
                }
            }
        },
    )
}

/** Turn an email-send failure into a message the user can act on (429 throttling vs. everything else). */
private fun sendErrorMessage(error: AppError): String =
    when {
        error is AppError.Backend && error.status == 429 -> {
            "Too many requests. Wait a minute, then try again."
        }

        else -> {
            "Couldn't send the code. Check the address and try again."
        }
    }

/**
 * First-launch product intro. Shows the [WelcomeScreen] carousel; on Skip/Get-started it stamps the
 * device-local [WELCOME_SEEN_KEY] so the carousel never shows again, then continues to sign-in.
 */
@Composable
fun WelcomeRoute(onFinished: () -> Unit) {
    val storage = koinInject<SecureStorage>()
    val koin = getKoin()
    val analytics = remember { koin.getOrNull<EvAnalytics>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        analytics?.capture(AnalyticsEvents.ONBOARDING_STEP_VIEWED, mapOf("step" to "welcome_slides"))
    }
    WelcomeScreen(onFinish = {
        analytics?.capture(AnalyticsEvents.ONBOARDING_STEP_COMPLETED, mapOf("step" to "welcome_slides"))
        scope.launch {
            storage.putString(WELCOME_SEEN_KEY, "1")
            onFinished()
        }
    })
}

/** Device-local flag: set once the welcome carousel has been dismissed. */
const val WELCOME_SEEN_KEY = "welcome_seen"

/** First-run profile capture: persists the chosen display name + base currency, then continues. */

/** Device-local flag: set once `onboarding_completed` has fired, so a later re-open never repeats it. */
const val ONBOARDING_COMPLETED_KEY = "onboarding_completed_seen"

@Composable
fun OnboardingRoute(onFinished: () -> Unit) {
    val profiles = koinInject<ProfileRepository>()
    val notifications = koinInject<NotificationPermission>()
    val storage = koinInject<SecureStorage>()
    val koin = getKoin()
    val analytics = remember { koin.getOrNull<EvAnalytics>() }
    val scope = rememberCoroutineScope()
    val profile by profiles.observeProfile().collectAsStateWithLifecycle(null)
    OnboardingScreen(
        initialName = profile?.displayName?.takeIf { it.isNotBlank() && it != "You" } ?: "",
        initialCurrency = profile?.baseCurrency ?: "USD",
        // The app's only notification-permission ask, and only on an explicit opt-in tap. The grant/refusal
        // isn't acted on here: the FCM token registers regardless, and the OS drops what it won't show.
        onEnableNotifications = { notifications.request() },
        onStepViewed = { step ->
            analytics?.capture(AnalyticsEvents.ONBOARDING_STEP_VIEWED, mapOf("step" to step))
        },
        onStepCompleted = { step ->
            analytics?.capture(AnalyticsEvents.ONBOARDING_STEP_COMPLETED, mapOf("step" to step))
        },
        onFinish = { name, currency, handles ->
            scope.launch {
                profiles.updateProfile(name, currency)
                // Onboarding only ever adds. A blank entry is a row that was opened and abandoned,
                // never an instruction to clear one, so it is dropped rather than written through.
                val filled = handles.filterValues { it.isNotBlank() }
                if (filled.isNotEmpty()) {
                    profiles.updatePaymentHandles(filled)
                    // Onboarding shows no preferred-method picker, so the first handle added claims the
                    // slot. Without this a two-handle sign-up reaches the settle screen with no default.
                    profiles.updatePreferredPaymentApp(resolvePreferredPaymentApp(filled, current = null))
                }
                if (!storage.contains(ONBOARDING_COMPLETED_KEY)) {
                    analytics?.capture(AnalyticsEvents.ONBOARDING_COMPLETED)
                    storage.putString(ONBOARDING_COMPLETED_KEY, "1")
                }
                onFinished()
            }
        },
    )
}

@Composable
fun HomeRoute(
    onOpenGroup: (String) -> Unit,
    onNewGroup: () -> Unit,
    onJoin: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenRecentlyDeleted: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val vm = koinViewModel<HomeViewModel>()
    val fx = koinInject<FxRepository>()
    val profiles = koinInject<ProfileRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val storage = koinInject<SecureStorage>()
    val meId by auth.currentUserId.collectAsStateWithLifecycle()
    val deletedGroups by remember(meId) {
        meId?.let { groups.observeDeletedGroups(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    // Re-read whenever the tombstone set changes, so returning from Recently deleted clears the banner.
    val seenAt by produceState(0L, deletedGroups) {
        value = storage.getString(DELETES_SEEN_KEY)?.toLongOrNull() ?: 0L
    }
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
        onOpenRecentlyDeleted = onOpenRecentlyDeleted,
        deletedAlert = deletedAlertText(deletedGroups, meId, seenAt),
        onOpenSettings = onOpenSettings,
    )
}

/** Join-by-invite, wired: resolves the token to a group preview, then joins via [GroupRepository.joinByToken]. */
@Composable
fun JoinRoute(
    token: String,
    onDismiss: () -> Unit,
    onOpenGroup: (String) -> Unit,
) {
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
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
    // Claimable identities = the still-active placeholders in this roster (already filtered to
    // unclaimed by observeMembers). Reusing the roster flow avoids a second query/injection.
    val placeholderOptions =
        members
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

/**
 * Recently deleted, wired. Reads the tombstones directly rather than through [HomeViewModel] (which
 * only carries a count) and resolves "Deleted by ..." against the signed-in user here, since the
 * screen is DI-free and cannot tell whose id it is holding.
 */
@Composable
fun RecentlyDeletedRoute(onBack: () -> Unit) {
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val scope = rememberCoroutineScope()
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val deleted by remember(userId) {
        userId?.let { groups.observeDeletedGroups(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    var restoredMessage by remember { mutableStateOf<String?>(null) }
    val now = Clock.System.nowEpochMillis()

    // Opening this screen IS the acknowledgement, so it clears Home's banner. Keyed on the newest
    // stamp rather than written once, so a delete that lands while the screen is open also counts as
    // seen. Worst case the marker is missed and the banner shows once more, the cheap way to fail.
    val storage = koinInject<SecureStorage>()
    val newestDelete = deleted.maxOfOrNull { it.deletedAt } ?: 0L
    LaunchedEffect(newestDelete) {
        if (newestDelete > 0L) storage.putString(DELETES_SEEN_KEY, newestDelete.toString())
    }

    RecentlyDeletedScreen(
        onBack = onBack,
        groups =
            deleted.map { g ->
                val daysLeft = RecentlyDeleted.daysLeft(g.deletedAt, now)
                DeletedGroupCardUi(
                    id = g.id.value,
                    emoji = g.emoji,
                    name = g.name,
                    deletedByLabel =
                        when {
                            g.deletedBy != null && g.deletedBy == userId -> "Deleted by you"

                            // A name the roster has not synced yet, or a deleter who has since been anonymized
                            // by account deletion. "Deleted" alone is honest; inventing "a member" is not.
                            g.deletedByName.isNullOrBlank() -> "Deleted"

                            else -> "Deleted by ${g.deletedByName}"
                        },
                    daysLeft = daysLeft,
                    urgent = daysLeft <= URGENT_DAYS_LEFT,
                )
            },
        onRestore = { id ->
            val me = userId ?: return@RecentlyDeletedScreen
            val name = deleted.firstOrNull { it.id.value == id }?.name
            scope.launch {
                restoredMessage =
                    when (groups.restoreGroup(GroupId(id), me)) {
                        is AppResult.Ok -> "${name ?: "The group"} is back for everyone"
                        is AppResult.Err -> "Couldn't restore that group. Please try again."
                    }
            }
        },
        restoredMessage = restoredMessage,
    )
}

/** The last week, where the countdown earns its color. */
private const val URGENT_DAYS_LEFT = 7

/**
 * Newest group-deletion this device has been shown, as epoch millis. Device-local in [SecureStorage],
 * exactly like `changesSeenKey`, and for the same reason: a "seen" marker is not financial data and
 * adding a synced column for a banner would cost that table's sync if it went wrong.
 *
 * The tradeoff is the same too, and it is the right way round here: a second device tells you again
 * that your group was deleted. That is a repeat of news worth hearing, not a nag.
 */
private const val DELETES_SEEN_KEY = "groups_deleted_seen_at"

/**
 * "Andrew deleted Tulum Trip." — the in-app stand-in for a push that cannot be sent yet.
 *
 * Only for deletions by SOMEONE ELSE: the person who tapped Delete watched a confirm sheet spell the
 * consequences out and does not need to be told what they just did.
 */
private fun deletedAlertText(
    deleted: List<DeletedGroup>,
    me: UserId?,
    seenAt: Long,
): String? {
    val fresh = deleted.filter { it.deletedBy != me && it.deletedAt > seenAt }
    val newest = fresh.maxByOrNull { it.deletedAt } ?: return null
    if (fresh.size > 1) return "${fresh.size} of your groups were deleted. Tap to restore them."
    val who = newest.deletedByName?.takeIf { it.isNotBlank() }
    return if (who != null) "$who deleted ${newest.name}. Tap to restore it." else "${newest.name} was deleted. Tap to restore it."
}

/**
 * The deleted-group gate, wired. Rendered by [app.splitevenly.ui.screen.group.GroupHomeScreen] in place
 * of its four tabs once the group carries a tombstone.
 *
 * The deleter's name comes off the roster this group already has locally rather than a lookup, and
 * degrades to "This group was deleted" rather than inventing an actor when the row has not synced.
 */
@Composable
fun GroupDeletedRoute(
    groupId: String,
    groupName: String,
    groupEmoji: String,
    deletedAt: Long,
    deletedBy: String?,
    onBackToGroups: () -> Unit,
) {
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val scope = rememberCoroutineScope()
    val gid = remember(groupId) { GroupId(groupId) }
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    var restoring by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val deleterName = members.firstOrNull { it.userId.value == deletedBy }?.displayName
    val on = shortDate(deletedAt)
    val line =
        when {
            deletedBy != null && deletedBy == userId?.value -> "You deleted this group for everyone on $on."
            !deleterName.isNullOrBlank() -> "$deleterName deleted this group for everyone on $on."
            else -> "This group was deleted for everyone on $on."
        }

    GroupDeletedScreen(
        groupName = groupName,
        groupEmoji = groupEmoji,
        deletedByLine = line,
        daysLeft = RecentlyDeleted.daysLeft(deletedAt, Clock.System.nowEpochMillis()),
        restoring = restoring,
        error = error,
        onRestore = {
            val me = userId ?: return@GroupDeletedScreen
            if (restoring) return@GroupDeletedScreen
            restoring = true
            error = null
            scope.launch {
                // On success the tombstone clears, GroupHomeScreen's flow re-emits, and this gate simply
                // stops being rendered. No navigation needed.
                if (groups.restoreGroup(gid, me) is AppResult.Err) {
                    error = "Couldn't restore this group. Please try again."
                }
                restoring = false
            }
        },
        onBackToGroups = onBackToGroups,
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
fun JoinByLinkRoute(
    onDismiss: () -> Unit,
    onResolved: (token: String) -> Unit,
) {
    JoinByLinkSheet(onDismiss = onDismiss, onSubmit = onResolved)
}

/**
 * Gates [MainShell] behind a pending-deletion check on every entry to Home (fresh sign-in, restored
 * session, or finishing onboarding) — the single choke point all three paths land on. A pending
 * deletion (from [AuthSession.pendingDeletionAt]) blocks the shell behind [PendingDeletionScreen]
 * instead: the account just asked to leave, so continuing to add expenses to groups it's about to
 * leave would be confusing. [HomeGateState.Checking] briefly holds a blank page rather than flashing
 * Home first.
 *
 * "Every entry to Home" means every entry to the *destination*, not every recomposition of it —
 * which is why the check lives in [HomeGateViewModel], scoped to the Home back-stack entry. Popping
 * a pushed screen (a group or Archived) re-composes this route, and a `remember` here would
 * restart the check, blanking the screen behind a network call on every Back.
 */
@Composable
fun HomeGateRoute(
    onOpenGroup: (String) -> Unit,
    onNewGroup: () -> Unit,
    onJoin: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenRecentlyDeleted: () -> Unit,
    onSignedOut: () -> Unit,
    onSignIn: () -> Unit,
    onEditPaymentApps: () -> Unit,
    onSendFeedback: () -> Unit,
) {
    val auth = koinInject<AuthSession>()
    val gate = koinViewModel<HomeGateViewModel>()
    val gateState by gate.state.collectAsStateWithLifecycle()
    val signOutFlow = rememberSignOutFlow(auth, onSignedOut)
    when (val g = gateState) {
        // Page-colored, not transparent: an empty Box here would show the window through as black.
        HomeGateState.Checking -> {
            Box(Modifier.fillMaxSize().background(EvenlyTheme.colors.page))
        }

        is HomeGateState.Blocked -> {
            PendingDeletionScreen(
                purgeAtMillis = g.purgeAtMillis,
                cancelling = g.cancelling,
                error = g.error,
                onCancelDeletion = gate::cancelDeletion,
                onSignOut = signOutFlow::start,
            )
        }

        HomeGateState.Allowed -> {
            MainShell(
                onOpenGroup = onOpenGroup,
                onNewGroup = onNewGroup,
                onJoin = onJoin,
                onOpenArchived = onOpenArchived,
                onOpenRecentlyDeleted = onOpenRecentlyDeleted,
                onSignedOut = onSignedOut,
                onSignIn = onSignIn,
                onEditPaymentApps = onEditPaymentApps,
                onSendFeedback = onSendFeedback,
            )
        }
    }
    // Only ever shown on the pending-deletion branch above (MainShell routes to ProfileRoute, which
    // owns its own flow), but hung outside the `when` so it survives a branch flip mid-sign-out.
    signOutFlow.Dialog()
}

@Composable
fun ProfileRoute(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onSignIn: () -> Unit,
    onEditPaymentApps: () -> Unit,
    onSendFeedback: () -> Unit = {},
) {
    val auth = koinInject<AuthSession>()
    val profiles = koinInject<ProfileRepository>()
    val urlOpener = koinInject<UrlOpener>()
    val notificationPermission = koinInject<NotificationPermission>()
    // Bound only when Supabase is configured, so this is also the answer to "is there anywhere for a
    // ticket to go". Resolved through the Koin scope rather than koinInject, which throws when unbound.
    val koin = getKoin()
    val canSubmitFeedback = remember(koin) { koin.getOrNull<FeedbackSubmitter>() != null }
    val scope = rememberCoroutineScope()
    val profile by profiles.observeProfile().collectAsStateWithLifecycle(null)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val handles = profile?.paymentHandles ?: emptyMap()
    var notifStatus by remember { mutableStateOf(NotificationPermissionStatus.NotDetermined) }
    var deleteAccountError by remember { mutableStateOf<String?>(null) }
    val signOutFlow = rememberSignOutFlow(auth, onSignedOut)
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
        onSignOut = signOutFlow::start,
        signingOut = signOutFlow.inProgress,
        onEditPaymentApps = onEditPaymentApps,
        onEditName = { name -> scope.launch { profiles.updateDisplayName(name) } },
        // The in-app form when there is a server to post to, the old mailto when there is not: the
        // offline stub build binds no FeedbackSubmitter, and a form whose Send can never do anything is
        // worse than a mail draft.
        onSendFeedback = {
            if (canSubmitFeedback) {
                onSendFeedback()
            } else {
                urlOpener.open("mailto:feedback@split-evenly.app?subject=Evenly%20feedback")
            }
        },
        onPrivacy = { urlOpener.open("https://split-evenly.app/privacy") },
        onTerms = { urlOpener.open("https://split-evenly.app/terms") },
        notifications = profile?.notifications ?: NotificationPrefs(),
        notificationsBlocked = notifStatus == NotificationPermissionStatus.Denied,
        onNotificationsChange = { prefs ->
            val current = profile?.notifications ?: NotificationPrefs()
            val turnedOn = (prefs.newExpenses && !current.newExpenses) || (prefs.payments && !current.payments)
            scope.launch {
                // A toggle switching ON needs the OS permission first — request() shows the one-time
                // system prompt if it hasn't fired yet, or just reports the existing grant/refusal. If
                // refused, the pref stays at its previous (off) value instead of silently persisting "on".
                val toApply =
                    if (turnedOn) {
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
        onDeleteAccount = {
            scope.launch {
                when (val result = auth.requestAccountDeletion()) {
                    is AppResult.Ok -> onSignedOut()
                    is AppResult.Err -> deleteAccountError = "Couldn't start account deletion. Check your connection and try again."
                }
            }
        },
        deleteAccountError = deleteAccountError,
    )
    signOutFlow.Dialog()
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
