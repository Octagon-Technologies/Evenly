package app.splitevenly.ui.navigation

import app.splitevenly.ui.screen.expense.RECEIPT_UNDO_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Deletes that wait out the receipt strip's Undo window before anything is gone for good: a staged file
 * discarded, an upload cancelled, a saved receipt soft-deleted.
 *
 * Runs on its own scope, not the route's, because the user often taps Save and leaves inside the window;
 * the route's scope is cancelled on dispose and the delete would never happen. Touch [schedule] and
 * [cancel] from the UI thread only.
 */
internal class DeferredRemovals {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()

    fun schedule(
        id: String,
        delete: suspend () -> Unit,
    ) {
        jobs.remove(id)?.cancel()
        jobs[id] = scope.launch {
            // A beat past the strip's window, so an Undo tapped at the last moment still wins.
            delay(RECEIPT_UNDO_MS + 1_000L)
            delete()
        }
    }

    /** Stops a pending delete. Returns false when it already ran (too late to undo). */
    fun cancel(id: String): Boolean {
        val job = jobs.remove(id) ?: return false
        if (job.isCompleted) return false
        job.cancel()
        return true
    }
}
