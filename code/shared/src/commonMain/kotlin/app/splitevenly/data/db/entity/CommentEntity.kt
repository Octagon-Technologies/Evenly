package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `comments` (06 §3 — expense activity). One row per message posted on an expense.
 *
 * Like every synced entity this is a dumb wire-mirror (snake_case `@ColumnInfo`, `@Serializable` so
 * it doubles as the Postgrest DTO). [deletedAt] is a soft-delete tombstone so a removed comment
 * propagates to other devices through last-write-wins rather than silently reappearing on the next
 * pull. [groupId] is denormalized off the parent expense so comments can be pulled/secured per group.
 */
@Entity(
    tableName = "comments",
    indices = [
        Index(value = ["expense_id"]),
        Index(value = ["group_id"]),
        Index(value = ["user_id"]),
    ],
)
@Serializable
data class CommentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "body")
    val body: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
)
