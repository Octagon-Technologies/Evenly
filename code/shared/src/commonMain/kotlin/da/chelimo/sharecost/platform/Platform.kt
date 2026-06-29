package da.chelimo.sharecost.platform

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
