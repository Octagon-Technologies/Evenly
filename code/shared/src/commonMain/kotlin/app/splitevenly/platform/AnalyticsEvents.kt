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

    // Onboarding — WelcomeScreen (`step = "welcome_slides"`) then OnboardingScreen's own step ids
    // (`name`, `currency`, `handle`, `analytics`, `notify`). `step` values must track
    // OnboardingScreen.kt's `steps` list; see PlaceholderClaimSources below for the equivalent pattern.
    const val ONBOARDING_STEP_VIEWED = "onboarding_step_viewed"
    const val ONBOARDING_STEP_COMPLETED = "onboarding_step_completed"

    // Fires once per install, guarded by a SecureStorage flag (mirrors WELCOME_SEEN_KEY) — never on a
    // later re-open.
    const val ONBOARDING_COMPLETED = "onboarding_completed"

    // Groups
    const val GROUP_CREATED = "group_created"
    const val GROUP_JOINED = "group_joined"
    const val GROUP_LEFT = "group_left"

    // Delete/restore are group-wide and any member can do either, so the ratio between these two is
    // the signal for whether the confirmation friction is set right.
    const val GROUP_DELETED = "group_deleted"
    const val GROUP_RESTORED = "group_restored"
    const val GROUPS_PURGED = "groups_purged"
    const val PLACEHOLDER_ADDED = "placeholder_added"
    const val PLACEHOLDER_NOT_ME = "placeholder_not_me"

    // Fires wherever GroupRepositoryImpl.reconcilePlaceholder actually merges an identity — see
    // PlaceholderClaimSources for the `claim_source` values and where each one fires.
    const val PLACEHOLDER_CLAIMED = "placeholder_claimed"

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

/**
 * `claim_source` values for [AnalyticsEvents.PLACEHOLDER_CLAIMED]. There is exactly one merge method
 * (`GroupRepositoryImpl.reconcilePlaceholder`) and three call sites: joining with an identity already
 * picked, and the one `ReconcileRoute` screen that both the in-group card's "See all" and the Group
 * settings row open (`Route.Reconcile.source`) — the card and the full-screen list are the same
 * destination, not two, so there is no separate "identity_card" value.
 */
object PlaceholderClaimSources {
    const val GROUP_JOIN = "group_join"
    const val GROUP_HOME = "group_home"
    const val GROUP_SETTINGS = "group_settings"
}
