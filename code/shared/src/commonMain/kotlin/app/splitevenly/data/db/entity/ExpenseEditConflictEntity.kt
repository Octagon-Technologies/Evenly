package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `expense_edit_conflicts`: a PARKED expense edit that lost an optimistic-concurrency
 * race. When two devices edit the same expense from the same `base_version`, the server's
 * `commit_expense` RPC accepts the first (canonical) and writes the loser here instead of silently
 * clobbering. The rejected payload is stored as JSON **text** ([rejectedExpense]/[rejectedShares]) so
 * this entity round-trips as the wire DTO without a jsonb type converter; the repository parses it
 * back when the user picks a side.
 *
 * [resolution] is null while pending, then `KEEP_CURRENT` or `USE_REJECTED` once resolved.
 */
@Entity(
    tableName = "expense_edit_conflicts",
    indices = [
        Index(value = ["group_id"]),
        Index(value = ["expense_id"]),
    ],
)
@Serializable
data class ExpenseEditConflictEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "base_version")
    val baseVersion: Long,

    @ColumnInfo(name = "server_version")
    val serverVersion: Long,

    @ColumnInfo(name = "rejected_by")
    val rejectedBy: String,

    // Actor who wrote the canonical (winning) version this edit lost to. Lets the UI name who you
    // actually collided with instead of the misleading "you edited this while you did too". Nullable:
    // conflicts parked before this column existed carry no winner.
    @ColumnInfo(name = "server_actor")
    val serverActor: String? = null,

    @ColumnInfo(name = "rejected_expense")
    val rejectedExpense: String,

    @ColumnInfo(name = "rejected_shares")
    val rejectedShares: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "resolved_at")
    val resolvedAt: Long? = null,

    @ColumnInfo(name = "resolution")
    val resolution: String? = null,

    @ColumnInfo(name = "resolved_by")
    val resolvedBy: String? = null,
)
