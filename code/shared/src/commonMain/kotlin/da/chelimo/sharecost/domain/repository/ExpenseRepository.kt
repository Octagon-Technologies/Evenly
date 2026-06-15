package da.chelimo.sharecost.domain.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.domain.balance.Debt
import da.chelimo.sharecost.domain.expense.EditExpense
import da.chelimo.sharecost.domain.expense.Expense
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

    /** Bilateral debts for the group (03 §2.2) — pairwise nets of outstanding shares, never simplified. */
    fun observeBalances(groupId: GroupId): Flow<List<Debt>>

    /** Add an expense + shares in one transaction. Rejects when `sum(shares) != amount` (AC-INV-001). */
    suspend fun addExpense(input: NewExpense): AppResult<Expense>

    /** Replace an expense's editable fields and share set (04 §2.3 `edit_expense`). */
    suspend fun editExpense(expenseId: ExpenseId, input: EditExpense): AppResult<Expense>

    /** Soft-delete an expense (04 §2.3 `delete_expense`); status becomes DELETED. */
    suspend fun deleteExpense(expenseId: ExpenseId): AppResult<Unit>
}
