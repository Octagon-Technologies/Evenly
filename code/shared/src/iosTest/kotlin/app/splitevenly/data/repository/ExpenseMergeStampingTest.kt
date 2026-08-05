package app.splitevenly.data.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.domain.expense.EditExpense
import app.splitevenly.domain.expense.NewExpense
import app.splitevenly.domain.expense.NewShare
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Track F — the CLIENT side of the zone-aware merge: `ExpenseRepositoryImpl` must stamp each Zone-1
 * field's `*_updated_at` ONLY when that field changes, and advance the causal `split_version` ONLY when
 * the money value (amount / mode / payer / share split) changes. Those stamps + version are what
 * `merge_expense` reads to merge metadata per-field and to detect a causally-stale split. Getting the
 * client stamping wrong is a silent data-loss vector, so it's pinned here.
 */
class ExpenseMergeStampingTest {

    private class AdvanceableClock(var millis: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    }

    private lateinit var db: EvenlyDatabase
    private lateinit var clock: AdvanceableClock
    private lateinit var repo: ExpenseRepositoryImpl

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        clock = AdvanceableClock(1_000)
        repo = ExpenseRepositoryImpl(db.expenseDao(), db.shareDao(), clock)
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun newExpense() = NewExpense(
        groupId = GroupId("g1"),
        title = "Dinner",
        amountSubunits = 3000,
        currency = "USD",
        expenseDate = "2026-06-01",
        payerUserId = UserId("u1"),
        splitMode = "EVEN",
        createdBy = UserId("u1"),
        shares = listOf(NewShare(UserId("u1"), 1000), NewShare(UserId("u2"), 1000), NewShare(UserId("u3"), 1000)),
    )

    private fun evenEdit(
        title: String = "Dinner",
        notes: String? = null,
        amount: Long = 3000,
        shares: List<NewShare> = listOf(NewShare(UserId("u1"), 1000), NewShare(UserId("u2"), 1000), NewShare(UserId("u3"), 1000)),
    ) = EditExpense(
        title = title,
        notes = notes,
        amountSubunits = amount,
        currency = "USD",
        expenseDate = "2026-06-01",
        payerUserId = UserId("u1"),
        splitMode = "EVEN",
        shares = shares,
        editedBy = UserId("u1"),
    )

    @Test
    fun create_stampsEveryFieldAndStartsSplitAtGenOne() = runTest {
        val created = (repo.addExpense(newExpense()) as AppResult.Ok).value
        val e = db.expenseDao().getById(created.id.value)!!
        assertEquals(1_000, e.titleUpdatedAt)
        assertEquals(1_000, e.notesUpdatedAt)
        assertEquals(1_000, e.categoryUpdatedAt)
        assertEquals(1_000, e.dateUpdatedAt)
        assertEquals(1L, e.splitVersion)
        assertEquals("u1", e.splitUpdatedBy)
    }

    @Test
    fun editTitleOnly_bumpsOnlyTitleStamp_leavesSplitVersion() = runTest {
        val created = (repo.addExpense(newExpense()) as AppResult.Ok).value
        clock.millis = 2_000
        assertTrue(repo.editExpense(created.id, evenEdit(title = "Lunch")) is AppResult.Ok)

        val e = db.expenseDao().getById(created.id.value)!!
        assertEquals("Lunch", e.title)
        assertEquals(2_000, e.titleUpdatedAt, "changed field is re-stamped")
        assertEquals(1_000, e.notesUpdatedAt, "untouched field keeps its old stamp (so it loses a concurrent merge)")
        assertEquals(1_000, e.categoryUpdatedAt)
        assertEquals(1_000, e.dateUpdatedAt)
        assertEquals(1L, e.splitVersion, "a metadata-only edit must NOT advance the split")
    }

    @Test
    fun editSplit_advancesSplitVersion() = runTest {
        val created = (repo.addExpense(newExpense()) as AppResult.Ok).value
        clock.millis = 3_000
        // Amount + share split change; title unchanged.
        val edit = evenEdit(amount = 2000, shares = listOf(NewShare(UserId("u1"), 1000), NewShare(UserId("u2"), 1000)))
        assertTrue(repo.editExpense(created.id, edit) is AppResult.Ok)

        val e = db.expenseDao().getById(created.id.value)!!
        assertEquals(2L, e.splitVersion, "a real split change advances the causal version by one")
        assertEquals(1_000, e.titleUpdatedAt, "unchanged title keeps its stamp even on a split edit")
        assertEquals(2000, e.amountSubunits)
    }

    @Test
    fun editSameSplitDifferentNote_advancesNothingButNote() = runTest {
        val created = (repo.addExpense(newExpense()) as AppResult.Ok).value
        clock.millis = 4_000
        // Only notes change; amount/shares/payer/mode identical → split must stay at gen 1.
        assertTrue(repo.editExpense(created.id, evenEdit(notes = "cash")) is AppResult.Ok)

        val e = db.expenseDao().getById(created.id.value)!!
        assertEquals(1L, e.splitVersion, "editing a note is Zone 1, never a split change")
        assertEquals(4_000, e.notesUpdatedAt)
        assertEquals(1_000, e.titleUpdatedAt)
    }
}
