package da.chelimo.sharecost.data.db.dao

import da.chelimo.sharecost.data.db.ShareCostDatabase
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
 * Room tests for [SettlementDao] (02 §3.9). Covers settlement + allocation round-trips, recency
 * ordering, soft-delete exclusion, and the applied-vs-remaining invariant (AC-INV-002).
 */
class SettlementDaoTest {

    private lateinit var db: ShareCostDatabase
    private lateinit var dao: SettlementDao
    private lateinit var shareDao: ShareDao

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        dao = db.settlementDao()
        shareDao = db.shareDao()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun settlement(
        id: String,
        settledAt: Long,
        deletedAt: Long? = null,
    ) = SettlementEntity(
        id = id,
        groupId = "g1",
        fromUserId = "u2",
        toUserId = "u1",
        paymentCurrency = "USD",
        paymentAmountSubunits = 1000,
        paymentApp = "VENMO",
        settledAt = settledAt,
        createdBy = "u2",
        createdAt = settledAt,
        updatedAt = settledAt,
        deletedAt = deletedAt,
    )

    private fun allocation(id: String, settlementId: String, shareId: String, amount: Long) =
        SettlementAllocationEntity(
            id = id,
            settlementId = settlementId,
            shareId = shareId,
            appliedAmountSubunits = amount,
            appliedCurrency = "USD",
            createdAt = 1L,
        )

    @Test
    fun settlement_roundTrips() = runTest {
        val s = settlement("s1", settledAt = 100).copy(notes = "thanks", deepLinkAttempted = true, deepLinkSucceeded = true)
        dao.upsert(s)
        assertEquals(s, dao.getById("s1"))
    }

    @Test
    fun observeByGroup_excludesDeleted_orderedBySettledAtDesc() = runTest {
        dao.upsertAll(
            listOf(
                settlement("s1", settledAt = 100),
                settlement("s3", settledAt = 300),
                settlement("s2", settledAt = 200),
                settlement("s-del", settledAt = 999, deletedAt = 1L),
            )
        )
        val ids = dao.observeByGroup("g1").first().map { it.id }
        assertEquals(listOf("s3", "s2", "s1"), ids)
    }

    @Test
    fun sumAppliedToShare_plusRemaining_equalsOwed_invariant002() = runTest {
        // share owed 1000, 600 still remaining ⇒ 400 covered by allocations. Partial pay means
        // TWO settlements (one allocation each) — UNIQUE(settlement_id, share_id) forbids two
        // allocations from the *same* settlement against the same share.
        shareDao.upsert(
            ShareEntity(
                id = "sh1", expenseId = "e1", userId = "u2",
                shareOwedSubunits = 1000, remainingSubunits = 600,
                createdAt = 1, updatedAt = 1,
            )
        )
        dao.upsertAll(listOf(settlement("s1", settledAt = 1), settlement("s2", settledAt = 2)))
        dao.upsertAllocations(
            listOf(
                allocation("a1", "s1", "sh1", amount = 250),
                allocation("a2", "s2", "sh1", amount = 150),
            )
        )
        val share = shareDao.getByExpense("e1").single()
        assertEquals(share.shareOwedSubunits - share.remainingSubunits, dao.sumAppliedToShare("sh1")) // 400
    }

    @Test
    fun allocationsForSettlement_returnsAllLegs() = runTest {
        dao.upsert(settlement("s1", settledAt = 1))
        dao.upsertAllocations(
            listOf(
                allocation("a1", "s1", "sh1", amount = 300),
                allocation("a2", "s1", "sh2", amount = 700),
            )
        )
        assertEquals(2, dao.allocationsForSettlement("s1").size)
    }
}
