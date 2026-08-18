package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.splitevenly.domain.feedback.FeedbackDraft
import app.splitevenly.domain.feedback.FeedbackOutcome
import app.splitevenly.domain.feedback.FeedbackSubmitter
import app.splitevenly.platform.ConnectivityObserver
import app.splitevenly.platform.NetworkStatus
import app.splitevenly.ui.screen.settings.FeedbackScreen
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Wires [FeedbackScreen] to the outbox.
 *
 * Deliberately thin: there is no repository logic here, only the submit call and the outcome it
 * returned. The durability decision (write to Room, then try the network) lives in
 * `data/repository/FeedbackOutbox`, where the layer rules put it.
 */
@Composable
fun FeedbackRoute(onBack: () -> Unit) {
    val submitter = koinInject<FeedbackSubmitter>()
    val connectivity = koinInject<ConnectivityObserver>()
    val scope = rememberCoroutineScope()

    val status by connectivity.status.collectAsStateWithLifecycle(NetworkStatus.Online)
    var submitting by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<FeedbackOutcome?>(null) }

    FeedbackScreen(
        onBack = onBack,
        submitting = submitting,
        offline = status == NetworkStatus.Offline,
        outcome = outcome,
        onSubmit = { draft: FeedbackDraft ->
            if (submitting) return@FeedbackScreen
            submitting = true
            // Clear the previous error before retrying, or a second failure looks like the first one
            // never went away and a success still shows the old red note underneath the form.
            outcome = null
            scope.launch {
                outcome = submitter.submit(draft)
                submitting = false
            }
        },
        onDone = onBack,
    )
}
