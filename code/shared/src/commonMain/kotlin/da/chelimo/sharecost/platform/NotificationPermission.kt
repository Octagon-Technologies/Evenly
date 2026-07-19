package da.chelimo.sharecost.platform

/** Where the OS currently stands on letting us post notifications. */
enum class NotificationPermissionStatus {
    /** Never asked — a [NotificationPermission.request] will show the system prompt. */
    NotDetermined,
    Granted,
    /** Asked and refused. Neither OS re-prompts; only a trip to system settings undoes it. */
    Denied,
    /** No runtime grant exists on this OS version (Android < 13) — we may post already. */
    NotApplicable,
}

/**
 * Runtime notification permission (Android 13+ `POST_NOTIFICATIONS`, iOS `UNUserNotificationCenter`).
 *
 * Both platforms give exactly **one** prompt per install: a refusal is final, and only system settings
 * undo it. So [request] must only ever be called from a surface that has already told the user what the
 * notifications are for and had them opt in — today the onboarding "Stay in the loop" step. This used to
 * fire from `MainActivity.onCreate`, which spent that single prompt on a user who had not signed in, had
 * no group, and had been given no reason; everyone who declined was then unreachable for good.
 *
 * Constructor is platform-specific (Android needs a way to reach the foreground activity) — instances
 * come from `platformModule()`.
 */
expect class NotificationPermission {
    /** What the OS says right now; never shows UI. */
    suspend fun status(): NotificationPermissionStatus

    /** Show the system prompt if one is still available; returns whether we may post afterwards. */
    suspend fun request(): Boolean
}
