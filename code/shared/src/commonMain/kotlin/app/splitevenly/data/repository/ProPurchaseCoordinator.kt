package app.splitevenly.data.repository

import app.splitevenly.data.remote.revenuecat.ActivationOutcome
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

    /**
     * Charged, and the server **refused** the activation rather than failing to answer.
     *
     * Split out from [ChargedNotActivated] because the sheet's copy for that one — "tap again, you
     * won't be charged twice" — is a promise a retry cannot keep here, and it was being made on every
     * launch and every paywall open forever. The money is still safe (RevenueCat's webhook plus
     * `pro_orphan_purchases` is the backstop, and `ProConfig.GROUP_ID_ATTRIBUTE` is set before
     * `purchase()` precisely so this is attributable), so this state's job is to say that and hand the
     * buyer a way to reach a human instead of a button that will not work.
     */
    ChargedActivationRefused,

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
    suspend fun buyPass(
        groupId: String,
        packageId: String,
    ): PassPurchaseResult =
        when (val outcome = billing.buyPass(packageId, groupId)) {
            is PassPurchaseOutcome.Cancelled -> {
                PassPurchaseResult.Cancelled
            }

            is PassPurchaseOutcome.Failed -> {
                PassPurchaseResult.Failed
            }

            is PassPurchaseOutcome.Bought -> {
                // Parked BEFORE the activate call, not after. If this process dies mid-call, the pending
                // record is the only thing that can tell the next launch a charge is owed a pass.
                park { it.with(groupId, outcome.storeTxnId) }
                activate(groupId, outcome.storeTxnId).toPurchaseResult()
            }
        }

    /**
     * Retry the charge parked for **this group**. Safe to call on launch and on every paywall open, which
     * is exactly what §6.2 asks for: the buyer should not have to remember that they were owed something.
     *
     * The [groupId] is the whole of finding R11. The park used to be one un-scoped key, so a stuck charge
     * in group A answered the pass sheet opened in group B: the sheet would retry and *activate group A*,
     * then either dismiss itself as though B were now Pro, or sit in `Charged` with the Buy button
     * replaced by a retry that could never buy B a pass.
     *
     * [ActivationOutcome.Retryable] when there is nothing parked for this group, because "no charge is
     * waiting" and "the charge waiting here is doomed" must not collapse back into one value — that
     * collapse is the whole of finding S8.
     */
    suspend fun retryPendingActivation(groupId: String): ActivationOutcome {
        val txnId = parked().txnFor(groupId) ?: return ActivationOutcome.Retryable(null)
        return activate(groupId, txnId)
    }

    /**
     * Best-effort retry of every *other* group's parked charge, so opening any Pro surface eventually
     * settles them all. Deliberately returns nothing: their outcomes belong to their own groups' sheets,
     * never to the one the buyer is looking at.
     */
    suspend fun retryOtherPendingActivations(exceptGroupId: String) {
        for (gid in parked().groupIds() - exceptGroupId) activate(gid, parked().txnFor(gid) ?: continue)
    }

    /** True when a charge is still waiting on a pass **for this group**, so its sheet opens into retry. */
    suspend fun hasPendingActivation(groupId: String): Boolean = parked().txnFor(groupId) != null

    /**
     * Ask the server to re-read this subscriber from RevenueCat. Called after a paywall purchase, from
     * Restore, and on launch: it takes no body, so calling it more often than needed costs one request
     * and can never write the wrong thing.
     */
    suspend fun syncSubscriber(): ActivationOutcome = subscriberSync?.sync() ?: ActivationOutcome.Retryable(null)

    /** `restorePurchases()` then the same server sync, which is why Restore needs no code path of its own. */
    suspend fun restore(): Boolean {
        val restored = billing.restore()
        val synced = syncSubscriber()
        return restored && synced.activated
    }

    private suspend fun activate(
        groupId: String,
        txnId: String,
    ): ActivationOutcome {
        val gateway = activation ?: return ActivationOutcome.Retryable(null)
        val outcome = gateway.activate(groupId, txnId)
        // Only a success clears the parked transaction, and only this group's. A refusal deliberately
        // keeps it: the buyer can still be handed the transaction id when they reach support, and the
        // RevenueCat webhook may yet settle it server-side, at which point a later retry succeeds and
        // clears it then.
        if (outcome.activated) park { it.without(groupId) }
        return outcome
    }

    private suspend fun parked(): ParkedActivations = ParkedActivations.decode(storage.getString(PENDING_KEY))

    private suspend fun park(edit: (ParkedActivations) -> ParkedActivations) {
        val next = edit(parked())
        if (next.isEmpty()) storage.remove(PENDING_KEY) else storage.putString(PENDING_KEY, next.encode())
    }

    private companion object {
        const val PENDING_KEY = "pro_pending_pass_activation"
    }
}

/**
 * The charges parked on this device waiting on a pass, keyed by the group each one bought.
 *
 * One record per line, `groupId|storeTxnId`. That shape is deliberate: the single un-scoped record this
 * replaces (finding R11) was exactly one such line, so a device that already had one decodes it as a
 * one-entry set with no migration step. Neither a group id (a uuid) nor a store transaction id can
 * contain a newline or a pipe, so nothing needs escaping.
 *
 * Pure and separate from [ProPurchaseCoordinator] so the group-scoping — the actual defect — is testable
 * without a Keychain, which a Kotlin/Native simulator test process does not have.
 */
internal data class ParkedActivations(
    private val byGroup: Map<String, String>,
) {
    fun isEmpty(): Boolean = byGroup.isEmpty()

    fun txnFor(groupId: String): String? = byGroup[groupId]

    fun groupIds(): Set<String> = byGroup.keys

    fun with(
        groupId: String,
        txnId: String,
    ): ParkedActivations = ParkedActivations(byGroup + (groupId to txnId))

    fun without(groupId: String): ParkedActivations = ParkedActivations(byGroup - groupId)

    fun encode(): String = byGroup.entries.joinToString("\n") { "${it.key}|${it.value}" }

    companion object {
        fun decode(raw: String?): ParkedActivations =
            ParkedActivations(
                raw
                    .orEmpty()
                    .lineSequence()
                    .mapNotNull { line ->
                        // A record we cannot read is unusable, so it is dropped rather than retried
                        // forever. Everything else in the store still stands.
                        val parts = line.split("|", limit = 2)
                        if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                            parts[0] to parts[1]
                        } else {
                            null
                        }
                    }.toMap(),
            )
    }
}

/** The buy-flow reading of an activation result: the money already moved, so nothing here is a plain failure. */
fun ActivationOutcome.toPurchaseResult(): PassPurchaseResult =
    when (this) {
        is ActivationOutcome.Activated -> PassPurchaseResult.Activated
        is ActivationOutcome.Retryable -> PassPurchaseResult.ChargedNotActivated
        is ActivationOutcome.Refused -> PassPurchaseResult.ChargedActivationRefused
    }
