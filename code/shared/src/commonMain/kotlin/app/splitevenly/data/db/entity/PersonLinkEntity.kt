package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `person_links`: one account's private statement that [userId] is the same person as
 * [personId] (`kind = SAME`), or that a same-name suggestion is not ([KIND_NOT_SAME]).
 *
 * Owner-scoped and never group-scoped: it only changes the owner's People tab, so the Boston group still
 * reads "Pookie". Synced so links survive a reinstall and reach the owner's other devices. Unlinking is a
 * soft delete; the tombstone has to reach the server or the link comes back on the next pull.
 */
@Entity(
    tableName = "person_links",
    indices = [Index(value = ["owner_user_id"])],
)
@Serializable
data class PersonLinkEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "owner_user_id")
    val ownerUserId: String,

    /** The name being attached. Always a placeholder for a SAME link. */
    @ColumnInfo(name = "user_id")
    val userId: String,

    /** The name it is attached to: a placeholder or a real account. */
    @ColumnInfo(name = "person_id")
    val personId: String,

    @ColumnInfo(name = "kind")
    val kind: String = KIND_SAME,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
) {
    companion object {
        const val KIND_SAME = "SAME"
        const val KIND_NOT_SAME = "NOT_SAME"
    }
}
