package app.splitevenly.data.remote.revenuecat

import app.splitevenly.platform.isIOS

/**
 * RevenueCat wiring for Evenly Pro (`PRO_PASS_SPEC.md` §9), in the shape of
 * [app.splitevenly.data.remote.supabase.SupabaseConfig]: a public key per store, and one
 * [isConfigured] flag that the whole feature hangs off.
 *
 * **Unconfigured must be inert.** Until the owner's console work (§10) lands there is no paywall, no
 * pass sheet, and no Pro entry point, and scans behave exactly as they do today. That is the same
 * contract as the app being fully usable with Supabase unconfigured, and it is what lets steps 3 to 6
 * ship before the store products exist.
 *
 * These are the **public** SDK keys, which are safe to ship: they can fetch offerings and start a
 * purchase, and nothing else. The **secret** key lives only in edge-function env, never here.
 */
object ProConfig {
    // Both hold the **Test Store** key, which is one key for every platform: purchases complete against
    // RevenueCat's own simulated store, so the flow is exercisable with no App Store Connect or Play
    // Console product. Swap for the per-store `appl_`/`goog_` keys (Project settings → API keys) before
    // any build reaches a store — RevenueCat will not serve real products to a `test_` key, so shipping
    // this is a paywall that loads nothing.
    private const val APPLE_SDK_KEY: String = "test_HIbimZFlgPXInnZbrtZdBGcpRYk"
    private const val GOOGLE_SDK_KEY: String = "test_HIbimZFlgPXInnZbrtZdBGcpRYk"

    /** The key for the store this build talks to. Resolved here rather than through an expect/actual,
     *  because nothing about it needs a platform API — only the answer to "which store". */
    val sdkKey: String get() = if (isIOS()) APPLE_SDK_KEY else GOOGLE_SDK_KEY

    val isConfigured: Boolean get() = !sdkKey.startsWith("YOUR_") && sdkKey.isNotBlank()

    /**
     * The offering the subscription paywall reads. Everything about that paywall (layout, copy, which
     * packages appear, price experiments) is a dashboard change against this id, which is the entire
     * reason the subscription exists (§2.1).
     */
    const val SUBSCRIPTION_OFFERING = "default"

    /**
     * The offering holding the three group passes. Deliberately attached to **no entitlement**: a
     * consumable on an entitlement makes RevenueCat report it unlocked forever after one purchase,
     * which is the exact opposite of a pass that expires (§2.3).
     */
    const val PASS_OFFERING = "group_pass"

    /**
     * The subscriber attribute carrying the group a pass was bought for. It is the webhook's **only**
     * route back to the group (§6.3): the store's data model has no place for it, so if this is not set
     * immediately before a pass `purchase()`, a purchase whose activate call dies in flight cannot be
     * attributed to anything and is parked for a human.
     */
    const val GROUP_ID_ATTRIBUTE = "evenly_group_id"

    /**
     * Where "Manage subscription" goes. The **store's** screen, never an in-app imitation (§8.6):
     * cancelling has to be exactly as easy as buying, and a subscriber who cannot find the cancel button
     * leaves a one-star review instead. purchases-kmp exposes no manage-subscriptions call, so these are
     * the canonical store URLs, opened through the platform [app.splitevenly.platform.UrlOpener].
     */
    fun manageSubscriptionsUrl(productId: String?): String =
        if (isIOS()) {
            "https://apps.apple.com/account/subscriptions"
        } else {
            val sku = productId?.let { "?sku=$it&package=$ANDROID_PACKAGE" }.orEmpty()
            "https://play.google.com/store/account/subscriptions$sku"
        }

    private const val ANDROID_PACKAGE = "app.splitevenly"
}
