package app.splitevenly.data.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.data.auth.StubAuthSession
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.PaymentProofEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.domain.activity.HistoryEventType
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

    private lateinit var db: EvenlyDatabase
    private lateinit var auth: StubAuthSession
    private lateinit var repo: ActivityRepositoryImpl

    private val eid = ExpenseId("e1")
    private val gid = GroupId("g1")

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        auth = StubAuthSession(db.userDao())
        // No ReceiptStorage in tests → the receipt path degrades to an error rather than uploading.
        repo =
            ActivityRepositoryImpl(
                db.commentDao(),
                db.receiptDao(),
                db.historyEventDao(),
                auth,
                receiptStorage = null,
                proofDao = db.paymentProofDao(),
            )
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

    private fun proof(
        id: String,
        chain: String,
        createdAt: Long,
        deletedAt: Long? = null,
    ) = PaymentProofEntity(
        id = id,
        settlementId = chain,
        groupId = gid.value,
        uploadedBy = "u1",
        storagePath = "g1/payments/$chain/$id.jpg",
        url = "https://x/$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 10,
        createdAt = createdAt,
        updatedAt = createdAt,
        deletedAt = deletedAt,
    )

    /** One screenshot per payment chain: the newest live one is the one shown, tombstones never are. */
    @Test
    fun paymentProofs_newestLivePerChain() =
        runTest {
            db.paymentProofDao().upsertAll(
                listOf(
                    proof("p-old", "s1", createdAt = 1),
                    proof("p-new", "s1", createdAt = 2),
                    proof("p-gone", "s2", createdAt = 3, deletedAt = 4),
                ),
            )
            val proofs = repo.observePaymentProofs(gid).first()
            assertEquals(setOf("s1"), proofs.keys)
            assertEquals("p-new", proofs.getValue("s1").id)
        }

    /** Removing is a soft delete, so the tombstone syncs (data/AGENTS.md Rule 1). */
    @Test
    fun removePaymentProof_softDeletes() =
        runTest {
            db.paymentProofDao().upsert(proof("p1", "s1", createdAt = 1))
            repo.removePaymentProof("p1")
            assertTrue(repo.observePaymentProofs(gid).first().isEmpty())
            assertTrue(db.paymentProofDao().getById("p1")?.deletedAt != null)
        }
}
