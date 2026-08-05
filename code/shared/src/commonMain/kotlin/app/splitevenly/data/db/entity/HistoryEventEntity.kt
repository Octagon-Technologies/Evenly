package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `expense_history` (06 §3 — activity log). One append-only row per event in an
 * expense's life: created, edited, settled, commented, receipt added, deleted.
 *
 * History is **append-only** — rows are never edited or deleted, so there is no `updated_at` /
 * `deleted_at` here (still a `row_version` for the uniform upsert path). [type] is a stable enum
 * string ([app.splitevenly.domain.activity.HistoryEventType]); [detail] holds the optional human
 * fragment (e.g. an amount label). The actor's *name* is resolved at render time from the member
 * roster (so "You" stays viewer-relative across devices) rather than baked into the row.
 */
@Entity(
    tableName = "expense_history",
    indices = [
        Index(value = ["expense_id"]),
        Index(value = ["group_id"]),
    ],
)
@Serializable
data class HistoryEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    /** The user who performed the action; null for system-generated events. */
    @ColumnInfo(name = "actor_user_id")
    val actorUserId: String? = null,

    /** Stable enum string — see [app.splitevenly.domain.activity.HistoryEventType]. */
    @ColumnInfo(name = "type")
    val type: String,

    /** Optional human-readable fragment appended after the verb (e.g. "$96.00", "the amount"). */
    @ColumnInfo(name = "detail")
    val detail: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
)
