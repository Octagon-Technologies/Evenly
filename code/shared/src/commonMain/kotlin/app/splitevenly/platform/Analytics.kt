package app.splitevenly.platform

/**
 * Thin analytics facade over the platform SDK (PostHog, via posthog-kmp on both Android and iOS).
 * Common code calls this; the platform modules bind the concrete implementation via Koin.
 * All methods are fire-and-forget — never suspend, never throw.
 *
 * PRIVACY: never send amounts, member names, emails, group names, expense titles, or receipt contents.
 * Counts, enums, durations, and opaque IDs only. `item_count`, `split_mode`, `group_id` are fine;
 * `total`, `title`, `email` are not. Every event name lives in [AnalyticsEvents] — no bare literals.
 *
 * **One carve-out, and only one: a STORE PRODUCT PRICE is not a user's money.** `$3.99` for a one-month
 * pass is public catalogue data, identical for every buyer in a currency, and it is the number without
 * which a purchase funnel cannot be read at all. A user's expense totals, balances and settlements stay
 * banned. If you are about to send an amount, it must have come from a `StoreProduct`, never from the
 * ledger.
 */
interface EvAnalytics {
    /** Record a user action with optional key/value properties. */
    fun capture(event: String, properties: Map<String, Any> = emptyMap())

    /** Tie subsequent events to the signed-in user; idempotent — safe to call on every session restore. */
    fun identify(userId: String)

    /** Disassociate the current distinct ID from the device (call on sign-out / account deletion). */
    fun reset()

    /**
     * Attach properties to the *person*, not the event. Used for the slow-moving facts a funnel needs to
     * segment by ("is this a subscriber?") without repeating them on every event.
     */
    fun setPersonProperties(properties: Map<String, Any>)

    /**
     * Register a **super property**: attached to every subsequent event from this device until
     * unregistered. The experiment variant rides here so no call site can forget it, which is what makes
     * the funnel splittable by variant at every step rather than only at the step that knew about it.
     */
    fun register(key: String, value: Any)
}

/**
 * Remote configuration and experiments, PostHog-backed (`PRO_PASS_SPEC.md` §2.1).
 *
 * Evenly grew a subscription so its paywall could be designed and A/B-tested from a dashboard. The
 * group-pass sheet is ours, so RevenueCat's editor and Experiments cannot reach it — this is the
 * replacement for that one surface, and the reason the pass sheet stays remotely tunable despite being
 * Compose.
 *
 * Every method is total: an unreachable PostHog, a missing flag or a malformed payload all resolve to
 * the caller's default. A pricing sheet must never fail to render because an experiment did not load.
 */
interface FeatureFlags {
    /** The variant name for a multivariate flag ("control", "cheapest_first"), or null. */
    fun variant(key: String): String?

    /** The flag's JSON payload as a flat map, or empty. Values arrive as strings, booleans or lists. */
    fun payload(key: String): Map<String, Any?>

    /** Re-fetch flags. Called once after sign-in, so a person's flags follow their identity, not the
     *  anonymous id they had before it. */
    fun reload()
}
