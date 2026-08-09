package app.splitevenly.platform

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

    /**
     * Write [content] to a temporary file named [fileName] and share *the file*, so the receiving app
     * gets an attachment it can open rather than a wall of pasted text.
     *
     * Used by the group CSV export. Sharing a ledger as `shareText` technically works and is wrong in
     * practice: a group with a few hundred expenses becomes an unreadable message body, and no
     * spreadsheet app can open it.
     *
     * The file goes to the platform cache (Android's `cacheDir`, already covered by the existing
     * FileProvider `<cache-path>`; iOS `NSTemporaryDirectory`). It is the OS's to reclaim, and it holds
     * a copy of the group's money data, so it must never be written somewhere user-visible or backed up.
     */
    fun shareFile(fileName: String, mimeType: String, content: String, subject: String? = null)
}
