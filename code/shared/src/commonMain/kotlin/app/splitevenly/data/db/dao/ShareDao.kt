package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.ShareEntity
import app.splitevenly.data.db.projection.OutstandingItemRow
import app.splitevenly.data.db.projection.OutstandingShareForPair
import app.splitevenly.data.db.projection.OutstandingShareRow
import app.splitevenly.data.db.projection.OverpaymentRow
import app.splitevenly.data.db.projection.ReconcileExpenseRow
import app.splitevenly.data.db.projection.ShareRow
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `shares` (02 §3.8).
 *
 * `remaining` is **never stored** — every read derives it as
 * `owed − Σ(applied allocations of non-voided settlements)`, and the payer's own share is always 0
 * (a person can't owe themselves). Deriving it on read means a split edit can never wipe recorded
 * payments and "settled" always reflects ground truth (the owed split + the settlement allocations).
 */
@Dao
interface ShareDao {
    @Upsert
    suspend fun upsert(share: ShareEntity)

    @Upsert
    suspend fun upsertAll(shares: List<ShareEntity>)

    /** The expense's **active** shares (excludes tombstones) — the input to an edit/reconcile merge. */
    @Query("SELECT * FROM shares WHERE expense_id = :expenseId AND deleted_at IS NULL")
    suspend fun getByExpense(expenseId: String): List<ShareEntity>

    /** Every local row (incl. tombstones) — the push side of sync. */
    @Query("SELECT * FROM shares")
    suspend fun allForSync(): List<ShareEntity>

    /** Soft-delete shares removed by an edit (Rule 1): tombstone so the removal syncs, never hard-delete. */
    @Query("UPDATE shares SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 WHERE id IN (:ids)")
    suspend fun softDeleteByIds(
        ids: List<String>,
        ts: Long,
    )

    /** Active shares of an expense with their **derived** remaining — the detail view. */
    @Query(
        """
        SELECT s.id AS id, s.expense_id AS expense_id, s.user_id AS user_id,
               s.share_owed_subunits AS share_owed_subunits,
               CASE WHEN e.payer_user_id IS NOT NULL AND s.user_id = e.payer_user_id THEN 0
                    ELSE s.share_owed_subunits - COALESCE((
                        SELECT SUM(sa.applied_amount_subunits) FROM settlement_allocations sa
                        INNER JOIN settlements st ON st.id = sa.settlement_id
                        WHERE sa.share_id = s.id AND st.deleted_at IS NULL), 0)
               END AS remaining_subunits,
               s.share_units AS share_units, s.share_percentage AS share_percentage,
               s.share_exact_subunits AS share_exact_subunits
        FROM shares s INNER JOIN expenses e ON e.id = s.expense_id
        WHERE s.expense_id = :expenseId AND s.deleted_at IS NULL
        """,
    )
    fun observeByExpense(expenseId: String): Flow<List<ShareRow>>

    /**
     * Every active share of a non-deleted expense in a group, with **derived** remaining — the input
     * to the spending tracker (grouped back to expenses by the repository to form [ExpenseWithShares]).
     */
    @Query(
        """
        SELECT s.id AS id, s.expense_id AS expense_id, s.user_id AS user_id,
               s.share_owed_subunits AS share_owed_subunits,
               CASE WHEN e.payer_user_id IS NOT NULL AND s.user_id = e.payer_user_id THEN 0
                    ELSE s.share_owed_subunits - COALESCE((
                        SELECT SUM(sa.applied_amount_subunits) FROM settlement_allocations sa
                        INNER JOIN settlements st ON st.id = sa.settlement_id
                        WHERE sa.share_id = s.id AND st.deleted_at IS NULL), 0)
               END AS remaining_subunits,
               s.share_units AS share_units, s.share_percentage AS share_percentage,
               s.share_exact_subunits AS share_exact_subunits
        FROM shares s INNER JOIN expenses e ON e.id = s.expense_id
        WHERE e.group_id = :groupId AND e.deleted_at IS NULL AND s.deleted_at IS NULL
        """,
    )
    fun observeByGroup(groupId: String): Flow<List<ShareRow>>

    /** AC-INV-001: must equal `expenses.amount_subunits`. `COALESCE` so no-rows returns 0, not null. */
    @Query("SELECT COALESCE(SUM(share_owed_subunits), 0) FROM shares WHERE expense_id = :expenseId AND deleted_at IS NULL")
    suspend fun sumOwed(expenseId: String): Long

    /**
     * How many non-voided settlement allocations point at this expense's shares — "has anybody actually
     * paid against this?", asked of the ground truth rather than of a stored flag.
     *
     * The caller is `ExpenseRepositoryImpl.editExpense`, which uses it to refuse a **currency** change
     * (R1). Tombstoned shares count deliberately: their allocations are still live history that a
     * re-denomination would silently re-interpret. A voided settlement does not, because its allocations
     * have already stopped offsetting anything (every derived-remaining query filters the same way).
     */
    @Query(
        """
        SELECT COUNT(*) FROM settlement_allocations sa
        INNER JOIN settlements st ON st.id = sa.settlement_id
        INNER JOIN shares s ON s.id = sa.share_id
        WHERE s.expense_id = :expenseId AND st.deleted_at IS NULL
        """,
    )
    suspend fun appliedAllocationCount(expenseId: String): Int

    /**
     * Outstanding shares across a group, with **derived** remaining (owed − applied), joined to their
     * expense for currency + payer — the input to the bilateral balance engine (03 §2.2). Excludes
     * fully-paid shares, the payer's own share, soft-deleted shares, and soft-deleted expenses.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT s.expense_id AS expense_id, e.currency AS currency, e.payer_user_id AS payer_user_id,
                   s.user_id AS user_id,
                   s.share_owed_subunits - COALESCE((
                       SELECT SUM(sa.applied_amount_subunits) FROM settlement_allocations sa
                       INNER JOIN settlements st ON st.id = sa.settlement_id
                       WHERE sa.share_id = s.id AND st.deleted_at IS NULL), 0) AS remaining_subunits
            FROM shares s INNER JOIN expenses e ON e.id = s.expense_id
            WHERE e.group_id = :groupId AND e.deleted_at IS NULL AND s.deleted_at IS NULL
              AND (e.payer_user_id IS NULL OR s.user_id <> e.payer_user_id)
        ) WHERE remaining_subunits > 0
        """,
    )
    fun observeOutstandingShares(groupId: String): Flow<List<OutstandingShareRow>>

    /**
     * Every outstanding line in a group, joined to its expense for title/date/currency + payer — the
     * input to the Balances per-counterparty breakdown and the one-page settle checklist. Remaining is
     * derived (owed − applied); excludes fully-paid shares, the payer's own share, outside-payer
     * expenses (no member creditor), soft-deleted shares, and soft-deleted expenses. Oldest first.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT s.expense_id AS expense_id, e.title AS title, e.expense_date AS expense_date,
                   e.currency AS currency, s.user_id AS debtor_user_id,
                   e.payer_user_id AS creditor_user_id,
                   s.share_owed_subunits - COALESCE((
                       SELECT SUM(sa.applied_amount_subunits) FROM settlement_allocations sa
                       INNER JOIN settlements st ON st.id = sa.settlement_id
                       WHERE sa.share_id = s.id AND st.deleted_at IS NULL), 0) AS remaining_subunits
            FROM shares s INNER JOIN expenses e ON e.id = s.expense_id
            WHERE e.group_id = :groupId AND e.deleted_at IS NULL AND s.deleted_at IS NULL
              AND e.payer_user_id IS NOT NULL AND s.user_id <> e.payer_user_id
        ) WHERE remaining_subunits > 0
        ORDER BY expense_date ASC, expense_id ASC
        """,
    )
    fun observeOutstandingItems(groupId: String): Flow<List<OutstandingItemRow>>

    /**
     * Outstanding shares [fromUserId] (debtor) still owes [toUserId] (payer), oldest expense first —
     * the exact input the settlement allocator walks (03 §4.2/§4.3.1). Remaining is derived; excludes
     * fully-paid shares, soft-deleted shares, and soft-deleted expenses.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT s.id AS id, s.expense_id AS expense_id, e.currency AS currency,
                   s.share_owed_subunits - COALESCE((
                       SELECT SUM(sa.applied_amount_subunits) FROM settlement_allocations sa
                       INNER JOIN settlements st ON st.id = sa.settlement_id
                       WHERE sa.share_id = s.id AND st.deleted_at IS NULL), 0) AS remaining_subunits,
                   e.expense_date AS expense_date
            FROM shares s INNER JOIN expenses e ON e.id = s.expense_id
            WHERE e.group_id = :groupId AND e.deleted_at IS NULL AND s.deleted_at IS NULL
              AND e.payer_user_id = :toUserId AND s.user_id = :fromUserId
        ) WHERE remaining_subunits > 0
        ORDER BY expense_date ASC, expense_id ASC, id ASC
        """,
    )
    suspend fun outstandingForPair(
        groupId: String,
        fromUserId: String,
        toUserId: String,
    ): List<OutstandingShareForPair>

    /**
     * Over-paid debtor→creditor pairs (P1 #9): shares whose derived remaining is **negative** (more paid
     * than owed) summed per pair+currency, then negated to a positive magnitude. A share only goes negative
     * when the same payment is recorded twice (both parties log it, or one logs it offline twice) — every
     * outstanding query filters `remaining > 0`, so without this the overpayment is invisible and reads as
     * "settled". NOT clamped at 0 in SQL — the negative is the whole signal. One row per over-paid pair.
     */
    @Query(
        """
        SELECT debtor_user_id, creditor_user_id, currency, -SUM(remaining_subunits) AS overpaid_subunits
        FROM (
            SELECT s.user_id AS debtor_user_id, e.payer_user_id AS creditor_user_id, e.currency AS currency,
                   s.share_owed_subunits - COALESCE((
                       SELECT SUM(sa.applied_amount_subunits) FROM settlement_allocations sa
                       INNER JOIN settlements st ON st.id = sa.settlement_id
                       WHERE sa.share_id = s.id AND st.deleted_at IS NULL), 0) AS remaining_subunits
            FROM shares s INNER JOIN expenses e ON e.id = s.expense_id
            WHERE e.group_id = :groupId AND e.deleted_at IS NULL AND s.deleted_at IS NULL
              AND e.payer_user_id IS NOT NULL AND s.user_id <> e.payer_user_id
        ) WHERE remaining_subunits < 0
        GROUP BY debtor_user_id, creditor_user_id, currency
        """,
    )
    fun observeOverpayments(groupId: String): Flow<List<OverpaymentRow>>

    /** Distinct parent expenses of the given shares. */
    @Query("SELECT DISTINCT expense_id FROM shares WHERE id IN (:shareIds)")
    suspend fun expenseIdsForShares(shareIds: List<String>): List<String>

    // NOTE: there is deliberately no blind `UPDATE shares SET user_id = …` reassignment here. It produced
    // two active rows with the same (expense_id, user_id) whenever the claimer was already in the expense
    // — silent locally, rejected by the server's partial unique index, taking the expense's sync with it.
    // The placeholder merge folds collisions instead; see [PlaceholderMergeDao].

    /** The expenses a (placeholder) user is booked into — the claim cards on the Reconcile screen. */
    @Query(
        """
        SELECT e.title AS title, e.amount_subunits AS amount_subunits
        FROM shares s INNER JOIN expenses e ON e.id = s.expense_id
        WHERE s.user_id = :userId AND e.group_id = :groupId AND e.deleted_at IS NULL AND s.deleted_at IS NULL
        ORDER BY e.expense_date ASC
        """,
    )
    suspend fun expensesForUser(
        groupId: String,
        userId: String,
    ): List<ReconcileExpenseRow>
}
