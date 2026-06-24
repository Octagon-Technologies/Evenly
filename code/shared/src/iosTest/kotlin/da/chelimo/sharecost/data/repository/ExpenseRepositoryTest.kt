package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.data.db.ExpenseStatus
import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.inMemoryTestDatabase
import da.chelimo.sharecost.domain.expense.EditExpense
import da.chelimo.sharecost.domain.expense.Expense
import da.chelimo.sharecost.domain.expense.NewExpense
import da.chelimo.sharecost.domain.expense.NewShare
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ExpenseRepositoryImpl]: the atomic expense+shares write with status recompute
 * (02 §7.5, AC-INV-001/003), the AC-INV-001 sum guard, edit (full share replacement + version bump),
 * and soft delete.
 */
class ExpenseRepositoryTest {

    private lateinit var db: ShareCostDatabase
    private lateinit var repo: ExpenseRepositoryImpl

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        repo = ExpenseRepositoryImpl(db.expenseDao(), db.shareDao(), clockAt("2026-06-12"))
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun newExpense(
        amount: Long = 3000,
        shares: List<NewShare> = listOf(
            NewShare(UserId("u1"), 1000),
            NewShare(UserId("u2"), 1000),
            NewShare(UserId("u3"), 1000),
        ),
    ) = NewExpense(
        groupId = GroupId("g1"),
        title = "Dinner",
        amountSubunits = amount,
        currency = "USD",
        expenseDate = "2026-06-01",
        payerUserId = UserId("u1"),
        splitMode = "EVEN",
        createdBy = UserId("u1"),
        shares = shares,
    )

    private suspend fun add(input: NewExpense = newExpense()): Expense {
        val result = repo.addExpense(input)
        assertTrue(result is AppResult.Ok, "addExpense should succeed")
        return result.value
    }

    @Test
    fun addExpense_persistsExpenseAndShares_activeStatus() = runTest {
        val expense = add()

        val detail = repo.observeExpense(expense.id).first()
        assertNotNull(detail)
        assertEquals(3, detail.shares.size)
        assertEquals(ExpenseStatus.ACTIVE, detail.expense.status)
        assertTrue(detail.shares.all { it.remainingSubunits == it.owedSubunits })
        assertEquals(3000, detail.shares.sumOf { it.owedSubunits })

        assertEquals(listOf(expense.id), repo.observeExpenses(GroupId("g1")).first().map { it.id })
    }

    @Test
    fun addExpense_sharesDontSumToAmount_returnsValidation() = runTest {
        val result = repo.addExpense(
            newExpense(amount = 3000, shares = listOf(NewShare(UserId("u1"), 1000), NewShare(UserId("u2"), 1000)))
        )
        assertTrue(result is AppResult.Err)
        assertTrue((result.error as AppError.Validation).fieldErrors.containsKey("shares")) // AC-INV-001
    }

    @Test
    fun addExpense_blankTitle_returnsValidation() = runTest {
        val result = repo.addExpense(newExpense().copy(title = "  "))
        assertTrue(result is AppResult.Err)
        assertTrue((result.error as AppError.Validation).fieldErrors.containsKey("title"))
    }

    @Test
    fun addExpense_nonPositiveAmount_returnsValidation() = runTest {
        val result = repo.addExpense(newExpense(amount = 0, shares = listOf(NewShare(UserId("u1"), 0))))
        assertTrue(result is AppResult.Err)
        assertTrue((result.error as AppError.Validation).fieldErrors.containsKey("amount"))
    }

    @Test
    fun editExpense_replacesSharesAndBumpsVersion() = runTest {
        val created = add()
        assertEquals(1, created.rowVersion)

        val edited = repo.editExpense(
            created.id,
            EditExpense(
                title = "Lunch",
                amountSubunits = 2000,
                currency = "USD",
                expenseDate = "2026-06-02",
                payerUserId = UserId("u1"),
                splitMode = "EVEN",
                shares = listOf(NewShare(UserId("u1"), 1000), NewShare(UserId("u2"), 1000)),
            ),
        )
        assertTrue(edited is AppResult.Ok)
        assertEquals("Lunch", edited.value.title)
        assertEquals(2, edited.value.rowVersion)

        val detail = repo.observeExpense(created.id).first()
        assertNotNull(detail)
        assertEquals(2, detail.shares.size)
        assertEquals(2000, detail.expense.amountSubunits)
    }

    @Test
    fun addExpense_preservesRawSplitInputs_forEditReRender() = runTest {
        // A PERCENT split: the raw percentages must survive the round-trip so the editor can re-render.
        val created = add(
            newExpense(
                amount = 3000,
                shares = listOf(
                    NewShare(UserId("u1"), 1500, sharePercentage = 50.0),
                    NewShare(UserId("u2"), 900, sharePercentage = 30.0),
                    NewShare(UserId("u3"), 600, sharePercentage = 20.0),
                ),
            ).copy(splitMode = "PERCENT"),
        )

        val detail = repo.observeExpense(created.id).first()
        assertNotNull(detail)
        val byUser = detail.shares.associateBy { it.userId.value }
        assertEquals(50.0, byUser["u1"]?.sharePercentage)
        assertEquals(30.0, byUser["u2"]?.sharePercentage)
        assertEquals(20.0, byUser["u3"]?.sharePercentage)
        assertTrue(detail.shares.all { it.shareUnits == null && it.shareExactSubunits == null })
    }

    @Test
    fun observeExpensesWithShares_attachesSharesAndExcludesDeleted() = runTest {
        val a = add(newExpense(amount = 3000))
        val b = add(newExpense(amount = 2000, shares = listOf(NewShare(UserId("u1"), 1000), NewShare(UserId("u2"), 1000))).copy(title = "Cab"))
        repo.deleteExpense(b.id)

        val rows = repo.observeExpensesWithShares(GroupId("g1")).first()
        assertEquals(listOf(a.id), rows.map { it.expense.id }, "soft-deleted expense is excluded")
        assertEquals(3, rows.single().shares.size)
        assertEquals(3000, rows.single().shares.sumOf { it.owedSubunits })
    }

    @Test
    fun deleteExpense_softDeletes() = runTest {
        val created = add()
        assertTrue(repo.deleteExpense(created.id) is AppResult.Ok)

        assertNull(repo.observeExpense(created.id).first())
        assertTrue(repo.observeExpenses(GroupId("g1")).first().isEmpty())
        assertEquals(ExpenseStatus.DELETED, db.expenseDao().getById(created.id.value)?.status)
    }
}
