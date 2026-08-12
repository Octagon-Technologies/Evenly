package app.splitevenly.data.repository

import app.splitevenly.data.remote.revenuecat.PassActivationGateway
import app.splitevenly.data.remote.revenuecat.SubscriberSyncGateway
import app.splitevenly.domain.pro.PassPurchaseOutcome
import app.splitevenly.domain.pro.ProBilling
import app.splitevenly.platform.SecureStorage

/** What the pass sheet should show after a buy attempt. */
enum class PassPurchaseResult {
    /** Charged and activated. The group is Pro at the next pull. */
    Activated,

    /**
     * Charged, but activation did not land. The transaction id is parked, so the retry costs nothing and
     * cannot double-charge. Never reported as a plain failure: the money moved.
     */
    ChargedNotActivated,

    Cancelled,
    Failed,
}

/**
 * Buys a group pass and turns it on (`PRO_PASS_SPEC.md` §6.2).
 *
 * The failure this class exists for is the one between the two halves: **the store charges, then our
 * activate call dies.** The transaction id is written to [SecureStorage] *before* activation is
 * attempted and only cleared once the server confirms, so the retry survives a crash, a kill, and a
 * flight-mode taxi ride. Idempotency on `(store, store_txn_id)` is what makes retrying free.
 *
 * It is device-local state on purpose, and [SecureStorage] rather than Room: it is a receipt for one
 * in-flight purchase on this phone, it must never ride the sync push, and it must die on sign-out.
 */
class ProPurchaseCoordinator(
    private val billing: ProBilling,
    private val storage: SecureStorage,
    // Optional-ctor-dep, like every other gateway here: unwired (tests, offline build) means a purchase
    // is never started rather than started and silently lost.
    private val activation: PassActivationGateway? = null,
    private val subscriberSync: SubscriberSyncGateway? = null,
) {

    suspend fun buyPass(groupId: String, packageId: String): PassPurchaseResult =
        when (val outcome = billing.buyPass(packageId, groupId)) {
            is PassPurchaseOutcome.Cancelled -> PassPurchaseResult.Cancelled
            is PassPurchaseOutcome.Failed -> PassPurchaseResult.Failed
            is PassPurchaseOutcome.Bought -> {
                // Parked BEFORE the activate call, not after. If this process dies mid-call, the pending
                // record is the only thing that can tell the next launch a charge is owed a pass.
                storage.putString(PENDING_KEY, "$groupId|${outcome.storeTxnId}")
                if (activate(groupId, outcome.storeTxnId)) PassPurchaseResult.Activated
                else PassPurchaseResult.ChargedNotActivated
            }
        }

    /**
     * Retry whatever is parked. Safe to call on launch and on every paywall open, which is exactly what
     * §6.2 asks for: the buyer should not have to remember that they were owed something.
     */
    suspend fun retryPendingActivation(): Boolean {
        val parked = storage.getString(PENDING_KEY) ?: return false
        val (groupId, txnId) = parked.split("|", limit = 2).takeIf { it.size == 2 } ?: run {
            storage.remove(PENDING_KEY)
            return false
        }
        return activate(groupId, txnId)
    }

    /** True when there is a charge still waiting on a pass, so the sheet can open straight into retry. */
    suspend fun hasPendingActivation(): Boolean = storage.contains(PENDING_KEY)

    /**
     * Ask the server to re-read this subscriber from RevenueCat. Called after a paywall purchase, from
     * Restore, and on launch: it takes no body, so calling it more often than needed costs one request
     * and can never write the wrong thing.
     */
    suspend fun syncSubscriber(): Boolean = subscriberSync?.sync() ?: false

    /** `restorePurchases()` then the same server sync, which is why Restore needs no code path of its own. */
    suspend fun restore(): Boolean {
        val restored = billing.restore()
        val synced = syncSubscriber()
        return restored && synced
    }

    private suspend fun activate(groupId: String, txnId: String): Boolean {
        val gateway = activation ?: return false
        val ok = gateway.activate(groupId, txnId)
        if (ok) storage.remove(PENDING_KEY)
        return ok
    }

    private companion object {
        const val PENDING_KEY = "pro_pending_pass_activation"
    }
}
