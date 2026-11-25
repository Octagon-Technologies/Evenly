package app.splitevenly.data.db.dao

import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.ReceiptUploadEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.domain.activity.ReceiptUploadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Room tests for the outbox writes behind the retry policy (see `ReceiptUploadRetryTest`). */
class ReceiptUploadDaoTest {
    private lateinit var db: EvenlyDatabase
    private lateinit var dao: ReceiptUploadDao

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        dao = db.receiptUploadDao()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun row(
        status: ReceiptUploadStatus,
        retryCount: Int = 0,
        lastError: String? = null,
    ) = ReceiptUploadEntity(
        id = "r1",
        expenseId = "e1",
        groupId = "g1",
        uploadedBy = "u1",
        fileName = "receipt.jpg",
        mimeType = "image/jpeg",
        storagePath = "g1/e1/r1.jpg",
        localPath = "/tmp/r1.jpg",
        sizeBytes = 100L,
        bytesUploaded = 60L,
        status = status.name,
        retryCount = retryCount,
        lastError = lastError,
        createdAt = 1L,
        updatedAt = 1L,
    )

    /** Manual retry is the escape hatch past the auto-retry budget: it must zero the counter too. */
    @Test
    fun resetForRetry_rearmsAnExhaustedFailure() =
        runTest {
            dao.upsert(row(ReceiptUploadStatus.FAILED, retryCount = 5, lastError = "Upload failed"))

            dao.resetForRetry("r1", now = 9_000L)

            val after = dao.getById("r1")!!
            assertEquals(ReceiptUploadStatus.PENDING.name, after.status)
            assertEquals(0, after.retryCount)
            assertEquals(0L, after.bytesUploaded)
            assertNull(after.lastError)
            assertEquals(9_000L, after.updatedAt)
        }

    @Test
    fun markFailed_recordsTheErrorAndCountsTheAttempt() =
        runTest {
            dao.upsert(row(ReceiptUploadStatus.UPLOADING, retryCount = 1))

            dao.markFailed("r1", ReceiptUploadStatus.FAILED.name, "HTTP 413", now = 9_000L)

            val after = dao.getById("r1")!!
            assertEquals(ReceiptUploadStatus.FAILED.name, after.status)
            assertEquals(2, after.retryCount)
            assertEquals("HTTP 413", after.lastError)
        }

    /** A payment proof rides the same queue; only rows carrying a settlement id are proofs. */
    @Test
    fun observeProofsByGroup_onlyProofRows() =
        runTest {
            dao.upsert(row(ReceiptUploadStatus.PENDING))
            dao.upsert(row(ReceiptUploadStatus.PENDING).copy(id = "p1", expenseId = "", settlementId = "s1", storagePath = "g1/payments/s1/p1.jpg"))

            val proofs = dao.observeProofsByGroup("g1").first()

            assertEquals(listOf("p1"), proofs.map { it.id })
            assertEquals("s1", proofs.single().settlementId)
        }
}
