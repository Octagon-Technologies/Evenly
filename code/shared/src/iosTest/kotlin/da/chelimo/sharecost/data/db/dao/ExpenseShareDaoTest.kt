package da.chelimo.sharecost.data.db.dao

import da.chelimo.sharecost.data.db.ExpenseStatus
import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.computeExpenseStatus
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.data.db.inMemoryTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Room tests for [ExpenseDao] + [ShareDao] (02 §3.7/§3.8). Covers column fidelity, the cross-table
 * SUM invariants (AC-INV-001, AC-INV-003), feed ordering (UUIDv7 id as same-day tiebreaker), and
 * the join projection that feeds the bilateral balance engine.
 */
class ExpenseShareDaoTest {

    private lateinit var db: ShareCostDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var shareDao: ShareDao

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        expenseDao = db.expenseDao()
        shareDao = db.shareDao()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun expense(
        id: String,
        groupId: String = "g1",
        amount: Long = 3000,
        currency: String = "USD",
        date: String = "2026-06-01",
        payer: String? = "u1",
        deletedAt: Long? = null,
    ) = ExpenseEntity(
        id = id,
        groupId = groupId,
        title = "Dinner $id",
        amountSubunits = amount,
        currency = currency,
        expenseDate = date,
        payerUserId = payer,
        splitMode = "EVEN",
        createdBy = "u1",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        deletedAt = deletedAt,
    )

    private fun share(id: String, expenseId: String, user: String, owed: Long, remaining: Long) =
        ShareEntity(
            id = id,
            expenseId = expenseId,
            userId = user,
            shareOwedSubunits = owed,
            remainingSubunits = remaining,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )

    @Test
    fun expense_roundTripsAllColumns() = runTest {
        val e = expense("e1").copy(notes = "split", categoryId = "cat", hasTaxRow = true, taxSubunits = 100, tipSubunits = 200)
        expenseDao.upsert(e)
        assertEquals(e, expenseDao.getById("e1"))
    }

    @Test
    fun sumOwed_matchesExpenseAmount_invariant001() = runTest {
        expenseDao.upsert(expense("e1", amount = 3000))
        shareDao.upsertAll(
            listOf(
                share("s1", "e1", "u1", owed = 1000, remaining = 0),
                share("s2", "e1", "u2", owed = 1000, remaining = 1000),
                share("s3", "e1", "u3", owed = 1000, remaining = 1000),
            )
        )
        assertEquals(3000, shareDao.sumOwed("e1"))
    }

    @Test
    fun sumRemaining_feedsStatus_invariant003() = runTest {
        expenseDao.upsert(expense("e1"))
        shareDao.upsertAll(
            listOf(
                share("s1", "e1", "u1", owed = 1000, remaining = 0),
                share("s2", "e1", "u2", owed = 2000, remaining = 0),
            )
        )
        assertEquals(0, shareDao.sumRemaining("e1"))
        assertEquals(ExpenseStatus.SETTLED, computeExpenseStatus(null, shareDao.sumRemaining("e1")))

        shareDao.upsert(share("s2", "e1", "u2", owed = 2000, remaining = 500))
        assertEquals(ExpenseStatus.ACTIVE, computeExpenseStatus(null, shareDao.sumRemaining("e1")))
    }

    @Test
    fun sumOwed_noShares_returnsZeroNotNull() = runTest {
        assertEquals(0, shareDao.sumOwed("missing"))
    }

    @Test
    fun observeByGroup_excludesDeleted_ordersByDateThenIdDesc() = runTest {
        expenseDao.upsertAll(
            listOf(
                expense("e-a", date = "2026-06-01"),
                expense("e-c", date = "2026-06-03"),
                expense("e-b", date = "2026-06-03"), // same day as e-c; id DESC ⇒ e-c before e-b
                expense("e-del", date = "2026-06-09", deletedAt = 1L),
            )
        )
        val ids = expenseDao.observeByGroup("g1").first().map { it.id }
        assertEquals(listOf("e-c", "e-b", "e-a"), ids)
    }

    @Test
    fun observeOutstandingShares_joinsExpense_excludesSettledAndDeleted() = runTest {
        expenseDao.upsertAll(
            listOf(
                expense("e1", currency = "USD", payer = "u1"),
                expense("e2", currency = "EUR", payer = "u2", deletedAt = 5L), // deleted ⇒ dropped
            )
        )
        shareDao.upsertAll(
            listOf(
                share("s1", "e1", "u2", owed = 1000, remaining = 1000), // outstanding
                share("s2", "e1", "u3", owed = 1000, remaining = 0),    // settled ⇒ dropped
                share("s3", "e2", "u1", owed = 500, remaining = 500),   // on deleted expense ⇒ dropped
            )
        )
        val rows = shareDao.observeOutstandingShares("g1").first()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("u2", row.participantUserId)
        assertEquals("u1", row.payerUserId)
        assertEquals("USD", row.currency)
        assertEquals(1000, row.remainingSubunits)
    }
}
