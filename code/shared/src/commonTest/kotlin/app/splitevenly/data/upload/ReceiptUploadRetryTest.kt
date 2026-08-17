package app.splitevenly.data.upload

import app.splitevenly.data.db.entity.ReceiptUploadEntity
import app.splitevenly.domain.activity.ReceiptUploadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the outbox retry policy: FAILED is terminal once the auto-retry budget is spent, so one
 * permanently-bad file cannot burn battery and quota forever — only PENDING rows and FAILED rows with
 * budget left are ever re-claimed. Manual retry re-arms by resetting the counter (see
 * `ReceiptUploadDao.resetForRetry`), not by bypassing this predicate.
 */
class ReceiptUploadRetryTest {
    private fun row(
        status: ReceiptUploadStatus,
        retryCount: Int = 0,
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
        status = status.name,
        retryCount = retryCount,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun pendingRows_areAlwaysRetryable() {
        assertTrue(row(ReceiptUploadStatus.PENDING).isAutoRetryable())
        assertTrue(row(ReceiptUploadStatus.PENDING, retryCount = MAX_AUTO_RETRIES + 3).isAutoRetryable())
    }

    @Test
    fun uploadingRows_areNeverClaimed() {
        assertFalse(row(ReceiptUploadStatus.UPLOADING).isAutoRetryable())
    }

    @Test
    fun failedRows_retryUntilTheBudgetIsSpent_thenStop() {
        assertTrue(row(ReceiptUploadStatus.FAILED, retryCount = 0).isAutoRetryable())
        assertTrue(row(ReceiptUploadStatus.FAILED, retryCount = MAX_AUTO_RETRIES - 1).isAutoRetryable())
        assertFalse(row(ReceiptUploadStatus.FAILED, retryCount = MAX_AUTO_RETRIES).isAutoRetryable())
        assertFalse(row(ReceiptUploadStatus.FAILED, retryCount = MAX_AUTO_RETRIES + 1).isAutoRetryable())
    }

    /**
     * The RECEIPT_ADDED history id must be a pure function of the receipt id: `reportSuccess` re-runs
     * after a crash-mid-publish (the outbox row survived), and a random id there mints a duplicate
     * activity-feed row that syncs to every device forever.
     */
    @Test
    fun receiptAddedHistoryId_isDeterministicPerReceipt() {
        assertEquals(receiptAddedHistoryId("r1"), receiptAddedHistoryId("r1"))
        assertEquals("r1__receipt_added", receiptAddedHistoryId("r1"))
    }
}
