package app.splitevenly.data.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.data.remote.supabase.JoinItemPortionGateway
import app.splitevenly.data.remote.supabase.JoinItemPortionOutcome
import app.splitevenly.domain.expense.NewBill
import app.splitevenly.domain.expense.NewBillItem
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Joining a line someone else already claimed (`join_item_portion`, WEB_CLAIM_SPEC.md §5.3) — the one
 * assignment the client may not make on its own, because it converts ANOTHER person's solo `item_claims`
 * row into a shared `item_shares` portion, and claims are partitioned by user.
 *
 * These cover the client half: that the conversion is routed to the gateway at all, that its canonical
 * result is mirrored faithfully (including attribution), and that a conversion which CANNOT be made is
 * reported rather than silently dropped or written locally as a double-counting row.
 */
class BillJoinPortionTest {
    private lateinit var db: EvenlyDatabase

    private val group = GroupId("g1")
    private val me = UserId("a")
    private val bob = UserId("b")
    private val cara = UserId("c")

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
    }

    @AfterTest
    fun tearDown() = db.close()

    /** A stand-in for the server RPC, computing the same outcome from the same rows the real one reads.
     *  Deliberately READ-ONLY: on a device the server owns its own copy, and the repository is what has
     *  to get the local mirror right. [failWith] simulates an unreachable server. */
    private class FakeJoinGateway(
        private val db: EvenlyDatabase,
        private val failWith: Exception? = null,
    ) : JoinItemPortionGateway {
        var calls = 0
            private set

        override suspend fun join(
            itemId: String,
            joinerUserId: String,
            portionId: String?,
            now: Long,
            overClaimAck: Boolean,
        ): JoinItemPortionOutcome {
            calls++
            failWith?.let { throw it }
            val expenseId =
                db
                    .expenseItemDao()
                    .allForSync()
                    .first { it.id == itemId }
                    .expenseId
            val liveShares =
                db
                    .itemShareDao()
                    .getByExpense(expenseId)
                    .filter { it.itemId == itemId && it.deletedAt == null }
            val inPortion = liveShares.filter { portionId != null && it.portionId == portionId }
            if (inPortion.isNotEmpty()) {
                val members = (inPortion.map { it.userId } + joinerUserId).distinct()
                return JoinItemPortionOutcome(true, portionId!!, inPortion.first().quantity, members)
            }
            // No live portion under that name: fold the line's one OTHER solo claim into a new portion.
            val target =
                db
                    .itemClaimDao()
                    .getByExpense(expenseId)
                    .firstOrNull { it.itemId == itemId && it.deletedAt == null && it.userId != joinerUserId }
            val resolved = portionId ?: if (target != null) "${itemId}__joined_${target.userId}" else "${itemId}__solo_$joinerUserId"
            return if (target != null) {
                JoinItemPortionOutcome(true, resolved, target.quantity, listOf(target.userId, joinerUserId))
            } else {
                JoinItemPortionOutcome(true, resolved, 1, listOf(joinerUserId))
            }
        }
    }

    private fun repo(gateway: JoinItemPortionGateway?) =
        BillRepositoryImpl(
            db.expenseDao(),
            db.expenseItemDao(),
            db.itemClaimDao(),
            db.itemShareDao(),
            db.billParticipantDao(),
            db.shareDao(),
            db.billWriteDao(),
            clockAt("2026-06-28"),
            joinItemGateway = gateway,
        )

    private suspend fun newBill(bills: BillRepositoryImpl): ExpenseId =
        (
            bills.createBill(
                NewBill(
                    groupId = group,
                    title = "Dinner at Tavolo",
                    currency = "USD",
                    expenseDate = "2026-06-28",
                    payerUserId = me,
                    createdBy = me,
                    items = listOf(NewBillItem("Margherita pizza", quantity = 2, lineTotalSubunits = 3600)),
                ),
            ) as AppResult.Ok
        ).value

    private suspend fun pizzaId(expenseId: ExpenseId): String =
        db
            .expenseItemDao()
            .getByExpense(expenseId.value)
            .first()
            .id

    private suspend fun owed(expenseId: ExpenseId): Map<String, Long> =
        db.shareDao().getByExpense(expenseId.value).associate { it.userId to it.shareOwedSubunits }

    @Test
    fun setPortion_foldsAnotherPersonsSoloClaim_leavingNoDoubleCount() =
        runTest {
            val gateway = FakeJoinGateway(db)
            val bills = repo(gateway)
            val bill = newBill(bills)
            val pizza = pizzaId(bill)

            bills.setClaim(bill, pizza, bob, 1)
            assertEquals(mapOf("b" to 1800L), owed(bill))

            // Ticking Bob AND Cara onto the shared chip: Cara's add collides with Bob's live solo claim.
            val result = bills.setPortion(bill, pizza, "${pizza}__all", listOf(bob, cara), 2, me)

            assertTrue(result is AppResult.Ok)
            assertEquals(1, gateway.calls, "only the colliding add goes to the server")
            assertNull(
                db.itemClaimDao().getActiveClaim(pizza, bob.value),
                "Bob's solo claim is retired, not left alongside his portion row",
            )
            assertEquals(mapOf("b" to 1800L, "c" to 1800L), owed(bill), "one line of 2 units shared 2 ways, counted once")
        }

    @Test
    fun joinItem_attributesEveryRowToTheJoiner() =
        runTest {
            val bills = repo(FakeJoinGateway(db))
            val bill = newBill(bills)
            val pizza = pizzaId(bill)
            bills.setClaim(bill, pizza, bob, 1)
            bills.setParticipant(bill, cara, included = true)

            assertTrue(bills.joinItem(bill, pizza, cara) is AppResult.Ok)

            val rows = db.itemShareDao().getByExpense(bill.value).filter { it.deletedAt == null }
            assertEquals(2, rows.size)
            // Including Bob's row: the join was Cara's action, and this is what the RPC wrote server-side.
            // Stamping each member as their own added_by diverged, and the next push would have overwritten
            // the server's attribution with it.
            assertTrue(rows.all { it.addedBy == cara.value }, "added_by records who joined, not who was joined")
        }

    @Test
    fun setPortion_reportsAFailedConversionInsteadOfReturningOk() =
        runTest {
            val gateway = FakeJoinGateway(db, failWith = RuntimeException("offline"))
            val bills = repo(gateway)
            val bill = newBill(bills)
            val pizza = pizzaId(bill)
            bills.setClaim(bill, pizza, bob, 1)

            val result = bills.setPortion(bill, pizza, "${pizza}__all", listOf(bob, cara), 2, me)

            assertTrue(result is AppResult.Err, "a target that never landed must not read as success")
            assertNull(
                db.itemShareDao().getByExpense(bill.value).firstOrNull { it.userId == cara.value && it.deletedAt == null },
                "Cara is genuinely not on the line",
            )
        }

    @Test
    fun setPortion_withNoGateway_skipsTheCollidingAddRatherThanDoubleCounting() =
        runTest {
            val bills = repo(null) // offline build / stub: no server to make the conversion
            val bill = newBill(bills)
            val pizza = pizzaId(bill)
            bills.setClaim(bill, pizza, bob, 1)

            val result = bills.setPortion(bill, pizza, "${pizza}__all", listOf(bob, cara), 2, me)

            assertTrue(result is AppResult.Err)
            val liveShares = db.itemShareDao().getByExpense(bill.value).filter { it.deletedAt == null }
            assertTrue(liveShares.none { it.userId == cara.value }, "no locally-written row while Bob's claim is still live")
            // Bob still applies (his own claim needs no RPC to fold in) and holds the line alone: 2 units.
            assertNull(db.itemClaimDao().getActiveClaim(pizza, bob.value))
            assertEquals(mapOf("b" to 3600L), owed(bill), "Bob is counted once, not once per row")
        }

    @Test
    fun setPortion_foldsATargetsOwnSoloClaim() =
        runTest {
            val bills = repo(FakeJoinGateway(db))
            val bill = newBill(bills)
            val pizza = pizzaId(bill)
            bills.setClaim(bill, pizza, bob, 1)

            // Bob alone, ticked onto the shared chip for the whole line. Nobody else's row is involved, so
            // this never reaches the gateway — but his own solo claim still has to give way to the portion.
            assertTrue(bills.setPortion(bill, pizza, "${pizza}__all", listOf(bob), 2, me) is AppResult.Ok)

            assertNull(db.itemClaimDao().getActiveClaim(pizza, bob.value))
            assertEquals(mapOf("b" to 3600L), owed(bill), "one claim plus one portion row must not bill him twice")
        }
}
