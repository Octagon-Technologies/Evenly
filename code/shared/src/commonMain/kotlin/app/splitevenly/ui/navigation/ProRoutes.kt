package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.core.time.shortDate
import app.splitevenly.data.remote.revenuecat.ProConfig
import app.splitevenly.data.repository.PassPurchaseResult
import app.splitevenly.data.repository.ProPurchaseCoordinator
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.pro.PassOffer
import app.splitevenly.domain.pro.PassTier
import app.splitevenly.domain.pro.ProBilling
import app.splitevenly.domain.pro.ProSource
import app.splitevenly.domain.repository.GroupProState
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.ProRepository
import app.splitevenly.platform.UrlOpener
import app.splitevenly.ui.screen.pro.CoveredGroupUi
import app.splitevenly.ui.screen.pro.MySubscriptionUi
import app.splitevenly.ui.screen.pro.PassSheet
import app.splitevenly.ui.screen.pro.PassSheetMode
import app.splitevenly.ui.screen.pro.PassSheetPhase
import app.splitevenly.ui.screen.pro.PickableGroupUi
import app.splitevenly.ui.screen.pro.ProGroupPickerScreen
import app.splitevenly.ui.screen.pro.ProPaywallScreen
import app.splitevenly.ui.screen.pro.ProSubscribedScreen
import app.splitevenly.ui.screen.pro.ProUnavailableScreen
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.koin.compose.koinInject

