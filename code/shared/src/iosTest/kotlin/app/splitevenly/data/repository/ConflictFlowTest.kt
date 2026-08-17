package app.splitevenly.data.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.domain.expense.NewBill
import app.splitevenly.domain.expense.NewBillItem
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
        groups =
            GroupRepositoryImpl(
                db.groupDao(),
                db.memberDao(),
                db.userDao(),
                db.expenseDao(),
                db.shareDao(),
                db.conflictDao(),
                db.placeholderMergeDao(),
                db.placeholderClaimAnswerDao(),
                clock,
            )
        expenses = ExpenseRepositoryImpl(db.expenseDao(), db.shareDao(), clock)
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun addExpense(
        mode: String,
        amount: Long,
        shares: List<NewShare>,
    ): ExpenseId {
        val r =
            expenses.addExpense(
                NewExpense(
                    groupId = gid,
                    title = "Dinner",
                    amountSubunits = amount,
                    currency = "USD",
                    expenseDate = "2026-06-01",
                    payerUserId = u1,
                    splitMode = mode,
                    createdBy = u1,
                    shares = shares,
                ),
            )
        assertTrue(r is AppResult.Ok)
        return r.value.id
    }

    @Test
    fun retroAdd_evenExpense_reSplitsToIncludeNewMember() =
        runTest {
            val id = addExpense("EVEN", 1000, listOf(NewShare(u1, 500), NewShare(u2, 500)))

            assertTrue(groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1) is AppResult.Ok)

            val shares = expenses.observeExpense(id).first()!!.shares
            assertEquals(3, shares.size)
            assertEquals(1000, shares.sumOf { it.owedSubunits })
            assertEquals(333, shares.first { it.userId == u3 }.owedSubunits)
            assertTrue(groups.observeConflicts(gid).first().isEmpty()) // no conflict for EVEN
        }

    @Test
    fun retroAdd_nonEvenExpense_raisesConflictWithoutTouchingShares() =
        runTest {
            val id = addExpense("EXACT", 1000, listOf(NewShare(u1, 600), NewShare(u2, 400)))

            groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1)

            val conflicts = groups.observeConflicts(gid).first()
            assertEquals(1, conflicts.size)
            assertEquals(id, conflicts.single().expenseId)
            assertEquals(u3, conflicts.single().addedUserId)
            assertEquals(
                2,
                expenses
                    .observeExpense(id)
                    .first()!!
                    .shares.size,
            ) // shares untouched until resolved

            // Idempotent: a second sweep doesn't duplicate the conflict.
            groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1)
            assertEquals(1, groups.observeConflicts(gid).first().size)
        }

    @Test
    fun resolveConflict_include_reducesOthersProportionally() =
        runTest {
            val id = addExpense("EXACT", 1000, listOf(NewShare(u1, 600), NewShare(u2, 400)))
            groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1)
            val conflictId =
                groups
                    .observeConflicts(gid)
                    .first()
                    .single()
                    .id

            assertTrue(groups.resolveConflict(conflictId, include = true, newShareSubunits = 200) is AppResult.Ok)

            val byUser =
                expenses
                    .observeExpense(id)
                    .first()!!
                    .shares
                    .associate { it.userId to it.owedSubunits }
            assertEquals(480, byUser[u1]) // 600/1000 of the remaining 800
            assertEquals(320, byUser[u2]) // 400/1000 of the remaining 800
            assertEquals(200, byUser[u3])
            assertEquals(1000, byUser.values.sum())
            assertTrue(groups.observeConflicts(gid).first().isEmpty())
        }

    @Test
    fun resolveConflict_dismiss_leavesExpenseUntouched() =
        runTest {
            val id = addExpense("PERCENT", 1000, listOf(NewShare(u1, 600), NewShare(u2, 400)))
            groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1)
            val conflictId =
                groups
                    .observeConflicts(gid)
                    .first()
                    .single()
                    .id

            assertTrue(groups.resolveConflict(conflictId, include = false) is AppResult.Ok)

            assertEquals(
                2,
                expenses
                    .observeExpense(id)
                    .first()!!
                    .shares.size,
            )
            assertTrue(groups.observeConflicts(gid).first().isEmpty())
        }

    @Test
    fun resolveConflict_include_rejectsOutOfRangeShare() =
        runTest {
            addExpense("EXACT", 1000, listOf(NewShare(u1, 600), NewShare(u2, 400)))
            groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1)
            val conflictId =
                groups
                    .observeConflicts(gid)
                    .first()
                    .single()
                    .id

            assertTrue(groups.resolveConflict(conflictId, include = true, newShareSubunits = 1000) is AppResult.Err) // >= total
            assertTrue(groups.resolveConflict(conflictId, include = true, newShareSubunits = 0) is AppResult.Err)
            assertEquals(1, groups.observeConflicts(gid).first().size) // still unresolved
        }

    // ── Itemized bills are not a conflict to resolve (R7) ────────────────────────────────────────

    /** A bill with one $18.00 line, claimed by u1 and u2. Its shares are a derived materialization. */
    private suspend fun addBill(): ExpenseId {
        val bills =
            BillRepositoryImpl(
                db.expenseDao(),
                db.expenseItemDao(),
                db.itemClaimDao(),
                db.itemShareDao(),
                db.billParticipantDao(),
                db.shareDao(),
                db.billWriteDao(),
                clockAt("2026-06-15"),
            )
        val bill =
            (
                bills.createBill(
                    NewBill(
                        groupId = gid,
                        title = "Dinner at Tavolo",
                        currency = "USD",
                        expenseDate = "2026-06-01",
                        payerUserId = u1,
                        createdBy = u1,
                        items = listOf(NewBillItem("Margherita pizza", quantity = 2, lineTotalSubunits = 3600)),
                        participantUserIds = listOf(u1, u2),
                    ),
                ) as AppResult.Ok
            ).value
        val pizza =
            db
                .expenseItemDao()
                .getByExpense(bill.value)
                .single()
                .id
        bills.setClaim(bill, pizza, u1, 1)
        bills.setClaim(bill, pizza, u2, 1)
        return bill
    }

    /**
     * A bill's `shares` are re-derived from its items and claims on **every pull**
     * (`SyncEngine` → `rematerializeGroups`). So the sweep raising a conflict card for one hands the user
     * a decision whose only possible outcome is a hand-written split that the next pull erases, and the
     * card is closed by then, so there is nothing left to tap. Adding someone to a past *bill* means a
     * `bill_participants` row and a claim, which this path does not write.
     */
    @Test
    fun retroAdd_itemizedBill_raisesNoConflictBecauseTheAnswerWouldBeErasedAtTheNextPull() =
        runTest {
            val bill = addBill()

            assertTrue(groups.addMemberToPastExpenses(gid, u3, triggeredBy = u1) is AppResult.Ok)

            assertTrue(groups.observeConflicts(gid).first().isEmpty(), "a bill is not a split anyone can hand-edit")
            val owed = db.shareDao().getByExpense(bill.value).associate { it.userId to it.shareOwedSubunits }
            assertEquals(mapOf("u1" to 1800L, "u2" to 1800L), owed, "and the derived split is untouched")
        }

    /** The sweep no longer raises one, but a card parked before this fix must not write shares either. */
    @Test
    fun resolveConflict_onAnItemizedBill_isRefusedRatherThanWritingASplitThePullWillErase() =
        runTest {
            val bill = addBill()
            db.conflictDao().upsert(
                app.splitevenly.data.db.entity.ConflictEntity(
                    id = "stale",
                    groupId = gid.value,
                    expenseId = bill.value,
                    addedUserId = u3.value,
                    triggeredByUserId = u1.value,
                    createdAt = 1,
                ),
            )

            assertTrue(groups.resolveConflict("stale", include = true, newShareSubunits = 500) is AppResult.Err)

            val owed = db.shareDao().getByExpense(bill.value).associate { it.userId to it.shareOwedSubunits }
            assertEquals(mapOf("u1" to 1800L, "u2" to 1800L), owed)
        }

    // ── The sweep is all-or-nothing (R12) ────────────────────────────────────────────────────────

    /** Delegates everything, and counts the per-expense writes the one sweep transaction replaces. */
    private class CountingExpenseDao(
        private val real: app.splitevenly.data.db.dao.ExpenseDao,
    ) : app.splitevenly.data.db.dao.ExpenseDao by real {
        var looseReplaces = 0

        override suspend fun replaceWithShares(
            expense: app.splitevenly.data.db.entity.ExpenseEntity,
            shares: List<app.splitevenly.data.db.entity.ShareEntity>,
            removedShareIds: List<String>,
            ts: Long,
        ) {
            looseReplaces++
            real.replaceWithShares(expense, shares, removedShareIds, ts)
        }
    }

    /**
     * The loop used to re-split one expense per iteration from a navigation-scoped coroutine, so
     * navigating away after eight of twenty left the group's history split at an arbitrary point: both
     * halves internally consistent, both pushing cleanly, nothing looking broken and nothing re-running
     * it. One transaction makes it all or none.
     */
    @Test
    fun retroAdd_sweepsEveryExpenseInOneTransaction() =
        runTest {
            val counting = CountingExpenseDao(db.expenseDao())
            val sweeping =
                GroupRepositoryImpl(
                    db.groupDao(),
                    db.memberDao(),
                    db.userDao(),
                    counting,
                    db.shareDao(),
                    db.conflictDao(),
                    db.placeholderMergeDao(),
                    db.placeholderClaimAnswerDao(),
                    clockAt("2026-06-15"),
                )
            val a = addExpense("EVEN", 1000, listOf(NewShare(u1, 500), NewShare(u2, 500)))
            val b = addExpense("EVEN", 900, listOf(NewShare(u1, 450), NewShare(u2, 450)))

            assertTrue(sweeping.addMemberToPastExpenses(gid, u3, triggeredBy = u1) is AppResult.Ok)

            assertEquals(0, counting.looseReplaces, "a per-expense write can be cancelled between two expenses")
            for (id in listOf(a, b)) {
                assertEquals(
                    3,
                    expenses
                        .observeExpense(id)
                        .first()!!
                        .shares.size,
                )
            }
        }
}
