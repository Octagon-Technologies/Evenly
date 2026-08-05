package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.splitevenly.data.db.entity.BillParticipantEntity
import app.splitevenly.data.db.entity.ItemClaimEntity
import app.splitevenly.data.db.entity.ItemShareEntity
import app.splitevenly.data.db.entity.ShareEntity

/**
 * The whole placeholder → real-user merge, as ONE Room transaction.
 *
 * Every table the retired name appears in has to move together: `shares`, the parent `expenses` (payer +
 * causal `split_version`), `settlements`, `settlement_allocations`, the itemized `item_claims` /
 * `item_shares` / `bill_participants`, and finally the `members` row. A merge that half-applies is worse
 * than one that doesn't run — it is silently wrong money across several people's balances — so the
 * queries for all of those tables are declared here rather than on their owning DAOs. (Same reason
 * [ItemShareDao.setServings] declares its one `item_claims` query: a Room `@Transaction` default method
 * can only call queries on its own DAO.)
 *
 * The hard part is **collisions**. Someone added me *and* "Chelimo" to the same dinner, so the expense
 * carries two active shares that both become mine. A blind `UPDATE shares SET user_id = …` then produces
 * two active rows with the same `(expense_id, user_id)`: Room's index is non-unique so it succeeds
 * locally and silently, while the server's partial unique index rejects the push and takes the whole
 * expense's sync down with it. So every table with a per-(parent, user) uniqueness rule folds instead of
 * reassigning: the real user's row absorbs the placeholder's, the placeholder's row is tombstoned
 * (Rule 1, never hard-deleted), and anything pointing at the tombstoned id is re-pointed at the survivor.
 */
@Dao
interface PlaceholderMergeDao {

    // ── Reads: the two users' active rows, so the caller can spot the collisions ─────────────────

    @Query(
        """
        SELECT * FROM shares
        WHERE deleted_at IS NULL AND user_id IN (:fromUserId, :toUserId)
          AND expense_id IN (SELECT id FROM expenses WHERE group_id = :groupId)
        """
    )
    suspend fun sharesOfEither(groupId: String, fromUserId: String, toUserId: String): List<ShareEntity>

    @Query("SELECT * FROM item_claims WHERE deleted_at IS NULL AND group_id = :groupId AND user_id IN (:fromUserId, :toUserId)")
    suspend fun itemClaimsOfEither(groupId: String, fromUserId: String, toUserId: String): List<ItemClaimEntity>

    @Query("SELECT * FROM item_shares WHERE deleted_at IS NULL AND group_id = :groupId AND user_id IN (:fromUserId, :toUserId)")
    suspend fun itemSharesOfEither(groupId: String, fromUserId: String, toUserId: String): List<ItemShareEntity>

    @Query("SELECT * FROM bill_participants WHERE deleted_at IS NULL AND group_id = :groupId AND user_id IN (:fromUserId, :toUserId)")
    suspend fun billParticipantsOfEither(groupId: String, fromUserId: String, toUserId: String): List<BillParticipantEntity>

    // ── Writes ───────────────────────────────────────────────────────────────────────────────────

    @Upsert
    suspend fun upsertShares(shares: List<ShareEntity>)

