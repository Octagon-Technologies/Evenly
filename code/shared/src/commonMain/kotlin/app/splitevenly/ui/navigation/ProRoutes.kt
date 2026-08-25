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
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.time.shortDate
import app.splitevenly.data.remote.revenuecat.ActivationOutcome
import app.splitevenly.data.remote.revenuecat.ProConfig
import app.splitevenly.data.remote.supabase.SyncEngine
import app.splitevenly.data.repository.PassPurchaseResult
import app.splitevenly.data.repository.ProPurchaseCoordinator
import app.splitevenly.domain.auth.AuthSession
import app.splitevenly.domain.pro.PassOffer
import app.splitevenly.domain.pro.PassSheetConfig
import app.splitevenly.domain.pro.PassTier
import app.splitevenly.domain.pro.ProBilling
import app.splitevenly.domain.pro.ProFunnel
import app.splitevenly.domain.pro.ProSource
import app.splitevenly.domain.pro.bestValueOffer
import app.splitevenly.domain.pro.ordered
import app.splitevenly.domain.pro.preselected
import app.splitevenly.domain.repository.GroupProState
import app.splitevenly.domain.repository.GroupRepository
import app.splitevenly.domain.repository.ProRepository
import app.splitevenly.platform.AnalyticsEvents
import app.splitevenly.platform.EvAnalytics
import app.splitevenly.platform.FeatureFlags
import app.splitevenly.platform.ProTriggers
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
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
    /** One of [ProTriggers]; carried through from whichever surface sent the user here. */
    trigger: String = ProTriggers.PROFILE,
) {
    val auth = koinInject<AuthSession>()
    val pro = koinInject<ProRepository>()
    val groups = koinInject<GroupRepository>()
    val purchases = koinInject<ProPurchaseCoordinator>()
    val urlOpener = koinInject<UrlOpener>()
    val billing = koinInject<ProBilling>()
    val analytics = koinInject<EvAnalytics>()
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
        live != null -> {
            ProSubscribedScreen(
                subscription =
                    MySubscriptionUi(
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
                        // Phrased as a CHECK, not as a switch being flipped: this button sits under
                        // "Manage or cancel", and "Restored, Pro is on" read there by someone who just
                        // cancelled looks exactly like an accidental undo.
                        restoreNote =
                            if (ok) {
                                "Checked. Pro is on in every group you're in."
                            } else {
                                "Nothing to restore on this account."
                            }
                        restoring = false
                    }
                },
                onBack = onBack,
            )
        }

        !billing.isAvailable -> {
            ProUnavailableScreen(onBack = onBack)
        }

        else -> {
            // `offering_id` is what makes a RevenueCat experiment readable on our side too, not only in
            // their dashboard. Null until the SDK has an offering, which is a real state and not a zero.
            // Same event, same properties as the pass sheet, separated only by `surface`. One PostHog
            // funnel then spans both doors, which is the whole point: routing the scan gate to our own
            // sheet is only defensible if the comparison between the two doors stays answerable.
            ProPaywallHost(
                trigger = trigger,
                onWantsPass = onPickGroupForPass,
                onDismiss = onBack,
            )
        }
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
        groups =
            myGroups.map { g ->
                val state = states[g.id.value]
                PickableGroupUi(
                    groupId = g.id.value,
                    emoji = g.emoji,
                    name = g.name,
                    state = pickerStateLine(state),
                    isPro = state?.status?.isPro == true,
                    coveredBySubscription = state?.status?.source == ProSource.Subscription,
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
            ProSource.Subscription -> "Already covered by a subscription"

            else -> status.expiresAt?.let { "Pro until ${shortDate(it)}, tap to extend" } ?: "Pro"
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
    /** One of [ProTriggers]. Which surface opened this, which is the only way to tell a scan-driven
     *  purchase from an export-driven one after the fact. */
    trigger: String,
    onSeeSubscription: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val billing = koinInject<ProBilling>()
    val purchases = koinInject<ProPurchaseCoordinator>()
    val pro = koinInject<ProRepository>()
    val auth = koinInject<AuthSession>()
    val groups = koinInject<GroupRepository>()
    val analytics = koinInject<EvAnalytics>()
    val flags = koinInject<FeatureFlags>()
    val urlOpener = koinInject<UrlOpener>()
    val koin = getKoin()
    // Absent in the offline/unconfigured build, same optional-dep pattern as everywhere else.
    val syncEngine = remember(koin) { koin.getOrNull<SyncEngine>() }
    val scope = rememberCoroutineScope()

    // The remote-design surface RevenueCat's editor gives the subscription paywall and cannot give a
    // consumable one (§2.1). Read once per opening: a sheet that re-ordered its own tiers under the
    // buyer's finger would be worse than no experiment at all.
    val config =
        remember {
            PassSheetConfig.from(
                variant = flags.variant(PassSheetConfig.FLAG_KEY),
                payload = flags.payload(PassSheetConfig.FLAG_KEY),
            )
        }
    // A super property, so every subsequent event carries the arm without any call site remembering to.
    LaunchedEffect(config.variant) {
        config.variant?.let { analytics.register(PassSheetConfig.FLAG_KEY, it) }
    }

    val proState by remember(groupId) { pro.observe(groupId) }.collectAsStateWithLifecycle(null)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    var offersAttempt by remember { mutableStateOf(0) }
    val rawOffers by produceState(initialValue = emptyList<PassOffer>(), billing, offersAttempt) {
        value = billing.passOffers()
    }
    val offers = remember(rawOffers, config) { rawOffers.ordered(config) }
    var selected by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf<PassSheetPhase>(PassSheetPhase.Idle) }

    // A charge already waiting on a pass reopens straight into its retry, so nobody has to remember
    // they were owed something (§6.2).
    LaunchedEffect(Unit) {
        if (purchases.hasPendingActivation(groupId)) {
            phase = purchases.retryPendingActivation(groupId).toSheetPhase()
        }
        // Another group's stuck charge is still owed a pass, and any Pro surface opening is as good a
        // moment as any to settle it. It never touches this sheet's phase: a sheet reports on the group
        // it names and no other (R11).
        purchases.retryOtherPendingActivations(exceptGroupId = groupId)
    }

    val members by remember(groupId) { groups.observeMembers(GroupId(groupId)) }
        .collectAsStateWithLifecycle(emptyList())
    val payerName = members.firstOrNull { it.userId.value == proState?.status?.purchasedBy }?.displayName

    /** Pull the server-owned pass row down, so "Pro" is true locally the moment the sheet closes. */
    suspend fun pullPass() {
        userId?.let { uid -> syncEngine?.syncNow(uid.value) }
    }

    val status = proState?.status
    val mode =
        when {
            // The one purchase we actively talk someone out of. It is NOT only the viewer's own
            // subscription: a pass bought for a group a flatmate already covers buys nothing, and unlike a
            // subscription it cannot be cancelled or refunded, so nothing later corrects it. Extending from
            // a subscription's expiry would be worse still — that date is someone's private renewal, and a
            // pass starting there is dead time sold as coverage.
            status?.source == ProSource.Subscription -> {
                PassSheetMode.AlreadySubscribed(
                    subscriberName = payerName,
                    isMe = status.purchasedBy == userId?.value,
                )
            }

            status?.isPro == true && status.expiresAt != null -> {
                PassSheetMode.Extend(
                    currentExpiresOn = shortDate(status.expiresAt!!),
                    holderName = payerName,
                    isMe = status.purchasedBy == userId?.value,
                )
            }

            else -> {
                PassSheetMode.Fresh(
                    scansLeft = proState?.freeUsed?.let { (proState!!.freeLimit - it).coerceAtLeast(0) },
                    freeLimit = proState?.freeLimit ?: 0,
                )
            }
        }
    // The default selection is the cheapest when extending (the honest recommendation when you are
    // already covered) and otherwise whatever the experiment says, defaulting to best value. Either way
    // the button below it always names a real amount.
    val defaultOffer =
        when (mode) {
            is PassSheetMode.Extend -> offers.minByOrNull { it.priceMicros }
            else -> offers.preselected(config)
        }
    val effectiveSelection = selected ?: defaultOffer?.packageId
    val bestValueId = offers.bestValueOffer()?.packageId

    // Assembled once so the impression, the selection, the start and the activation cannot disagree
    // about what they are describing.
    val base =
        ProFunnel.base(
            surface = ProFunnel.Surface.PASS_SHEET,
            kind = ProFunnel.Kind.PASS,
            trigger = trigger,
            groupId = groupId,
            variant = config.variant,
        )
    val modeName =
        when (mode) {
            is PassSheetMode.Extend -> "extend"
            is PassSheetMode.AlreadySubscribed -> "already_covered"
            is PassSheetMode.Fresh -> "fresh"
        }
    // Fires on the FIRST render that has prices, not on open: an impression recorded before the offers
    // load would report an empty offer set and make every experiment arm look identical.
    var impressionSent by remember { mutableStateOf(false) }
    LaunchedEffect(offers, modeName) {
        if (impressionSent || (offers.isEmpty() && mode !is PassSheetMode.AlreadySubscribed)) return@LaunchedEffect
        impressionSent = true
        analytics.capture(
            AnalyticsEvents.PRO_OFFER_SHOWN,
            ProFunnel.offerShown(
                base = base,
                productIds = offers.map { it.productId },
                currency = offers.firstOrNull()?.currency,
                preselectedProductId = defaultOffer?.productId,
                bestValueProductId = if (config.showBestValueFlag) offers.bestValueOffer()?.productId else null,
                mode = modeName,
                scansUsed = proState?.freeUsed,
            ),
        )
    }
    val extendToLabel =
        (effectiveSelection?.let { PassTier.byPackageId(it) })?.let { tier ->
            shortDate(PassTier.expiryIfBought(tier, Clock.System.now().toEpochMilliseconds(), status?.expiresAt))
        }

    PassSheet(
        groupName = groupName,
        offers = offers,
        selectedPackageId = effectiveSelection,
        mode = mode,
        phase = phase,
        extendToLabel = extendToLabel,
        onSelect = { packageId ->
            selected = packageId
            // The step RevenueCat's paywall never reports: it separates "nobody engaged" from
            // "everybody engaged and balked at the price", which are opposite problems with opposite fixes.
            offers.firstOrNull { it.packageId == packageId }?.let { offer ->
                analytics.capture(
                    AnalyticsEvents.PRO_OFFER_SELECTED,
                    ProFunnel.offerSelected(
                        base = base,
                        productId = offer.productId,
                        priceMicros = offer.priceMicros,
                        currency = offer.currency,
                        position = offers.indexOf(offer),
                        wasPreselected = offer.packageId == defaultOffer?.packageId,
                    ),
                )
            }
        },
        onBuy = {
            val pkg = effectiveSelection ?: return@PassSheet
            phase = PassSheetPhase.Working
            val offer = offers.firstOrNull { it.packageId == pkg }
            val productId = offer?.productId ?: pkg
            offer?.let {
                analytics.capture(
                    AnalyticsEvents.PURCHASE_STARTED,
                    ProFunnel.purchaseStarted(base, it.productId, it.priceMicros, it.currency),
                )
            }
            scope.launch {
                phase =
                    when (purchases.buyPass(groupId, pkg)) {
                        PassPurchaseResult.Activated -> {
                            // Fired on ACTIVATION, not on the charge: a purchase the server never turned
                            // into an entitlement is not a conversion, and counting it as one would hide the
                            // exact failure the retry below exists for.
                            analytics.capture(
                                AnalyticsEvents.PURCHASE_ACTIVATED,
                                ProFunnel.purchaseActivated(
                                    base = base,
                                    productId = productId,
                                    priceMicros = offer?.priceMicros ?: 0L,
                                    currency = offer?.currency,
                                    store = null,
                                    // Whether this extended an existing pass rather than starting one, which
                                    // is the difference between a first sale and a repeat.
                                    stacked = status?.isPro == true,
                                ),
                            )
                            // `activate-pass` inserts the row SERVER-side, and `group_passes` is
                            // pull-only (SyncEngine), so without this the buyer sits on a screen still
                            // saying "out of free scans" until the next pull happens to run. Awaited
                            // while the sheet is still up rather than launched into a scope that dies
                            // with it: the sheet closing IS the promise that the group is Pro now.
                            pullPass()
                            PassSheetPhase.Idle.also { onDismiss() }
                        }

                        PassPurchaseResult.ChargedNotActivated -> {
                            // The one failure that costs someone money. Logged separately from a refusal so
                            // its rate is visible rather than buried in a generic failure count.
                            analytics.capture(
                                AnalyticsEvents.PURCHASE_ACTIVATION_FAILED,
                                base + mapOf("product_id" to productId, "reason" to "activation"),
                            )
                            PassSheetPhase.Charged
                        }

                        PassPurchaseResult.ChargedActivationRefused -> {
                            // Its own reason, or the metric cannot answer the one question that matters
                            // here: how many of these are a flaky network (which fixes itself) versus the
                            // server rejecting real transactions (which does not, and needs a human).
                            analytics.capture(
                                AnalyticsEvents.PURCHASE_ACTIVATION_FAILED,
                                base + mapOf("product_id" to productId, "reason" to "activation_refused"),
                            )
                            PassSheetPhase.ChargedRefused
                        }

                        PassPurchaseResult.Cancelled -> {
                            PassSheetPhase.Idle
                        }

                        PassPurchaseResult.Failed -> {
                            analytics.capture(
                                AnalyticsEvents.PURCHASE_ACTIVATION_FAILED,
                                base + mapOf("product_id" to productId, "reason" to "store"),
                            )
                            PassSheetPhase.Failed(null)
                        }
                    }
            }
        },
        onRetryOffers = { offersAttempt++ },
        onSeeSubscription =
            onSeeSubscription?.let { seePro ->
                {
                    onDismiss()
                    seePro()
                }
            },
        onRetryActivation = {
            phase = PassSheetPhase.Working
            scope.launch {
                phase =
                    purchases
                        .retryPendingActivation(groupId)
                        .toSheetPhase()
                        .also {
                            if (it == PassSheetPhase.Idle) {
                                pullPass()
                                onDismiss()
                            }
                        }
            }
        },
        onContactSupport = { urlOpener.open(PASS_SUPPORT_MAILTO) },
        onDismiss = {
            // The funnel's denominator. Without it, "shown minus purchased" absorbs every crash and
            // backgrounding, and the drop-off number stops meaning anything.
            if (phase != PassSheetPhase.Working) {
                analytics.capture(
                    AnalyticsEvents.PRO_OFFER_DISMISSED,
                    ProFunnel.offerDismissed(base, hadSelection = selected != null),
                )
            }
            onDismiss()
        },
    )
}

