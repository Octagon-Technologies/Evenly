package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * **Device-local outbox** for feedback tickets, mirroring [ReceiptUploadEntity]: deliberately absent
 * from `SyncEngine.SYNCED_TABLES` and *not* `@Serializable`. One row per ticket whose POST has not yet
 * been accepted by the `feedback` edge function.
 *
 * **Why a table and not a direct POST.** Someone reporting a bill that went wrong is, very often,
 * standing in the restaurant where it went wrong, on a network that barely works. ADMIN_FEEDBACK_SPEC.md
 * §4.4 asks for submission to be offline-tolerant by name. A row here survives process death and reboot;
 * a coroutine holding the text does not.
 *
 * **No `user_id` column, on purpose.** The server reads identity from the bearer token at send time, and
 * the sign-out wipe clears this table, so a queued ticket can never be flushed under a different
 * account's session. Storing the author here would create exactly that possibility and add a second
 * place for it to be wrong.
 *
 * On success the row is deleted. The table therefore only ever holds work that is pending or failing.
 */
@Entity(tableName = "feedback_outbox")
data class FeedbackOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "message")
    val message: String,
    /** Captured at enqueue, not at send: the point is the version the bug was seen on. */
    @ColumnInfo(name = "app_version")
    val appVersion: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    /** Bounded by `FeedbackOutbox.MAX_ATTEMPTS` so a permanently failing row cannot retry forever. */
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,
)
