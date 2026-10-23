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
 */
interface EvAnalytics {
    /** Record a user action with optional key/value properties. */
    fun capture(event: String, properties: Map<String, Any> = emptyMap())

    /** Tie subsequent events to the signed-in user; idempotent — safe to call on every session restore. */
    fun identify(userId: String)

    /** Disassociate the current distinct ID from the device (call on sign-out / account deletion). */
    fun reset()
}
