@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package da.chelimo.sharecost.platform

import da.chelimo.sharecost.data.upload.PreparedUpload
import da.chelimo.sharecost.data.upload.ReceiptUploadDriver
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject

/**
 * iOS [ReceiptUploadScheduler] over a **background** `NSURLSession`. Upload tasks are file-based
 * (`uploadTaskWithRequest:fromFile:`), which is what lets iOS keep transferring them while the app is
 * suspended — and, with the host app's `handleEventsForBackgroundURLSession` hook, even relaunch us to
 * finish. The delegate reports progress + completion back through the shared [ReceiptUploadDriver], so
 * all Room bookkeeping stays in one place.
 *
 * Whole-file retry: a row is marked UPLOADING when its task starts (so a re-`schedule()` won't re-claim
 * it); on completion the delegate publishes the receipt (success) or flips it FAILED for a later retry.
 *
 * **Host-app wiring (iosApp):** the `AppDelegate` must implement
 * `application(_:handleEventsForBackgroundURLSession:completionHandler:)` and store the handler, and the
 * background session identifier here must match what the system expects. Documented in PUSH_SETUP-style
 * notes; without it, suspended-app completions are delivered on next foreground instead of immediately.
 */
actual class ReceiptUploadScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var driver: ReceiptUploadDriver? = null
    private val lastReported = mutableMapOf<String, Long>()

    private val session: NSURLSession by lazy {
        val config = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(SESSION_ID)
        config.sessionSendsLaunchEvents = true
        NSURLSession.sessionWithConfiguration(config, Delegate(), delegateQueue = null)
    }

    actual fun bind(driver: ReceiptUploadDriver) {
        this.driver = driver
        // Touch the session so the background config is created on launch — this lets the delegate
        // receive completion callbacks for tasks that finished while the app was suspended/terminated.
        session
    }

    actual fun schedule() {
        val driver = driver ?: return
        scope.launch {
            // claimPending() marks each row UPLOADING, so concurrent schedule() calls won't double-send.
            driver.claimPending().forEach { startUploadTask(it) }
        }
    }

    actual fun cancel(id: String) {
        session.getAllTasksWithCompletionHandler { tasks ->
            (tasks as? List<*>)?.forEach { t ->
                val task = t as? NSURLSessionTask ?: return@forEach
                if (task.taskDescription == id) task.cancel()
            }
        }
    }

    private fun startUploadTask(task: PreparedUpload) {
        val url = NSURL.URLWithString(task.uploadUrl) ?: run {
            scope.launch { driver?.reportFailure(task.id, "Bad upload URL") }
            return
        }
        val request = NSMutableURLRequest(uRL = url).apply {
            setHTTPMethod("PUT")
            setValue("Bearer ${task.token}", forHTTPHeaderField = "Authorization")
            setValue("true", forHTTPHeaderField = "x-upsert")
            setValue(task.mimeType, forHTTPHeaderField = "Content-Type")
        }
        val fileUrl = NSURL.fileURLWithPath(task.localPath)
        val uploadTask = session.uploadTaskWithRequest(request, fromFile = fileUrl)
        uploadTask.taskDescription = task.id
        uploadTask.resume()
    }

    /** Bridges background-session callbacks (on the session's serial delegate queue) to the driver. */
    private inner class Delegate : NSObject(), NSURLSessionDataDelegateProtocol {

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didSendBodyData: Long,
            totalBytesSent: Long,
            totalBytesExpectedToSend: Long,
        ) {
            val id = task.taskDescription ?: return
            // Throttle DB writes: only persist on a meaningful jump or at completion.
            val last = lastReported[id] ?: 0L
            if (totalBytesSent - last >= PROGRESS_STEP_BYTES || totalBytesSent >= totalBytesExpectedToSend) {
                lastReported[id] = totalBytesSent
                scope.launch { driver?.reportProgress(id, totalBytesSent) }
            }
        }

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didCompleteWithError: NSError?,
        ) {
            val id = task.taskDescription ?: return
            lastReported.remove(id)
            val status = (task.response as? NSHTTPURLResponse)?.statusCode ?: 0
            val ok = didCompleteWithError == null && status in 200..299
            scope.launch {
                if (ok) driver?.reportSuccess(id)
                else driver?.reportFailure(id, didCompleteWithError?.localizedDescription ?: "HTTP $status")
            }
        }
    }

    private companion object {
        const val SESSION_ID = "da.chelimo.sharecost.receiptUpload"
        const val PROGRESS_STEP_BYTES = 16 * 1024L
    }
}
