package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.data.auth.StubAuthSession
import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.inMemoryTestDatabase
import da.chelimo.sharecost.domain.activity.HistoryEventType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ActivityRepositoryImpl] (F5): comments persist + trim + record a COMMENTED history event, blanks are
 * rejected, deletes hide the row, and a receipt without a storage backend fails gracefully (not silently).
 */
class ActivityRepositoryTest {

    private lateinit var db: ShareCostDatabase
    private lateinit var auth: StubAuthSession
    private lateinit var repo: ActivityRepositoryImpl

    private val eid = ExpenseId("e1")
    private val gid = GroupId("g1")

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        auth = StubAuthSession(db.userDao())
        // No ReceiptStorage in tests → the receipt path degrades to an error rather than uploading.
        repo = ActivityRepositoryImpl(db.commentDao(), db.receiptDao(), db.historyEventDao(), auth, receiptStorage = null)
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun postComment_persistsTrimmed_andRecordsHistory() = runTest {
        auth.signIn("Alex")
        val posted = repo.postComment(eid, gid, "  first!  ")
        assertTrue(posted is AppResult.Ok)
        assertEquals("first!", posted.value.body)

        assertEquals(listOf("first!"), repo.observeComments(eid).first().map { it.body })
        assertTrue(repo.observeHistory(eid).first().any { it.type == HistoryEventType.COMMENTED })
    }

    @Test
    fun postComment_blank_returnsValidationError() = runTest {
        auth.signIn("Alex")
        assertTrue(repo.postComment(eid, gid, "   ") is AppResult.Err)
    }

    @Test
    fun deleteComment_hidesFromThread() = runTest {
        auth.signIn("Alex")
        val posted = repo.postComment(eid, gid, "to delete")
        assertTrue(posted is AppResult.Ok)
        repo.deleteComment(posted.value.id)
        assertTrue(repo.observeComments(eid).first().isEmpty())
    }

    @Test
    fun addReceipt_withoutStorage_returnsError() = runTest {
        auth.signIn("Alex")
        val result = repo.addReceipt(eid, gid, "r.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        assertTrue(result is AppResult.Err)
    }
}
