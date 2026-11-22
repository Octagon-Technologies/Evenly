package app.splitevenly.platform

/** Minimal platform probe + the home for expect/actual surfaces (06 §5). */
interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/** True on Apple platforms (Kotlin/Native iOS), false on Android. Lets shared Compose UI render a
 *  platform-idiomatic variant — e.g. the floating-island bottom nav on iOS vs. the edge-to-edge
 *  Material bar on Android — without an expect/actual composable. Keep UI branching on this rare. */
expect fun isIOS(): Boolean

/** True for a debuggable build (Android `FLAG_DEBUGGABLE` / Kotlin-Native debug binary). Gates dev-only
 *  affordances such as the test-account password sign-in. Defaults to `false` if it can't be determined. */
expect fun isDebugBuild(): Boolean

/** The OS-level "reduce motion" accessibility setting. Screens with a custom animation (the feedback
 *  thank-you screen) check this and render straight into the end state instead. Defaults to `false` if
 *  it can't be determined, which is the safer default: a missed check plays an animation nobody asked
 *  to skip, rather than silently freezing one somebody wanted. */
expect fun isReduceMotionEnabled(): Boolean

/** ISO 4217 code of the device region's currency (Android default locale, iOS `NSLocale`), or null when the
 *  locale carries no region (e.g. plain "en"). Seeds the base currency at onboarding; callers must still
 *  check the code against the supported set. */
expect fun deviceCurrencyCode(): String?
