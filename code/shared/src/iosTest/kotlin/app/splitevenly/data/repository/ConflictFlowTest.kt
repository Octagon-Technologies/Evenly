package app.splitevenly.data.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.domain.expense.NewExpense
import app.splitevenly.domain.expense.NewShare
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Retroactive-member flow (03 §8): EVEN expenses re-split silently, non-EVEN raise conflicts to resolve. */
class ConflictFlowTest {

    private lateinit var db: EvenlyDatabase
    private lateinit var groups: GroupRepositoryImpl
    private lateinit var expenses: ExpenseRepositoryImpl

    private val gid = GroupId("g1")
    private val u1 = UserId("u1")
    private val u2 = UserId("u2")
    private val u3 = UserId("u3")

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        val clock = clockAt("2026-06-15")
        groups = GroupRepositoryImpl(db.groupDao(), db.memberDao(), db.userDao(), db.expenseDao(), db.shareDao(), db.conflictDao(), db.placeholderMergeDao(), db.placeholderClaimAnswerDao(), clock)
        expenses = ExpenseRepositoryImpl(db.expenseDao(), db.shareDao(), clock)
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun addExpense(mode: String, amount: Long, shares: List<NewShare>): ExpenseId {
        val r = expenses.addExpense(
            NewExpense(
                groupId = gid, title = "Dinner", amountSubunits = amount, currency = "USD",
                expenseDate = "2026-06-01", payerUserId = u1, splitMode = mode, createdBy = u1, shares = shares,
            ),
        )
        assertTrue(r is AppResult.Ok); return r.value.id
    }

    @Test
    fun retroAdd_evenExpense_reSplitsToIncludeNewMember() = runTest {
        val id = addExpense("EVEN", 1000, listOf(NewShare(u1, 500), NewShare(u2, 500)))

        assertTrue(groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1) is AppResult.Ok)

        val shares = expenses.observeExpense(id).first()!!.shares
        assertEquals(3, shares.size)
        assertEquals(1000, shares.sumOf { it.owedSubunits })
        assertEquals(333, shares.first { it.userId == u3 }.owedSubunits)
        assertTrue(groups.observeConflicts(gid).first().isEmpty()) // no conflict for EVEN
    }

    @Test
    fun retroAdd_nonEvenExpense_raisesConflictWithoutTouchingShares() = runTest {
        val id = addExpense("EXACT", 1000, listOf(NewShare(u1, 600), NewShare(u2, 400)))

        groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1)

        val conflicts = groups.observeConflicts(gid).first()
        assertEquals(1, conflicts.size)
        assertEquals(id, conflicts.single().expenseId)
        assertEquals(u3, conflicts.single().addedUserId)
        assertEquals(2, expenses.observeExpense(id).first()!!.shares.size) // shares untouched until resolved

        // Idempotent: a second sweep doesn't duplicate the conflict.
        groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1)
        assertEquals(1, groups.observeConflicts(gid).first().size)
    }

    @Test
    fun resolveConflict_include_reducesOthersProportionally() = runTest {
        val id = addExpense("EXACT", 1000, listOf(NewShare(u1, 600), NewShare(u2, 400)))
        groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1)
        val conflictId = groups.observeConflicts(gid).first().single().id

        assertTrue(groups.resolveConflict(conflictId, include = true, newShareSubunits = 200) is AppResult.Ok)

        val byUser = expenses.observeExpense(id).first()!!.shares.associate { it.userId to it.owedSubunits }
        assertEquals(480, byUser[u1]) // 600/1000 of the remaining 800
        assertEquals(320, byUser[u2]) // 400/1000 of the remaining 800
        assertEquals(200, byUser[u3])
        assertEquals(1000, byUser.values.sum())
        assertTrue(groups.observeConflicts(gid).first().isEmpty())
    }

    @Test
    fun resolveConflict_dismiss_leavesExpenseUntouched() = runTest {
        val id = addExpense("PERCENT", 1000, listOf(NewShare(u1, 600), NewShare(u2, 400)))
        groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1)
        val conflictId = groups.observeConflicts(gid).first().single().id

        assertTrue(groups.resolveConflict(conflictId, include = false) is AppResult.Ok)

        assertEquals(2, expenses.observeExpense(id).first()!!.shares.size)
        assertTrue(groups.observeConflicts(gid).first().isEmpty())
    }

    @Test
    fun resolveConflict_include_rejectsOutOfRangeShare() = runTest {
        addExpense("EXACT", 1000, listOf(NewShare(u1, 600), NewShare(u2, 400)))
        groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1)
        val conflictId = groups.observeConflicts(gid).first().single().id

        assertTrue(groups.resolveConflict(conflictId, include = true, newShareSubunits = 1000) is AppResult.Err) // >= total
        assertTrue(groups.resolveConflict(conflictId, include = true, newShareSubunits = 0) is AppResult.Err)
        assertEquals(1, groups.observeConflicts(gid).first().size) // still unresolved
    }
}
