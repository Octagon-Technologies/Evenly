package app.splitevenly.data.db.projection

import androidx.room.ColumnInfo

/**
 * Read-only projection for Recently deleted: the tombstoned group joined to whoever deleted it.
 *
 * A `LEFT JOIN` on the deleter, for the same reason [MemberWithUserRow] uses one — a group can arrive
 * from sync before the `users` row of a member this device has never seen, and a row that vanishes is
 * far worse here than one reading "Deleted by someone else": it would look like the group is gone for
 * good when it is still restorable.
 */
data class DeletedGroupRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "emoji") val emoji: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
    @ColumnInfo(name = "deleted_by") val deletedBy: String?,
    @ColumnInfo(name = "deleted_by_name") val deletedByName: String?,
    @ColumnInfo(name = "member_count") val memberCount: Int,
)
