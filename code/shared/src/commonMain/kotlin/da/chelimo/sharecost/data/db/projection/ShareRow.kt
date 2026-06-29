package da.chelimo.sharecost.data.db.projection

import androidx.room.ColumnInfo

/**
 * A participant share with its `remaining_subunits` **derived in SQL** (owed − Σ applied of
 * non-voided settlements; the payer's own share is always 0). The stored `shares` table no longer
 * carries an authoritative remaining — it is computed on read, so a split edit can never wipe
 * recorded payments and "settled" is always derived from ground truth (owed split + allocations).
 */
data class ShareRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "share_owed_subunits") val shareOwedSubunits: Long,
    @ColumnInfo(name = "remaining_subunits") val remainingSubunits: Long,
    @ColumnInfo(name = "share_units") val shareUnits: Int? = null,
    @ColumnInfo(name = "share_percentage") val sharePercentage: Double? = null,
    @ColumnInfo(name = "share_exact_subunits") val shareExactSubunits: Long? = null,
)
