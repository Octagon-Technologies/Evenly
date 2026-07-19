package da.chelimo.sharecost.platform

import io.github.samuolis.posthog.PostHog
import io.github.samuolis.posthog.PostHogConfig
import io.github.samuolis.posthog.PostHogContext
import io.github.samuolis.posthog.SessionRecordingConfig

/** Common implementation — posthog-kmp's `PostHog` singleton works from any source set (Android/iOS). */
class PostHogAnalytics : ScAnalytics {
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
            // Autocapture + session replay: TEMPORARY default masking (maskAllTextInputs=true only —
            // library default). Static Text showing dollar amounts/names is NOT masked by this. Owner
            // is reviewing a real replay before deciding on stricter masking (maskAllText/maskAllImages) —
            // don't treat this config as final, and don't ship a release build with replay on until that
            // review happens.
            autocapture = true,
            sessionRecording = SessionRecordingConfig(enabled = true),
        ),
        context = context,
    )
}
