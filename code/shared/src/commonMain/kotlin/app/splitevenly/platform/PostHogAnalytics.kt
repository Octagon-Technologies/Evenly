package app.splitevenly.platform

import io.github.samuolis.posthog.PostHog
import io.github.samuolis.posthog.PostHogConfig
import io.github.samuolis.posthog.PostHogContext
import io.github.samuolis.posthog.SessionRecordingConfig

/** Common implementation — posthog-kmp's `PostHog` singleton works from any source set (Android/iOS). */
class PostHogAnalytics : EvAnalytics {
    override fun capture(event: String, properties: Map<String, Any>) {
        PostHog.capture(event, properties)
    }

    override fun identify(userId: String) {
        PostHog.identify(userId)
    }

    override fun reset() {
        PostHog.reset()
    }
}

/**
 * Initializes the PostHog SDK. Must run before Koin starts so [PostHogAnalytics] (bound in each
 * platform's `platformModule`) can call `PostHog.*` immediately once Koin finishes.
 * Android: call with `PostHogContext(application)` from `Application.onCreate`.
 * iOS: call with the no-arg `PostHogContext()` from `MainViewController`.
 */
fun setupAnalytics(context: PostHogContext, debug: Boolean) {
    PostHog.setup(
        config = PostHogConfig(
            apiKey = PostHogCredentials.API_KEY,
            host = PostHogCredentials.HOST,
            debug = debug,
            captureApplicationLifecycleEvents = true,
            captureScreenViews = true,
            autocapture = true,
            // Session recording is OFF: this wrapper's SessionRecordingConfig only masks text INPUTS
            // (maskAllTextInputs) and images (maskAllImages) — there is no "mask all static text" option,
            // so a replay would show every rendered balance, expense title, and member name in the clear
            // on an app whose whole purpose is displaying people's money and who they owe. Re-enable only
            // once there's a way to mask the specific Composables that render money/names (e.g. a
            // per-element replay-mask modifier), not as a blanket default.
            sessionRecording = SessionRecordingConfig(enabled = false),
        ),
        context = context,
    )
}