/**
 * Where someone whose charge was refused goes. The same address as Settings' Send feedback, with its
 * own subject so these land identifiable rather than mixed into general feedback — the reply needs the
 * `pro_orphan_purchases` row, not a bug triage.
 */
private const val PASS_SUPPORT_MAILTO = "mailto:feedback@split-evenly.app?subject=Evenly%20Pro%20payment"

/**
 * The pass sheet's reading of an activation attempt.
 *
 * [ActivationOutcome.Retryable] is the only one that keeps offering the retry, and that is the whole
 * point of the split: the sheet used to show "tap again, you won't be charged twice" for a permanent
 * server refusal too, on every launch and every paywall open, forever.
 */
internal fun ActivationOutcome.toSheetPhase(): PassSheetPhase =
    when (this) {
        is ActivationOutcome.Activated -> PassSheetPhase.Idle
        is ActivationOutcome.Retryable -> PassSheetPhase.Charged
        is ActivationOutcome.Refused -> PassSheetPhase.ChargedRefused
    }

/**
 * RevenueCat's subscription paywall, wired, and **hostable anywhere** (`PRO_PASS_SPEC.md` §8.2).
 *
 * It exists as a host rather than only as a destination because the editors need it *without a
 * navigation*. A push disposes the editor underneath (see `ui/AGENTS.md`), which is why the pass sheet's
 * "See Evenly Pro" link used to be null inside one: leaving a half-typed bill to browse a subscription
 * lost the draft. Rendered in place, over the editor, the draft is simply still there when the paywall
 * closes, so the link can be real on every surface.
 *
 * Full-screen and opaque, so hosting it is a matter of composing it last; it is not a sheet.
 */
