package app.splitevenly.platform

import io.github.samuolis.posthog.PostHog
import io.github.samuolis.posthog.PostHogConfig
import io.github.samuolis.posthog.PostHogContext
import io.github.samuolis.posthog.SessionRecordingConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Common implementation — posthog-kmp's `PostHog` singleton works from any source set (Android/iOS).
 *
 * One class implements both [EvAnalytics] and [FeatureFlags] because they are one SDK; splitting the
 * interfaces keeps "what we measure" and "what we vary" separate at the call sites, which is where the
 * distinction actually matters.
 */
class PostHogAnalytics :
    EvAnalytics,
    FeatureFlags {
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

    override fun setPersonProperties(properties: Map<String, Any>) {
        PostHog.setPersonProperties(properties)
    }

    override fun register(
        key: String,
        value: Any,
    ) {
        PostHog.register(key, value)
    }

    override fun variant(key: String): String? =
        when (val flag = PostHog.getFeatureFlag(key)) {
            is String -> flag

            // A boolean flag is a two-variant experiment whose variants have no names. Reporting them as
            // "on"/"off" keeps every flag readable as a variant in the funnel breakdown, rather than some
            // flags splitting the data and others silently not.
            is Boolean -> if (flag) "on" else "off"

            else -> null
        }

    override fun payload(key: String): Map<String, Any?> {
        val raw = PostHog.getFeatureFlagPayload(key) ?: return emptyMap()
        // The native SDKs hand back either a parsed map or the raw JSON string, depending on platform and
        // payload shape. Both are handled rather than assumed, because the failure mode of assuming is a
        // pricing sheet that silently ignores its own experiment on one OS.
        (raw as? Map<*, *>)?.let { map ->
            return map.entries.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }.toMap()
        }
        val text = raw as? String ?: return emptyMap()
        return runCatching {
            (Json.parseToJsonElement(text) as? JsonObject)?.mapValues { (_, v) -> v.unwrap() }.orEmpty()
        }.getOrDefault(emptyMap())
    }

    override fun reload() {
        PostHog.reloadFeatureFlags()
    }
}

/** JSON to the plain Kotlin values a caller can read without pulling kotlinx.serialization in with it. */
private fun kotlinx.serialization.json.JsonElement.unwrap(): Any? =
    when (this) {
        is JsonPrimitive -> if (isString) content else (booleanOrNull ?: content.toLongOrNull() ?: content)
        is JsonArray -> map { it.unwrap() }
        is JsonObject -> mapValues { (_, v) -> v.unwrap() }
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
