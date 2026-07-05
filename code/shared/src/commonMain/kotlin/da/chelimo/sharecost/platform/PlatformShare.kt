package da.chelimo.sharecost.platform

/**
 * Hand a plain string to the OS share sheet (Android chooser / iOS `UIActivityViewController`) so the
 * user can send it through whatever app they like — Messages, WhatsApp, email, etc. Used by the invite
 * sheet so sharing a group link is one tap instead of copy-then-paste.
 *
 * **Android**: `Intent(ACTION_SEND)` wrapped in `createChooser`, launched from the application
 * `Context` (so it needs `FLAG_ACTIVITY_NEW_TASK`).
 * **iOS**: `UIActivityViewController` presented from the top view controller; the iPad popover anchor is
 * set to the presenter's own view so it can't crash on a null source.
 *
 * Call on the main thread on iOS (it touches `UIApplication`).
 */
expect class PlatformShare {
    /** Present the OS share sheet for [text]; [subject] seeds the subject line where a target supports it (email). */
    fun shareText(text: String, subject: String? = null)
}
