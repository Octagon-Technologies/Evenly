package app.splitevenly.data.repository

import app.splitevenly.data.db.dao.FeedbackOutboxDao
import app.splitevenly.data.db.entity.FeedbackOutboxEntity
import app.splitevenly.data.remote.supabase.FeedbackPostResult
import app.splitevenly.data.remote.supabase.FeedbackPoster
import app.splitevenly.domain.feedback.FeedbackCategory
import app.splitevenly.domain.feedback.FeedbackDraft
import app.splitevenly.domain.feedback.FeedbackOutcome
import app.splitevenly.domain.feedback.FeedbackType
import app.splitevenly.platform.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The property that matters: **a submitted ticket is on disk before the network is touched**, and it
 * only leaves disk when the server has either taken it or refused it permanently. Everything else here
 * is a corollary of that.
 */
class FeedbackOutboxTest {
    private class FakeDao : FeedbackOutboxDao {
        val rows = mutableListOf<FeedbackOutboxEntity>()

        override suspend fun insert(row: FeedbackOutboxEntity) {
            rows += row
        }

        override suspend fun pending(): List<FeedbackOutboxEntity> = rows.sortedBy { it.createdAt }

        override suspend fun delete(id: String) {
            rows.removeAll { it.id == id }
        }

        override suspend fun recordAttempt(id: String) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(attempts = rows[i].attempts + 1)
        }

        override suspend fun clear() = rows.clear()
    }

    private class FakePoster(
        var next: FeedbackPostResult,
    ) : FeedbackPoster {
        var calls = 0
        val bodies = mutableListOf<String>()

        override suspend fun post(
            type: String,
            category: String,
            message: String,
            appVersion: String?,
        ): FeedbackPostResult {
            calls++
            bodies += message
            return next
        }
    }

    /**
     * Built on the test's own `backgroundScope`, so the constructor's drain launches run on the test
     * dispatcher instead of racing the assertions on a real thread pool. The flows are Offline/false for
     * the same reason: each test drives `submit` and `drain` explicitly, and a background trigger firing
     * mid-assertion would be testing the scheduler rather than the outbox.
     */
    private fun TestScope.outbox(
        dao: FeedbackOutboxDao,
        poster: FeedbackPoster,
    ) = FeedbackOutbox(
        dao = dao,
        http = poster,
        connectivity = MutableStateFlow(NetworkStatus.Offline),
        signedIn = MutableStateFlow(false),
        versionLabel = { "1.4.0 (218)" },
        scope = backgroundScope,
    )

    private val draft =
        FeedbackDraft(
            type = FeedbackType.Problem,
            category = FeedbackCategory.Splitting,
            message = "  the balance is wrong  ",
        )

    @Test
    fun `an accepted ticket leaves no row behind`() =
        runTest {
            val dao = FakeDao()
            val poster = FakePoster(FeedbackPostResult.Accepted)

            assertEquals(FeedbackOutcome.Sent, outbox(dao, poster).submit(draft))
            assertTrue(dao.rows.isEmpty())
            assertEquals(1, poster.calls)
        }

    @Test
    fun `the message is trimmed before it is stored matching the server`() =
        runTest {
            val dao = FakeDao()
            val poster = FakePoster(FeedbackPostResult.Unreachable)

            outbox(dao, poster).submit(draft)

            assertEquals("the balance is wrong", dao.rows.single().message)
            assertEquals("1.4.0 (218)", dao.rows.single().appVersion)
        }

    @Test
    fun `an unreachable server keeps the ticket and reports it as queued`() =
        runTest {
            val dao = FakeDao()
            val poster = FakePoster(FeedbackPostResult.Unreachable)

            assertEquals(FeedbackOutcome.Queued, outbox(dao, poster).submit(draft))
            assertEquals(1, dao.rows.size)
            assertEquals(1, dao.rows.single().attempts)
        }

    @Test
    fun `an expired session keeps the ticket rather than discarding the words`() =
        runTest {
            val dao = FakeDao()
            val poster = FakePoster(FeedbackPostResult.Unauthenticated)

            assertEquals(FeedbackOutcome.NeedsSignIn, outbox(dao, poster).submit(draft))
            assertEquals(1, dao.rows.size)
        }

    @Test
    fun `a permanent rejection drops the row instead of retrying forever`() =
        runTest {
            val dao = FakeDao()
            val poster = FakePoster(FeedbackPostResult.Rejected("bad_category"))

            assertEquals(FeedbackOutcome.Failed, outbox(dao, poster).submit(draft))
            assertTrue(dao.rows.isEmpty())
        }

    @Test
    fun `a later drain sends what an outage left behind`() =
        runTest {
            val dao = FakeDao()
            val poster = FakePoster(FeedbackPostResult.Unreachable)
            val outbox = outbox(dao, poster)

            outbox.submit(draft)
            assertEquals(1, dao.rows.size)

            poster.next = FeedbackPostResult.Accepted
            outbox.drain()

            assertTrue(dao.rows.isEmpty())
            assertEquals("the balance is wrong", poster.bodies.last())
        }

    @Test
    fun `a failing drain stops at the first row instead of burning the whole queue's budget`() =
        runTest {
            val dao = FakeDao()
            val poster = FakePoster(FeedbackPostResult.Unreachable)
            val outbox = outbox(dao, poster)

            outbox.submit(draft)
            outbox.submit(draft.copy(message = "second"))

            outbox.drain()

            // The first row wears the drain's failed attempt; the second is untouched, because the drain
            // gave up rather than marching on. Asserted on the rows instead of a call count so an extra
            // background drain (which would also stop at the first row) cannot make this flaky.
            assertEquals(2, dao.rows.size)
            assertEquals(2, dao.rows[0].attempts)
            assertEquals(1, dao.rows[1].attempts)
        }

    @Test
    fun `a row that has failed too many times is finally dropped`() =
        runTest {
            val dao = FakeDao()
            val poster = FakePoster(FeedbackPostResult.Unreachable)
            val outbox = outbox(dao, poster)
            dao.rows +=
                FeedbackOutboxEntity(
                    id = "stuck",
                    type = "problem",
                    category = "other",
                    message = "m",
                    appVersion = null,
                    createdAt = 1,
                    attempts = 24,
                )

            outbox.drain()

            assertTrue(dao.rows.isEmpty())
        }
}
