package app.splitevenly.platform

/**
 * Every PostHog event name this app captures, as compile-time constants — no bare string literals at a
 * call site. Naming convention: `object_verb_past_tense`, snake_case, no screen names in the event name
 * (`group_created`, `scan_completed` — not `group_home_screen_opened`). Keep every new event listed here
 * AND in `.posthog-events.json` at the repo root (name, description, source file) — a missing catalogue
 * entry is an incomplete change.
 */
object AnalyticsEvents {
    // Auth
    const val USER_SIGNED_IN = "user_signed_in"
    const val USER_SIGNED_OUT = "user_signed_out"
    const val ACCOUNT_DELETION_REQUESTED = "account_deletion_requested"
    const val ACCOUNT_DELETION_CANCELLED = "account_deletion_cancelled"

    // Groups
    const val GROUP_CREATED = "group_created"
    const val GROUP_JOINED = "group_joined"
    const val GROUP_LEFT = "group_left"
    const val PLACEHOLDER_ADDED = "placeholder_added"
    const val PLACEHOLDER_NOT_ME = "placeholder_not_me"
    // Device-local-deduped snapshot of a group's shape, fired the first time a group is observed each
    // app session (see GroupRepositoryImpl.maybeSnapshotGroup) — the fallback for group-level analysis
    // since the PostHog wrapper's group() associates only ONE current group per user, which doesn't fit
    // a member belonging to many groups at once.
    const val GROUP_SNAPSHOT = "group_snapshot"

    // Expenses
    const val EXPENSE_ADDED = "expense_added"
    const val EXPENSE_EDITED = "expense_edited"
    const val EXPENSE_DELETED = "expense_deleted"
    const val SETTLEMENT_APPLIED = "settlement_applied"
    const val BILL_CREATED = "bill_created"

    // The up-front "how are you splitting this?" question (SplitApproachChooser).
    const val SPLIT_APPROACH_CHOSEN = "split_approach_chosen"

    // Scan funnel — the single action that costs real money, so every step is tracked even when it's
    // abandoned before a bill exists.
    const val SCAN_SOURCE_CHOSEN = "scan_source_chosen"
    const val SCAN_STARTED = "scan_started"
    const val SCAN_COMPLETED = "scan_completed"
    const val SCAN_FAILED = "scan_failed"
    const val SCAN_CANCELLED = "scan_cancelled"
    const val SCAN_RESULT_EDITED = "scan_result_edited"
    const val SCAN_BLOCKED = "scan_blocked"

    // Evenly Pro (PRO_PASS_SPEC.md §12). RevenueCat's own dashboard covers revenue; these answer the
    // questions it cannot see, because they happen on our side of the paywall.
    //
    // The counts here come from the server's own post-scan answer, never from decrementing a local
    // number: the client cannot tell a scan that counted from one that did not.
    const val FREE_SCAN_USED = "free_scan_used"

    // ── The purchase funnel: ONE spine across BOTH doors ──────────────────────────────────────────
    // §12 named `paywall_shown` and `pass_sheet_shown` separately. They are deliberately collapsed into
    // `pro_offer_shown` with a `surface` property instead, because the scan gate opens our own pass
    // sheet rather than RevenueCat's paywall: with two event names, the one question that routing
    // decision creates — does the pass door convert better than the subscription door? — needs two
    // reports that cannot be laid over each other. Every property §12 asked for survives as a property.
    // See `domain/pro/ProFunnel.kt` for the shape.
    const val PRO_OFFER_SHOWN = "pro_offer_shown"
    const val PRO_OFFER_SELECTED = "pro_offer_selected"
    const val PRO_OFFER_DISMISSED = "pro_offer_dismissed"
    const val PURCHASE_STARTED = "purchase_started"
    const val PURCHASE_ACTIVATED = "purchase_activated"
    const val PURCHASE_ACTIVATION_FAILED = "purchase_activation_failed"

    /**
     * Someone hit the paywall and typed the bill in by hand instead.
     *
     * The honest counterpart to conversion rate: it measures how many people we pushed onto the slow
     * path, which is the cost of the gate and the number a conversion chart is designed not to show.
     */
    const val MANUAL_ENTRY_AFTER_PAYWALL = "manual_entry_after_paywall"

    // §12's `pro_expired` is deliberately NOT here. There is no client-side moment that observes an
    // expiry: the row simply stops being live, so any event fired from the app would really mean "a
    // screen was opened after the expiry", which is a different and much less useful number. Its
    // `scans_during` property needs a server-side count over `receipt_scan_log` as well. It belongs in a
    // scheduled server job, not in a Composable, and inventing a client approximation of it would put a
    // wrong number in the funnel that reads exactly like a right one.
}

/** Person-level properties: slow-moving facts a funnel segments by, not repeated on every event. */
object AnalyticsPerson {
    const val IS_SUBSCRIBER = "is_pro_subscriber"
    const val SUBSCRIPTION_PERIOD = "pro_period"
}

/** Where a paywall or pass sheet was opened from (`trigger` in §12). */
object ProTriggers {
    const val SCAN = "scan"
    const val EXPORT = "export"
    const val GROUP_SETTINGS = "group_settings"
    const val PROFILE = "profile"
}
