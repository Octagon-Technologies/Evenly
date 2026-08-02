package app.splitevenly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local mirror of `placeholder_claim_answers`: one row per "that name is not me".
 *
 * Only NEGATIVE answers live here. "That's me" is already represented by the merge itself
 * (`members.placeholder_claim_completed_at`), and "Later" is device-local.
 *
 * This is **synced**, not a local "card dismissed" flag, and the difference is the whole feature: a local
 * flag comes back on reinstall and re-asks names already ruled out, hides the question when a NEW name is
 * added later, and lets two people claim the same name. Keyed on (group, name, answerer), an answer travels
 * with the account and the list a member sees is just "unclaimed names here" minus "names I've answered" —
 * so nobody has to dismiss forever, they run out of question.
 *
 * Keyed on the placeholder's **id**, so renaming a name someone already ruled out is invisible to this
 * logic by construction. That is deliberate: the person didn't change, only the label, and re-asking would
 * make a rename a way to nag someone who already answered.
 */
@Entity(
    tableName = "placeholder_claim_answers",
    indices = [
        Index(value = ["group_id", "placeholder_user_id", "answered_by_user_id"], unique = true),
        Index(value = ["group_id"]),
    ],
)
@Serializable
data class PlaceholderClaimAnswerEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "group_id")
    val groupId: String,

    /** The name being ruled out. */
    @ColumnInfo(name = "placeholder_user_id")
    val placeholderUserId: String,

    /** The account saying "not me". */
    @ColumnInfo(name = "answered_by_user_id")
    val answeredByUserId: String,

    @ColumnInfo(name = "answered_at")
    val answeredAt: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "row_version")
    val rowVersion: Long = 1,
)
