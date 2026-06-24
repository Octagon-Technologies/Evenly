package da.chelimo.sharecost.platform

import da.chelimo.sharecost.data.upload.ReceiptUploadDriver

/**
 * Platform glue that actually moves receipt bytes in a way that survives backgrounding, process death,
 * and reboot. **Android**: enqueues a `WorkManager` `CoroutineWorker` (network constraint + backoff)
 * that drains the outbox via the shared Ktor uploader; WorkManager persists the job and re-runs it
 * after a reboot. **iOS**: hands each pending file to a background `URLSession` whose tasks the OS runs
 * even while the app is suspended; a delegate reports progress/completion back through the [driver].
 *
 * Either way, all DB bookkeeping lives in the shared [ReceiptUploadDriver] ([ReceiptUploadManager]) —
 * this type only schedules and cancels transfers.
 *
 * Constructor is platform-specific (Android needs a `Context`) — instances come from `platformModule()`.
 */
expect class ReceiptUploadScheduler {
    /** Wire the shared driver in (called once from [ReceiptUploadManager.bind]). */
    fun bind(driver: ReceiptUploadDriver)

    /** Ensure the background pipeline runs and drains all pending uploads. Idempotent. */
    fun schedule()

    /** Cancel an in-flight/queued transfer for [id] (the outbox row is removed separately). */
    fun cancel(id: String)
}
