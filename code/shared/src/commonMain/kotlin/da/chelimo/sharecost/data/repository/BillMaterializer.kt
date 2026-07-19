package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.data.db.dao.ExpenseDao
import da.chelimo.sharecost.data.db.dao.ExpenseItemDao
import da.chelimo.sharecost.data.db.dao.ItemClaimDao
import da.chelimo.sharecost.data.db.dao.ItemShareDao
import da.chelimo.sharecost.data.db.dao.ShareDao
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.ItemShareEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.domain.expense.BillExtras
import da.chelimo.sharecost.domain.expense.BillExtrasInput
import da.chelimo.sharecost.domain.expense.BillItem
import da.chelimo.sharecost.domain.expense.IndividualClaim
import da.chelimo.sharecost.domain.expense.SPLIT_MODE_ITEMIZED
import da.chelimo.sharecost.domain.expense.SharedMember
import da.chelimo.sharecost.domain.expense.SharedPortion
import da.chelimo.sharecost.domain.expense.TipSplitMode
import da.chelimo.sharecost.domain.expense.splitBill

/**
 * Derives an itemized bill's `shares` from its synced items + claims + item-shares + extras and writes
 * them with **deterministic ids** (`"<expenseId>__<userId>"`). This is the single home of that derivation,
 * used by both [BillRepositoryImpl] (on every claim/edit write) and the [da.chelimo.sharecost.data.remote
 * .supabase.SyncEngine] (on pull, and on the push-adoption path for a bill).
 *
 * Bill shares are a **local derived materialization**, never independently pushed and never trusted from
 * the server: the server's `shares` for a bill freeze at the last split-changing edit (often the empty
 * create set), so any device that adopted them blindly would show wrong/missing debts. Every device
 * instead re-derives from the synced source rows — the items/claims/item-shares that DO sync — so all
 * devices converge (P0 #3). The write is a no-op when nothing changed, so it's cheap to run on every pull.
 */
class BillMaterializer(
    private val expenseDao: ExpenseDao,
    private val expenseItemDao: ExpenseItemDao,
    private val itemClaimDao: ItemClaimDao,
    private val itemShareDao: ItemShareDao,
    private val shareDao: ShareDao,
) {

    /**
     * Re-derive one bill's shares from its current items + claims + extras and write them with
     * deterministic ids. A participant who drops out of every claim is tombstoned; an unchanged share is
     * left untouched (so re-running on every pull doesn't churn `updated_at`/`row_version`). Never touches
     * the expense row, so a claim change doesn't mark the expense dirty (claims sync on their own).
     */
    suspend fun materialize(expense: ExpenseEntity, now: Long) {
        val items = expenseItemDao.getByExpense(expense.id)
        val claims = itemClaimDao.getByExpense(expense.id)
        val sharedRows = itemShareDao.getByExpense(expense.id)
        val owed = splitBill(
            items.map { BillItem(it.id, it.lineTotalSubunits, it.quantity) },
            claims.map { IndividualClaim(it.itemId, UserId(it.userId), it.quantity) },
            sharedRows.toLegacyMembers(),
            expense.toExtras().toEngine(),
            sharedPortions = sharedRows.toEnginePortions(),
        ).owedByUser
        val owedUsers = owed.keys.mapTo(HashSet()) { it.value }
        val existing = shareDao.getByExpense(expense.id).associateBy { it.userId }

        val shares = owed.mapNotNull { (user, amount) ->
            val current = existing[user.value]
            when {
                // New participant, or a previously-tombstoned share resurrecting (the deterministic id
                // upserts back onto the tombstone, clearing deleted_at).
                current == null -> ShareEntity(
                    id = "${expense.id}__${user.value}",
                    expenseId = expense.id,
                    userId = user.value,
                    shareOwedSubunits = amount,
                    createdAt = now,
                    updatedAt = now,
                )
                current.shareOwedSubunits != amount ->
                    current.copy(shareOwedSubunits = amount, updatedAt = now, rowVersion = current.rowVersion + 1)
                else -> null // unchanged — skip the write so pull-time re-derivation stays cheap
            }
        }
        val removed = existing.values.filter { it.userId !in owedUsers }.map { it.id }
        if (removed.isNotEmpty()) shareDao.softDeleteByIds(removed, now)
        if (shares.isNotEmpty()) shareDao.upsertAll(shares)
    }

    /**
     * Re-derive every itemized bill's shares across the given groups — the pull-side convergence so a
     * fresh device / non-writing participant materializes correct shares from the synced claims (P0 #3).
     */
    suspend fun rematerializeGroups(groupIds: List<String>, now: Long) {
        for (gid in groupIds) {
            for (e in expenseDao.getActiveByGroup(gid)) {
                if (e.splitMode == SPLIT_MODE_ITEMIZED) materialize(e, now)
            }
        }
    }
}

// Shared mapping helpers (internal so BillRepositoryImpl reuses the same logic instead of duplicating it).
internal fun ExpenseEntity.toExtras() = BillExtrasInput(
    taxSubunits = taxSubunits,
    gratuitySubunits = gratuitySubunits,
    tipSubunits = tipSubunits,
    tipSplitMode = TipSplitMode.entries.firstOrNull { it.name == tipSplitMode } ?: TipSplitMode.EVEN,
    discountSubunits = discountSubunits,
)

internal fun BillExtrasInput.toEngine() = BillExtras(
    taxSubunits = taxSubunits,
    gratuitySubunits = gratuitySubunits,
    tipSubunits = tipSubunits,
    tipSplitMode = tipSplitMode,
    discountSubunits = discountSubunits,
)

// A line's sharing splits two ways: legacy rows (no portion) feed the old single all-leftover set;
// portioned rows group by portion_id into explicit slices (quantity is denormalised → take the first row's).
internal fun List<ItemShareEntity>.toLegacyMembers() =
    filter { it.portionId == null }.map { SharedMember(it.itemId, UserId(it.userId)) }

internal fun List<ItemShareEntity>.toEnginePortions(): List<SharedPortion> =
    filter { it.portionId != null }
        .groupBy { it.itemId to it.portionId!! }
        .map { (key, rows) -> SharedPortion(key.first, key.second, rows.first().quantity, rows.map { UserId(it.userId) }) }
