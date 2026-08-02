package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `bill_participants` — who a "Split the bill" expense is *for*. The creator picks the
 * participants ("Select all" the group, then deselect); the bill then surfaces to each of them as a
 * "claim your items" card until they've claimed. This is deliberately separate from claims: it's the set
 * of people who *should* claim, set before anyone has.
 *
 * [doneAt] is the per-person "I'm done claiming" stamp — a personal nudge-silencer, not a resolution of
 * the bill (a bill is only resolved when every item unit is claimed, regardless of who tapped done).
 *
 * Synced (`@Serializable`, `group_id`/`expense_id` for pull scoping, `row_version` + `updated_at` for the
 * `keepNewer` guard). Soft-delete only (Rule 1): removing a participant tombstones the row.
 */
@Entity(
    tableName = "bill_participants",
    indices = [
        Index(value = ["expense_id"]),
        Index(value = ["group_id"]),
    ],
)
@Serializable
data class BillParticipantEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "done_at")
    val doneAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
)
