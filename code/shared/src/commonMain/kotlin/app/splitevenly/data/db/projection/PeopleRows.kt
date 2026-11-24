package app.splitevenly.data.db.projection

import androidx.room.ColumnInfo

/**
 * One outstanding share line in a group the viewer is an ACTIVE member of, where the viewer is either
 * the debtor or the creditor. Remaining is derived (owed minus applied, voided payments excluded).
 */
data class PeopleLineRow(
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "group_name") val groupName: String,
    @ColumnInfo(name = "group_emoji") val groupEmoji: String,
    @ColumnInfo(name = "group_kind") val groupKind: String,
    @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "expense_date") val expenseDate: String,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "debtor_user_id") val debtorUserId: String,
    @ColumnInfo(name = "creditor_user_id") val creditorUserId: String,
    @ColumnInfo(name = "remaining_subunits") val remainingSubunits: Long,
)

/** An ACTIVE member of one of the viewer's live groups, joined to its user row for the name. */
data class PeopleRosterRow(
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "group_name") val groupName: String,
    @ColumnInfo(name = "group_emoji") val groupEmoji: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "is_placeholder") val isPlaceholder: Boolean,
)
