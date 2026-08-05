package app.splitevenly.platform

/**
 * E-3 — open an arbitrary external URL (06 §5.6). Used by the settle flow to launch a payment app
 * via its deep link (03 §5, U-7) and for any web link. Returns `false` when nothing can handle the
 * URL (e.g. the payment app isn't installed) so the caller can fall back — copy-handle, web URL, etc.
 *
 * **Android**: `Intent(ACTION_VIEW)` guarded by `try/catch ActivityNotFoundException`.
 * **iOS**: `UIApplication.openURL`, with `canOpenURL` as the up-front can-handle probe.
 *
 * > iOS note: probing a custom scheme (`venmo://`, `cashapp://`) with `canOpenURL` requires that
 * > scheme to be listed in the app's `Info.plist` `LSApplicationQueriesSchemes`, otherwise it always
 * > reports `false`. Registering those schemes is an `iosApp` host concern handled with U-7.
 */
expect class UrlOpener {
    /** Attempt to open [url] in an external handler; returns `true` if a handler accepted it. */
    fun open(url: String): Boolean
}
