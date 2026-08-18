package app.splitevenly.domain.pro

/**
 * The Evenly Pro purchase funnel, as one shape across both doors (`PRO_PASS_SPEC.md` §12).
 *
 * ## Why this file exists
 *
 * The scan gate opens **our** pass sheet rather than RevenueCat's paywall, which is the right call for
 * the buyer (a control naming a group must not open an all-groups recurring plan) and costs us
 * something real: RevenueCat's paywall analytics and Experiments never see the highest-intent door.
 * This is the replacement, and it has to be *better* than what it replaces or the trade was bad.
 *
 * The single design move is that **both surfaces emit the same events with the same properties**,
 * separated only by [ProSurface]. One PostHog funnel then spans both doors and breaks down by surface,
 * trigger, tier and experiment variant. Two disjoint event families would have made the comparison that
 * actually matters ("does the pass door convert better than the subscription door?") unanswerable, which
 * is exactly the question routing the scan gate away from RevenueCat created.
 *
 * Pure and testable on purpose: a property map assembled ad hoc at four call sites drifts, and a funnel
 * whose steps disagree about their property names is not a funnel.
 */
object ProFunnel {
    /** Which door the person is standing in. The one property the whole cross-surface analysis hangs on. */
    object Surface {
        /** Evenly's own group-pass sheet. */
        const val PASS_SHEET = "pass_sheet"

        /** RevenueCat's remotely-designed subscription paywall. */
        const val RC_PAYWALL = "rc_paywall"
    }

    /** What is being sold. Kept distinct from [Surface] because the two are not one-to-one forever. */
    object Kind {
        const val PASS = "pass"
        const val SUBSCRIPTION = "subscription"
    }

    /**
     * Properties every event in the funnel carries, so a step can be filtered the same way as any other.
     *
     * [groupId] is null on the subscription door, which needs no group. That is not missing data, it is
     * the product difference showing up in the schema, and it is what makes "pass buyers vs subscribers"
     * a breakdown rather than two reports.
     */
    fun base(
        surface: String,
        kind: String,
        trigger: String,
        groupId: String?,
        variant: String?,
    ): Map<String, Any> =
        buildMap {
            put("surface", surface)
            put("kind", kind)
            put("trigger", trigger)
            groupId?.let { put("group_id", it) }
            // Also registered as a super property, but repeated here so an event that somehow arrives before
            // the flag resolves is still attributable rather than silently pooled into the control arm.
            variant?.let { put("variant", it) }
        }

    /**
     * The impression. Records **what was actually shown**, not what we intended to show: the product ids
     * present, which one carried the best-value flag, and which was preselected.
     *
     * Without those, a conversion difference between variants is uninterpretable — you cannot tell a
     * price effect from an ordering effect from a default-selection effect, which is the whole content of
     * a pricing experiment.
     */
    fun offerShown(
        base: Map<String, Any>,
        productIds: List<String>,
        currency: String?,
        preselectedProductId: String?,
        bestValueProductId: String?,
        mode: String,
        scansUsed: Int?,
    ): Map<String, Any> =
        base +
            buildMap {
                put("product_ids", productIds)
                put("offer_count", productIds.size)
                put("mode", mode)
                currency?.let { put("currency", it) }
                preselectedProductId?.let { put("preselected_product_id", it) }
                bestValueProductId?.let { put("best_value_product_id", it) }
                scansUsed?.let { put("scans_used", it) }
            }

    /**
     * One offer's money, in the two forms the analysis needs.
     *
     * [priceMicros] is the exact integer and the one to compute with. `price` is the same number as a
     * decimal, because PostHog's own revenue views read a plain numeric property and cannot divide micros
     * for you. Both are a **store catalogue price**, never a user's balance — see the privacy note on
     * `EvAnalytics`.
     */
    fun money(
        priceMicros: Long,
        currency: String?,
    ): Map<String, Any> =
        buildMap {
            put("price_micros", priceMicros)
            put("price", priceMicros / MICROS_PER_UNIT)
            currency?.let { put("currency", it) }
        }

    /** A tier or plan being tapped. RevenueCat's paywall does not report this, so it is pass-sheet only,
     *  and it is the step that separates "nobody looked" from "everybody balked at the price". */
    fun offerSelected(
        base: Map<String, Any>,
        productId: String,
        priceMicros: Long,
        currency: String?,
        position: Int,
        wasPreselected: Boolean,
    ): Map<String, Any> =
        base + money(priceMicros, currency) +
            mapOf(
                "product_id" to productId,
                "position" to position,
                "was_preselected" to wasPreselected,
            )

    /**
     * The sheet or paywall closing with no purchase started.
     *
     * The funnel's denominator. Without an explicit abandon event, "shown minus purchased" silently
     * absorbs every crash, backgrounding and navigation away, and the drop-off number stops meaning
     * anything.
     */
    fun offerDismissed(
        base: Map<String, Any>,
        hadSelection: Boolean,
    ): Map<String, Any> = base + mapOf("had_selection" to hadSelection)

