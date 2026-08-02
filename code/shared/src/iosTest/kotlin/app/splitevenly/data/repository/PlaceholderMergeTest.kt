package app.splitevenly.data.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.BillParticipantEntity
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.ItemClaimEntity
import app.splitevenly.data.db.entity.ItemShareEntity
import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.entity.SettlementAllocationEntity
import app.splitevenly.data.db.entity.SettlementEntity
import app.splitevenly.data.db.entity.ShareEntity
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Correctness of the placeholder → real-user merge ([app.splitevenly.data.db.dao.PlaceholderMergeDao]).
 *
 * These cover the three defects that were rare only while the Reconcile screen was buried and become
 * load-bearing once the claim is offered on the group's front page: a duplicate share when the claimer is
 * already in the same expense, settlements left pointing at the retired name, and itemized bills left
 * pointing at it.
 *
 * DB-backed, so `iosTest` rather than `commonTest` — `inMemoryTestDatabase()` only exists on non-Android
 * targets (see its KDoc), which is why every repository test in this codebase lives here.
 */
class PlaceholderMergeTest {

    private lateinit var db: EvenlyDatabase
    private lateinit var repo: GroupRepositoryImpl

    private val group = GroupId("g1")
    private val me = UserId("me")
    private val ghost = UserId("ph") // the placeholder name carrying the history

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        repo = GroupRepositoryImpl(
            db.groupDao(), db.memberDao(), db.userDao(), db.expenseDao(), db.shareDao(), db.conflictDao(),
            db.placeholderMergeDao(), clockAt("2026-08-01"),
        )
    }

    @AfterTest
    fun tearDown() = db.close()

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────

    private suspend fun seedPeople() {
        for (u in listOf(me.value, ghost.value, "maya")) {
            db.userDao().upsert(
                UserEntity(
                    id = u, isPlaceholder = u == ghost.value, displayName = u,
                    placeholderGroupId = if (u == ghost.value) group.value else null,
                    createdAt = 1, updatedAt = 1,
                ),
            )
            db.memberDao().upsert(
                MemberEntity(id = "m_$u", groupId = group.value, userId = u, joinedAt = 1, createdAt = 1, updatedAt = 1),
            )
        }
    }

    private suspend fun expense(
        id: String,
        amount: Long,
        payer: String = "maya",
        splitMode: String = "EVEN",
    ): ExpenseEntity = ExpenseEntity(
        id = id, groupId = group.value, title = id, amountSubunits = amount, currency = "USD",
        expenseDate = "2026-07-01", payerUserId = payer, splitMode = splitMode, createdBy = payer,
        createdAt = 1, updatedAt = 1,
    ).also { db.expenseDao().upsert(it) }

    private suspend fun share(id: String, expenseId: String, userId: String, owed: Long, units: Int? = null) {
        db.shareDao().upsert(
            ShareEntity(
                id = id, expenseId = expenseId, userId = userId, shareOwedSubunits = owed,
                shareUnits = units, createdAt = 1, updatedAt = 1,
            ),
        )
    }

    private suspend fun activeShares(expenseId: String) = db.shareDao().getByExpense(expenseId)

    private suspend fun merge() {
        assertTrue(repo.reconcilePlaceholder(group, ghost, me) is AppResult.Ok)
    }

    // ── §5.1 shares ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun noCollision_shareSimplyChangesHands() = runTest {
        seedPeople()
        expense("e1", 3000)
        share("s_ph", "e1", ghost.value, 3000)

        merge()

        val shares = activeShares("e1")
        assertEquals(1, shares.size)
        assertEquals(me.value, shares.single().userId)
        assertEquals(3000, shares.single().shareOwedSubunits)
    }

    @Test
    fun collision_foldsIntoOneShareInsteadOfTwo() = runTest {
        // Someone added me AND "Chelimo" to the same dinner. A blind reassignment leaves two active rows
        // with the same (expense_id, user_id): silent locally, rejected by the server's partial unique
        // index, and it takes the whole expense's sync down with it.
        seedPeople()
        expense("e1", 3000)
        share("s_me", "e1", me.value, 1000, units = 1)
        share("s_ph", "e1", ghost.value, 2000, units = 2)

        merge()

        val shares = activeShares("e1")
        assertEquals(1, shares.size, "the two shares must fold into one, not stack up")
        assertEquals("s_me", shares.single().id, "the claimer's own row survives so its allocations stay linked")
        assertEquals(3000, shares.single().shareOwedSubunits, "AC-INV-001: shares must still sum to the amount")
        assertEquals(3, shares.single().shareUnits, "the raw split inputs fold too, or the editor re-renders wrong")
        val tombstoned = db.shareDao().allForSync().single { it.id == "s_ph" }
        assertNotNull(tombstoned.deletedAt, "the folded-away row is tombstoned, never hard-deleted")
    }

    @Test
    fun collision_onPartiallySettledShare_movesThePaymentsToTheSurvivor() = runTest {
        // The placeholder's share was half paid off. Allocations are the ground truth `remaining` derives
        // from, so left pointing at the tombstoned share the payment stops offsetting the debt.
        seedPeople()
        expense("e1", 3000)
        share("s_me", "e1", me.value, 1000)
        share("s_ph", "e1", ghost.value, 2000)
        db.settlementDao().upsert(
            SettlementEntity(
                id = "st1", groupId = group.value, fromUserId = ghost.value, toUserId = "maya",
                paymentCurrency = "USD", paymentAmountSubunits = 800, settledAt = 2, createdBy = "maya",
                createdAt = 2, updatedAt = 2,
            ),
        )
        db.settlementDao().upsertAllocation(
            SettlementAllocationEntity(
                id = "a1", settlementId = "st1", groupId = group.value, shareId = "s_ph",
                appliedAmountSubunits = 800, appliedCurrency = "USD", createdAt = 2,
            ),
        )

        merge()

        val survivor = activeShares("e1").single()
        assertEquals("s_me", survivor.id)
        assertEquals(800, db.settlementDao().sumAppliedToShare("s_me"), "the payment follows the fold")
        assertEquals(3000 - 800, db.settlementDao().derivedRemainingForShare("s_me"))
    }

    @Test
    fun collision_onItemizedBill_foldsTheDerivedSharesToo() = runTest {
        seedPeople()
        expense("b1", 4000, splitMode = "ITEMIZED")
        // Bill shares use deterministic "<expenseId>__<userId>" ids (they are a derived materialization).
        share("b1__me", "b1", me.value, 1500)
        share("b1__ph", "b1", ghost.value, 2500)

        merge()

        val shares = activeShares("b1")
        assertEquals(1, shares.size)
        assertEquals(4000, shares.single().shareOwedSubunits)
    }

    @Test
    fun merge_advancesTheCausalSplitVersionOfTouchedExpenses() = runTest {
        // Bumping only row_version marks the expense dirty but leaves split_version == base, so
        // merge_expense reads the push as metadata-only and reverts the merge on the next sync.
        seedPeople()
        val owed = expense("e1", 3000)
        val paid = expense("e2", 5000, payer = ghost.value)
        share("s_ph", "e1", ghost.value, 3000)

        merge()

        assertTrue(db.expenseDao().getById("e1")!!.splitVersion > owed.splitVersion)
        val reassigned = db.expenseDao().getById("e2")!!
        assertEquals(me.value, reassigned.payerUserId)
        assertTrue(reassigned.splitVersion > paid.splitVersion)
    }

    // ── §5.2 settlements ─────────────────────────────────────────────────────────────────────────

    @Test
    fun settlementsInvolvingTheNameAreReassigned() = runTest {
        seedPeople()
        db.settlementDao().upsert(
            SettlementEntity(
                id = "st_from", groupId = group.value, fromUserId = ghost.value, toUserId = "maya",
                paymentCurrency = "USD", paymentAmountSubunits = 500, settledAt = 2, createdBy = "maya",
                createdAt = 2, updatedAt = 2,
            ),
        )
        db.settlementDao().upsert(
            SettlementEntity(
                id = "st_to", groupId = group.value, fromUserId = "maya", toUserId = ghost.value,
                paymentCurrency = "USD", paymentAmountSubunits = 700, settledAt = 2, createdBy = "maya",
                createdAt = 2, updatedAt = 2,
            ),
        )

        merge()

        assertEquals(me.value, db.settlementDao().getById("st_from")!!.fromUserId)
        assertEquals(me.value, db.settlementDao().getById("st_to")!!.toUserId)
    }

    @Test
    fun aPaymentBetweenMeAndTheNameIsVoided() = runTest {
        // "I paid Chelimo back" is a payment from me to me the moment Chelimo IS me. Kept alive it keeps
        // paying down shares on expenses I am now the payer of, which surfaces as an overpayment.
        seedPeople()
        db.settlementDao().upsert(
            SettlementEntity(
                id = "st_self", groupId = group.value, fromUserId = me.value, toUserId = ghost.value,
                paymentCurrency = "USD", paymentAmountSubunits = 900, settledAt = 2, createdBy = me.value,
                createdAt = 2, updatedAt = 2,
            ),
        )

        merge()

        assertNotNull(db.settlementDao().getById("st_self")!!.deletedAt)
    }

    // ── §5.3 itemized bills ──────────────────────────────────────────────────────────────────────

    @Test
    fun itemClaims_onlyThePlaceholderClaimed_changeHands() = runTest {
        seedPeople()
        expense("b1", 4000, splitMode = "ITEMIZED")
        db.itemClaimDao().upsert(
            ItemClaimEntity(
                id = "c_ph", itemId = "i1", expenseId = "b1", groupId = group.value,
                userId = ghost.value, quantity = 2, createdAt = 1, updatedAt = 1,
            ),
        )

        merge()

        val claims = db.itemClaimDao().getByExpense("b1")
        assertEquals(1, claims.size)
        assertEquals(me.value, claims.single().userId)
        assertEquals(2, claims.single().quantity)
    }

    @Test
    fun itemClaims_bothClaimedTheSameLine_fold() = runTest {
        seedPeople()
        expense("b1", 4000, splitMode = "ITEMIZED")
        db.itemClaimDao().upsertAll(
            listOf(
                ItemClaimEntity(
                    id = "c_me", itemId = "i1", expenseId = "b1", groupId = group.value,
                    userId = me.value, quantity = 1, createdAt = 1, updatedAt = 1,
                ),
                ItemClaimEntity(
                    id = "c_ph", itemId = "i1", expenseId = "b1", groupId = group.value,
                    userId = ghost.value, quantity = 2, createdAt = 1, updatedAt = 1,
                ),
            ),
        )

        merge()

        val claims = db.itemClaimDao().getByExpense("b1")
        assertEquals(1, claims.size, "one person cannot hold two claims on one line")
        assertEquals("c_me", claims.single().id)
        assertEquals(3, claims.single().quantity, "a claim quantity is per person, so it sums")
        assertNotNull(db.itemClaimDao().allForSync().single { it.id == "c_ph" }.deletedAt)
    }

    @Test
    fun itemShares_collidingPortionMembershipDropsTheDuplicateWithoutResizingTheSlice() = runTest {
        // An item_share's quantity is the PORTION's unit count, carried identically on every member row.
        // Summing it would silently enlarge the slice.
        seedPeople()
        expense("b1", 4000, splitMode = "ITEMIZED")
        db.itemShareDao().upsertAll(
            listOf(
                ItemShareEntity(
                    id = "is_me", itemId = "i1", expenseId = "b1", groupId = group.value, userId = me.value,
                    portionId = "p1", quantity = 2, addedBy = "maya", createdAt = 1, updatedAt = 1,
                ),
                ItemShareEntity(
                    id = "is_ph", itemId = "i1", expenseId = "b1", groupId = group.value, userId = ghost.value,
                    portionId = "p1", quantity = 2, addedBy = "maya", createdAt = 1, updatedAt = 1,
                ),
                ItemShareEntity(
                    id = "is_ph2", itemId = "i2", expenseId = "b1", groupId = group.value, userId = ghost.value,
                    portionId = "p2", quantity = 1, addedBy = "maya", createdAt = 1, updatedAt = 1,
                ),
            ),
        )

        merge()

        val live = db.itemShareDao().getByExpense("b1")
        assertEquals(2, live.size)
        assertTrue(live.all { it.userId == me.value })
        assertEquals(2, live.single { it.itemId == "i1" }.quantity, "the portion keeps its size")
        assertNotNull(db.itemShareDao().allForSync().single { it.id == "is_ph" }.deletedAt)
    }

    @Test
    fun billParticipantIsMergedSoTheBillCanResolve() = runTest {
        // Left pointing at the retired name, the bill keeps a participant who will never mark done and
        // stays "unresolved" forever.
        seedPeople()
        expense("b1", 4000, splitMode = "ITEMIZED")
        db.billParticipantDao().upsertAll(
            listOf(
                BillParticipantEntity(
                    id = "bp_me", expenseId = "b1", groupId = group.value, userId = me.value,
                    doneAt = 5, createdAt = 1, updatedAt = 1,
                ),
                BillParticipantEntity(
                    id = "bp_ph", expenseId = "b1", groupId = group.value, userId = ghost.value,
                    createdAt = 1, updatedAt = 1,
                ),
            ),
        )

        merge()

        val live = db.billParticipantDao().allForSync().filter { it.deletedAt == null }
        assertEquals(1, live.size)
        assertEquals("bp_me", live.single().id)
    }

    // ── The membership stamp ─────────────────────────────────────────────────────────────────────

    @Test
    fun theNameIsRetiredAndRecordsWhoClaimedIt() = runTest {
        seedPeople()

        merge()

        val retired = db.memberDao().getMember(group.value, ghost.value)!!
        assertEquals(MemberEntity.STATUS_LEFT, retired.status)
        assertNotNull(retired.placeholderClaimCompletedAt, "the stamp is what hides it from every picker")
        assertEquals(me.value, retired.placeholderClaimedBy)
    }

    @Test
    fun claimingYourselfIsRejected() = runTest {
        seedPeople()
        assertTrue(repo.reconcilePlaceholder(group, me, me) is AppResult.Err)
        assertNull(db.memberDao().getMember(group.value, me.value)!!.placeholderClaimCompletedAt)
    }
}
