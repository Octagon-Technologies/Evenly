package app.splitevenly.data.repository

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.ExpenseStatus
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.domain.expense.EditExpense
import app.splitevenly.domain.expense.Expense
import app.splitevenly.domain.expense.NewExpense
import app.splitevenly.domain.expense.NewShare
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

    private lateinit var db: EvenlyDatabase
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
        val expense = add() // payer u1 is also a participant

        val detail = repo.observeExpense(expense.id).first()
        assertNotNull(detail)
        assertEquals(3, detail.shares.size)
        assertEquals(ExpenseStatus.ACTIVE, detail.expense.status)
        // The payer's own share is born resolved; the other two stay unpaid. 2000 still outstanding.
        val byUser = detail.shares.associateBy { it.userId.value }
        assertEquals(0, byUser["u1"]?.remainingSubunits)
        assertTrue(listOf("u2", "u3").all { byUser[it]?.remainingSubunits == byUser[it]?.owedSubunits })
        assertEquals(2000, detail.shares.sumOf { it.remainingSubunits })
        assertEquals(3000, detail.shares.sumOf { it.owedSubunits })

        assertEquals(listOf(expense.id), repo.observeExpenses(GroupId("g1")).first().map { it.id })
    }

    @Test
    fun addExpense_payerIsParticipant_resolvesOnlyPayerShare() = runTest {
        // u1 pays and is a participant — a person can't owe themselves, so u1's share starts resolved
        // while u2/u3 start unpaid. Owed amounts are untouched (only remaining is zeroed).
        val expense = add()

        val byUser = repo.observeExpense(expense.id).first()!!.shares.associateBy { it.userId.value }
        assertEquals(0, byUser["u1"]?.remainingSubunits, "payer's own share starts resolved")
        assertEquals(1000, byUser["u1"]?.owedSubunits, "owed is unchanged — only remaining is zeroed")
        assertEquals(1000, byUser["u2"]?.remainingSubunits, "non-payer starts unpaid")
        assertEquals(1000, byUser["u3"]?.remainingSubunits, "non-payer starts unpaid")
    }

    @Test
    fun addExpense_payerCoversWholeExpense_isSettledImmediately() = runTest {
        // The payer is the only participant (a solo expense) — nothing is outstanding, so it is SETTLED
        // at creation rather than perpetually ACTIVE on a debt to oneself.
        val expense = add(newExpense(amount = 1000, shares = listOf(NewShare(UserId("u1"), 1000))))

        val detail = repo.observeExpense(expense.id).first()
        assertNotNull(detail)
        // "Settled" is derived, not stored: the payer's own share derives to remaining 0, so every
        // share is paid ⇒ settled. The stored status stays ACTIVE (only deletion is stored).
        assertEquals(0, detail.shares.single().remainingSubunits)
        assertTrue(detail.shares.all { it.remainingSubunits == 0L }, "self-covered expense is settled (derived)")
        assertEquals(ExpenseStatus.ACTIVE, detail.expense.status)
    }

    @Test
    fun addExpense_outsidePayer_resolvesNoShare() = runTest {
        // An outside payer (payerUserId == null) is not a participant, so no self-share resolution
        // applies: every share stays unpaid and the expense is ACTIVE.
        val expense = add(
            newExpense().copy(payerUserId = null, payerOutsideName = "Hotel"),
        )

        val detail = repo.observeExpense(expense.id).first()
        assertNotNull(detail)
        assertTrue(detail.shares.all { it.remainingSubunits == it.owedSubunits }, "no share is pre-resolved")
        assertEquals(3000, detail.shares.sumOf { it.remainingSubunits })
        assertEquals(ExpenseStatus.ACTIVE, detail.expense.status)
    }

    @Test
    fun editExpense_payerIsParticipant_resolvesPayerShare() = runTest {
        val created = add() // status starts with u1 resolved

        val edited = repo.editExpense(
            created.id,
            EditExpense(
                title = "Lunch",
                amountSubunits = 2000,
                currency = "USD",
                expenseDate = "2026-06-02",
                payerUserId = UserId("u2"), // payer changes to u2
                splitMode = "EVEN",
                shares = listOf(NewShare(UserId("u1"), 1000), NewShare(UserId("u2"), 1000)),
            ),
        )
        assertTrue(edited is AppResult.Ok)

        // After the edit the new payer (u2) owns the resolved self-share; u1 is now unpaid.
        val byUser = repo.observeExpense(created.id).first()!!.shares.associateBy { it.userId.value }
        assertEquals(0, byUser["u2"]?.remainingSubunits, "new payer's share is resolved")
        assertEquals(1000, byUser["u1"]?.remainingSubunits, "former payer now owes their share")
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
