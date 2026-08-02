package app.splitevenly.domain.activity

/**
 * Lifecycle of a receipt's *bytes* on their way to Storage. This is **device-local** state — it never
 * syncs (peers only ever see the published `receipts` row, created once [DONE] is reached and the row
 * committed). A row in this state machine outlives the process: it's persisted in Room and the picked
 * bytes are copied to app-private storage, so an OOM kill / reboot / network drop can't lose it.
 *
 * - [PENDING]  queued, not yet (or no longer) being sent. The drain on launch/reconnect resets any
 *   stale [UPLOADING] back to this so it gets picked up again.
 * - [UPLOADING] bytes are actively transferring; `bytesUploaded` advances.
 * - [FAILED]    a transfer attempt errored; whole-file retry will restart it (auto on reconnect, or
 *   manually). There is no terminal "DONE" row — success deletes the outbox row outright.
 */
enum class ReceiptUploadStatus {
    PENDING,
    UPLOADING,
    FAILED,
    ;

    companion object {
        fun fromName(name: String): ReceiptUploadStatus =
            entries.firstOrNull { it.name == name } ?: PENDING
    }
}

/**
 * An in-flight (or failed) receipt upload, as the UI sees it. Rendered as a local thumbnail with a
 * centered progress ring overlaid; [fraction] drives the ring. Distinct from a published [Receipt],
 * which is what exists once the bytes have landed in Storage.
 */
data class ReceiptUpload(
    val id: String,
    val expenseId: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bytesUploaded: Long,
    val status: ReceiptUploadStatus,
) {
    val isPdf: Boolean get() = mimeType.contains("pdf", ignoreCase = true)

    /** Upload progress in 0f..1f. Unknown total (0 bytes) reads as 0f rather than NaN. */
    val fraction: Float
        get() = if (sizeBytes > 0) (bytesUploaded.toFloat() / sizeBytes).coerceIn(0f, 1f) else 0f
}
