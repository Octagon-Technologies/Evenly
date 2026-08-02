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
    const val ACCOUNT_DELETED = "account_deleted"

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
}
