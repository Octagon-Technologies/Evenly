package da.chelimo.sharecost.platform

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import da.chelimo.sharecost.data.upload.ReceiptUploadDriver
import da.chelimo.sharecost.data.upload.ReceiptUploadManager
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/**
 * Android [ReceiptUploadScheduler] backed by `WorkManager`. `schedule()` enqueues a unique
 * network-constrained worker that drains the outbox; WorkManager persists the job in its own DB, so it
 * survives process death and is **re-scheduled automatically after a reboot**. Exponential backoff
 * retries through transient network loss without any manual re-tap.
 *
 * The worker reaches the shared [ReceiptUploadManager] through Koin's [GlobalContext] (Android has one),
 * so this class needs no reference to the driver — [bind] is a no-op here.
 */
actual class ReceiptUploadScheduler(private val context: Context) {

    actual fun bind(driver: ReceiptUploadDriver) {
        // Android resolves the driver (manager) inside the worker via Koin; nothing to hold here.
    }

    actual fun schedule() {
        val request = OneTimeWorkRequest.Builder(ReceiptUploadWorker::class.java)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        // APPEND_OR_REPLACE so a drain enqueued while one is running still runs afterwards, catching any
        // rows added late (claimPending() reads the outbox once at the start of a run).
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    actual fun cancel(id: String) {
        // No per-id task on Android — deleting the outbox row makes the next/running drain skip it.
    }

    internal companion object {
        const val WORK_NAME = "sharecost_receipt_upload"
        const val WORK_TAG = "sharecost_receipt_upload"
    }
}

/**
 * Drains the receipt outbox via the shared [ReceiptUploadManager]. Returns `retry()` if anything still
 * needs sending (lets WorkManager back off and try again on a better connection); `success()` when clear.
 */
class ReceiptUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manager = runCatching { GlobalContext.get().getOrNull<ReceiptUploadManager>() }.getOrNull()
            ?: return Result.success() // Koin not up / Supabase unconfigured — nothing to drain.
        return try {
            if (manager.uploadAll()) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
