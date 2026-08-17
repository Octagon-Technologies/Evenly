package app.splitevenly.data.remote.supabase

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S3 — push and pull health are separate, because they fail separately.
 *
 * `SyncManager` runs two independent loops: push on Room invalidation, pull on the realtime doorbell.
 * A push wedged on a 403 or a unique-index violation keeps failing while pulls keep succeeding, and
 * with one shared counter every successful pull reset it. `consecutiveFailures` therefore never got
 * past 1, `looksOffline` stayed false, and the health flow reported a healthy sync while not one local
 * write had reached the server since the wedge. Push-fails/pull-succeeds is the *normal* interleaving,
 * so the masking was permanent rather than rare — and this is the signal every other sync defect is
 * supposed to be diagnosed through.
 */
class SyncHealthTest {
    private val backend = AppResult.Err(AppError.Backend(403, "insufficient_privilege", null))
    private val offline = AppResult.Err(AppError.Network(AppError.Network.Kind.Unreachable))
    private val ok = AppResult.Ok(Unit)

    private fun SyncHealth.push(
        result: AppResult<Unit>,
        at: Long,
    ) = record(SyncChannel.Push, result, at)

    private fun SyncHealth.pull(
        result: AppResult<Unit>,
        at: Long,
    ) = record(SyncChannel.Pull, result, at)

    @Test
    fun aWedgedPush_isNotErasedByTheSuccessfulPullsThatFollowIt() {
        // The exact interleaving SyncManager produces: push fails, doorbell fires, pull succeeds, repeat.
        var health = SyncHealth()
        repeat(10) { round ->
            health = health.push(backend, at = round * 2L)
            health = health.pull(ok, at = round * 2L + 1)
        }

        assertEquals(10, health.push.consecutiveFailures, "ten failed pushes must read as ten")
        assertEquals(0, health.pull.consecutiveFailures)
        assertTrue(health.isWedged(), "a push failing forever is the definition of wedged")
        assertEquals(AppError.Backend(403, "insufficient_privilege", null), health.activeError)
    }

    @Test
    fun aSuccessfulPush_clearsOnlyItsOwnStreak() {
        val health =
            SyncHealth()
                .push(backend, at = 1)
                .pull(offline, at = 2)
                .push(ok, at = 3)

        assertEquals(0, health.push.consecutiveFailures)
        assertEquals(1, health.pull.consecutiveFailures, "the pull failure is untouched by a push success")
    }

    @Test
    fun successKeepsTheLastError_soDiagnosticsCanStillSayWhatWentWrong() {
        val health = SyncHealth().push(backend, at = 1).push(ok, at = 2)

        assertEquals(0, health.push.consecutiveFailures)
        assertEquals(AppError.Backend(403, "insufficient_privilege", null), health.push.lastError)
        assertEquals(1L, health.push.lastFailureAt)
        assertEquals(2L, health.push.lastSuccessAt)
    }

    @Test
    fun offlineCopyNeedsBothDirectionsDown_soAWedgedPushIsNotBlamedOnTheUsersWifi() {
        val pushOnly = SyncHealth().push(offline, at = 1).pull(ok, at = 2)
        assertFalse(pushOnly.looksOffline, "pulls are landing, so the phone is plainly not offline")

        val both = SyncHealth().push(offline, at = 1).pull(offline, at = 2)
        assertTrue(both.looksOffline)
    }

    @Test
    fun offlineCopyIsNeverShownForANonTransportFailure() {
        val rlsDenied = SyncHealth().push(backend, at = 1).pull(backend, at = 2)

        assertFalse(rlsDenied.looksOffline, "a 403 is not a dead radio and must not be reported as one")
        assertTrue(rlsDenied.isWedged(threshold = 1))
    }

    @Test
    fun aFreshHealth_isNeitherWedgedNorOffline() {
        val health = SyncHealth()

        assertFalse(health.looksOffline)
        assertFalse(health.isWedged())
        assertEquals(null, health.activeError)
    }
}
