package da.chelimo.sharecost.data.db.dao

import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.SettlementAllocationEntity
import da.chelimo.sharecost.data.db.entity.SettlementEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.data.db.inMemoryTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Room tests for [ExpenseDao] + [ShareDao] (02 §3.7/§3.8). Covers column fidelity, the owed-sum
 * invariant (AC-INV-001), feed ordering (UUIDv7 id as same-day tiebreaker), and — crucially — that
 * `remaining` is **derived** (owed − Σ applied of non-voided settlements; payer's own share = 0), so
 * "settled" reflects ground truth rather than a stored value.
 */
class ExpenseShareDaoTest {

    private lateinit var db: ShareCostDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var shareDao: ShareDao
    private lateinit var settlementDao: SettlementDao

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        expenseDao = db.expenseDao()
        shareDao = db.shareDao()
        settlementDao = db.settlementDao()
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

    private fun share(id: String, expenseId: String, user: String, owed: Long) =
        ShareEntity(
            id = id,
            expenseId = expenseId,
            userId = user,
            shareOwedSubunits = owed,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )

    /** Record a (non-voided) payment against a share via a settlement + allocation — the ground truth. */
    private suspend fun pay(shareId: String, amount: Long, settlementId: String = "set_$shareId") {
        settlementDao.upsert(
            SettlementEntity(
                id = settlementId, groupId = "g1", fromUserId = "x", toUserId = "y",
                paymentCurrency = "USD", paymentAmountSubunits = amount, settledAt = 1L,
                createdBy = "x", createdAt = 1L, updatedAt = 1L,
            ),
        )
        settlementDao.upsertAllocation(
            SettlementAllocationEntity(
                id = "alloc_$settlementId", settlementId = settlementId, groupId = "g1",
                shareId = shareId, appliedAmountSubunits = amount, appliedCurrency = "USD", createdAt = 1L,
            ),
        )
    }

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
                share("s1", "e1", "u1", owed = 1000),
                share("s2", "e1", "u2", owed = 1000),
                share("s3", "e1", "u3", owed = 1000),
            ),
        )
        assertEquals(3000, shareDao.sumOwed("e1"))
    }

    @Test
    fun remaining_isDerivedFromAllocations_payerOwnShareIsZero() = runTest {
        // payer u1, so u1's own share derives to 0; u2/u3 start fully owed.
        expenseDao.upsert(expense("e1", payer = "u1"))
        shareDao.upsertAll(
            listOf(
                share("s1", "e1", "u1", owed = 1000), // payer's own → derived 0
                share("s2", "e1", "u2", owed = 1000),
                share("s3", "e1", "u3", owed = 1000),
            ),
        )
        // Before any payment: 0 (payer) + 1000 + 1000 = 2000 outstanding.
        assertEquals(2000, shareDao.observeByExpense("e1").first().sumOf { it.remainingSubunits })
        // Pay u2 fully and u3 partially ⇒ 0 + 0 + 400 = 400 outstanding (derived, nothing stored).
        pay("s2", 1000)
        pay("s3", 600)
        assertEquals(400, shareDao.observeByExpense("e1").first().sumOf { it.remainingSubunits })
    }

    @Test
    fun voidedSettlement_isExcludedFromDerivedRemaining() = runTest {
        expenseDao.upsert(expense("e1", payer = "u9")) // u9 not a participant
        shareDao.upsert(share("s1", "e1", "u2", owed = 1000))
        pay("s1", 1000, settlementId = "set1")
        assertEquals(0, shareDao.observeByExpense("e1").first().single().remainingSubunits)
        // Void the settlement ⇒ its allocation drops out of the derived remaining (restored to owed).
        settlementDao.voidSettlement("set1", ts = 2L)
        assertEquals(1000, shareDao.observeByExpense("e1").first().single().remainingSubunits)
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
            ),
        )
        val ids = expenseDao.observeByGroup("g1").first().map { it.id }
        assertEquals(listOf("e-c", "e-b", "e-a"), ids)
    }

    @Test
    fun observeOutstandingShares_joinsExpense_excludesSettledPayerAndDeleted() = runTest {
        expenseDao.upsertAll(
            listOf(
                expense("e1", currency = "USD", payer = "u1"),
                expense("e2", currency = "EUR", payer = "u2", deletedAt = 5L), // deleted ⇒ dropped
            ),
        )
        shareDao.upsertAll(
            listOf(
                share("s1", "e1", "u2", owed = 1000), // outstanding
                share("s2", "e1", "u3", owed = 1000), // will be fully paid ⇒ dropped
                share("s-payer", "e1", "u1", owed = 1000), // payer's own share ⇒ never outstanding
                share("s3", "e2", "u1", owed = 500),  // on deleted expense ⇒ dropped
            ),
        )
        pay("s2", 1000) // settle s2 fully
        val rows = shareDao.observeOutstandingShares("g1").first()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("u2", row.participantUserId)
        assertEquals("u1", row.payerUserId)
        assertEquals("USD", row.currency)
        assertEquals(1000, row.remainingSubunits)
    }
}