@Composable
fun ProPaywallHost(
    /** One of [ProTriggers]; carried through from whichever surface opened this. */
    trigger: String,
    /** The one exit the paywall itself offers: "only need it for one trip?" */
    onWantsPass: () -> Unit,
    onDismiss: () -> Unit,
) {
    val purchases = koinInject<ProPurchaseCoordinator>()
    val analytics = koinInject<EvAnalytics>()
    val urlOpener = koinInject<UrlOpener>()
    val scope = rememberCoroutineScope()

    // `offering_id` is what makes a RevenueCat experiment readable on our side too, not only in their
    // dashboard. Same event, same properties as the pass sheet, separated only by `surface`. One PostHog
    // funnel then spans both doors, which is the whole point: routing the scan gate to our own sheet is
    // only defensible if the comparison between the two doors stays answerable.
    val paywallBase =
        ProFunnel.base(
            surface = ProFunnel.Surface.RC_PAYWALL,
            kind = ProFunnel.Kind.SUBSCRIPTION,
            trigger = trigger,
            groupId = null,
            variant = null,
        )
    LaunchedEffect(trigger) {
        analytics.capture(
            AnalyticsEvents.PRO_OFFER_SHOWN,
            ProFunnel.offerShown(
                base = paywallBase,
                // RevenueCat's paywall renders its own packages and does not report them to us, so the
                // offering id is what identifies the offer set. Recorded as such rather than left blank,
                // or the two surfaces stop being comparable at the impression.
                productIds = emptyList(),
                currency = null,
                preselectedProductId = null,
                bestValueProductId = null,
                mode = "subscription",
                scansUsed = null,
            ),
        )
    }
    ProPaywallScreen(
        // Null means "the current offering", which is what the dashboard's own placement rules decide.
        // Naming ours here would take the placement decision away from the console.
        offering = null,
        onDismiss = {
            analytics.capture(
                AnalyticsEvents.PRO_OFFER_DISMISSED,
                ProFunnel.offerDismissed(paywallBase, hadSelection = false),
            )
            onDismiss()
        },
        // RevenueCat says a purchase happened; the server still decides who is Pro, so the only thing
        // done here is asking the server to re-read this subscriber.
        onPurchased = { txn ->
            // RevenueCat does not hand us the price on this callback, so the amount is absent rather
            // than guessed; revenue for the subscription door comes from their dashboard, which is
            // authoritative for it. The step still lands in the same funnel.
            analytics.capture(
                AnalyticsEvents.PURCHASE_ACTIVATED,
                paywallBase +
                    mapOf(
                        "product_id" to txn.productIds.firstOrNull().orEmpty(),
                        "stacked" to false,
                    ),
            )
            scope.launch { purchases.syncSubscriber() }
        },
        onRestored = { scope.launch { purchases.syncSubscriber() } },
        onWantsOneTrip = onWantsPass,
        onTerms = { urlOpener.open("https://split-evenly.app/terms") },
        onPrivacy = { urlOpener.open("https://split-evenly.app/privacy") },
    )
}
