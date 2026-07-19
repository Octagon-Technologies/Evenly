package da.chelimo.sharecost.domain.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.domain.balance.Debt
import da.chelimo.sharecost.domain.balance.OutstandingItem
import da.chelimo.sharecost.domain.balance.Overpayment
import da.chelimo.sharecost.domain.expense.EditExpense
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.expense.ExpenseEditConflict
import da.chelimo.sharecost.domain.expense.ExpenseWithShares
import da.chelimo.sharecost.domain.expense.NewExpense
import kotlinx.coroutines.flow.Flow

/**
 * Expenses + their shares (06 §3.1). An expense and its shares are written in one Room transaction
 * with the denormalized status recomputed (02 §7.5, AC-INV-001/003). Reads are local-first `Flow`s;
 * writes are optimistic [AppResult] (server push deferred to S-1).
 */
interface ExpenseRepository {

    /** The group feed: non-deleted expenses, newest first (U-3). */
    fun observeExpenses(groupId: GroupId): Flow<List<Expense>>

    /** A single expense with its shares, or null once deleted (the detail screen). */
    fun observeExpense(expenseId: ExpenseId): Flow<ExpenseWithShares?>

    /**
     * The group feed with each expense's shares attached, newest first — the input to the spending
     * tracker (per-participant "what you spent" needs each share's owed amount, F2).
     */
    fun observeExpensesWithShares(groupId: GroupId): Flow<List<ExpenseWithShares>>

    /** Bilateral debts for the group (03 §2.2) — pairwise nets of outstanding shares, never simplified. */
    fun observeBalances(groupId: GroupId): Flow<List<Debt>>

    /**
     * Every outstanding line in the group (debtor still owes creditor on expense X) — the per-expense
     * breakdown behind the Balances rows and the one-page settle checklist. Each line is in its own
     * expense's currency; the caller filters to the counterparty + direction it's showing.
     */
    fun observeOutstandingItems(groupId: GroupId): Flow<List<OutstandingItem>>

    /**
     * Over-paid pairs the [viewer] is party to (P1 #9) — a debtor→creditor pair whose derived remaining
     * has gone negative, i.e. the same payment was recorded twice. Drives the "possible double payment"
     * banner on the Balances tab. Empty when nothing is over-paid; clears the instant the extra payment is
     * voided (it's derived, never stored). Pass a null viewer to see every over-paid pair in the group.
     */
    fun observeOverpayments(groupId: GroupId, viewer: UserId?): Flow<List<Overpayment>>

    /** Add an expense + shares in one transaction. Rejects when `sum(shares) != amount` (AC-INV-001). */
    suspend fun addExpense(input: NewExpense): AppResult<Expense>

    /** Replace an expense's editable fields and share set (04 §2.3 `edit_expense`). */
    suspend fun editExpense(expenseId: ExpenseId, input: EditExpense): AppResult<Expense>

    /** Soft-delete an expense (04 §2.3 `delete_expense`); status becomes DELETED. */
    suspend fun deleteExpense(expenseId: ExpenseId): AppResult<Unit>

    /** Retired (Track F): the zone-aware merge no longer parks bilateral edit-collisions, so this is empty. */
    fun observeEditConflicts(groupId: GroupId): Flow<List<ExpenseEditConflict>>

    /**
     * Resolve a parked edit-collision. [useRejected] = false keeps the canonical version; true re-applies
     * the rejected payload as a fresh edit on top of the current version (a clean base, so it commits).
     */
    suspend fun resolveEditConflict(conflictId: String, useRejected: Boolean, resolvedBy: UserId?): AppResult<Unit>

    /**
     * Track F one-sided nudge: `true` when THIS device pushed a split edit that lost the causal guard
     * (the server kept its advanced split; ours was logged) and the author hasn't reviewed it yet. Drives
     * a dismissible "your change was superseded — review the current split?" banner on the expense.
     */
    fun observeSupersededNotice(expenseId: ExpenseId): Flow<Boolean>

    /** Dismiss the superseded-edit notice for [expenseId] (the user acknowledged it). */
    suspend fun dismissSupersededNotice(expenseId: ExpenseId)
}
