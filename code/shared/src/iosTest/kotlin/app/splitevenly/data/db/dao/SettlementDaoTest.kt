package app.splitevenly.data.db.dao

import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.SettlementAllocationEntity
import app.splitevenly.data.db.entity.SettlementEntity
import app.splitevenly.data.db.entity.ShareEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Room tests for [SettlementDao] (02 §3.9). Covers settlement + allocation round-trips, recency
 * ordering, soft-delete exclusion, and the applied-vs-remaining invariant (AC-INV-002).
 */
class SettlementDaoTest {

    private lateinit var db: EvenlyDatabase
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
            groupId = "g1",
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
        // share owed 1000; two settlements apply 250 + 150 = 400 ⇒ derived remaining 600 (owed − applied).
        // Partial pay means TWO settlements (one allocation each) — UNIQUE(settlement_id, share_id)
        // forbids two allocations from the *same* settlement against the same share.
        shareDao.upsert(
            ShareEntity(id = "sh1", expenseId = "e1", userId = "u2", shareOwedSubunits = 1000, createdAt = 1, updatedAt = 1),
        )
        dao.upsertAll(listOf(settlement("s1", settledAt = 1), settlement("s2", settledAt = 2)))
        dao.upsertAllocations(
            listOf(
                allocation("a1", "s1", "sh1", amount = 250),
                allocation("a2", "s2", "sh1", amount = 150),
            )
        )
        val applied = dao.sumAppliedToShare("sh1")
        assertEquals(400, applied)
        assertEquals(600, 1000 - applied) // derived remaining = owed − applied
    }

    @Test
    fun sumAppliedToShare_excludesVoidedSettlements() = runTest {
        shareDao.upsert(
            ShareEntity(id = "sh1", expenseId = "e1", userId = "u2", shareOwedSubunits = 1000, createdAt = 1, updatedAt = 1),
        )
        dao.upsertAll(listOf(settlement("s1", settledAt = 1), settlement("s2", settledAt = 2)))
        dao.upsertAllocations(
            listOf(
                allocation("a1", "s1", "sh1", amount = 250),
                allocation("a2", "s2", "sh1", amount = 150),
            )
        )
        dao.voidSettlement("s2", ts = 3) // soft-delete s2 ⇒ its 150 drops out of the applied sum
        assertEquals(250, dao.sumAppliedToShare("sh1"))
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

    // --- replaceSettlement: void + re-record must be ONE transaction (#17) ----------------------
    // `editSettlement` used to void and re-record across two separate writes. A crash between them
    // left the payment voided with no replacement: money that was actually paid silently became owed
    // again, on every device, with nothing to say why.

    @Test
    fun replaceSettlement_correctsInOneTransaction() = runTest {
        shareDao.upsert(
            ShareEntity(id = "sh1", expenseId = "e1", userId = "u2", shareOwedSubunits = 1000, createdAt = 1, updatedAt = 1),
        )
        dao.upsert(settlement("s1", settledAt = 1))
        dao.upsertAllocations(listOf(allocation("a1", "s1", "sh1", amount = 1000)))

        val replaced = dao.replaceSettlement(
            oldSettlementId = "s1",
            voidedAt = 5,
            settlement = settlement("s2", settledAt = 5),
            allocations = listOf(allocation("a2", "s2", "sh1", amount = 600)),
        )

        assertTrue(replaced)
        assertEquals(600, dao.sumAppliedToShare("sh1"), "only the replacement counts")
        assertNotNull(dao.getById("s1")?.deletedAt, "the old payment is voided, not hard-deleted (Rule 1)")
        assertNull(dao.getById("s2")?.deletedAt, "the replacement is live")
    }

    @Test
    fun replaceSettlement_refused_rollsTheVoidBackToo() = runTest {
        shareDao.upsert(
            ShareEntity(id = "sh1", expenseId = "e1", userId = "u2", shareOwedSubunits = 1000, createdAt = 1, updatedAt = 1),
        )
        dao.upsert(settlement("s1", settledAt = 1))
        dao.upsertAllocations(listOf(allocation("a1", "s1", "sh1", amount = 400)))

        // 5000 against a 1000 share: the in-transaction over-apply guard must refuse. The point of the
        // test is the SECOND assertion — the void has to roll back with it, or a rejected correction
        // un-pays a real payment.
        assertFailsWith<SettlementReplaceRefused> {
            dao.replaceSettlement(
                oldSettlementId = "s1",
                voidedAt = 5,
                settlement = settlement("s2", settledAt = 5),
                allocations = listOf(allocation("a2", "s2", "sh1", amount = 5000)),
            )
        }

        assertNull(dao.getById("s1")?.deletedAt, "the original payment survives a refused replacement")
        assertEquals(400, dao.sumAppliedToShare("sh1"), "and still counts against the share")
        assertNull(dao.getById("s2"), "nothing of the replacement was written")
    }
}
