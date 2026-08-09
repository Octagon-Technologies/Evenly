package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `expense_blocked_users` (CHAT_MODERATION_SPEC.md). One row per (expense, blocker,
 * blocked) pair: [blockerUserId] no longer sees [blockedUserId]'s comments on [expenseId]. Scoped to
 * one expense's thread, not the group or any other expense.
 *
 * Dumb wire-mirror like [CommentEntity]. [deletedAt] is the tombstone that doubles as "unblock" — a
 * removed block propagates via last-write-wins like every other soft-deleted synced row. [reason]
 * distinguishes a deliberate Block from a Report (which performs the same write today) for a future
 * escalation surface; nothing reads it yet.
 */
@Entity(
    tableName = "expense_blocked_users",
    indices = [
        Index(value = ["expense_id"]),
        Index(value = ["group_id"]),
        Index(value = ["blocker_user_id"]),
    ],
)
@Serializable
data class ExpenseBlockedUserEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "expense_id")
    val expenseId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "blocker_user_id")
    val blockerUserId: String,

    @ColumnInfo(name = "blocked_user_id")
    val blockedUserId: String,

    @ColumnInfo(name = "reason")
    val reason: String = "block",

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
)
