package app.splitevenly.data.remote.revenuecat

import app.splitevenly.core.error.AppError
import app.splitevenly.data.repository.PassPurchaseResult
import app.splitevenly.data.repository.toPurchaseResult
import app.splitevenly.ui.navigation.toSheetPhase
import app.splitevenly.ui.screen.pro.PassSheetPhase
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S8 — a paying customer must not be told "tap again" about something tapping again cannot fix.
 *
 * Both gateways collapsed every outcome into `false`, so a permanent server refusal (a 403 because
 * RevenueCat's server-side verification failed, a 400 on an unrecognised product id, a 500 from a
 * misconfigured deploy) was indistinguishable from airplane mode. The sheet showed "Payment went
 * through. Tap again, you won't be charged twice." — and `retryPendingActivation` runs on every launch
 * and every paywall open, so it kept saying that forever, with no route to a human.
 */
class ActivationOutcomeTest {
    @Test
    fun aSuccessActivates() {
        assertEquals(ActivationOutcome.Activated, ActivationOutcome.of(HttpStatusCode.OK))
        assertTrue(ActivationOutcome.of(HttpStatusCode.NoContent).activated)
    }

    @Test
    fun aServerRefusalIsPermanent_andNeverOffersARetry() {
        // These are the server's verdict on this exact transaction. It gives the same one every time.
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.NotFound)) {
            val outcome = ActivationOutcome.of(status)
            assertEquals(ActivationOutcome.Refused(status.value), outcome, "$status")
            assertEquals(PassPurchaseResult.ChargedActivationRefused, outcome.toPurchaseResult(), "$status")
            assertEquals(PassSheetPhase.ChargedRefused, outcome.toSheetPhase(), "$status")
        }
    }

    @Test
    fun rateLimitAndTimeoutAreRetryable_eventThoughTheyAre4xx() {
        // The two 4xx codes that mean "not now" rather than "no": a retry is genuinely the answer.
        assertTrue(ActivationOutcome.of(HttpStatusCode.TooManyRequests) is ActivationOutcome.Retryable)
        assertTrue(ActivationOutcome.of(HttpStatusCode.RequestTimeout) is ActivationOutcome.Retryable)
    }

    @Test
    fun serverErrorsAreRetryable() {
        val serverErrors =
            listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable)
        for (status in serverErrors) {
            val outcome = ActivationOutcome.of(status)
            assertTrue(outcome is ActivationOutcome.Retryable, "$status")
            assertEquals(PassPurchaseResult.ChargedNotActivated, outcome.toPurchaseResult(), "$status")
            assertEquals(PassSheetPhase.Charged, outcome.toSheetPhase(), "$status")
        }
    }

    @Test
    fun aTransportFailureIsRetryable_andCarriesTheRealErrorKind() {
        // A thrown exception is never a verdict on the purchase, so it can only ever be retryable — but
        // the error it carries still has to say what actually happened, not "offline" for everything.
        val offline = ActivationOutcome.of(IOException("connection reset"))
        val unreachable = ActivationOutcome.Retryable(AppError.Network(AppError.Network.Kind.Unreachable))
        assertEquals(unreachable, offline.withoutCause())

        val bug = ActivationOutcome.of(IllegalStateException("bug"))
        assertTrue(bug is ActivationOutcome.Retryable)
        assertTrue(bug.error is AppError.Unexpected)
    }

    @Test
    fun noOutcomeIsEverAPlainFailure_becauseTheMoneyAlreadyMoved() {
        val outcomes =
            listOf(
                ActivationOutcome.Activated,
                ActivationOutcome.Retryable(null),
                ActivationOutcome.Refused(403),
            )

        assertTrue(outcomes.none { it.toPurchaseResult() == PassPurchaseResult.Failed })
        assertTrue(outcomes.none { it.toPurchaseResult() == PassPurchaseResult.Cancelled })
    }

    /** `AppError.Network` carries the causing throwable, which no test wants to reconstruct. */
    private fun ActivationOutcome.withoutCause(): ActivationOutcome =
        when (this) {
            is ActivationOutcome.Retryable -> {
                ActivationOutcome.Retryable((error as? AppError.Network)?.copy(cause = null) ?: error)
            }

            else -> {
                this
            }
        }
}