/**
 * The "Evenly Pro" destination (`PRO_PASS_SPEC.md` §8.2 / §8.6).
 *
 * Three states, decided here rather than inside a screen: the viewer already subscribes, RevenueCat is
 * unconfigured or its offering is unreadable, or the paywall. The paywall itself is RevenueCat's own
 * composable and nothing about its content is decided in Kotlin.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun ProRoute(
    onBack: () -> Unit,
    onPickGroupForPass: () -> Unit,
) {
    val auth = koinInject<AuthSession>()
    val pro = koinInject<ProRepository>()
    val groups = koinInject<GroupRepository>()
    val purchases = koinInject<ProPurchaseCoordinator>()
    val urlOpener = koinInject<UrlOpener>()
    val billing = koinInject<ProBilling>()
    val scope = rememberCoroutineScope()
    val userId by auth.currentUserId.collectAsStateWithLifecycle()

    val subscription by remember(userId) {
        userId?.let { pro.observeMySubscription(it.value) } ?: flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val myGroups by remember(userId) {
        userId?.let { groups.observeGroupsForUser(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())

    var restoring by remember { mutableStateOf(false) }
    var restoreNote by remember { mutableStateOf<String?>(null) }

    // Safe to call on every open: sync-subscriber takes no body, so it can neither be forged nor write
    // the wrong thing, and it is also how a purchase made on another device shows up here.
    LaunchedEffect(userId) { if (userId != null) purchases.syncSubscriber() }

    val live = subscription?.takeIf { it.expiresAt > Clock.System.now().toEpochMilliseconds() }
    when {
        live != null -> ProSubscribedScreen(
            subscription = MySubscriptionUi(
                periodLabel = if (live.period == "annual") "Yearly" else "Monthly",
                // will_renew picks the verb and nothing else. A cancelled-but-unexpired subscriber is
                // still Pro, and telling them "ends" rather than "renews" is the honest version of that.
                renewalLine = (if (live.willRenew) "renews " else "ends ") + shortDate(live.expiresAt),
            ),
            coveredGroups = myGroups.map { CoveredGroupUi(emoji = it.emoji, name = it.name) },
            restoring = restoring,
            restoreNote = restoreNote,
            onManage = { urlOpener.open(ProConfig.manageSubscriptionsUrl(null)) },
            onRestore = {
                restoring = true
                restoreNote = null
                scope.launch {
                    val ok = purchases.restore()
                    restoreNote = if (ok) "Restored. Pro is on in every group you're in."
                    else "Nothing to restore on this account."
                    restoring = false
                }
            },
            onBack = onBack,
        )
        !billing.isAvailable -> ProUnavailableScreen(onBack = onBack)
        else -> ProPaywallScreen(
            // Null means "the current offering", which is what the dashboard's own placement rules
            // decide. Naming ours here would take the placement decision away from the console.
            offering = null,
            onDismiss = onBack,
            // RevenueCat says a purchase happened; the server still decides who is Pro, so the only
            // thing done here is asking the server to re-read this subscriber.
            onPurchased = { scope.launch { purchases.syncSubscriber() } },
            onRestored = { scope.launch { purchases.syncSubscriber() } },
            onWantsOneTrip = onPickGroupForPass,
        )
    }
}

/** "Which group is this pass for?" (§8.4). Reached only from the Profile route, and only for a pass. */
@Composable
fun ProGroupPickerRoute(
    onBack: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val groups = koinInject<GroupRepository>()
    val pro = koinInject<ProRepository>()
    val auth = koinInject<AuthSession>()
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val myGroups by remember(userId) {
        userId?.let { groups.observeGroupsForUser(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val ids = myGroups.map { it.id.value }
    val states by remember(ids) { pro.observeAll(ids) }.collectAsStateWithLifecycle(emptyMap())

    ProGroupPickerScreen(
        groups = myGroups.map { g ->
            val state = states[g.id.value]
            PickableGroupUi(
                groupId = g.id.value,
                emoji = g.emoji,
                name = g.name,
                state = pickerStateLine(state),
                isPro = state?.status?.isPro == true,
            )
        },
        onPick = onPicked,
        onBack = onBack,
    )
}

private fun pickerStateLine(state: GroupProState?): String {
    val status = state?.status
    if (status?.isPro == true) {
        return when (status.source) {
            // No date for a subscription: it is someone's personal renewal, not a group fact.
            ProSource.Subscription -> "Pro through a subscription"
            else -> status.expiresAt?.let { "Pro until ${shortDate(it)}" } ?: "Pro"
        }
    }
    val used = state?.freeUsed ?: return "Free scans left"
    val left = (state.freeLimit - used).coerceAtLeast(0)
    return if (left == 0) "No free scans left" else "$left of ${state.freeLimit} free scans left"
}

/**
 * The pass sheet, wired (§8.3). Hosted by whichever surface opened it — group settings, the scan sheet,
 * the export sheet, or the group picker — so the sheet itself never owns navigation.
 *
 * The purchase round trip lives in [ProPurchaseCoordinator], not here: a charge that our activate call
 * fails to turn into a pass has to survive this composable leaving the screen.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun PassSheetHost(
    groupId: String,
    groupName: String,
    onDismiss: () -> Unit,
) {
    val billing = koinInject<ProBilling>()
    val purchases = koinInject<ProPurchaseCoordinator>()
    val pro = koinInject<ProRepository>()
    val auth = koinInject<AuthSession>()
    val scope = rememberCoroutineScope()

    val proState by remember(groupId) { pro.observe(groupId) }.collectAsStateWithLifecycle(null)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val offers by produceState(initialValue = emptyList<PassOffer>(), billing) { value = billing.passOffers() }
    var selected by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf<PassSheetPhase>(PassSheetPhase.Idle) }

    // A charge already waiting on a pass reopens straight into its retry, so nobody has to remember
    // they were owed something (§6.2).
    LaunchedEffect(Unit) {
        if (purchases.hasPendingActivation()) {
            phase = if (purchases.retryPendingActivation()) PassSheetPhase.Idle else PassSheetPhase.Charged
        }
    }

    val status = proState?.status
    val mode = when {
        // The one purchase we actively talk someone out of: this group is already covered by the
        // viewer's own subscription, so no purchase is offered at all (§5.4).
        status?.source == ProSource.Subscription && status.purchasedBy == userId?.value ->
            PassSheetMode.AlreadySubscribed
        status?.isPro == true && status.expiresAt != null ->
            PassSheetMode.Extend(
                currentExpiresOn = shortDate(status.expiresAt!!),
                holderName = null,
            )
        else -> PassSheetMode.Fresh
    }
    // The default selection is the best-value tier when fresh and the cheapest when extending, so the
    // highlighted option is always the honest recommendation for the situation.
    val effectiveSelection = selected ?: offers.firstOrNull()?.packageId
    val extendToLabel = (effectiveSelection?.let { PassTier.byPackageId(it) })?.let { tier ->
        shortDate(PassTier.expiryIfBought(tier, Clock.System.now().toEpochMilliseconds(), status?.expiresAt))
    }

    PassSheet(
        groupName = groupName,
        offers = offers,
        selectedPackageId = effectiveSelection,
        mode = mode,
        phase = phase,
        extendToLabel = extendToLabel,
        onSelect = { selected = it },
        onBuy = {
            val pkg = effectiveSelection ?: return@PassSheet
            phase = PassSheetPhase.Working
            scope.launch {
                phase = when (purchases.buyPass(groupId, pkg)) {
                    PassPurchaseResult.Activated -> PassSheetPhase.Idle.also { onDismiss() }
                    PassPurchaseResult.ChargedNotActivated -> PassSheetPhase.Charged
                    PassPurchaseResult.Cancelled -> PassSheetPhase.Idle
                    PassPurchaseResult.Failed -> PassSheetPhase.Failed(null)
                }
            }
        },
        onRetryActivation = {
            phase = PassSheetPhase.Working
            scope.launch {
                phase = if (purchases.retryPendingActivation()) PassSheetPhase.Idle.also { onDismiss() }
                else PassSheetPhase.Charged
            }
        },
        onDismiss = onDismiss,
    )
}