    @Query("UPDATE shares SET user_id = :toUserId, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun reassignShares(ids: List<String>, toUserId: String, ts: Long)

    @Query("UPDATE shares SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun tombstoneShares(ids: List<String>, ts: Long)

    /**
     * Move a folded-away share's payments onto the surviving row. Allocations are the ground truth a
     * share's `remaining` derives from; left pointing at a tombstoned share id they stop offsetting the
     * debt they were made against, so the merged person looks like they never paid.
     */
    @Query("UPDATE settlement_allocations SET share_id = :toShareId, row_version = row_version + 1 WHERE share_id = :fromShareId")
    suspend fun repointAllocations(fromShareId: String, toShareId: String)

    /**
     * Void a payment between the placeholder and the person claiming it. "I paid Chelimo back" becomes a
     * payment from me to me the moment Chelimo *is* me: its allocations would keep paying down shares on
     * expenses I am now the payer of, which reads as an overpayment. There is no debt left to offset.
     */
    @Query(
        """
        UPDATE settlements SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1
        WHERE group_id = :groupId AND deleted_at IS NULL
          AND ((from_user_id = :fromUserId AND to_user_id = :toUserId)
            OR (from_user_id = :toUserId AND to_user_id = :fromUserId))
        """
    )
    suspend fun voidSettlementsBetween(groupId: String, fromUserId: String, toUserId: String, ts: Long)

    @Query("UPDATE settlements SET from_user_id = :toUserId, updated_at = :ts, row_version = row_version + 1 WHERE group_id = :groupId AND from_user_id = :fromUserId")
    suspend fun reassignSettlementPayer(groupId: String, fromUserId: String, toUserId: String, ts: Long)

    @Query("UPDATE settlements SET to_user_id = :toUserId, updated_at = :ts, row_version = row_version + 1 WHERE group_id = :groupId AND to_user_id = :fromUserId")
    suspend fun reassignSettlementPayee(groupId: String, fromUserId: String, toUserId: String, ts: Long)

    @Upsert
    suspend fun upsertItemClaims(claims: List<ItemClaimEntity>)

    @Query("UPDATE item_claims SET user_id = :toUserId, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun reassignItemClaims(ids: List<String>, toUserId: String, ts: Long)

    @Query("UPDATE item_claims SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun tombstoneItemClaims(ids: List<String>, ts: Long)

    @Query("UPDATE item_shares SET user_id = :toUserId, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun reassignItemShares(ids: List<String>, toUserId: String, ts: Long)

    @Query("UPDATE item_shares SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun tombstoneItemShares(ids: List<String>, ts: Long)

    @Query("UPDATE bill_participants SET user_id = :toUserId, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun reassignBillParticipants(ids: List<String>, toUserId: String, ts: Long)

    @Query("UPDATE bill_participants SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun tombstoneBillParticipants(ids: List<String>, ts: Long)

    /** See [ExpenseDao.reassignPayerInGroup] — declared here so it joins the merge transaction. */
    @Query("UPDATE expenses SET payer_user_id = :toUserId, updated_at = :ts, row_version = row_version + 1, split_version = split_version + 1, split_updated_by = :actor WHERE group_id = :groupId AND payer_user_id = :fromUserId")
    suspend fun reassignPayer(groupId: String, fromUserId: String, toUserId: String, actor: String, ts: Long)

    /** See [ExpenseDao.touchExpensesWithShareOfUser] — declared here so it joins the merge transaction. */
    @Query(
        """
        UPDATE expenses SET updated_at = :ts, row_version = row_version + 1,
            split_version = split_version + 1, split_updated_by = :actor
        WHERE deleted_at IS NULL AND id IN (
            SELECT DISTINCT s.expense_id FROM shares s
            WHERE s.user_id = :userId AND s.deleted_at IS NULL
              AND s.expense_id IN (SELECT id FROM expenses WHERE group_id = :groupId)
        )
        """
    )
    suspend fun touchExpensesWithShareOfUser(groupId: String, userId: String, actor: String, ts: Long)

    /** See [MemberDao.markClaimedByUser] — declared here so it joins the merge transaction. */
    @Query(
        """
        UPDATE members
        SET status = 'LEFT', left_at = :ts, is_admin = 0, placeholder_claim_completed_at = :ts,
            placeholder_claimed_by = :claimedBy, updated_at = :ts, row_version = row_version + 1
        WHERE group_id = :groupId AND user_id = :userId
        """
    )
    suspend fun markClaimed(groupId: String, userId: String, claimedBy: String, ts: Long)

    // ── The merge ────────────────────────────────────────────────────────────────────────────────

    /**
     * Move everything the placeholder [fromUserId] owns in [groupId] onto [toUserId] and retire the
     * placeholder's membership. Atomic: either the whole identity moves or nothing does.
     */
    @Transaction
    suspend fun mergePlaceholder(groupId: String, fromUserId: String, toUserId: String, ts: Long) {
        mergeShares(groupId, fromUserId, toUserId, ts)
        mergeItemClaims(groupId, fromUserId, toUserId, ts)
        mergeItemShares(groupId, fromUserId, toUserId, ts)
        mergeBillParticipants(groupId, fromUserId, toUserId, ts)

        // Settlements: void the now-self-directed ones first, then move the rest (tombstones included, so
        // the retired name stops appearing in history that has already synced).
        voidSettlementsBetween(groupId, fromUserId, toUserId, ts)
        reassignSettlementPayer(groupId, fromUserId, toUserId, ts)
        reassignSettlementPayee(groupId, fromUserId, toUserId, ts)

        // A reassigned share IS a split change, so the parent expenses need their causal `split_version`
        // advanced, not just `row_version`: bumping only row_version marks the row dirty but leaves
        // split_version == base, and `merge_expense` then treats the push as metadata-only and reverts
        // the whole merge back to the placeholder on the next sync.
        touchExpensesWithShareOfUser(groupId, toUserId, actor = toUserId, ts)
        reassignPayer(groupId, fromUserId, toUserId, actor = toUserId, ts)

        // Stamping `placeholder_claim_completed_at` (not a plain soft-leave) is what removes the retired
        // name from the roster AND from every identity picker.
        markClaimed(groupId, fromUserId, claimedBy = toUserId, ts)
    }

    /**
     * Shares fold on `(expense_id, user_id)`. Where both users hold an active share of one expense the
     * owed amounts (and the raw split inputs behind them) sum onto the real user's row so the expense
     * still satisfies `SUM(share_owed) == amount` (AC-INV-001), and the placeholder's row is tombstoned
     * with its payments re-pointed. Everything else just changes hands.
     *
     * An itemized bill's shares are a derived materialization of its claims, so folding them here and
     * folding the claims below agree by construction: the sum of the two derived shares is what a
     * re-derivation from the merged claims produces.
     */
    private suspend fun mergeShares(groupId: String, fromUserId: String, toUserId: String, ts: Long) {
        val rows = sharesOfEither(groupId, fromUserId, toUserId)
        val mine = rows.filter { it.userId == toUserId }.associateBy { it.expenseId }
        val theirs = rows.filter { it.userId == fromUserId }
        val plainMoves = mutableListOf<String>()
        val folded = mutableListOf<ShareEntity>()
        val tombstones = mutableListOf<String>()
        for (their in theirs) {
            val survivor = mine[their.expenseId]
            if (survivor == null) {
                plainMoves += their.id
                continue
            }
            folded += survivor.copy(
                shareOwedSubunits = survivor.shareOwedSubunits + their.shareOwedSubunits,
                shareUnits = sumOrNull(survivor.shareUnits, their.shareUnits),
                sharePercentage = sumOrNull(survivor.sharePercentage, their.sharePercentage),
                shareExactSubunits = sumOrNull(survivor.shareExactSubunits, their.shareExactSubunits),
                updatedAt = ts,
                rowVersion = survivor.rowVersion + 1,
            )
            repointAllocations(fromShareId = their.id, toShareId = survivor.id)
            tombstones += their.id
        }
        if (plainMoves.isNotEmpty()) reassignShares(plainMoves, toUserId, ts)
        if (folded.isNotEmpty()) upsertShares(folded)
        if (tombstones.isNotEmpty()) tombstoneShares(tombstones, ts)
    }

    /**
     * Claims fold on `(item_id, user_id)`, and their `quantity` is a per-person unit count ("I had 2 of
     * these"), so a collision sums: the merged person had both.
     */
    private suspend fun mergeItemClaims(groupId: String, fromUserId: String, toUserId: String, ts: Long) {
        val rows = itemClaimsOfEither(groupId, fromUserId, toUserId)
        val mine = rows.filter { it.userId == toUserId }.associateBy { it.itemId }
        val plainMoves = mutableListOf<String>()
        val folded = mutableListOf<ItemClaimEntity>()
        val tombstones = mutableListOf<String>()
        for (their in rows.filter { it.userId == fromUserId }) {
            val survivor = mine[their.itemId]
            if (survivor == null) {
                plainMoves += their.id
                continue
            }
            folded += survivor.copy(
                quantity = survivor.quantity + their.quantity,
                updatedAt = ts,
                rowVersion = survivor.rowVersion + 1,
            )
            tombstones += their.id
        }
        if (plainMoves.isNotEmpty()) reassignItemClaims(plainMoves, toUserId, ts)
        if (folded.isNotEmpty()) upsertItemClaims(folded)
        if (tombstones.isNotEmpty()) tombstoneItemClaims(tombstones, ts)
    }

    /**
     * Item shares fold on `(item_id, user_id, portion_id)`. Unlike a claim, `quantity` here is the
     * PORTION's unit count, carried identically on every member row of that portion — summing it would
     * silently enlarge the slice. So a collision keeps the real user's row untouched and just drops the
     * duplicate membership.
     */
    private suspend fun mergeItemShares(groupId: String, fromUserId: String, toUserId: String, ts: Long) {
        val rows = itemSharesOfEither(groupId, fromUserId, toUserId)
        val mine = rows.filter { it.userId == toUserId }.mapTo(HashSet()) { it.itemId to it.portionId }
        val (collisions, plainMoves) = rows.filter { it.userId == fromUserId }
            .partition { (it.itemId to it.portionId) in mine }
        if (plainMoves.isNotEmpty()) reassignItemShares(plainMoves.map { it.id }, toUserId, ts)
        if (collisions.isNotEmpty()) tombstoneItemShares(collisions.map { it.id }, ts)
    }

    /**
     * A bill participant is a membership, not a quantity, so a collision just drops the duplicate. Left
     * unmerged, the retired name stays on the bill's "still to claim" list and the bill never resolves.
     */
    private suspend fun mergeBillParticipants(groupId: String, fromUserId: String, toUserId: String, ts: Long) {
        val rows = billParticipantsOfEither(groupId, fromUserId, toUserId)
        val mine = rows.filter { it.userId == toUserId }.mapTo(HashSet()) { it.expenseId }
        val (collisions, plainMoves) = rows.filter { it.userId == fromUserId }
            .partition { it.expenseId in mine }
        if (plainMoves.isNotEmpty()) reassignBillParticipants(plainMoves.map { it.id }, toUserId, ts)
        if (collisions.isNotEmpty()) tombstoneBillParticipants(collisions.map { it.id }, ts)
    }

    private fun sumOrNull(a: Int?, b: Int?): Int? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

    private fun sumOrNull(a: Long?, b: Long?): Long? = if (a == null && b == null) null else (a ?: 0L) + (b ?: 0L)

    private fun sumOrNull(a: Double?, b: Double?): Double? = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
}
