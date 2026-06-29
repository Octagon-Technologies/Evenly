package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.newId

/** One desired participant share for an expense write — userId + owed + the raw split inputs. */
internal data class DesiredShare(
    val userId: String,
    val owedSubunits: Long,
    val shareUnits: Int? = null,
    val sharePercentage: Double? = null,
    val shareExactSubunits: Long? = null,
)

/**
 * Identity-preserving merge of an expense's share set. A participant who stays keeps the **same**
 * share `id`, so settlement allocations pointing at it survive the edit — payments are never wiped;
 * a removed participant's id is returned to be soft-deleted; a new participant gets a fresh id. This
 * is precisely what lets a split edit re-derive `remaining` from existing allocations instead of
 * resetting it to owed (the split-vs-settlement bug this whole change closes).
 *
 * @param existingActive the expense's current non-deleted shares.
 * @return (shares to upsert, ids of shares to soft-delete).
 */
internal fun mergeShares(
    existingActive: List<ShareEntity>,
    desired: List<DesiredShare>,
    expenseId: String,
    now: Long,
): Pair<List<ShareEntity>, List<String>> {
    val byUser = existingActive.associateBy { it.userId }
    val desiredUsers = desired.mapTo(HashSet()) { it.userId }
    val upserts = desired.map { d ->
        val existing = byUser[d.userId]
        existing?.copy(
            shareOwedSubunits = d.owedSubunits,
            shareUnits = d.shareUnits,
            sharePercentage = d.sharePercentage,
            shareExactSubunits = d.shareExactSubunits,
            updatedAt = now,
            rowVersion = existing.rowVersion + 1,
        ) ?: ShareEntity(
            id = newId(),
            expenseId = expenseId,
            userId = d.userId,
            shareOwedSubunits = d.owedSubunits,
            shareUnits = d.shareUnits,
            sharePercentage = d.sharePercentage,
            shareExactSubunits = d.shareExactSubunits,
            createdAt = now,
            updatedAt = now,
        )
    }
    val removed = existingActive.filter { it.userId !in desiredUsers }.map { it.id }
    return upserts to removed
}
