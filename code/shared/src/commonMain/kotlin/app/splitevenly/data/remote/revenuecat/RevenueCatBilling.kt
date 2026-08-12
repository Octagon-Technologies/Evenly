package app.splitevenly.data.remote.revenuecat

import app.splitevenly.core.id.UserId
import app.splitevenly.domain.pro.PassOffer
import app.splitevenly.domain.pro.PassPurchaseOutcome
import app.splitevenly.domain.pro.ProBilling
import com.revenuecat.purchases.kmp.LogLevel
import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.configure
import com.revenuecat.purchases.kmp.ktx.awaitLogIn
import com.revenuecat.purchases.kmp.ktx.awaitLogOut
import com.revenuecat.purchases.kmp.ktx.awaitOfferings
import com.revenuecat.purchases.kmp.ktx.awaitPurchase
import com.revenuecat.purchases.kmp.ktx.awaitRestore
import com.revenuecat.purchases.kmp.models.Offering
import com.revenuecat.purchases.kmp.models.PurchasesTransactionException
import app.splitevenly.platform.isDebugBuild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The live [ProBilling] (`PRO_PASS_SPEC.md` §9). Bound only when [ProConfig.isConfigured]; the inert
 * [NoProBilling] stands in otherwise, so nothing above this layer ever branches on configuration.
 *
 * Deliberately thin. Offerings drive both surfaces and nothing about packages or prices is hardcoded
 * beyond a fallback ordering, because the moment Kotlin knows the price mix a pricing experiment stops
 * being a dashboard change (§9). The subscription paywall is not here at all: it is RevenueCat's own
 * `Paywall()` composable reading the `default` offering.
 */
class RevenueCatBilling : ProBilling {

    override val isAvailable: Boolean get() = ProConfig.isConfigured

    /**
     * Idempotent, and safe to call from anywhere. `Purchases.configure` twice replaces the instance and
     * drops any in-flight listener, so the `isConfigured` guard is not a micro-optimisation.
     */
    fun configure() {
        if (!ProConfig.isConfigured || Purchases.isConfigured) return
        if (isDebugBuild()) Purchases.logLevel = LogLevel.DEBUG
        Purchases.configure(ProConfig.sdkKey)
    }

    override fun bind(scope: CoroutineScope, userId: StateFlow<UserId?>) {
        if (!ProConfig.isConfigured) return
        scope.launch {
            // StateFlow already conflates, so no distinctUntilChanged: applying it is a no-op the
            // compiler now warns about.
            userId.collect { id ->
                configure()
                // Failures are swallowed on purpose: a RevenueCat outage must never block sign-in or
                // sign-out. The next launch re-runs this, and entitlement is the server's answer anyway.
                runCatching {
                    // logIn makes the RC app user id OUR user id, which is what makes webhook
                    // attribution and support lookups possible at all (§9).
                    if (id != null) Purchases.sharedInstance.awaitLogIn(id.value)
                    // Without the logOut, one device's subscription follows the next person who signs
                    // in on it.
                    else Purchases.sharedInstance.awaitLogOut()
                }
            }
        }
    }

    /** The `group_pass` offering, or null when unconfigured or unreachable. Also the pass sheet's
     *  "we could not load prices" signal, which is a real state and not a spinner to hide behind. */
    private suspend fun passOffering(): Offering? {
        if (!ProConfig.isConfigured) return null
        configure()
        return runCatching {
            Purchases.sharedInstance.awaitOfferings().get(ProConfig.PASS_OFFERING)
        }.getOrNull()
    }

    override suspend fun passOffers(): List<PassOffer> =
        passOffering()?.availablePackages.orEmpty().map { pkg ->
            PassOffer(
                packageId = pkg.identifier,
                productId = pkg.storeProduct.id,
                title = pkg.storeProduct.title,
                // The store's own formatted string, never assembled here.
                price = pkg.storeProduct.price.formatted,
            )
        }

    override suspend fun buyPass(packageId: String, groupId: String): PassPurchaseOutcome {
        val pkg = passOffering()?.availablePackages?.firstOrNull { it.identifier == packageId }
            ?: return PassPurchaseOutcome.Failed(null)
        // Set BEFORE purchase(), not after: if the app dies between the charge and our activate call,
        // this attribute on the RevenueCat event is the webhook's only route back to the group (§6.3).
        // Setting it afterwards would leave exactly the purchase we most need to recover unattributed.
        runCatching { Purchases.sharedInstance.setAttributes(mapOf(ProConfig.GROUP_ID_ATTRIBUTE to groupId)) }
        return try {
            val purchase = Purchases.sharedInstance.awaitPurchase(pkg)
            val txnId = purchase.storeTransaction.transactionId
                // No transaction id means nothing can be deduplicated or verified server-side. Reported
                // as a failure rather than activated on trust: the reward for a forged pass is unlimited
                // paid vision calls for six people.
                ?: return PassPurchaseOutcome.Failed(null)
            PassPurchaseOutcome.Bought(storeTxnId = txnId, productId = pkg.storeProduct.id)
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) PassPurchaseOutcome.Cancelled else PassPurchaseOutcome.Failed(e.message)
        }
    }

    override suspend fun restore(): Boolean {
        if (!ProConfig.isConfigured) return false
        configure()
        return runCatching { Purchases.sharedInstance.awaitRestore() }.isSuccess
    }
}

/**
 * The unconfigured build's [ProBilling]: every call is a no-op and [isAvailable] is false.
 *
 * It exists so callers can inject `ProBilling` unconditionally and never ask whether monetization is
 * switched on. A `getOrNull` would put that question in every call site instead.
 */
object NoProBilling : ProBilling {
    override val isAvailable: Boolean get() = false
    override fun bind(scope: CoroutineScope, userId: StateFlow<UserId?>) = Unit
    override suspend fun passOffers(): List<PassOffer> = emptyList()
    override suspend fun buyPass(packageId: String, groupId: String) = PassPurchaseOutcome.Failed(null)
    override suspend fun restore(): Boolean = false
}
