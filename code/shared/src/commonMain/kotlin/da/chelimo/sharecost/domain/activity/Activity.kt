package da.chelimo.sharecost.domain.activity

import da.chelimo.sharecost.core.id.CommentId
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.ReceiptId
import da.chelimo.sharecost.core.id.UserId

/** A message posted on an expense (06 §3 — expense activity). */
data class Comment(
    val id: CommentId,
    val expenseId: ExpenseId,
    val authorUserId: UserId,
    val body: String,
    val createdAt: Long,
)

/**
 * An image/PDF attached to an expense. The bytes live in Supabase Storage at [storagePath]; [url] is
 * the resolved address the UI renders (re-derivable from [storagePath] if it expires).
 */
data class Receipt(
    val id: ReceiptId,
    val expenseId: ExpenseId,
    val uploadedBy: UserId,
    val storagePath: String,
    val url: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
) {
    /** PDFs render as a generic file tile rather than an image thumbnail. */
    val isPdf: Boolean get() = mimeType.contains("pdf", ignoreCase = true)
}

/** The kinds of events recorded in an expense's activity log. Names are the stable wire enum. */
enum class HistoryEventType { CREATED, EDITED, SETTLED, SETTLEMENT_EDITED, COMMENTED, RECEIPT_ADDED, DELETED }

/**
 * One entry in an expense's activity log (06 §3). The actor's display name is resolved at render time
 * from the member roster so "You" stays viewer-relative; [detail] is an optional human fragment.
 */
data class HistoryEvent(
    val id: String,
    val expenseId: ExpenseId,
    val actorUserId: UserId?,
    val type: HistoryEventType,
    val detail: String?,
    val createdAt: Long,
)
