package app.splitevenly.platform

import io.github.samuolis.posthog.PostHog
import io.github.samuolis.posthog.PostHogConfig
import io.github.samuolis.posthog.PostHogContext
import io.github.samuolis.posthog.SessionRecordingConfig

/**
 * Common implementation — posthog-kmp's `PostHog` singleton works from any source set (Android/iOS).
 */
class PostHogAnalytics : EvAnalytics {
    override fun capture(
        event: String,
        properties: Map<String, Any>,
    ) {
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
fun setupAnalytics(
    context: PostHogContext,
    debug: Boolean,
) {
    PostHog.setup(
        config =
            PostHogConfig(
                apiKey = PostHogCredentials.API_KEY,
                host = PostHogCredentials.HOST,
                debug = debug,
                captureApplicationLifecycleEvents = true,
                captureScreenViews = true,
                autocapture = true,
                // TEMPORARY DIAGNOSTIC STATE (2026-08-22): turned on in debug AND release, deliberately, so
                // the owner can pull a real recording and inspect it by hand for whether balances, names, or
                // expense titles leak in the clear. This wrapper's SessionRecordingConfig only exposes masking
                // for text INPUTS and images, not static Compose text, so that leak is the expected risk being
                // tested, not a surprise. Do not treat `enabled = true` here as a decision that this is
                // privacy-safe — it is the opposite: it exists to gather the evidence needed to decide whether
                // to invest in per-element masking, accept input/image-only masking, or turn this back off
                // entirely. maskAllTextInputs/maskAllImages stay on as the only mitigation this wrapper offers.
                sessionRecording =
                    SessionRecordingConfig(
                        enabled = true,
                        maskAllTextInputs = true,
                        maskAllImages = true,
                    ),
            ),
        context = context,
    )
}
