package da.chelimo.sharecost.platform

/**
 * App-private storage for receipt bytes that are queued for upload (D-22, resilient upload). A picked
 * `content://` / security-scoped URL is a *borrowed* grant — revoked the moment the picker closes or
 * the process restarts — so the upload pipeline copies the (already compressed) bytes here the instant
 * they're picked. This on-disk copy is what survives OOM kills, reboots, and network drops; it's the
 * single durable thing the resumable uploader stands on.
 *
 * Files live under a dedicated `receipt_uploads/` directory in the app sandbox (never user-visible,
 * never backed up to cloud — these are transient). [delete] is called once the bytes reach Storage.
 *
 * Constructor is platform-specific (Android needs a `Context`) — instances come from `platformModule()`.
 */
expect class ReceiptFileStore {
    /** Persist [bytes] under a file named `<id>.<ext>`; returns its absolute path. Overwrites if present. */
    suspend fun save(id: String, ext: String, bytes: ByteArray): String

    /** Read the bytes back (e.g. for the Android upload worker); null if the file is gone. */
    suspend fun read(path: String): ByteArray?

    /** Best-effort delete of a previously [save]d file. No-op if already gone. */
    fun delete(path: String)
}