    /** The store sheet is about to open. The last step we control before the OS takes over. */
    fun purchaseStarted(
        base: Map<String, Any>,
        productId: String,
        priceMicros: Long,
        currency: String?,
    ): Map<String, Any> = base + money(priceMicros, currency) + mapOf("product_id" to productId)

    /**
     * Money changed hands **and** the server turned it on.
     *
     * Fired only after activation, not after the charge: a purchase our server never turned into an
     * entitlement is not a conversion, and counting it as one would hide precisely the failure the
     * retry flow exists for. That failure has its own event.
     */
    fun purchaseActivated(
        base: Map<String, Any>,
        productId: String,
        priceMicros: Long,
        currency: String?,
        store: String?,
        stacked: Boolean,
    ): Map<String, Any> =
        base + money(priceMicros, currency) +
            buildMap {
                put("product_id", productId)
                put("stacked", stacked)
                store?.let { put("store", it) }
            }

    private const val MICROS_PER_UNIT = 1_000_000.0
}

/**
 * How the pass sheet is configured for this person, from a PostHog feature flag.
 *
 * This is the remote-design surface RevenueCat's editor gives the subscription paywall and cannot give a
 * consumable one (§2.1). Every field has a default that is exactly today's behaviour, so a missing flag,
 * an unreachable PostHog or a malformed payload all render the sheet we would have rendered anyway.
 */
data class PassSheetConfig(
    /** Package ids in display order. Empty means "the offering's own order", which is the default. */
    val order: List<String> = emptyList(),
    /** `best_value` | `cheapest` | a package id. Which tier starts selected, and therefore which price
     *  the buy button names before anyone touches anything. */
    val preselect: String = PRESELECT_BEST_VALUE,
    /** Whether the cents-per-day winner is flagged at all. Off is a real arm of the experiment: the flag
     *  may be steering people to a tier they regret. */
    val showBestValueFlag: Boolean = true,
    /** The variant name, for the funnel breakdown. Null when the flag is absent. */
    val variant: String? = null,
) {
    companion object {
        const val FLAG_KEY = "pro_pass_sheet"
        const val PRESELECT_BEST_VALUE = "best_value"
        const val PRESELECT_CHEAPEST = "cheapest"

        val Default = PassSheetConfig()

        /**
         * Read a flag payload into a config, keeping every value we do not recognise out.
         *
         * Deliberately forgiving: this is remote input into a screen that takes money, so an unexpected
         * type falls back to the default rather than throwing. A pricing sheet that fails to render is a
         * worse outcome than one that ignores a typo in a dashboard.
         */
        fun from(
            variant: String?,
            payload: Map<String, Any?>,
        ): PassSheetConfig {
            val order = (payload["order"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            val preselect = (payload["preselect"] as? String)?.takeIf { it.isNotBlank() } ?: PRESELECT_BEST_VALUE
            val flag = payload["best_value_flag"]
            return PassSheetConfig(
                order = order,
                preselect = preselect,
                showBestValueFlag =
                    when (flag) {
                        is Boolean -> flag
                        is String -> flag != "false"
                        else -> true
                    },
                variant = variant,
            )
        }
    }
}

/**
 * Apply a [PassSheetConfig] to the offers the store returned.
 *
 * Ordering is by the config's list where it names a package; with no config, by [PassTier]'s own
 * declared order (shortest to longest) rather than the store's package order, which the dashboard
 * controls and is not guaranteed to be smallest-to-longest. Either way, a partial or stale `order`
 * degrades to "the packages it named first, then the rest, by tier length" instead of dropping a tier
 * the person could have bought.
 */
fun List<PassOffer>.ordered(config: PassSheetConfig): List<PassOffer> {
    val rank =
        if (config.order.isNotEmpty()) {
            config.order.withIndex().associate { (i, id) -> id to i }
        } else {
            PassTier.entries.withIndex().associate { (i, tier) -> tier.packageId to i }
        }
    return sortedBy { rank[it.packageId] ?: (rank.size + indexOf(it)) }
}

/**
 * Which offer starts selected, so the buy button always names a real amount.
 *
 * `best_value` is cents per day, computed from [PassTier.days] and the store's own minor units, so it
 * stays true in every currency and after any dashboard price change. An offer whose package id we do not
 * recognise has no known duration and cannot win that comparison.
 */
fun List<PassOffer>.preselected(config: PassSheetConfig): PassOffer? =
    when (config.preselect) {
        PassSheetConfig.PRESELECT_CHEAPEST -> minByOrNull { it.priceMicros }
        PassSheetConfig.PRESELECT_BEST_VALUE -> bestValueOffer()
        else -> firstOrNull { it.packageId == config.preselect } ?: bestValueOffer()
    } ?: firstOrNull()

/** The cheapest per day, or null when no offer has a duration we know. */
fun List<PassOffer>.bestValueOffer(): PassOffer? =
    this
        .mapNotNull { offer -> PassTier.byPackageId(offer.packageId)?.let { offer to offer.priceMicros / it.days } }
        .minByOrNull { it.second }
        ?.first
